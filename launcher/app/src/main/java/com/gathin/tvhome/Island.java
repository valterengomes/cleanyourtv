package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The island (docs/ui/launcher/SPEC.md section 5): one black pill at the top centre in place of the top bar's pills.
 * <ul>
 *   <li>Rest: the Inputs, Wi-Fi, Apps and Settings icons, nothing focused.</li>
 *   <li>Tray: Up from the dock's row focuses it; the four become labelled pills and Left/Right move between them.</li>
 *   <li>Open: OK on a pill grows the island into a panel with the four as tabs on top and the tab's content below.</li>
 *   <li>Live: for 8 s after something is plugged into an HDMI port, "PlayStation 5 on HDMI 2"; OK switches to it.</li>
 * </ul>
 * Back walks out one level (content, tabs, tray, the dock tile it came from); Home closes it. IslandShape draws the
 * one black shape behind every state and animates its bounds between them.
 */
final class Island {

    static final int INPUTS = 0, WIFI = 1, APPS = 2, SETTINGS = 3;

    /** Where a source goes: a card in the Inputs tab (MainActivity's dropdown fills rows from the same list). */
    interface InputSink {
        /**
         * @param title  the port or source, "HDMI 1"
         * @param device what is on it ("Apple TV"), or null when the TV does not say
         * @param state  TvInputManager's INPUT_STATE_*, or -1 when the TV gives none worth showing
         */
        void add(int iconRes, String title, String device, int state, Runnable action);
        void separator();
        int count();
    }

    /** One row of the Settings tab. */
    interface RowSink {
        void add(int label, Runnable action);
    }

    interface Host {
        /** Fills the Inputs tab; false when the TV's own source panel is the whole list (openSourcePanel then). */
        boolean collectInputs(InputSink sink);
        void openSourcePanel();
        void openNetworkSettings();
        void openLibrary();
        void settingsRows(RowSink sink);
        /** Focus goes back to the dock tile the island was entered from. */
        void focusDock();
    }

    private static final int REST = 0, TRAY = 1, OPEN = 2;
    /** SPEC section 6. */
    private static final int TRAY_MS = 200, OPEN_MS = 260, CLOSE_MS = 180, LIVE_MS = 220, SCRIM_MS = 150;
    private static final int CONTENT_FADE_MS = 120, CONTENT_DELAY_MS = 100;
    private static final long LIVE_SHOWN_MS = 8000;
    private static final int[] ICONS = {R.drawable.lw_ic_input, R.drawable.lw_ic_wifi, R.drawable.lw_ic_apps, R.drawable.lw_ic_settings};

    private final Activity activity;
    private final Host host;
    private final float density;
    private final boolean animations, reducedMotion;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PathInterpolator islandCurve = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    private final PathInterpolator closeCurve = new PathInterpolator(0.3f, 0f, 1f, 1f);

    private final View scrim;
    private final IslandShape shape;
    private final LinearLayout bar;
    private final TextView[] pills = new TextView[4];
    private final Drawable[] pillIcons = new Drawable[4];
    private final View live;
    private final TextView liveText;
    private final LinearLayout panel;
    private final TextView[] tabs = new TextView[4];
    private final ScrollView scroll;
    private final LinearLayout content;
    private final View hint;
    private final ColorStateList warn;

    private int state = REST;
    private int tab = -1;
    /** The pill focused last, for Up from the dock; -1 for the first one shown. */
    private int lastPill = -1;
    /** How long the shape takes to follow the bar's next layout (0: jump). */
    private long barMoveMs;
    private Runnable liveAction;
    private final Runnable endLive = this::endLive;
    private boolean offlineLabel;

    Island(Activity activity, FrameLayout root, Host host, boolean animations, boolean reducedMotion) {
        this.activity = activity;
        this.host = host;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        View layer = LayoutInflater.from(activity).inflate(R.layout.island, root, false);
        root.addView(layer);
        scrim = layer.findViewById(R.id.islandScrim);
        shape = layer.findViewById(R.id.islandShape);
        bar = layer.findViewById(R.id.islandBar);
        live = layer.findViewById(R.id.islandLive);
        liveText = layer.findViewById(R.id.islandLiveText);
        panel = layer.findViewById(R.id.islandPanel);
        scroll = layer.findViewById(R.id.islandScroll);
        content = layer.findViewById(R.id.islandContent);
        hint = layer.findViewById(R.id.islandHint);
        warn = new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{activity.getColor(R.color.lw_ink), activity.getColor(R.color.lw_warn)});

        int[] pillIds = {R.id.islandInputs, R.id.islandWifi, R.id.islandApps, R.id.islandSettings};
        int[] tabIds = {R.id.islandTabInputs, R.id.islandTabWifi, R.id.islandTabApps, R.id.islandTabSettings};
        for (int i = 0; i < 4; i++) {
            final int which = i;
            pills[i] = bar.findViewById(pillIds[i]);
            pillIcons[i] = activity.getDrawable(ICONS[i]).mutate();
            pills[i].setContentDescription(pills[i].getText());
            pills[i].setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    lastPill = which;
                    if (state == REST) showTray();
                }
            });
            pills[i].setOnClickListener(v -> open(which));
            pills[i].setOnKeyListener(this::onBarKey);

            tabs[i] = panel.findViewById(tabIds[i]);
            // Tab icons at 15dp next to the 14sp label (SPEC section 5).
            Drawable tabIcon = activity.getDrawable(ICONS[i]).mutate();
            int tabIconSize = Math.round(15 * density);
            tabIcon.setBounds(0, 0, tabIconSize, tabIconSize);
            tabs[i].setCompoundDrawablesRelative(tabIcon, null, null, null);
            tabs[i].setOnFocusChangeListener((v, focused) -> {
                if (focused && state == OPEN) showTab(which);
            });
            tabs[i].setOnClickListener(v -> content.requestFocus(View.FOCUS_DOWN));
            tabs[i].setOnKeyListener(this::onTabKey);
        }
        live.setOnKeyListener(this::onBarKey);
        live.setOnClickListener(v -> {
            Runnable action = liveAction;
            endLive();
            collapse();
            if (action != null) action.run();
        });

        restLook();
        // The shape follows the bar wherever its layout puts it, except while the panel stands in for it.
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (state == OPEN) return;
            float rim = state == TRAY ? 2f * density : 1.5f * density;
            int rimColor = activity.getColor(state == TRAY ? R.color.lw_cream : R.color.lw_line);
            shape.moveTo(bounds(bar), bar.getHeight() / 2f, rimColor, rim, barMoveMs, islandCurve);
            barMoveMs = 0;
        });
        // A new tab's content changes the panel's height: the shape grows or shrinks with it.
        panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (state != OPEN || (b - t == ob - ot && r - l == or - ol)) return;
            if (shape.isMoving()) return;   // opening: the open animation is already on its way there
            shape.moveTo(bounds(panel), 22f * density, activity.getColor(R.color.lw_line), 1.5f * density,
                    duration(TRAY_MS), islandCurve);
        });
        // Focus leaving the island by any other way (a touch, a dialog) puts it back to rest.
        layer.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (state == REST) return;
            boolean inBar = newFocus != null && isInside(newFocus, bar);
            boolean inPanel = newFocus != null && isInside(newFocus, panel);
            if (state == TRAY && !inBar || state == OPEN && !inPanel) {
                collapse();
                return;
            }
            if (state == OPEN && tab >= 0) tabs[tab].setSelected(newFocus != null && isInside(newFocus, content));
        });
    }

    // ---- what Home asks of it ---------------------------------------------------------------------------------

    /** Up from the dock: the live activity when it is showing, else the pill used last. */
    void focusFromDock() {
        if (live.getVisibility() == View.VISIBLE) {
            live.requestFocus();
            return;
        }
        View pill = lastPill >= 0 && pills[lastPill].getVisibility() == View.VISIBLE ? pills[lastPill] : firstPill();
        pill.requestFocus();
    }

    /** Focuses one pill (the tray), e.g. when the app library it opened closes. */
    void focusPill(int which) {
        if (pills[which].getVisibility() == View.VISIBLE) pills[which].requestFocus();
        else firstPill().requestFocus();
    }

    /** The first pill shown, for Home with no tiles to focus. */
    View firstPill() {
        for (TextView pill : pills) if (pill.getVisibility() == View.VISIBLE) return pill;
        return pills[SETTINGS];
    }

    /**
     * Shows or hides one pill and its tab: Inputs only on a TV with inputs to list (MainActivity.updateInputsButton),
     * Wi-Fi with show_network_pill, Apps with the app library.
     */
    void setShown(int which, boolean shown) {
        int v = shown ? View.VISIBLE : View.GONE;
        // During the live activity the pills are hidden and remember their own state in their tag.
        if (live.getVisibility() == View.VISIBLE) pills[which].setTag(v);
        else pills[which].setVisibility(v);
        tabs[which].setVisibility(v);
        if (!shown && lastPill == which) lastPill = -1;
        if (state == REST) restLook();
        else if (state == TRAY) trayLook();
    }

    /** Opens the island on one tab (a pill's OK, the remote's Inputs key, VIEW_INPUTS). */
    void open(int which) {
        if (live.getVisibility() == View.VISIBLE) endLive();
        if (tabs[which].getVisibility() != View.VISIBLE) return;
        if (which == APPS) {
            // Until the library moves into the island (phase 3), Apps opens it as before.
            collapse();
            host.openLibrary();
            return;
        }
        if (which == INPUTS && !buildInputs(new ArrayList<>())) {
            collapse();
            host.openSourcePanel();
            return;
        }
        boolean wasOpen = state == OPEN;
        state = OPEN;
        tab = -1;
        if (!wasOpen) {
            panel.setAlpha(0f);
            panel.setVisibility(View.VISIBLE);
        }
        showTab(which);
        // Focus moves into the panel before the bar hides: hiding the focused pill first would send focus elsewhere.
        tabs[which].requestFocus();
        if (wasOpen) return;
        bar.setVisibility(View.INVISIBLE);
        fade(scrim, true, duration(SCRIM_MS));
        panel.post(() -> {
            if (state != OPEN) return;
            boolean morph = animations && !reducedMotion;
            shape.moveTo(bounds(panel), 22f * density, activity.getColor(R.color.lw_line), 1.5f * density,
                    morph ? OPEN_MS : 0, islandCurve);
            // Reduced motion: no shape morph, the panel fades in place (SPEC section 6).
            panel.animate().alpha(1f).setStartDelay(morph ? CONTENT_DELAY_MS : 0)
                    .setDuration(animations ? CONTENT_FADE_MS : 0).start();
        });
    }

    /** Home, an app opening, focus going elsewhere: straight back to rest. */
    void collapse() {
        if (state == REST) return;
        boolean wasOpen = state == OPEN;
        boolean hadFocus = bar.hasFocus() || panel.hasFocus();
        state = REST;
        if (wasOpen) {
            panel.animate().cancel();
            panel.setVisibility(View.GONE);
            content.removeAllViews();
            tab = -1;
            fade(scrim, false, duration(SCRIM_MS));
            bar.setVisibility(View.VISIBLE);
        }
        barMoveMs = duration(wasOpen ? CLOSE_MS : TRAY_MS);
        restLook();
        if (hadFocus) host.focusDock();
    }

    /**
     * Something was plugged in: the live activity for 8 s, unless the island is in use. OK on it (Up from the dock
     * reaches it) runs {@code action}.
     */
    void showLive(String text, Runnable action) {
        if (state != REST) return;
        liveAction = action;
        liveText.setText(text);
        for (TextView pill : pills) pill.setTag(pill.getVisibility());
        for (TextView pill : pills) pill.setVisibility(View.GONE);
        live.setVisibility(View.VISIBLE);
        bar.setPaddingRelative(Math.round(1 * density), 0, Math.round(1 * density), 0);
        barMoveMs = duration(LIVE_MS);
        handler.removeCallbacks(endLive);
        handler.postDelayed(endLive, LIVE_SHOWN_MS);
    }

    private void endLive() {
        handler.removeCallbacks(endLive);
        if (live.getVisibility() != View.VISIBLE) return;
        boolean hadFocus = live.hasFocus();
        live.setVisibility(View.GONE);
        liveAction = null;
        for (TextView pill : pills) {
            Object shown = pill.getTag();
            pill.setVisibility(shown instanceof Integer ? (Integer) shown : View.VISIBLE);
        }
        barMoveMs = duration(LIVE_MS);
        restLook();
        if (hadFocus) host.focusDock();
    }

    /**
     * The Wi-Fi pill at rest: its icon alone while online; the crossed-out icon and "No internet" (or "Offline") in the
     * warn colour while not, until it reconnects (SPEC section 7, edge states).
     */
    void refreshNetwork() {
        Net net = Net.read(activity);
        boolean trouble = !net.online || !net.validated;
        TextView pill = pills[WIFI];
        pillIcons[WIFI] = activity.getDrawable(trouble ? R.drawable.lw_ic_wifi_off : R.drawable.lw_ic_wifi).mutate();
        pill.setCompoundDrawableTintList(trouble ? warn : activity.getColorStateList(R.color.lw_on_cream));
        pill.setTextColor(trouble ? warn : activity.getColorStateList(R.color.lw_on_cream));
        offlineLabel = trouble;
        pill.setContentDescription(activity.getString(!net.online ? R.string.offline
                : !net.validated ? R.string.island_no_internet : R.string.wifi));
        if (state == REST) restLook();
        else if (state == TRAY) trayLook();
        // The open Wi-Fi tab follows, unless focus is on its button: rebuilding would take the focus away.
        if (state == OPEN && tab == WIFI && !content.hasFocus()) {
            tab = -1;
            showTab(WIFI);
        }
    }

    // ---- looks ----------------------------------------------------------------------------------------------------

    /** Rest: icons only, 19.5dp and 13dp apart, in a 38dp pill (the Wi-Fi pill keeps its warning label). */
    private void restLook() {
        int icon = Math.round(19.5f * density);
        int gap = Math.round(13 * density);
        bar.getLayoutParams().height = Math.round(38 * density);
        if (live.getVisibility() != View.VISIBLE) {
            bar.setPaddingRelative(Math.round(15 * density), 0, Math.round(15 * density), 0);
        }
        boolean first = true;
        for (int i = 0; i < 4; i++) {
            TextView pill = pills[i];
            boolean label = i == WIFI && offlineLabel;
            setIcon(pill, i, icon);
            pill.setText(label ? pill.getContentDescription() : null);
            pill.setCompoundDrawablePadding(label ? Math.round(6 * density) : 0);
            pill.setPaddingRelative(0, 0, 0, 0);
            pill.setBackground(null);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pill.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.setMarginStart(first || pill.getVisibility() != View.VISIBLE ? 0 : gap);
            if (pill.getVisibility() == View.VISIBLE) first = false;
            pill.setLayoutParams(lp);
        }
        bar.requestLayout();
    }

    /** Tray: the four as labelled pills (15dp icon, 14sp) in a 48dp island with a cream rim. */
    private void trayLook() {
        int icon = Math.round(15 * density);
        bar.getLayoutParams().height = Math.round(48 * density);
        bar.setPaddingRelative(Math.round(6 * density), 0, Math.round(6 * density), 0);
        boolean first = true;
        for (int i = 0; i < 4; i++) {
            TextView pill = pills[i];
            setIcon(pill, i, icon);
            pill.setText(i == WIFI && offlineLabel ? pill.getContentDescription() : labelOf(i));
            pill.setCompoundDrawablePadding(Math.round(7 * density));
            pill.setPaddingRelative(Math.round(15 * density), 0, Math.round(15 * density), 0);
            pill.setBackgroundResource(R.drawable.lw_island_pill);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pill.getLayoutParams();
            lp.height = Math.round(36 * density);
            lp.setMarginStart(first || pill.getVisibility() != View.VISIBLE ? 0 : Math.round(2 * density));
            if (pill.getVisibility() == View.VISIBLE) first = false;
            pill.setLayoutParams(lp);
        }
        bar.requestLayout();
    }

    private void showTray() {
        state = TRAY;
        barMoveMs = duration(TRAY_MS);
        trayLook();
    }

    private String labelOf(int which) {
        return activity.getString(which == INPUTS ? R.string.inputs : which == WIFI ? R.string.wifi
                : which == APPS ? R.string.island_apps : R.string.settings);
    }

    private void setIcon(TextView pill, int which, int size) {
        Drawable d = pillIcons[which];
        d.setBounds(0, 0, size, size);
        pill.setCompoundDrawablesRelative(d, null, null, null);
    }

    // ---- keys -----------------------------------------------------------------------------------------------------

    /** Tray and live activity: Left/Right stay in the bar, Up stays put, Down and Back return to the dock. */
    private boolean onBarKey(View v, int keyCode, KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    collapse();
                    if (live.getVisibility() == View.VISIBLE) endLive();
                    host.focusDock();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return FocusFinder.getInstance().findNextFocus(bar, v, View.FOCUS_LEFT) == null;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return FocusFinder.getInstance().findNextFocus(bar, v, View.FOCUS_RIGHT) == null;
            default:
                return false;
        }
    }

    /** Tabs: Down goes into the content, Up stays put, Back closes the panel to the tray. */
    private boolean onTabKey(View v, int keyCode, KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) content.requestFocus(View.FOCUS_DOWN);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) closeToTray();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return FocusFinder.getInstance().findNextFocus(panel, v, View.FOCUS_LEFT) == null;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return FocusFinder.getInstance().findNextFocus(panel, v, View.FOCUS_RIGHT) == null;
            default:
                return false;
        }
    }

    /** Content: moves stay inside it; Up off its top row and Back go to the tab. */
    private boolean onContentKey(View v, int keyCode, KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int direction;
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (down && tab >= 0) tabs[tab].requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: direction = View.FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: direction = View.FOCUS_DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: direction = View.FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: direction = View.FOCUS_RIGHT; break;
            default: return false;
        }
        if (FocusFinder.getInstance().findNextFocus(content, v, direction) != null) return false;
        if (direction == View.FOCUS_UP && down && tab >= 0) tabs[tab].requestFocus();
        return true;
    }

    /** Back from the tabs: the panel shrinks back into the tray with that tab's pill focused. */
    private void closeToTray() {
        int which = tab;
        state = TRAY;
        bar.setVisibility(View.VISIBLE);
        barMoveMs = duration(CLOSE_MS);
        trayLook();
        // The pill takes focus before the panel hides, as in open().
        focusPill(which);
        panel.animate().cancel();
        panel.setVisibility(View.GONE);
        content.removeAllViews();
        tab = -1;
        fade(scrim, false, duration(SCRIM_MS));
    }

    // ---- tab content -------------------------------------------------------------------------------------------------

    private void showTab(int which) {
        if (which == tab) return;
        tab = which;
        for (int i = 0; i < 4; i++) tabs[i].setSelected(false);
        content.removeAllViews();
        hint.setVisibility(View.GONE);
        switch (which) {
            case INPUTS:
                List<View> cards = new ArrayList<>();
                if (!buildInputs(cards)) {
                    addButton(content, activity.getString(R.string.inputs), host::openSourcePanel);
                } else if (cards.isEmpty()) {
                    addNote(activity.getString(R.string.no_inputs));
                } else {
                    addGrid(cards, 4);
                    hint.setVisibility(View.VISIBLE);
                }
                break;
            case WIFI:
                buildNetwork();
                break;
            case APPS:
                addNote(activity.getString(R.string.island_library_hint));
                addButton(content, activity.getString(R.string.app_library), host::openLibrary);
                break;
            default:
                List<View> rows = new ArrayList<>();
                host.settingsRows((label, action) -> rows.add(button(activity.getString(label), action)));
                addGrid(rows, 2);
                break;
        }
        limitHeight();
    }

    /** Source cards into {@code cards}; false when the TV's source panel is the whole list. */
    private boolean buildInputs(List<View> cards) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        return host.collectInputs(new InputSink() {
            @Override
            public void add(int iconRes, String title, String device, int state, Runnable action) {
                View card = inflater.inflate(R.layout.island_card, content, false);
                ImageView icon = card.findViewById(R.id.cardIcon);
                icon.setImageResource(iconRes == R.drawable.ic_livetv ? R.drawable.lw_ic_aerial
                        : iconRes == R.drawable.ic_av ? R.drawable.lw_ic_input : R.drawable.lw_ic_hdmi);
                ((TextView) card.findViewById(R.id.cardName)).setText(title);
                TextView meta = card.findViewById(R.id.cardMeta);
                String detail = device;
                if (state == TvInputManager.INPUT_STATE_CONNECTED) {
                    meta.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.lw_dot, 0, 0, 0);
                    if (detail == null) detail = activity.getString(R.string.connected);
                } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
                    meta.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.lw_dot_off, 0, 0, 0);
                    if (detail == null) detail = activity.getString(R.string.not_connected);
                }
                if (detail == null) meta.setVisibility(View.GONE);
                else meta.setText(detail);
                card.setOnClickListener(v -> {
                    collapse();
                    action.run();
                });
                cards.add(card);
            }

            @Override
            public void separator() {
                // Cards need no separator: the icon tells HDMI, AV and the aerial apart.
            }

            @Override
            public int count() {
                return cards.size();
            }
        });
    }

    /** Wi-Fi: the Wi-Fi card (network, signal, band) and the cable card with the way to network settings. */
    private void buildNetwork() {
        Net net = Net.read(activity);
        LayoutInflater inflater = LayoutInflater.from(activity);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);

        View wifi = inflater.inflate(R.layout.island_card, row, false);
        wifi.setFocusable(false);
        wifi.findViewById(R.id.cardIcon).setVisibility(View.GONE);
        TextView kicker = wifi.findViewById(R.id.cardKicker);
        kicker.setVisibility(View.VISIBLE);
        kicker.setText(net.wifi ? R.string.island_wifi_connected : R.string.wifi);
        TextView name = wifi.findViewById(R.id.cardName);
        name.setTextSize(18f);
        name.setText(net.wifi ? (net.ssid != null ? net.ssid : activity.getString(R.string.wifi))
                : activity.getString(net.online ? R.string.island_not_connected : R.string.offline));
        TextView meta = wifi.findViewById(R.id.cardMeta);
        if (net.wifi && !net.validated) {
            meta.setText(R.string.island_no_internet);
            meta.setTextColor(activity.getColor(R.color.lw_warn));
        } else if (net.wifi && net.level >= 0) {
            Drawable bars = new SignalBars(activity, net.level, density);
            bars.setBounds(0, 0, Math.round(14 * density), Math.round(10 * density));
            meta.setCompoundDrawablesRelative(bars, null, null, null);
            int[] words = {R.string.island_signal_weak, R.string.island_signal_fair, R.string.island_signal_good,
                    R.string.island_signal_strong};
            String text = activity.getString(words[net.level]);
            if (net.ghz != null) text += " · " + activity.getString(R.string.island_ghz, net.ghz);
            meta.setText(text);
        } else {
            meta.setVisibility(View.GONE);
        }
        LinearLayout.LayoutParams wlp = (LinearLayout.LayoutParams) wifi.getLayoutParams();
        wlp.weight = 1.2f;
        wlp.height = ViewGroup.LayoutParams.MATCH_PARENT;   // as tall as the cable card beside it
        wlp.setMarginEnd(Math.round(8 * density));
        row.addView(wifi, wlp);

        LinearLayout cable = (LinearLayout) inflater.inflate(R.layout.island_card, row, false);
        cable.setFocusable(false);
        cable.findViewById(R.id.cardIcon).setVisibility(View.GONE);
        cable.findViewById(R.id.cardMeta).setVisibility(View.GONE);
        TextView cableKicker = cable.findViewById(R.id.cardKicker);
        cableKicker.setVisibility(View.VISIBLE);
        cableKicker.setText(R.string.island_cable);
        TextView cableName = cable.findViewById(R.id.cardName);
        cableName.setText(net.ethernet ? R.string.connected : R.string.island_not_plugged_in);
        cableName.setTextColor(activity.getColor(net.ethernet ? R.color.lw_text : R.color.lw_text2));
        View settings = button(activity.getString(R.string.island_network_settings), host::openNetworkSettings);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(36 * density));
        slp.topMargin = Math.round(10 * density);
        cable.addView(settings, slp);
        row.addView(cable);
        content.addView(row);
    }

    private TextView button(String label, Runnable action) {
        TextView b = (TextView) LayoutInflater.from(activity).inflate(R.layout.island_button, content, false);
        b.setText(label);
        b.setOnClickListener(v -> {
            collapse();
            action.run();
        });
        b.setOnKeyListener(this::onContentKey);
        return b;
    }

    private void addButton(ViewGroup parent, String label, Runnable action) {
        parent.addView(button(label, action));
    }

    private void addNote(String text) {
        TextView note = new TextView(activity);
        note.setText(text);
        note.setTextColor(activity.getColor(R.color.lw_text2));
        note.setTextSize(12f);
        note.setTypeface(UiFont.get(activity, R.string.font_regular));
        note.setPaddingRelative(Math.round(4 * density), 0, 0, Math.round(10 * density));
        content.addView(note);
    }

    /** Lays views out {@code columns} to a row, 12dp apart, every cell the same width. */
    private void addGrid(List<View> cells, int columns) {
        int gap = Math.round(12 * density);
        for (int start = 0; start < cells.size(); start += columns) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            for (int i = 0; i < columns; i++) {
                View cell = start + i < cells.size() ? cells.get(start + i) : new View(activity);
                if (cell.isFocusable()) cell.setOnKeyListener(this::onContentKey);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        // Buttons are 42dp; cards in a row all take the tallest one's height.
                        cell instanceof TextView ? Math.round(42 * density) : ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (i > 0) lp.setMarginStart(gap);
                row.addView(cell, lp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (start > 0) rlp.topMargin = gap;
            content.addView(row, rlp);
        }
    }

    /** The open island stays within 280dp (SPEC section 2): longer content scrolls. */
    private void limitHeight() {
        int panelWidth = Math.round(590 * density) - panel.getPaddingLeft() - panel.getPaddingRight();
        content.measure(View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int room = Math.round((hint.getVisibility() == View.VISIBLE ? 182 : 210) * density);
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = content.getMeasuredHeight() > room ? room : ViewGroup.LayoutParams.WRAP_CONTENT;
        scroll.setLayoutParams(lp);
        scroll.scrollTo(0, 0);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private long duration(int ms) {
        if (!animations) return 0;
        return reducedMotion ? Math.min(ms, Motion.REDUCED_MS) : ms;
    }

    private void fade(View v, boolean in, long ms) {
        v.animate().cancel();
        if (in) {
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1f).setDuration(ms).start();
        } else {
            v.animate().alpha(0f).setDuration(ms).withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    private static RectF bounds(View v) {
        return new RectF(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
    }

    private static boolean isInside(View v, View parent) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            if (p == parent) return true;
        }
        return false;
    }

    /** The connection as the island shows it. */
    static final class Net {
        boolean online, validated, wifi, ethernet;
        /** The network's name, or null when Android hides it. */
        String ssid;
        /** 0 (weak) to 3 (strong), or -1 when unknown. */
        int level = -1;
        /** "2.4", "5" or "6", or null. */
        String ghz;

        static Net read(Context context) {
            Net net = new Net();
            try {
                ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
                // All networks, not just the active one: with a VPN up the active network is the VPN itself.
                for (Network n : cm != null ? cm.getAllNetworks() : new Network[0]) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                    net.online = true;
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) net.validated = true;
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) net.ethernet = true;
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) net.wifi = true;
                }
                if (net.wifi) {
                    WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                    if (info != null) {
                        net.level = WifiManager.calculateSignalLevel(info.getRssi(), 4);
                        net.ghz = band(info.getFrequency());
                        net.ssid = networkName(info.getSSID());
                    }
                }
            } catch (RuntimeException ignored) {
                // A TV whose connectivity service misbehaves shows the island as offline rather than taking Home down.
            }
            return net;
        }

        /** The Wi-Fi band for a channel's frequency: "2.4", "5" or "6" (GHz), or null when unknown. */
        static String band(int mhz) {
            return mhz >= 5925 ? "6" : mhz >= 4900 ? "5" : mhz > 0 ? "2.4" : null;
        }

        /** WifiInfo.getSSID() without its quotes, or null when Android hides the name (no location access). */
        static String networkName(String ssid) {
            if (ssid == null) return null;
            String name = ssid.replace("\"", "").trim();
            return name.isEmpty() || name.contains("unknown") || name.equals("0x") ? null : name;
        }
    }

    /** Four rising bars, the first {@code level + 1} in the ok colour. */
    private static final class SignalBars extends Drawable {
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int level;
        private final float density;

        SignalBars(Context context, int level, float density) {
            this.level = level;
            this.density = density;
            on.setColor(context.getColor(R.color.lw_ok));
            off.setColor(context.getColor(R.color.lw_line_strong));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            float w = getBounds().width(), h = getBounds().height();
            float bar = w / 5.5f, gap = (w - 4 * bar) / 3f, r = density;
            for (int i = 0; i < 4; i++) {
                float top = h * (1f - (i + 1) / 4f);
                float left = getBounds().left + i * (bar + gap);
                canvas.drawRoundRect(left, getBounds().top + top, left + bar, getBounds().bottom, r, r, i <= level ? on : off);
            }
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
