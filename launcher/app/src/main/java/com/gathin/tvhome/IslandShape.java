package com.gathin.tvhome;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The island's one black shape (docs/ui/launcher/SPEC.md section 5): a rounded rectangle behind the bar at rest,
 * the tray and the open panel. Changing state animates its bounds, corner radius and rim, never a blur or a shadow,
 * so the island grows and shrinks as one object while only this view redraws.
 */
public class IslandShape extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF from = new RectF(), to = new RectF(), now = new RectF();
    private float fromRadius, toRadius, radius;
    private int fromRim, toRim;
    private float fromRimWidth, toRimWidth;
    private ValueAnimator animator;

    public IslandShape(Context context, AttributeSet attrs) {
        super(context, attrs);
        fill.setColor(context.getColor(R.color.lw_island));
        rim.setStyle(Paint.Style.STROKE);
    }

    /**
     * Moves the shape to {@code bounds} (in this view's coordinates) with that corner radius and rim. {@code ms} 0 jumps
     * there; otherwise it animates from wherever it is now. An empty current shape always jumps.
     */
    void moveTo(RectF bounds, float cornerRadius, int rimColor, float rimWidth, long ms, TimeInterpolator curve) {
        if (animator != null) animator.cancel();
        if (ms <= 0 || now.isEmpty()) {
            now.set(bounds);
            radius = cornerRadius;
            rim.setColor(rimColor);
            rim.setStrokeWidth(rimWidth);
            invalidate();
            return;
        }
        from.set(now);
        to.set(bounds);
        fromRadius = radius;
        toRadius = cornerRadius;
        fromRim = rim.getColor();
        toRim = rimColor;
        fromRimWidth = rim.getStrokeWidth();
        toRimWidth = rimWidth;
        animator = ValueAnimator.ofFloat(0f, 1f).setDuration(ms);
        animator.setInterpolator(curve);
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            now.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                    lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
            radius = lerp(fromRadius, toRadius, t);
            rim.setColor(blend(fromRim, toRim, t));
            rim.setStrokeWidth(lerp(fromRimWidth, toRimWidth, t));
            invalidate();
        });
        animator.start();
    }

    /** True while the shape is still on its way: content waits for it before it fades in. */
    boolean isMoving() {
        return animator != null && animator.isRunning();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static int blend(int a, int b, float t) {
        int alpha = Math.round(lerp(a >>> 24, b >>> 24, t));
        int red = Math.round(lerp((a >> 16) & 0xFF, (b >> 16) & 0xFF, t));
        int green = Math.round(lerp((a >> 8) & 0xFF, (b >> 8) & 0xFF, t));
        int blue = Math.round(lerp(a & 0xFF, b & 0xFF, t));
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (now.isEmpty()) return;
        canvas.drawRoundRect(now, radius, radius, fill);
        if (rim.getStrokeWidth() > 0f && (rim.getColor() >>> 24) > 0) {
            // The rim sits just outside the shape, as the mockups' outer ring does.
            float h = rim.getStrokeWidth() / 2f;
            canvas.drawRoundRect(now.left - h, now.top - h, now.right + h, now.bottom + h, radius + h, radius + h, rim);
        }
    }
}
