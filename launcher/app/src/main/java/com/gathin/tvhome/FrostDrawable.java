package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * One sheet of frosted glass, in four layers: the blurred slice of Home, a smoked tint over it, a
 * sheen along the top edge, and a hairline rim.
 *
 * <p>This is what makes a panel read as glass instead of as a grey box. A translucent fill alone
 * still shows the sharp edge of whatever tile is underneath, and the eye reads that as "the panel
 * is a window" — whereas blurred colour behind the same fill reads as "the panel is a surface".
 *
 * <p>Corner radius and density arrive in pixels, so the same drawable works for the folder (22dp),
 * the control centre (24dp) and the centred menus (22dp).
 */
final class FrostDrawable extends Drawable {

    /**
     * No smoke by default: the control centre sits twenty percent *above* the desktop in brightness,
     * so a tint of any weight would work against it. The colour work is done on the bitmap instead
     * (see {@link #BRIGHTNESS}), which leaves the panel's own tone free to follow the wallpaper.
     *
     * <p>A caller that wants a readable panel over a bright wallpaper — the long-press menu, for
     * instance — passes a small {@code tint} (0..1) and gets a uniform ink wash over the frost so
     * the white labels have something to sit on without the panel reading as a solid slab.
     */
    private final int tintTop;
    private final int tintBottom;

    /**
     * Glass that catches light rather than absorbing it: the frost is lifted well past the desktop's
     * brightness.
     *
     * <p>Tried at 2.4x as asked for "twice as bright": the frost clips to white across the whole
     * panel and the white labels disappear into it, which defeats the other half of the request.
     * 1.5x is the highest lift that still leaves the panel a picture rather than a light box.
     */
    private static final float BRIGHTNESS = 1.5f;

    private final Bitmap src;
    private final float radius;
    private final float rimWidth;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint tintPaint = new Paint(Paint.DITHER_FLAG);
    private final Paint sheenPaint = new Paint(Paint.DITHER_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF area = new RectF();
    private final Path shape = new Path();
    private final Rect srcRect = new Rect();
    private boolean prepared;

    /**
     * @param tint uniform ink wash over the frost, 0 (none) to 1 (near-opaque). A small value,
     *            ~0.2, is enough to lift white labels off a bright wallpaper without burying the
     *            blurred colour underneath.
     */
    FrostDrawable(Bitmap src, float radius, float density, float tint) {
        this.src = src;
        this.radius = radius;
        this.rimWidth = Math.max(1f, 1.5f * density);
        int a = (int) (Math.max(0f, Math.min(1f, tint)) * 255f + 0.5f);
        this.tintTop = Color.argb(a, 0, 0, 0);
        this.tintBottom = Color.argb(a, 0, 0, 0);
        // Frosted glass mutes colour; it does not hand it back at full strength. Without this the
        // sunset behind the control centre bled through as a brown wash, which read as a dirty
        // panel rather than as glass — the blur was there, the silk was not.
        // Then the lift: the panel is glass catching the light from the screen behind it, so it
        // comes out brighter than the room. Bright highlights clip to white, which is exactly what
        // a lit sheet of glass does at the specular end.
        ColorMatrix mute = new ColorMatrix();
        mute.setSaturation(0.35f);
        ColorMatrix lift = new ColorMatrix();
        lift.setScale(BRIGHTNESS, BRIGHTNESS, BRIGHTNESS, 1f);
        mute.postConcat(lift);
        bitmapPaint.setColorFilter(new ColorMatrixColorFilter(mute));
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(rimWidth);
        rimPaint.setColor(0x3DFFFFFF);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        area.set(bounds);
        shape.reset();
        shape.addRoundRect(area, radius, radius, Path.Direction.CW);
        if (src != null) srcRect.set(0, 0, src.getWidth(), src.getHeight());
        // The sheen sits on the top third, where light would catch the upper lip of the glass.
        sheenPaint.setShader(new LinearGradient(0f, area.top, 0f, area.top + area.height() * 0.38f,
                0x16FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        tintPaint.setShader(new LinearGradient(0f, area.top, 0f, area.bottom,
                tintTop, tintBottom, Shader.TileMode.CLAMP));
        prepared = true;
    }

    @Override
    public void draw(Canvas canvas) {
        if (!prepared) onBoundsChange(getBounds());
        final int save = canvas.save();
        canvas.clipPath(shape);
        if (src != null && !src.isRecycled()) {
            canvas.drawBitmap(src, srcRect, area, bitmapPaint);
        } else {
            canvas.drawColor(0xFF14141A);
        }
        canvas.drawRect(area, tintPaint);
        canvas.drawRect(area, sheenPaint);
        canvas.restoreToCount(save);
        // The rim goes on outside the clip, or its outer half would be cut off and it would look
        // like a hairline inside the panel instead of an edge on it.
        final float inset = rimWidth / 2f;
        canvas.drawRoundRect(new RectF(area.left + inset, area.top + inset,
                area.right - inset, area.bottom - inset), radius, radius, rimPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        bitmapPaint.setAlpha(alpha);
        tintPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        bitmapPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
