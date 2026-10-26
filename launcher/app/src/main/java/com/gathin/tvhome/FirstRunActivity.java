package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * First run (docs/ui/launcher board 12): three steps, the step list on the left. 1 Favourites: the TV's apps, OK picks
 * or unpicks one, as many as the dock holds. 2 Your name, for the greeting. 3 Photos: where to send them from (the
 * LWNGE app on the computer). Every step can be skipped. Shown once, on a TV with no theme; Launcher settings › About
 * › Set up again runs it again, and "Add favourites" on an empty dock place opens step 1 alone.
 */
public final class FirstRunActivity extends Activity {
    /** Only the favourites step ("Add favourites" on an empty dock place). */
    static final String EXTRA_FAVOURITES_ONLY = "com.gathin.tvhome.extra.FAVOURITES_ONLY";
    /** Set once first run was finished or skipped: Home never opens it on its own again. */
    static final String DONE_KEY = "first_run_done";

    private SharedPreferences choices;
    private float density;
    private int maxPicks;
    private boolean favouritesOnly;
    private int step;
    private final Set<String> picked = new LinkedHashSet<>();
    private LinearLayout steps;
    private TextView title, description;
    private FrameLayout content;
    private EditText nameField;

    /** On a TV with no theme, the first time Home starts: true when Home should open first run now. */
    static boolean due(Context context) {
        SharedPreferences choices = context.getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        if (choices.getBoolean(DONE_KEY, false)) return false;
        File dir = Config.dir(context);
        // A themed TV (an LWNGE or CleanYourTV install) was set up from the computer: no first run there.
        if (dir != null && new File(dir, Config.FILE_NAME).isFile()) {
            choices.edit().putBoolean(DONE_KEY, true).apply();
            return false;
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        Config config = Config.load(this);
        maxPicks = Math.max(1, config.columns);
        favouritesOnly = getIntent().getBooleanExtra(EXTRA_FAVOURITES_ONLY, false);
        // The dock as it is now starts picked.
        PackageManager pm = getPackageManager();
        for (String pkg : config.dockApps) {
            if (picked.size() >= maxPicks) break;
            if (pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null) picked.add(pkg);
        }
        setContentView(buildFrame());
        show(0);
    }

    // ---- the frame: steps on the left, the step's content on the right ------------------------------------------

    private View buildFrame() {
        LinearLayout frame = new LinearLayout(this);
        frame.setBackgroundColor(getColor(R.color.lw_bg));
        frame.setPadding(dp(48), dp(27), dp(48), dp(20));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPaddingRelative(0, dp(20), dp(32), 0);
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        TextView welcome = text(getString(R.string.fr_welcome).toUpperCase(locale), 12, R.string.font_medium, R.color.lw_text2);
        welcome.setLetterSpacing(0.08f);
        left.addView(welcome);
        title = text("", 32, R.string.font_medium, R.color.lw_text);
        title.setSingleLine(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(14);
        left.addView(title, tlp);
        description = text("", 16, R.string.font_regular, R.color.lw_text2);
        description.setSingleLine(false);
        description.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(12);
        left.addView(description, dlp);
        steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(32);
        left.addView(steps, slp);
        if (!favouritesOnly) {
            addStep(1, R.string.fr_step_fav, getString(R.string.fr_step_fav_sub, maxPicks));
            addStep(2, R.string.fr_step_name, getString(R.string.fr_step_name_sub));
            addStep(3, R.string.fr_step_photos, getString(R.string.fr_step_photos_sub));
        }
        frame.addView(left, new LinearLayout.LayoutParams(0, -1, 0.42f));
        content = new FrameLayout(this);
        frame.addView(content, new LinearLayout.LayoutParams(0, -1, 0.58f));
        return frame;
    }

    private void addStep(int number, int name, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView circle = text(String.valueOf(number), 12, R.string.font_medium, R.color.lw_text2);
        circle.setGravity(Gravity.CENTER);
        row.addView(circle, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(getString(name), 16, R.string.font_regular, R.color.lw_text2));
        TextView subtitle = text(sub, 12, R.string.font_regular, R.color.lw_text3);
        subtitle.setSingleLine(false);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = dp(3);
        words.addView(subtitle, wlp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -2, 1f);
        rlp.setMarginStart(dp(12));
        row.addView(words, rlp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(16);
        steps.addView(row, lp);
    }

    /** The current step's number in a cream disc, the others outlined (board 12). */
    private void markSteps() {
        for (int i = 0; i < steps.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) steps.getChildAt(i);
            TextView circle = (TextView) row.getChildAt(0);
            boolean current = i == step;
            circle.setBackgroundResource(current ? R.drawable.lw_step_on : R.drawable.lw_step_off);
            circle.setTextColor(getColor(current ? R.color.lw_ink : R.color.lw_text2));
            TextView name = (TextView) ((LinearLayout) row.getChildAt(1)).getChildAt(0);
            name.setTextColor(getColor(current ? R.color.lw_text : R.color.lw_text2));
        }
    }

    private void show(int which) {
        step = which;
        markSteps();
        content.removeAllViews();
        switch (which) {
            case 0: showFavourites(); break;
            case 1: showName(); break;
            default: showPhotos(); break;
        }
    }

    private void next() {
        if (favouritesOnly || step == 2) {
            finishRun();
            return;
        }
        show(step + 1);
    }

    private void finishRun() {
        choices.edit().putBoolean(DONE_KEY, true).apply();
        finish();
    }

    // ---- step 1: favourites ---------------------------------------------------------------------------------------

    private void showFavourites() {
        title.setText(R.string.fr_fav_title);
        description.setText(getString(R.string.fr_fav_desc));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = text("", 16, R.string.font_medium, R.color.lw_text);
        head.addView(count, new LinearLayout.LayoutParams(0, -2, 1f));
        addHint(head, getString(R.string.island_key_ok), getString(R.string.fr_pick_hint));
        column.addView(head);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setUseDefaultMargins(false);
        List<ResolveInfo> apps = tvApps();
        int gap = dp(10);
        for (int i = 0; i < apps.size(); i++) {
            ResolveInfo app = apps.get(i);
            String pkg = app.activityInfo.packageName;
            View card = appCard(app, picked.contains(pkg));
            card.setOnClickListener(v -> {
                if (picked.contains(pkg)) picked.remove(pkg);
                else if (picked.size() < maxPicks) picked.add(pkg);
                v.findViewById(R.id.dot).setVisibility(picked.contains(pkg) ? View.VISIBLE : View.GONE);
                count.setText(getString(R.string.fr_picked, picked.size(), maxPicks));
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 4), GridLayout.spec(i % 4, 1f));
            lp.width = 0;
            lp.height = dp(64);
            lp.setMargins(i % 4 == 0 ? 0 : gap / 2, gap / 2, i % 4 == 3 ? 0 : gap / 2, gap / 2);
            grid.addView(card, lp);
        }
        count.setText(getString(R.string.fr_picked, picked.size(), maxPicks));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(4), dp(8), dp(4), dp(8));
        scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
        glp.topMargin = dp(10);
        column.addView(scroll, glp);
        column.addView(buttons(() -> {
            saveFavourites();
            next();
        }));
        content.addView(column);
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    /** The TV's apps with a TV entry point, A to Z, Home itself left out. */
    private List<ResolveInfo> tvApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            apps.add(r);
        }
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(String.valueOf(a.loadLabel(pm)), String.valueOf(b.loadLabel(pm))));
        return apps;
    }

    private View appCard(ResolveInfo app, boolean on) {
        PackageManager pm = getPackageManager();
        FrameLayout card = new FrameLayout(this);
        card.setBackgroundResource(R.drawable.lw_library_card);
        card.setFocusable(true);
        card.setClickable(true);
        LinearLayout inside = new LinearLayout(this);
        inside.setOrientation(LinearLayout.VERTICAL);
        inside.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        Drawable drawable;
        try {
            drawable = app.loadIcon(pm);
        } catch (RuntimeException unavailable) {
            drawable = getDrawable(R.drawable.lw_ic_apps);
        }
        icon.setImageDrawable(drawable);
        inside.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView name = text(String.valueOf(app.loadLabel(pm)), 13, R.string.font_medium, R.color.lw_text);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = dp(5);
        nlp.setMarginStart(dp(6));
        nlp.setMarginEnd(dp(6));
        inside.addView(name, nlp);
        card.addView(inside, new FrameLayout.LayoutParams(-1, -1));
        View check = new View(this);
        check.setId(R.id.dot);
        check.setBackgroundResource(R.drawable.lw_check_badge);
        check.setVisibility(on ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(5), dp(5), 0);
        card.addView(check, clp);
        card.setContentDescription(app.loadLabel(pm));
        return card;
    }

    /** Saved as an on-TV choice: the dock in the order picked, until Follow theme or a theme push. */
    private void saveFavourites() {
        choices.edit().putString(Config.FAVOURITES_KEY, TextUtils.join(",", picked)).apply();
        changed();
    }

    // ---- step 2: your name ----------------------------------------------------------------------------------------

    private void showName() {
        title.setText(R.string.fr_name_title);
        description.setText(R.string.fr_name_desc);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_VERTICAL);
        nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setHint(R.string.fr_name_hint);
        nameField.setText(Config.load(this).greetingName);
        nameField.setTextColor(getColor(R.color.lw_text));
        nameField.setHintTextColor(getColor(R.color.lw_text3));
        nameField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        nameField.setTypeface(UiFont.get(this, R.string.font_regular));
        nameField.setBackgroundResource(R.drawable.lw_field);
        nameField.setPaddingRelative(dp(18), 0, dp(18), 0);
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameField.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            saveName();
            next();
            return true;
        });
        column.addView(nameField, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(24);
        LinearLayout buttonRow = (LinearLayout) buttons(() -> {
            saveName();
            next();
        });
        column.addView(buttonRow, blp);
        // On a remote: OK in the field brings up the on-screen keyboard (a TV maps the centre key to 5 in a text
        // field otherwise), and Down leaves the field for Continue.
        nameField.setOnKeyListener((v, code, event) -> {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    InputMethodManager ime = getSystemService(InputMethodManager.class);
                    if (ime != null) ime.showSoftInput(nameField, InputMethodManager.SHOW_IMPLICIT);
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
                buttonRow.getChildAt(buttonRow.getChildCount() - 1).requestFocus();
                return true;
            }
            return false;
        });
        content.addView(column, new FrameLayout.LayoutParams(-1, -1));
        nameField.requestFocus();
    }

    private void saveName() {
        String name = nameField.getText().toString().trim();
        choices.edit().putString(Config.GREETING_NAME_KEY, name).apply();
        changed();
    }

    // ---- step 3: photos -------------------------------------------------------------------------------------------

    private void showPhotos() {
        title.setText(R.string.fr_photos_title);
        description.setText(R.string.fr_photos_desc);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.BOTTOM);
        TextView done = button(getString(R.string.fr_done), true);
        done.setOnClickListener(v -> finishRun());
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.addView(done, new LinearLayout.LayoutParams(-2, dp(44)));
        column.addView(row);
        content.addView(column, new FrameLayout.LayoutParams(-1, -1));
        done.requestFocus();
    }

    // ---- parts --------------------------------------------------------------------------------------------------

    /** Skip for now and Continue, at the bottom right. */
    private View buttons(Runnable onContinue) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
        TextView skip = button(getString(R.string.fr_skip), false);
        skip.setOnClickListener(v -> next());
        row.addView(skip, lp);
        TextView go = button(getString(R.string.fr_continue), true);
        go.setOnClickListener(v -> onContinue.run());
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, dp(44));
        glp.setMarginStart(dp(12));
        row.addView(go, glp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(12);
        row.setLayoutParams(rlp);
        return row;
    }

    private TextView button(String label, boolean filled) {
        TextView b = text(label, 16, R.string.font_medium, 0);
        b.setTextColor(getColorStateList(R.color.lw_on_cream));
        b.setBackgroundResource(filled ? R.drawable.lw_button_filled : R.drawable.lw_island_pill);
        b.setGravity(Gravity.CENTER);
        b.setPaddingRelative(dp(22), 0, dp(22), 0);
        b.setFocusable(true);
        b.setClickable(true);
        return b;
    }

    private void addHint(LinearLayout row, String key, String action) {
        TextView cap = new TextView(this, null, 0, R.style.IslandKeycap);
        cap.setText(key);
        row.addView(cap, new LinearLayout.LayoutParams(-2, dp(20)));
        TextView what = new TextView(this, null, 0, R.style.IslandHint);
        what.setText(action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(dp(8));
        lp.setMarginEnd(0);
        row.addView(what, lp);
    }

    /** Home rebuilds with the new dock or name when it next resumes (Config.settingsChanged). */
    private void changed() {
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Back steps back through the steps, and out from the first (Skip for now's way, without saving).
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !favouritesOnly && step > 0) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) show(step - 1);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (!favouritesOnly) choices.edit().putBoolean(DONE_KEY, true).apply();
        super.onBackPressed();
    }

    private TextView text(String value, float sp, int font, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(UiFont.get(this, font));
        if (color != 0) view.setTextColor(getColor(color));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    /** Arrays.asList for the favourites read back, without empty names. */
    static List<String> splitFavourites(String saved) {
        List<String> out = new ArrayList<>();
        if (saved == null) return out;
        for (String pkg : Arrays.asList(saved.split(","))) {
            String clean = pkg.trim();
            if (!clean.isEmpty() && !out.contains(clean)) out.add(clean);
        }
        return out;
    }
}
