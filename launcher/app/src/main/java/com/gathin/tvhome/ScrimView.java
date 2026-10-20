package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * A vertical scrim between the wallpaper and everything drawn on top of it.
 *
 * <p>The launcher paints its clock, greeting, hero name and pills in white, which assumes the dark
 * background it was designed for. A bright wallpaper swallows all of it. Rather than refuse bright
 * pictures, Home measures the wallpaper's luminance and lays this gradient over it, heavier where
 * there is actually type: across the top for the greeting and clock, and again over the dock band.
 * The middle stays almost clear, which is the part of the picture the eye reads.
 *
 * <p>Drawn as one shader on a single view, no per-frame work; a wallpaper dark enough to be safe gets
 * strength 0 and is never added at all.
 */
final class ScrimView extends View {

    /** Base opacity at the six stops, before the measured strength scales them. Only ever used under
     *  white type: a bright wallpaper switches to dark type instead and gets no scrim at all. */
    private static final float[] ALPHA = {0.86f, 0.64f, 0.10f, 0.04f, 0.30f, 0.58f};
    private static final float[] STOP = {0f, 0.14f, 0.32f, 0.60f, 0.78f, 1f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float strength;

    ScrimView(Context context) {
        super(context);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** 0 leaves the wallpaper untouched, 1 is the full gradient. */
    void setStrength(float strength) {
        this.strength = Math.max(0f, Math.min(1f, strength));
        invalidate();
    }

    float strength() {
        return strength;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (strength <= 0f || getWidth() == 0 || getHeight() == 0) return;
        int[] colors = new int[ALPHA.length];
        for (int i = 0; i < ALPHA.length; i++) {
            colors[i] = ((int) (255 * ALPHA[i] * strength)) << 24;
        }
        paint.setShader(new LinearGradient(0f, 0f, 0f, getHeight(),
                colors, STOP, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
    }
}
