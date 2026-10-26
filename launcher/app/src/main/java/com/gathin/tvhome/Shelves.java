package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Home layout Shelves (docs/ui/launcher board 11, home_layout "shelves"): one row per group, Watch, Listen, Play and
 * Tools, in place of the dock and the grid. An app goes to the row its Play Store category gives (Video, Audio, Game,
 * everything else Tools) until the owner moves it with hold OK; inside a row the app opened last comes first. The
 * island stays on top; Up from the first row goes to it.
 */
final class Shelves {

    static final int WATCH = 0, LISTEN = 1, PLAY = 2, TOOLS = 3;
    private static final int[] TITLES = {R.string.shelf_watch, R.string.shelf_listen, R.string.shelf_play, R.string.shelf_tools};
    /** The owner's moves, pkg=row;pkg=row; never cleared by a theme push (they are the owner's own sorting). */
    static final String MOVES_KEY = "shelf_moves";

    interface Host {
        /** Up from the first row. */
        void focusIsland();
    }

    private final Activity activity;
    private final AppAdapter adapter;
    private final Host host;
    private final ScrollView scroll;
    private final LinearLayout rows;
    private final float density;
    private final boolean reducedMotion;
    private final SharedPreferences choices;
    private String lastFocusedPkg;

    Shelves(Activity activity, FrameLayout container, AppAdapter adapter, Host host, boolean reducedMotion) {
        this.activity = activity;
        this.adapter = adapter;
        this.host = host;
        this.reducedMotion = reducedMotion;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.choices = activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        scroll = new ScrollView(activity);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(4), 0, dp(40));
        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        container.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    // ---- grouping ------------------------------------------------------------------------------------------------

    /** The row a Play Store category puts an app in. */
    static int shelfFor(int category) {
        switch (category) {
            case ApplicationInfo.CATEGORY_VIDEO: return WATCH;
            case ApplicationInfo.CATEGORY_AUDIO: return LISTEN;
            case ApplicationInfo.CATEGORY_GAME: return PLAY;
            default: return TOOLS;
        }
    }

    static Map<String, Integer> decodeMoves(String text) {
        Map<String, Integer> moves = new HashMap<>();
        if (text == null) return moves;
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                int row = Integer.parseInt(part.substring(eq + 1).trim());
                if (row >= WATCH && row <= TOOLS) moves.put(part.substring(0, eq).trim(), row);
            } catch (NumberFormatException ignored) {
                // a damaged entry just drops that app back to its category's row
            }
        }
        return moves;
    }

    static String encodeMoves(Map<String, Integer> moves) {
        List<String> keys = new ArrayList<>(moves.keySet());
        Collections.sort(keys);
        StringBuilder out = new StringBuilder();
        for (String key : keys) out.append(key).append('=').append(moves.get(key)).append(';');
        return out.toString();
    }

    /** Inside a row: the app opened last first, never-opened ones after, A to Z. */
    static int compare(long openedA, String labelA, long openedB, String labelB) {
        if (openedA != openedB) return Long.compare(openedB, openedA);
        return labelA.compareToIgnoreCase(labelB);
    }

    private int rowOf(AppAdapter.Entry e, Map<String, Integer> moves, PackageManager pm) {
        Integer moved = moves.get(e.pkg);
        if (moved != null) return moved;
        try {
            return shelfFor(pm.getApplicationInfo(e.pkg, 0).category);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return TOOLS;
        }
    }

    // ---- the view ------------------------------------------------------------------------------------------------

    /** Rebuilds the rows from Home's app tiles; focus goes back to the app that had it. */
    void update() {
        boolean hadFocus = scroll.hasFocus();
        Map<String, Integer> moves = decodeMoves(choices.getString(MOVES_KEY, null));
        PackageManager pm = activity.getPackageManager();
        List<List<AppAdapter.Entry>> groups = new ArrayList<>();
        for (int i = 0; i < TITLES.length; i++) groups.add(new ArrayList<>());
        for (AppAdapter.Entry e : adapter.homeApps()) groups.get(rowOf(e, moves, pm)).add(e);
        Map<String, Long> opened = new HashMap<>();
        for (List<AppAdapter.Entry> group : groups) {
            for (AppAdapter.Entry e : group) opened.put(e.pkg, LastOpened.get(activity, e.pkg));
            Collections.sort(group, (a, b) -> compare(opened.get(a.pkg), a.label, opened.get(b.pkg), b.label));
        }
        rows.removeAllViews();
        boolean firstRow = true;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).isEmpty()) continue;
            rows.addView(row(i, groups.get(i), firstRow));
            firstRow = false;
        }
        if (hadFocus) focus(lastFocusedPkg);
    }

    /** Focus on the app that had it, or the first tile. */
    void focus(String pkg) {
        View target = pkg == null ? null : rows.findViewWithTag(pkg);
        if (target == null) target = firstTile();
        if (target != null) target.requestFocus();
    }

    void focusFirst() {
        focus(lastFocusedPkg);
    }

    private View firstTile() {
        if (rows.getChildCount() == 0) return null;
        LinearLayout tiles = (LinearLayout) ((HorizontalScrollView) ((ViewGroup) rows.getChildAt(0)).getChildAt(1)).getChildAt(0);
        return tiles.getChildCount() > 0 ? tiles.getChildAt(0) : null;
    }

    private View row(int shelf, List<AppAdapter.Entry> apps, boolean firstRow) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClipChildren(false);
        LinearLayout title = new LinearLayout(activity);
        title.setGravity(Gravity.CENTER_VERTICAL);
        Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
        TextView name = text(activity.getString(TITLES[shelf]).toUpperCase(locale), 12, R.string.font_medium);
        name.setLetterSpacing(0.08f);
        TextView count = text(activity.getResources().getQuantityString(R.plurals.library_app_count, apps.size(), apps.size()),
                12, R.string.font_regular);
        title.addView(name);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMarginStart(dp(10));
        title.addView(count, clp);
        title.setPaddingRelative(0, dp(14), 0, dp(10));
        row.addView(title);
        setTitleBright(title, false);

        HorizontalScrollView strip = new HorizontalScrollView(activity);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        strip.setClipToPadding(false);
        strip.setClipChildren(false);
        LinearLayout tiles = new LinearLayout(activity);
        tiles.setClipChildren(false);
        // Room for the focused tile's zoom at the row's ends.
        tiles.setPaddingRelative(dp(6), dp(6), dp(6), dp(6));
        for (int i = 0; i < apps.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(140), dp(79));
            if (i > 0) lp.setMarginStart(dp(14));
            tiles.addView(tile(apps.get(i), shelf, title, firstRow, i == 0, i == apps.size() - 1), lp);
        }
        strip.addView(tiles);
        row.addView(strip);
        return row;
    }

    /** The focused row's title at full brightness, the others in text2 (SPEC section 7). */
    private void setTitleBright(LinearLayout title, boolean bright) {
        ((TextView) title.getChildAt(0)).setTextColor(activity.getColor(bright ? R.color.lw_text : R.color.lw_text2));
        ((TextView) title.getChildAt(1)).setTextColor(activity.getColor(bright ? R.color.lw_text2 : R.color.lw_text3));
    }

    private View tile(AppAdapter.Entry e, int shelf, LinearLayout title, boolean firstRow, boolean first, boolean last) {
        FrameLayout tile = new FrameLayout(activity);
        tile.setTag(e.pkg);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(e.label);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 10 * density);
            }
        });
        tile.setClipToOutline(true);
        if (e.banner != null) {
            ImageView banner = new ImageView(activity);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            banner.setImageDrawable(e.banner);
            tile.setBackgroundColor(activity.getColor(R.color.lw_panel2));
            tile.addView(banner, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // An app without banner art: its icon on a card tinted with the icon's colour, the name under it.
            tile.setBackground(new ColorDrawable(AppAdapter.tintedCard(adapter.colorOf(e))));
            ImageView icon = new ImageView(activity);
            icon.setImageDrawable(e.icon);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP | Gravity.START);
            ilp.setMargins(dp(12), dp(10), dp(12), 0);
            tile.addView(icon, ilp);
            TextView name = text(e.label, 14, R.string.font_medium);
            name.setTextColor(activity.getColor(R.color.lw_text));
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
            nlp.setMargins(dp(12), 0, dp(12), dp(10));
            tile.addView(name, nlp);
        }
        // The focus rim, on top of the artwork: a view of its own drawn last, following the tile's focused state.
        View rim = new View(activity);
        rim.setBackgroundResource(R.drawable.lw_shelf_focus);
        rim.setDuplicateParentStateEnabled(true);
        rim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(rim, new FrameLayout.LayoutParams(-1, -1));
        tile.setOnClickListener(v -> adapter.open(e));
        tile.setOnLongClickListener(v -> {
            showMenu(e, shelf, v);
            return true;
        });
        tile.setOnFocusChangeListener((v, focused) -> {
            // The rim alone marks focus: a zoom inside the row's scroll strip gets its edges cut off.
            setTitleBright(title, focused || hasFocusInRow(title));
            if (focused) {
                lastFocusedPkg = e.pkg;
                // Keep the whole row, title included, in view.
                View row = (View) title.getParent();
                scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, row.getTop() - dp(4))));
            }
        });
        tile.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP && firstRow) {
                host.focusIsland();
                return true;
            }
            boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            boolean toStart = rtl ? code == KeyEvent.KEYCODE_DPAD_RIGHT : code == KeyEvent.KEYCODE_DPAD_LEFT;
            boolean toEnd = rtl ? code == KeyEvent.KEYCODE_DPAD_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                View row = (View) title.getParent();
                return rows.indexOfChild(row) == rows.getChildCount() - 1;
            }
            // A row's ends stay put instead of jumping to another row.
            return toStart && first || toEnd && last;
        });
        return tile;
    }

    private boolean hasFocusInRow(LinearLayout title) {
        View row = (View) title.getParent();
        return row != null && row.hasFocus();
    }

    /** Hold OK: move the app to another row, or its App info page. */
    private void showMenu(AppAdapter.Entry e, int shelf, View anchor) {
        LwMenu menu = new LwMenu(activity).header(e.icon, e.label);
        for (int target = WATCH; target <= TOOLS; target++) {
            if (target == shelf) continue;
            final int to = target;
            menu.add(R.drawable.lw_ic_move, MOVE_LABELS[to], () -> move(e.pkg, to));
        }
        Intent info = SystemSettings.appInfo(activity, e.pkg);
        if (info != null) menu.add(R.drawable.lw_ic_info, R.string.library_app_info, () -> {
            try {
                activity.startActivity(info);
            } catch (ActivityNotFoundException | SecurityException ex) {
                Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        });
        menu.show(anchor, reducedMotion, null);
    }

    private static final int[] MOVE_LABELS = {R.string.shelf_move_watch, R.string.shelf_move_listen,
            R.string.shelf_move_play, R.string.shelf_move_tools};

    private void move(String pkg, int to) {
        Map<String, Integer> moves = decodeMoves(choices.getString(MOVES_KEY, null));
        moves.put(pkg, to);
        choices.edit().putString(MOVES_KEY, encodeMoves(moves)).apply();
        lastFocusedPkg = pkg;
        update();
        focus(pkg);
    }

    private TextView text(String value, float sp, int font) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(UiFont.get(activity, font));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
