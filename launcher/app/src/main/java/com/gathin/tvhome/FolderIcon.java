package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.util.List;

/**
 * The picture of a folder tile: the icons of the apps inside it, laid out in a 2x2 grid, with a
 * count badge in the corner when there are more than four. Drawn rather than composed from layers
 * so each app icon keeps its own mask and its own colours.
 *
 * <p>Transparent by design: the tile already provides the frosted glass behind it (tile_bg), and a
 * scrim here would hide the wallpaper the way a banner does.
 */
final class FolderIcon extends Drawable {

    /** Icons actually drawn, at most four: the first apps in the folder's order. */
    private final List<Drawable> icons;
    /** Apps in the folder, which is what the badge counts; equals {@link #icons}.size() at most. */
    private final int total;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    FolderIcon(List<Drawable> icons, int total) {
        this.icons = icons;
        this.total = Math.max(total, icons.size());
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int n = icons.size();
        if (n == 0 || b.isEmpty()) return;
        int columns = n == 1 ? 1 : 2;
        int rows = (n + columns - 1) / columns;
        float gap = b.width() * 0.055f;
        float cell = Math.min(
                (b.width() - gap * (columns - 1)) / columns,
                (b.height() - gap * (rows - 1)) / rows);
        float gridW = cell * columns + gap * (columns - 1);
        float gridH = cell * rows + gap * (rows - 1);
        float x0 = b.left + (b.width() - gridW) / 2f;
        float y0 = b.top + (b.height() - gridH) / 2f;
        for (int i = 0; i < n; i++) {
            Drawable d = icons.get(i);
            if (d == null) continue;
            float x = x0 + (i % columns) * (cell + gap);
            float y = y0 + (i / columns) * (cell + gap);
            d.setBounds((int) x, (int) y, (int) (x + cell), (int) (y + cell));
            d.draw(canvas);
        }
        int extra = total - n;
        if (extra <= 0) return;
        // Corner badge over the grid's top-right icon: how many more apps are inside.
        float r = cell * 0.30f;
        float cx = x0 + gridW - r * 0.55f;
        float cy = y0 + r * 0.55f;
        String text = "+" + extra;
        paint.setColor(0xF2111111);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(r * 1.25f);
        paint.setColor(Color.WHITE);
        // Centre the glyphs on the circle: ascent alone leaves them sitting high.
        float baseline = cy - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(text, cx, baseline, paint);
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
