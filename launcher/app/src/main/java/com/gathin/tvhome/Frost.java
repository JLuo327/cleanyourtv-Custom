package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;

/**
 * The blurred picture a panel is made of.
 *
 * <p>Android 9 has no blur behind a window and no {@code RenderEffect}, so the frost has to be made
 * by hand: draw the part of Home that sits under the panel into a small bitmap, blur it twice, and
 * let the panel stretch it back over itself. Only that slice is captured — blurring the whole screen
 * (the first attempt) turned the wallpaper into a smear the moment a menu opened, and made every
 * menu look like the projector had lost focus instead of like a sheet of glass had slid over Home.
 *
 * <p>Everything is guarded: if a device refuses to draw a view into a software canvas, this returns
 * null and the caller keeps the plain gradient surface.
 */
final class Frost {

    private Frost() {
    }

    /**
     * A blurred copy of the slice of {@code root} that lies under {@code screenRect}.
     *
     * @param screenRect where the panel is, in screen coordinates
     * @param scale      how much to shrink before blurring, e.g. 0.35
     * @param radius     box-blur radius in the small bitmap's pixels
     * @return the blurred slice, or null if it could not be captured
     */
    static Bitmap ofRegion(View root, Rect screenRect, float scale, int radius) {
        if (screenRect == null || screenRect.width() <= 0 || screenRect.height() <= 0) return null;
        int[] at = new int[2];
        root.getLocationOnScreen(at);
        // Panel position in root's own coordinates: what root.draw() will be working in.
        final int left = screenRect.left - at[0];
        final int top = screenRect.top - at[1];
        final int w = screenRect.width();
        final int h = screenRect.height();

        Bitmap small = null;
        try {
            int sw = Math.max(1, Math.round(w * scale));
            int sh = Math.max(1, Math.round(h * scale));
            small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(small);
            // A transparent capture would blur to nothing; start from black so an area that no tile
            // covers still reads as dark glass rather than as a hole in the panel.
            canvas.drawColor(Color.BLACK);
            canvas.scale((float) sw / w, (float) sh / h);
            canvas.translate(-left, -top);
            root.draw(canvas);
            // Two passes: one box blur leaves visible banding in a sky, two are close to a gaussian.
            blur(small, radius);
            blur(small, radius);
            return small;
        } catch (Throwable t) {
            if (small != null) small.recycle();
            return null;
        }
    }

    /** Separable box blur, in place. Horizontal pass then vertical, both with edge clamping. */
    private static void blur(Bitmap bmp, int radius) {
        final int w = bmp.getWidth();
        final int h = bmp.getHeight();
        int[] src = new int[w * h];
        int[] dst = new int[w * h];
        bmp.getPixels(src, 0, w, 0, 0, w, h);
        pass(src, dst, w, h, radius, true);
        pass(dst, src, w, h, radius, false);
        bmp.setPixels(src, 0, w, 0, 0, w, h);
    }

    private static void pass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        final int lines = horizontal ? h : w;
        final int len = horizontal ? w : h;
        final int div = 2 * r + 1;
        for (int i = 0; i < lines; i++) {
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[index(i, k, w, h, horizontal)];
                a += p >>> 24;
                rr += (p >> 16) & 0xFF;
                gg += (p >> 8) & 0xFF;
                bb += p & 0xFF;
            }
            for (int j = 0; j < len; j++) {
                out[horizontal ? i * w + j : j * w + i] =
                        ((a / div) << 24) | ((rr / div) << 16) | ((gg / div) << 8) | (bb / div);
                int add = in[index(i, j + r + 1, w, h, horizontal)];
                int sub = in[index(i, j - r, w, h, horizontal)];
                a += (add >>> 24) - (sub >>> 24);
                rr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                gg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                bb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int index(int line, int at, int w, int h, boolean horizontal) {
        if (horizontal) {
            int x = Math.max(0, Math.min(w - 1, at));
            return line * w + x;
        }
        int y = Math.max(0, Math.min(h - 1, at));
        return y * w + line;
    }
}
