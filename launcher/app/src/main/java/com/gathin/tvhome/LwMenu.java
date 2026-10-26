package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The LWNGE menu (docs/ui/launcher board 07): an opaque panel beside the thing it is about, a header with the app's
 * icon and name, then 42dp items with a 15dp icon; the focused item is cream. Home's tile menu and the app library's
 * hold-OK menu both use it.
 */
final class LwMenu {

    private final Activity activity;
    private final float density;
    private final List<Object[]> items = new ArrayList<>();
    private Drawable icon;
    private CharSequence title;

    LwMenu(Activity activity) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    /** The header: the app's icon and name. */
    LwMenu header(Drawable appIcon, CharSequence name) {
        icon = appIcon;
        title = name;
        return this;
    }

    LwMenu add(int iconRes, int label, Runnable action) {
        items.add(new Object[]{iconRes, label, action});
        return this;
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * Shows the menu beside {@code anchor}: to its end when there is room, else to its start, centred on it and kept
     * inside the 48 x 27dp safe area. Back closes it; {@code dismissed} runs however it closes.
     */
    PopupWindow show(View anchor, boolean reducedMotion, Runnable dismissed) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.lw_menu_bg);
        int pad = dp(8);
        panel.setPadding(pad, pad, pad, pad);
        PopupWindow popup = new PopupWindow(panel, dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        if (dismissed != null) popup.setOnDismissListener(dismissed::run);

        if (title != null) {
            LinearLayout head = new LinearLayout(activity);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPaddingRelative(dp(8), dp(4), dp(8), dp(8));
            if (icon != null) {
                ImageView image = new ImageView(activity);
                image.setImageDrawable(icon);
                image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(28), dp(28));
                ilp.setMarginEnd(dp(10));
                head.addView(image, ilp);
            }
            TextView name = text(title, 16f, R.string.font_medium);
            name.setTextColor(activity.getColor(R.color.lw_text));
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            panel.addView(head);
        }
        for (Object[] item : items) {
            panel.addView(item(popup, (Integer) item[0], (Integer) item[1], (Runnable) item[2]),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        }

        panel.measure(View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = panel.getMeasuredWidth(), height = panel.getMeasuredHeight();
        View root = activity.getWindow().getDecorView();
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        Rect safe = new Rect(dp(48), dp(27), root.getWidth() - dp(48), root.getHeight() - dp(27));
        int gap = dp(16);
        boolean rtl = anchor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int after = rtl ? at[0] - gap - width : at[0] + anchor.getWidth() + gap;
        int before = rtl ? at[0] + anchor.getWidth() + gap : at[0] - gap - width;
        int x = after >= safe.left && after + width <= safe.right ? after : before;
        x = Math.max(safe.left, Math.min(x, safe.right - width));
        int y = at[1] + anchor.getHeight() / 2 - height / 2;
        y = Math.max(safe.top, Math.min(y, safe.bottom - height));

        popup.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);
        // Everything else under a scrim (SPEC: ink 72%; a window dim is the one that costs no extra layer).
        View container = panel.getRootView();
        if (container.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.6f;
            activity.getWindowManager().updateViewLayout(container, params);
        }
        // Menus fade in with an 8dp rise over 150 ms (SPEC section 6).
        panel.setAlpha(0f);
        panel.setTranslationY(dp(8));
        panel.animate().alpha(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 150)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        View first = panel.getChildAt(title != null ? 1 : 0);
        if (first != null) first.requestFocus();
        return popup;
    }

    private View item(PopupWindow popup, int iconRes, int label, Runnable action) {
        TextView row = text(activity.getString(label), 14f, R.string.font_medium);
        row.setTextColor(activity.getColorStateList(R.color.lw_on_cream));
        row.setBackgroundResource(R.drawable.lw_menu_item);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(12), 0, dp(12), 0);
        row.setFocusable(true);
        row.setClickable(true);
        Drawable d = activity.getDrawable(iconRes).mutate();
        d.setBounds(0, 0, dp(15), dp(15));
        row.setCompoundDrawablesRelative(d, null, null, null);
        row.setCompoundDrawablePadding(dp(12));
        row.setCompoundDrawableTintList(activity.getColorStateList(R.color.lw_on_cream));
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK release that ends the long press opening the menu must not choose its first item; Left/Right and the
        // ends stay inside the menu.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (down) {
                    if (event.getRepeatCount() == 0) pressed[0] = true;
                    return true;
                }
                boolean activate = pressed[0];
                pressed[0] = false;
                if (activate) {
                    popup.dismiss();
                    action.run();
                }
                return true;
            }
            ViewGroup parent = (ViewGroup) v.getParent();
            int index = parent.indexOfChild(v), firstItem = title != null ? 1 : 0;
            return down && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP && index == firstItem
                    || code == KeyEvent.KEYCODE_DPAD_DOWN && index == parent.getChildCount() - 1);
        });
        return row;
    }

    private TextView text(CharSequence value, float sp, int font) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
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
