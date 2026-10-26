package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The focus highlight (docs/ui/launcher/SPEC.md section 4). One instance floats over the grid and is moved with
 * spring animations between tiles. Three looks (config glass_style):
 * <ul>
 *   <li>rim (default): a 2dp cream rim and a soft glow in the focused app's colour;</li>
 *   <li>glass: a cream 16% slab with a 42% hairline, reaching a little past the tile;</li>
 *   <li>halo: a 1.5dp ring in the app's colour and a softer ring around it, no cream.</li>
 * </ul>
 * Everything is drawn once into the view's hardware layer: the spring only moves the view, so no frame redraws the
 * glow (no live shadow or blur, which the 1-2 GB TVs this runs on cannot afford).
 */
public class GlassView extends View {

    private static final int CREAM = 0xF5F2EC;

    private final Paint slab = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    /** Glow colour as 0xRRGGBB: the focused app's colour, a festival's or reorder's accent, cream when none. */
    private int glowRgb = CREAM;
    /** Glow strength at its inner edge: an app colour at about half alpha (SPEC: 50-60%), cream fainter. */
    private int glowAlpha = 0x40;
    private boolean glass;
    private boolean halo;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 10f * density;
        rim.setStyle(Paint.Style.STROKE);
        glow.setStyle(Paint.Style.STROKE);
        slab.setColor(0x29000000 | CREAM);   // cream 16%
        // Cache the highlight as a texture: the spring only changes translation, so the GPU moves it without
        // replaying the glow rings on every frame (that path is software-rendered on the MT5895).
        setLayerType(LAYER_TYPE_HARDWARE, null);
        applyStyle();
    }

    /** Tints the glow (and the halo's ring); 0 goes back to the plain cream highlight. */
    void setAccent(int argb) {
        if (argb == 0) {
            glowRgb = CREAM;
            glowAlpha = 0x40;
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x8C;   // 55%
        }
        applyStyle();
        invalidate();
    }

    /** One of Config's GLASS_* styles. */
    void setStyle(String style) {
        glass = Config.GLASS_DEFAULT.equals(style);
        halo = Config.GLASS_HALO.equals(style);
        applyStyle();
        invalidate();
    }

    private void applyStyle() {
        if (glass) {
            rim.setStrokeWidth(1f * density);
            rim.setColor(0x6B000000 | CREAM);   // cream 42% hairline
        } else if (halo) {
            rim.setStrokeWidth(1.5f * density);
            rim.setColor(0xFF000000 | glowRgb);
        } else {
            rim.setStrokeWidth(2f * density);
            rim.setColor(0xFF000000 | CREAM);
        }
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the glow; MainActivity sizes the view as the zoomed tile plus this
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        if (glass) {
            // The slab reaches past the tile into the glow's room, with its hairline at the slab's edge.
            float out = inset - 1f * density;
            canvas.drawRoundRect(rect.left - out, rect.top - out, rect.right + out, rect.bottom + out,
                    radius + out, radius + out, slab);
            canvas.drawRoundRect(rect.left - out, rect.top - out, rect.right + out, rect.bottom + out,
                    radius + out, radius + out, rim);
            return;
        }

        // Glow: thin rings across the inset, each a little fainter, so it fades out smoothly. The hardware layer
        // caches the result, so the rings cost one draw, not one per frame of the spring.
        int rings = 12;
        float step = inset / rings;
        int alpha = halo ? glowAlpha / 2 : glowAlpha;
        glow.setStrokeWidth(step + 0.5f);
        for (int i = 0; i < rings; i++) {
            float o = step * (i + 0.5f);
            float t = 1f - (float) i / rings;
            glow.setColor(Math.round(alpha * t * t) << 24 | glowRgb);
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, radius + o, radius + o, glow);
        }
        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
