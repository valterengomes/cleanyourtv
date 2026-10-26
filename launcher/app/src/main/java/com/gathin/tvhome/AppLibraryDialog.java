package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * The app library (docs/ui/launcher board 08): every installed app A to Z on a full screen, seven to a row, with the
 * All / On Home / Not on Home filters and a cream check on the apps on Home. OK adds or removes an app (or opens it,
 * see LibraryApps.okOpens); hold OK offers Open, Add or Remove, App info. No backdrop capture or blur.
 */
final class AppLibraryDialog {
    /**
     * The catalogue, the labels and the icons load here, off the UI thread: each label and icon opens the app's
     * resources, and on a TCL G09 that froze Home for 60-140 ms (2 to 4 janky frames) every time the library opened.
     */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Labels kept between openings (LOADER thread only), until the app is updated. */
    private static final Map<String, Known> KNOWN = new HashMap<>();
    /** Seven apps to a row (SPEC section 7). */
    private static final int COLUMNS = 7;

    private static final class Known {
        final long updated;
        final String label;

        Known(long updated, String label) {
            this.updated = updated;
            this.label = label;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Icons loaded for this opening, dropped with it. */
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Set<String> iconsLoading = new HashSet<>();
    private final Runnable dismissed;
    private boolean closed;
    /** Only the latest load fills the catalogue, and it shows the popup if an opening is waiting. */
    private final LibraryLoads loads = new LibraryLoads();
    private final Activity activity;
    private final Predicate<String> onHome;
    private final Runnable changed;
    private final LinearLayout panel;
    private final boolean reducedMotion;
    private final boolean selectionMode;
    /** library_ok_opens without app_library_selection: OK opens every app, Add to Home is in the hold menu. */
    private final boolean okOpens;
    private final boolean rtl;
    final PopupWindow popup;
    private final float density;
    /** Every app, A to Z; {@link #shown} is the part the chosen filter lets through. */
    private List<ResolveInfo> apps = new ArrayList<>();
    private final List<ResolveInfo> shown = new ArrayList<>();
    private int filter = LibraryApps.FILTER_ALL;
    private String selectedPackage;
    private int selectedPosition;
    private final Map<String, String> labels = new HashMap<>();
    /** Packages with a leanback entry, from the last refresh. */
    private final Set<String> tvPackages = new HashSet<>();
    private PopupWindow actionPopup;
    private RecyclerView catalog;
    private TextView okHint;
    private final TextView[] chips = new TextView[3];

    AppLibraryDialog(Activity activity, boolean animations, boolean reducedMotion, boolean selectionMode, boolean okOpens, Predicate<String> onHome, Runnable changed, Runnable dismissed) {
        this.activity = activity;
        this.rtl = activity.getResources().getBoolean(R.bool.rtl_app_grid);
        this.reducedMotion = reducedMotion || !animations;
        this.selectionMode = selectionMode;
        this.okOpens = okOpens && !selectionMode;
        this.onHome = onHome;
        this.changed = changed;
        this.dismissed = dismissed;
        density = activity.getResources().getDisplayMetrics().density;
        panel = new LinearLayout(activity);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setOrientation(LinearLayout.VERTICAL);
        // The safe area: 48dp at the sides, 27dp at the top and bottom.
        panel.setPadding(dp(48), dp(27), dp(48), dp(20));
        panel.setBackgroundColor(activity.getColor(R.color.lw_bg));
        popup = new PopupWindow(panel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            closed = true;
            dismissActions();
            catalog = null;
            icons.clear();
            dismissed.run();
        });
    }

    void close() {
        dismissActions();
        if (popup.isShowing()) {
            popup.dismiss();
        } else if (!closed) {
            closed = true;   // still loading: it never shows
            dismissed.run();
        }
    }

    void show() {
        load(true);
    }

    private void reveal() {
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(reducedMotion ? Motion.REDUCED_MS : 150).start();
    }

    /** Called by the activity's existing package receiver; an uninstall cannot leave a stale action. */
    void refresh() {
        load(false);
    }

    /**
     * Reads the catalogue on LOADER, then fills it on the UI thread. reveal: from opening the library; the popup
     * appears once the latest load has filled it, even when a refresh replaced the opening's own load.
     */
    private void load(boolean reveal) {
        dismissActions();
        int load = loads.start(reveal);
        PackageManager pm = activity.getPackageManager();
        String self = activity.getPackageName();
        LOADER.execute(() -> {
            Map<String, ResolveInfo> unique = new LinkedHashMap<>();
            Set<String> tv = new HashSet<>();
            // Prefer the TV entry when an app declares both categories.
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                    if (info.activityInfo == null || !info.activityInfo.exported) continue;
                    String pkg = info.activityInfo.packageName;
                    if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) tv.add(pkg);
                    if (!pkg.equals(self) && !unique.containsKey(pkg)) unique.put(pkg, info);
                }
            }
            Map<String, String> names = new HashMap<>();
            for (Map.Entry<String, ResolveInfo> entry : unique.entrySet()) {
                String pkg = entry.getKey();
                long updated = 0;
                try {
                    PackageInfo info = pm.getPackageInfo(pkg, 0);
                    updated = info.lastUpdateTime;
                } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                    // updated stays 0: the label is read again next time
                }
                Known known = KNOWN.get(pkg);
                if (known == null || known.updated != updated) {
                    known = new Known(updated, loadLabel(pm, entry.getValue()));
                    KNOWN.put(pkg, known);
                }
                names.put(pkg, known.label);
            }
            KNOWN.keySet().retainAll(unique.keySet());
            main.post(() -> {
                if (closed || !loads.isLatest(load)) return;
                tvPackages.clear();
                tvPackages.addAll(tv);
                labels.clear();
                labels.putAll(names);
                fill(new ArrayList<>(unique.values()));
                if (loads.takeReveal()) reveal();
            });
        });
    }

    private void fill(List<ResolveInfo> catalogue) {
        apps = catalogue;
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> LibraryApps.compareAtoZ(label(a), a.activityInfo.packageName,
                label(b), b.activityInfo.packageName, collator));
        showCatalog();
    }

    private String label(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String value = loadLabel(activity.getPackageManager(), app);
        labels.put(pkg, value);
        return value;
    }

    private static String loadLabel(PackageManager pm, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        try { return Cards.cleanLabel(String.valueOf(app.loadLabel(pm)), pkg); }
        catch (RuntimeException unavailable) { return pkg; }
    }

    /** The app's icon from this opening's cache, or loaded on LOADER and set once it arrives. */
    private void bindIcon(ImageView icon, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        icon.setTag(pkg);
        Drawable cached = icons.get(pkg);
        icon.setImageDrawable(cached);
        if (cached != null || !iconsLoading.add(pkg)) return;
        PackageManager pm = activity.getPackageManager();
        LOADER.execute(() -> {
            Drawable loaded;
            try { loaded = app.loadIcon(pm); }
            catch (RuntimeException unavailable) { loaded = null; }
            Drawable result = loaded;
            main.post(() -> {
                iconsLoading.remove(pkg);
                if (closed) return;
                Drawable shownIcon = result != null ? result : activity.getDrawable(R.drawable.lw_ic_apps);
                icons.put(pkg, shownIcon);
                // The view may show another app by now; a view bound to it later takes it from the cache.
                if (pkg.equals(icon.getTag())) icon.setImageDrawable(shownIcon);
            });
        });
    }

    /** What OK does on this app: open it, add it to Home or take it off. */
    private int okLabel(boolean home) {
        if (LibraryApps.okOpens(selectionMode, okOpens, home)) return R.string.library_open;
        return home ? R.string.library_remove_shortcut : R.string.library_add_home;
    }

    /** The screen: title and counts, the filter chips, the grid, and the remote keys' hints. Rebuilt on each fill. */
    private void showCatalog() {
        int homeCount = 0;
        shown.clear();
        for (ResolveInfo app : apps) {
            boolean home = onHome.test(app.activityInfo.packageName);
            if (home) homeCount++;
            if (LibraryApps.shown(filter, home)) shown.add(app);
        }
        panel.removeAllViews();

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(activity.getString(R.string.app_library), 22, R.string.font_medium, R.color.lw_text));
        TextView count = text(activity.getResources().getQuantityString(R.plurals.library_app_count, apps.size(), apps.size())
                + " · " + activity.getString(R.string.library_on_home_count, homeCount),
                14, R.string.font_regular, R.color.lw_text2);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = dp(6);
        titles.addView(count, clp);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        int[] chipLabels = {R.string.library_filter_all, R.string.library_on_home, R.string.library_filter_not_on_home};
        for (int i = 0; i < chips.length; i++) {
            final int which = i;
            TextView chip = text(activity.getString(chipLabels[i]), 14, R.string.font_medium, 0);
            chip.setTextColor(activity.getColorStateList(R.color.lw_on_cream));
            chip.setBackgroundResource(R.drawable.lw_chip);
            chip.setGravity(Gravity.CENTER);
            chip.setPaddingRelative(dp(16), 0, dp(16), 0);
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setSelected(filter == i);
            chip.setOnClickListener(v -> {
                if (filter == which) return;
                filter = which;
                selectedPackage = null;
                selectedPosition = 0;
                showCatalog();
                chips[which].requestFocus();
            });
            chip.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusCard(selectedPosition);
                    return true;
                }
                boolean toStart = rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT;
                boolean toEnd = rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT;
                return toStart && which == 0 || toEnd && which == chips.length - 1;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            if (i > 0) lp.setMarginStart(dp(8));
            header.addView(chip, lp);
            chips[i] = chip;
        }
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        if (shown.isEmpty()) {
            TextView empty = text(activity.getString(R.string.library_empty), 16, R.string.font_regular, R.color.lw_text2);
            empty.setGravity(Gravity.CENTER);
            panel.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
            catalog = null;
        } else {
            RecyclerView list = new RecyclerView(activity);
            catalog = list;
            list.setLayoutManager(new GridLayoutManager(activity, COLUMNS));
            list.setItemAnimator(null);
            list.setOverScrollMode(View.OVER_SCROLL_NEVER);
            list.setClipToPadding(false);
            list.setClipChildren(false);
            // Room for the focused card's 1.04 zoom at the edges.
            list.setPadding(dp(4), dp(6), dp(4), dp(6));
            list.setAdapter(new Catalog());
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1);
            glp.topMargin = dp(16);
            panel.addView(list, glp);
        }

        LinearLayout hints = new LinearLayout(activity);
        hints.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        okHint = hint(hints, activity.getString(R.string.island_key_ok), activity.getString(okLabel(false)));
        hint(hints, activity.getString(R.string.library_key_hold), activity.getString(R.string.library_hold_hint));
        hint(hints, activity.getString(R.string.island_key_back), activity.getString(R.string.island_close));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(8);
        panel.addView(hints, hlp);

        int position = LibraryApps.refocus(indexOf(selectedPackage), selectedPosition, shown.size());
        if (position < 0) {
            chips[filter].post(() -> chips[filter].requestFocus());
            return;
        }
        selectedPosition = position;
        catalog.scrollToPosition(position);
        catalog.post(() -> focusCard(position));
    }

    private int indexOf(String pkg) {
        if (pkg == null) return -1;
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).activityInfo.packageName.equals(pkg)) return i;
        }
        return -1;
    }

    private void focusCard(int position) {
        if (catalog == null || shown.isEmpty()) return;
        int target = Math.max(0, Math.min(position, shown.size() - 1));
        RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(target);
        if (holder != null) {
            holder.itemView.requestFocus();
            return;
        }
        catalog.scrollToPosition(target);
        catalog.post(() -> {
            if (catalog == null) return;
            RecyclerView.ViewHolder later = catalog.findViewHolderForAdapterPosition(target);
            if (later != null) later.itemView.requestFocus();
        });
    }

    /** "OK  Add to Home": a key cap and what it does; returns the text so it can follow the focused app. */
    private TextView hint(LinearLayout row, String key, String action) {
        TextView cap = new TextView(activity, null, 0, R.style.IslandKeycap);
        cap.setText(key);
        row.addView(cap, new LinearLayout.LayoutParams(-2, dp(20)));
        TextView what = new TextView(activity, null, 0, R.style.IslandHint);
        what.setText(action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(dp(8));
        lp.setMarginEnd(dp(20));
        row.addView(what, lp);
        return what;
    }

    private void dismissActions() {
        PopupWindow menu = actionPopup;
        actionPopup = null;
        if (menu != null) menu.dismiss();
    }

    /** Adds or removes the Home shortcut; the list is rebuilt with the focus staying on the app (or its place). */
    private void changeHome(ResolveInfo app, boolean add) {
        if (!LibraryApps.save(activity, app.activityInfo.packageName, add)) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        selectedPackage = app.activityInfo.packageName;
        dismissActions();
        changed.run();
        showCatalog();
    }

    private void open(ResolveInfo app) {
        close();
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(
                new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
        // TV apps keep the leanback launch intent, so an app already running resumes instead of restarting.
        if (tvPackages.contains(app.activityInfo.packageName)) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
        }
    }

    /** Hold OK: Open, Add to Home or Remove from Home, App info, in the LWNGE menu beside the card. */
    private void showActions(ResolveInfo app, View anchor) {
        dismissActions();
        String pkg = app.activityInfo.packageName;
        selectedPackage = pkg;
        boolean home = onHome.test(pkg);
        LwMenu menu = new LwMenu(activity).header(icons.get(pkg), label(app));
        menu.add(R.drawable.lw_ic_chevron, R.string.library_open, () -> open(app));
        menu.add(home ? R.drawable.lw_ic_remove : R.drawable.lw_ic_add,
                home ? R.string.library_remove_shortcut : R.string.library_add_home, () -> changeHome(app, !home));
        Intent info = SystemSettings.appInfo(activity, pkg);
        if (info != null) menu.add(R.drawable.lw_ic_info, R.string.library_app_info, () -> {
            close();
            try {
                activity.startActivity(info);
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
            }
        });
        PopupWindow shownMenu = menu.show(anchor, reducedMotion, null);
        actionPopup = shownMenu;
        shownMenu.setOnDismissListener(() -> {
            if (actionPopup == shownMenu) actionPopup = null;
            anchor.post(() -> {
                if (actionPopup == null && popup.isShowing() && anchor.isAttachedToWindow()) anchor.requestFocus();
            });
        });
    }

    private TextView text(String value, int sp, int font, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        if (color != 0) view.setTextColor(activity.getColor(color));
        view.setTypeface(UiFont.get(activity, font));
        view.setIncludeFontPadding(false);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }

    private final class Catalog extends RecyclerView.Adapter<Row> {
        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout card = new FrameLayout(activity);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, dp(63));
            params.setMargins(dp(6), dp(6), dp(6), dp(6));
            card.setLayoutParams(params);
            card.setBackgroundResource(R.drawable.lw_library_card);
            card.setFocusable(true);
            card.setClickable(true);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            card.addView(content, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(activity);
            icon.setId(R.id.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
            TextView name = text("", 12, R.string.font_medium, R.color.lw_text);
            name.setId(R.id.title);
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
            nameParams.topMargin = dp(5);
            nameParams.setMarginStart(dp(6));
            nameParams.setMarginEnd(dp(6));
            content.addView(name, nameParams);
            View check = new View(activity);
            check.setId(R.id.dot);
            check.setBackgroundResource(R.drawable.lw_check_badge);
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP | Gravity.END);
            badge.setMargins(0, dp(5), dp(5), 0);
            card.addView(check, badge);
            return new Row(card);
        }

        @Override public void onBindViewHolder(@NonNull Row holder, int position) {
            ResolveInfo app = shown.get(position);
            String pkg = app.activityInfo.packageName;
            View card = holder.itemView;
            bindIcon(card.findViewById(R.id.icon), app);
            TextView name = card.findViewById(R.id.title);
            name.setText(label(app));
            boolean home = onHome.test(pkg);
            card.findViewById(R.id.dot).setVisibility(home ? View.VISIBLE : View.GONE);
            card.setContentDescription(label(app));
            card.setOnClickListener(v -> {
                boolean onHomeNow = onHome.test(pkg);
                if (LibraryApps.okOpens(selectionMode, okOpens, onHomeNow)) open(app);
                else changeHome(app, !onHomeNow);
            });
            card.setOnLongClickListener(v -> {
                showActions(app, v);
                return true;
            });
            card.setOnFocusChangeListener((v, focused) -> {
                // Cards grow by 1.04 at most (SPEC section 4).
                float scale = focused && !reducedMotion ? 1.04f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(focused ? 160 : 120).start();
                if (!focused) return;
                selectedPackage = pkg;
                int index = holder.getBindingAdapterPosition();
                if (index != RecyclerView.NO_POSITION) selectedPosition = index;
                if (okHint != null) okHint.setText(okLabel(onHome.test(pkg)));
            });
            card.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int index = holder.getBindingAdapterPosition();
                if (index == RecyclerView.NO_POSITION) return false;
                int column = index % COLUMNS;
                boolean toStart = rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT;
                boolean toEnd = rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (key == KeyEvent.KEYCODE_DPAD_UP && index < COLUMNS) {
                    // Up from the first row: the chosen filter's chip.
                    chips[filter].requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (index + COLUMNS < shown.size()) return false;
                    // A card above the short last row moves to its last card; the last row itself stays.
                    int lastRowStart = ((shown.size() - 1) / COLUMNS) * COLUMNS;
                    if (index < lastRowStart) focusCard(shown.size() - 1);
                    return true;
                }
                // A row's ends stay put instead of wrapping to the next or previous row.
                return toStart && column == 0 || toEnd && (column == COLUMNS - 1 || index == shown.size() - 1);
            });
        }

        @Override public int getItemCount() { return shown.size(); }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        Row(View view) { super(view); }
    }
}
