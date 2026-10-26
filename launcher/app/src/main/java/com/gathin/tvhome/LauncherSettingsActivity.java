package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Remote-friendly settings owned by the launcher (docs/ui/launcher board 09): sections on the left, the open
 * section's rows on the right. Left/Right change a value in place, OK flips a switch or runs a row, Back goes from
 * the rows to the sections and from there out. Each choice is saved on the TV (Config.ON_TV_KEYS) and Home rebuilds
 * with it when it resumes; system settings stay one row away.
 */
public final class LauncherSettingsActivity extends Activity {
    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    /** The choices offered for Apps per row, Rows below the dock and Dock band opacity. */
    static final int[] COLUMN_CHOICES = {4, 5, 6, 7};
    static final int[] ROW_CHOICES = {0, 1, 2, 3};
    static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    static final int[] DIM_CHOICES = {0, 15, 25, 35, 45, 60};
    static final int[] SCREENSAVER_MINUTES = {0, 5, 10, 15, 30, 60};

    private SharedPreferences choices;
    private Config config;
    private final List<Section> sections = new ArrayList<>();
    private LinearLayout sectionList;
    private LinearLayout rowList;
    private ScrollView rowScroll;
    private int openSection = -1;
    private boolean rtl;
    private float density;

    /** One row: a switch, a value Left/Right step through, or something OK runs (a picker, a page). */
    private static final class Row {
        final int title, hint;
        BooleanSupplier on;
        Runnable flip;
        String[] values;
        IntSupplier index;
        IntConsumer choose;
        Runnable action;
        Supplier<String> shown;
        BooleanSupplier enabled = () -> true;
        TextView valueView;
        ImageView switchView;
        View view;

        Row(int title, int hint) {
            this.title = title;
            this.hint = hint;
        }
    }

    private static final class Section {
        final int title;
        final List<Row> rows = new ArrayList<>();

        Section(int title) {
            this.title = title;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        config = Config.load(this);
        density = getResources().getDisplayMetrics().density;
        rtl = getResources().getBoolean(R.bool.rtl_app_grid);
        sectionList = findViewById(R.id.sections);
        rowList = findViewById(R.id.rows);
        rowScroll = findViewById(R.id.rowsScroll);
        ((TextView) findViewById(R.id.settingsVersion)).setText(getString(R.string.settings_version, version()));
        buildSections();
        for (int i = 0; i < sections.size(); i++) sectionList.addView(sectionView(i));
        showSection(0);
        sectionList.getChildAt(0).requestFocus();
    }

    /** Back from Set up again (or a picker's page): the rows show what was saved there. */
    @Override
    protected void onResume() {
        super.onResume();
        if (openSection >= 0) refreshRows();
    }

    // ---- the rows -------------------------------------------------------------------------------------------

    private void buildSections() {
        Section look = new Section(R.string.section_look);
        String[] glassKeys = {Config.GLASS_RIM, Config.GLASS_DEFAULT, Config.GLASS_HALO};
        look.rows.add(choice(R.string.set_focus_style, R.string.set_focus_style_hint,
                labels(R.string.focus_rim, R.string.focus_glass, R.string.focus_halo),
                () -> Math.max(0, indexOf(glassKeys, config.glassStyle)),
                i -> save(choices.edit().putString("glass_style", glassKeys[i]))));
        String[] layouts = {Config.LAYOUT_GRID, Config.LAYOUT_SHELVES};
        look.rows.add(choice(R.string.set_home_layout, R.string.set_home_layout_hint,
                labels(R.string.layout_grid, R.string.layout_shelves),
                () -> Math.max(0, indexOf(layouts, config.homeLayout)),
                i -> save(choices.edit().putString("home_layout", layouts[i]))));
        look.rows.add(toggle(R.string.set_cinematic, R.string.set_cinematic_hint, () -> config.cinematicHome,
                on -> save(choices.edit().putBoolean("cinematic_home", on))));
        look.rows.add(choice(R.string.columns_label, R.string.set_columns_hint, numbers(COLUMN_CHOICES, null),
                () -> Math.max(0, indexOf(COLUMN_CHOICES, config.columns)),
                i -> save(choices.edit().putInt("columns", COLUMN_CHOICES[i]))));
        look.rows.add(toggle(R.string.set_app_names, R.string.set_app_names_hint, () -> config.alwaysShowNames,
                on -> save(choices.edit().putBoolean("always_show_names", on))));
        look.rows.add(choice(R.string.set_animations, R.string.set_animations_hint,
                labels(R.string.anim_full, R.string.anim_reduced, R.string.off),
                () -> !config.animations ? 2 : config.reducedMotion ? 1 : 0,
                i -> save(choices.edit().putBoolean("animations", i != 2).putBoolean("reduced_motion", i == 1)
                        .putBoolean("advanced_effects", i == 0 && config.advancedEffects))));
        Row advanced = toggle(R.string.advanced_effects, R.string.set_advanced_hint,
                () -> config.animations && config.advancedEffects, this::setAdvanced);
        advanced.enabled = () -> config.animations && !config.reducedMotion;
        look.rows.add(advanced);
        look.rows.add(choice(R.string.set_dim, R.string.set_dim_hint, numbers(DIM_CHOICES, "%"),
                () -> nearest(DIM_CHOICES, config.wallpaperDim),
                i -> save(choices.edit().putInt("wallpaper_dim", DIM_CHOICES[i]))));
        sections.add(look);

        Section clock = new Section(R.string.section_clock);
        clock.rows.add(choice(R.string.set_date_position, 0, labels(R.string.date_beside, R.string.date_under),
                () -> config.dateBelowClock ? 1 : 0,
                i -> save(choices.edit().putBoolean("date_below_clock", i == 1))));
        clock.rows.add(toggle(R.string.set_greeting, R.string.set_greeting_hint, () -> config.showGreeting,
                on -> save(choices.edit().putBoolean("show_greeting", on))));
        clock.rows.add(toggle(R.string.set_stats, R.string.set_stats_hint, () -> config.showStats,
                on -> save(choices.edit().putBoolean("show_stats", on))));
        sections.add(clock);

        Section dock = new Section(R.string.section_dock);
        dock.rows.add(choice(R.string.rows_below_dock_label, R.string.set_rows_hint, numbers(ROW_CHOICES, null),
                () -> Math.max(0, indexOf(ROW_CHOICES, config.rowsBelowDock)),
                i -> save(choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, ROW_CHOICES[i]))));
        dock.rows.add(toggle(R.string.set_recent, R.string.set_recent_hint, () -> config.recentRow,
                on -> save(choices.edit().putBoolean("recent_row", on))));
        dock.rows.add(choice(R.string.dock_opacity_label, 0, numbers(DOCK_OPACITIES, "%"),
                () -> nearest(DOCK_OPACITIES, config.dockOpacity),
                i -> save(choices.edit().putInt(Config.DOCK_OPACITY_KEY, DOCK_OPACITIES[i]))));
        if (config.openOnStartChoice) {
            dock.rows.add(action(R.string.open_on_start_label, 0, this::showOpenOnStartPicker, this::openOnStartLabel));
        }
        dock.rows.add(action(R.string.reorder, 0, () -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        }, null));
        sections.add(dock);

        Section background = new Section(R.string.section_background);
        background.rows.add(action(R.string.wallpaper_label, 0, this::showWallpaperPicker, () -> {
            String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
            return "none".equals(current) ? getString(R.string.wallpaper_none)
                    : current != null ? current : getString(R.string.wallpaper_theme_default);
        }));
        sections.add(background);

        Section island = new Section(R.string.section_island);
        island.rows.add(toggle(R.string.set_classic_bar, R.string.set_classic_bar_hint, () -> !config.island,
                classic -> save(choices.edit().putBoolean("island", !classic))));
        sections.add(island);

        Section screensaver = new Section(R.string.section_screensaver);
        String[] minutes = new String[SCREENSAVER_MINUTES.length];
        for (int i = 0; i < minutes.length; i++) {
            minutes[i] = SCREENSAVER_MINUTES[i] == 0 ? getString(R.string.off)
                    : getString(R.string.minutes_short, SCREENSAVER_MINUTES[i]);
        }
        screensaver.rows.add(choice(R.string.set_screensaver, R.string.set_screensaver_hint, minutes,
                () -> nearest(SCREENSAVER_MINUTES, config.homeIdleClockMinutes),
                i -> save(choices.edit().putInt("home_idle_clock_minutes", SCREENSAVER_MINUTES[i]))));
        sections.add(screensaver);

        Section about = new Section(R.string.section_about);
        Row follow = action(R.string.follow_theme, R.string.follow_theme_hint, () -> {
            if (!hasOnTvChoices()) return;
            Config.clearOnTvChoices(choices.edit()).commit();
            changed();
        }, null);
        follow.enabled = this::hasOnTvChoices;
        about.rows.add(follow);
        about.rows.add(action(R.string.set_again, R.string.set_again_hint,
                () -> startActivity(new Intent(this, FirstRunActivity.class)), null));
        about.rows.add(action(R.string.home_settings, 0, () -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        }, null));
        about.rows.add(action(R.string.tv_settings, 0, () -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)), null));
        sections.add(about);
    }

    private Row toggle(int title, int hint, BooleanSupplier on, Consumer<Boolean> set) {
        Row row = new Row(title, hint);
        row.on = on;
        row.flip = () -> set.accept(!on.getAsBoolean());
        return row;
    }

    private Row choice(int title, int hint, String[] values, IntSupplier index, IntConsumer choose) {
        Row row = new Row(title, hint);
        row.values = values;
        row.index = index;
        row.choose = choose;
        return row;
    }

    private Row action(int title, int hint, Runnable run, Supplier<String> shown) {
        Row row = new Row(title, hint);
        row.action = run;
        row.shown = shown;
        return row;
    }

    private String[] labels(int... ids) {
        String[] out = new String[ids.length];
        for (int i = 0; i < ids.length; i++) out[i] = getString(ids[i]);
        return out;
    }

    private static String[] numbers(int[] values, String suffix) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i] + (suffix == null ? "" : suffix);
        return out;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }

    /** The choice closest to a theme's value, which can lie between the steps offered here. */
    static int nearest(int[] values, int value) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - value) < Math.abs(values[best] - value)) best = i;
        }
        return best;
    }

    // ---- the two panes ------------------------------------------------------------------------------------------

    private View sectionView(int index) {
        TextView view = text(getString(sections.get(index).title), 16, R.string.font_medium);
        view.setTextColor(getColorStateList(R.color.lw_section_text));
        view.setBackgroundResource(R.drawable.lw_section);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPaddingRelative(dp(14), 0, dp(14), 0);
        view.setFocusable(true);
        view.setClickable(true);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        view.setOnFocusChangeListener((v, focused) -> {
            if (focused) showSection(index);
        });
        view.setOnClickListener(v -> focusFirstRow());
        view.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            boolean toRows = rtl ? code == KeyEvent.KEYCODE_DPAD_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (toRows) {
                focusFirstRow();
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_UP && index == 0
                    || code == KeyEvent.KEYCODE_DPAD_DOWN && index == sections.size() - 1
                    || code == (rtl ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
        });
        return view;
    }

    private void showSection(int index) {
        if (index == openSection) return;
        openSection = index;
        for (int i = 0; i < sectionList.getChildCount(); i++) sectionList.getChildAt(i).setSelected(i == index);
        rowList.removeAllViews();
        for (Section section : sections) for (Row row : section.rows) row.view = null;
        Section section = sections.get(index);
        TextView heading = text(getString(section.title).toUpperCase(getResources().getConfiguration().getLocales().get(0)),
                12, R.string.font_medium);
        heading.setTextColor(getColor(R.color.lw_text2));
        heading.setLetterSpacing(0.08f);
        heading.setPaddingRelative(dp(16), dp(10), 0, dp(10));
        rowList.addView(heading);
        for (Row row : section.rows) rowList.addView(rowView(row));
        rowScroll.scrollTo(0, 0);
    }

    private void focusFirstRow() {
        for (int i = 0; i < rowList.getChildCount(); i++) {
            View child = rowList.getChildAt(i);
            if (child.isFocusable()) {
                child.requestFocus();
                return;
            }
        }
    }

    private View rowView(Row row) {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinimumHeight(dp(52));
        view.setPaddingRelative(dp(16), dp(8), dp(16), dp(8));
        view.setBackgroundResource(R.drawable.lw_setting_row);
        view.setFocusable(true);
        view.setClickable(true);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setDuplicateParentStateEnabled(true);
        TextView title = text(getString(row.title), 16, R.string.font_medium);
        title.setTextColor(getColorStateList(R.color.lw_on_cream));
        title.setDuplicateParentStateEnabled(true);
        words.addView(title);
        if (row.hint != 0) {
            TextView hint = text(getString(row.hint), 12, R.string.font_regular);
            hint.setTextColor(getColorStateList(R.color.lw_on_cream2));
            hint.setDuplicateParentStateEnabled(true);
            hint.setSingleLine(false);
            hint.setMaxLines(2);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
            hlp.topMargin = dp(4);
            words.addView(hint, hlp);
        }
        view.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.switchView = null;
        row.valueView = null;
        if (row.on != null) {
            row.switchView = new ImageView(this);
            row.switchView.setDuplicateParentStateEnabled(true);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(36), dp(20));
            slp.setMarginStart(dp(16));
            view.addView(row.switchView, slp);
        } else if (row.values != null || row.shown != null) {
            row.valueView = text("", 16, R.string.font_regular);
            row.valueView.setTextColor(getColorStateList(R.color.lw_on_cream));
            row.valueView.setDuplicateParentStateEnabled(true);
            row.valueView.setCompoundDrawablePadding(dp(10));
            row.valueView.setCompoundDrawableTintList(getColorStateList(R.color.lw_on_cream));
            row.valueView.setMaxWidth(dp(260));
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
            vlp.setMarginStart(dp(16));
            view.addView(row.valueView, vlp);
        }
        row.view = view;
        view.setOnClickListener(v -> {
            if (!row.enabled.getAsBoolean()) return;
            if (row.flip != null) row.flip.run();
            else if (row.values != null) step(row, 1, true);
            else if (row.action != null) row.action.run();
            refreshRows();
        });
        view.setOnKeyListener((v, code, event) -> {
            if (code == KeyEvent.KEYCODE_BACK) {
                // Back from the rows goes to the sections (on the key's release, so the release does not also
                // leave the screen from there).
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    sectionList.getChildAt(openSection).requestFocus();
                }
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            boolean next = rtl ? code == KeyEvent.KEYCODE_DPAD_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT;
            boolean previous = rtl ? code == KeyEvent.KEYCODE_DPAD_RIGHT : code == KeyEvent.KEYCODE_DPAD_LEFT;
            if (next || previous) {
                if (row.values != null) {
                    if (row.enabled.getAsBoolean()) step(row, next ? 1 : -1, false);
                    refreshRows();
                } else if (previous) {
                    // Rows with no value to change: back towards the sections.
                    sectionList.getChildAt(openSection).requestFocus();
                }
                return true;
            }
            int at = rowList.indexOfChild(v);
            return code == KeyEvent.KEYCODE_DPAD_DOWN && at == rowList.getChildCount() - 1
                    || code == KeyEvent.KEYCODE_DPAD_UP && at == 1;
        });
        updateRow(row);
        return view;
    }

    /** Moves a value one step: Left/Right stop at the ends, OK wraps round to the first. */
    private void step(Row row, int by, boolean wrap) {
        int count = row.values.length;
        int now = row.index.getAsInt();
        int to = now + by;
        if (wrap) to = (to + count) % count;
        if (to < 0 || to >= count || to == now) return;
        row.choose.accept(to);
    }

    private void updateRow(Row row) {
        boolean enabled = row.enabled.getAsBoolean();
        row.view.setAlpha(enabled ? 1f : 0.45f);
        if (row.switchView != null) {
            row.switchView.setImageResource(row.on.getAsBoolean() ? R.drawable.lw_switch_on : R.drawable.lw_switch_off);
        }
        if (row.valueView == null) return;
        if (row.values != null) {
            int index = row.index.getAsInt();
            row.valueView.setText(row.values[Math.max(0, Math.min(index, row.values.length - 1))]);
            Drawable back = chevron(R.drawable.lw_ic_chevron_back), on = chevron(R.drawable.lw_ic_chevron);
            row.valueView.setCompoundDrawablesRelative(rtl ? on : back, null, rtl ? back : on, null);
        } else {
            row.valueView.setText(row.shown.get());
            row.valueView.setCompoundDrawablesRelative(null, null, chevron(R.drawable.lw_ic_chevron), null);
        }
    }

    private Drawable chevron(int res) {
        Drawable d = getDrawable(res).mutate();
        d.setBounds(0, 0, dp(15), dp(15));
        return d;
    }

    /** After a change: the saved choices read back through Config, so every row shows what Home will use. */
    private void refreshRows() {
        config = Config.load(this);
        for (Section section : sections) {
            for (Row row : section.rows) if (row.view != null) updateRow(row);
        }
    }

    // ---- saving -------------------------------------------------------------------------------------------------

    private void save(SharedPreferences.Editor edit) {
        edit.apply();
        changed();
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        if (!enabled) {
            save(choices.edit().putBoolean("advanced_effects", false));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.advanced_effects)
                .setMessage(R.string.advanced_effects_warning)
                .setPositiveButton(R.string.enable, (dialog, which) -> {
                    save(choices.edit().putBoolean("advanced_effects", true));
                    refreshRows();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean hasOnTvChoices() {
        for (String key : Config.ON_TV_KEYS) if (choices.contains(key)) return true;
        return false;
    }

    // ---- pickers --------------------------------------------------------------------------------------------------

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size() : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refreshRows();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refreshRows();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refreshRows();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private TextView text(String value, int sp, int font) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTypeface(UiFont.get(this, font));
        view.setIncludeFontPadding(false);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
