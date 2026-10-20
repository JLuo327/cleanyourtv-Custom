package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.List;

/**
 * The picture of a folder tile: the icons of the apps inside it, spread in one row across the top
 * of the tile. Drawn rather than composed from layers so each app icon keeps its own mask and its
 * own colours.
 *
 * <p>One row pinned to the top, not a 2x2 block centred in the middle: the tile is a wide rounded
 * rectangle, and a square block floating in the middle of it read as a small clump. The apps now
 * run left to right across the full width, each as large as the tile's height allows, starting at
 * the top edge where the eye expects the content to begin. No count badge: how many apps are
 * inside is not the tile's business.
 *
 * <p>Transparent by design: the tile already provides the frosted glass behind it (tile_bg), and a
 * scrim here would hide the wallpaper the way a banner does.
 */
final class FolderIcon extends Drawable {

    /** Icons actually drawn, at most four: the first apps in the folder's order. */
    private final List<Drawable> icons;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    FolderIcon(List<Drawable> icons) {
        this.icons = icons;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int n = icons.size();
        if (n == 0 || b.isEmpty()) return;
        // One row across the tile's width, pinned to the top: the icons spread edge to edge instead
        // of clumping in a centred square, each as tall as the tile allows.
        int columns = n;
        int rows = 1;
        float gap = b.width() * 0.055f;
        float cell = Math.min(
                (b.width() - gap * (columns - 1)) / columns,
                (b.height() - gap * (rows - 1)) / rows);
        float gridW = cell * columns + gap * (columns - 1);
        float x0 = b.left + (b.width() - gridW) / 2f;
        float y0 = b.top;
        for (int i = 0; i < n; i++) {
            Drawable d = icons.get(i);
            if (d == null) continue;
            float x = x0 + (i % columns) * (cell + gap);
            float y = y0 + (i / columns) * (cell + gap);
            d.setBounds((int) x, (int) y, (int) (x + cell), (int) (y + cell));
            d.draw(canvas);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
