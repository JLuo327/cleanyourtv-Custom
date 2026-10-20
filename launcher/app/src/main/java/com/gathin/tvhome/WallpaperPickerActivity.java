package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Picks the home wallpaper on the TV itself.
 *
 * <p>The launcher has no wallpaper of its own: it draws {@code wallpaper.jpg} out of its external files
 * directory, and that file used to be pushed from a computer. This screen writes it in place instead, so
 * the wallpaper can be changed from the couch. Two sources:
 *
 * <ul>
 *   <li>the JPEGs bundled under {@code assets/wallpapers/}, always available;</li>
 *   <li>pictures already on the device, from Pictures, Download and DCIM, when the read permission is
 *       granted. Without it the list simply shows the bundled ones.</li>
 * </ul>
 *
 * <p>Only {@code wallpaper.jpg} is written. {@code config.json} is left alone on purpose: rewriting it
 * would change the theme signature and reset the column and motion choices made on the TV.
 */
public final class WallpaperPickerActivity extends Activity {
    private static final String ASSET_DIR = "wallpapers";
    private static final int PERMISSION_REQUEST = 41;
    private static final String[] USER_DIRS = {"Pictures", "Download", "DCIM"};
    private static final String[] IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".bmp"};

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallpaper);
        list = findViewById(R.id.wallpaperList);
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST);
        }
        buildList();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == PERMISSION_REQUEST) buildList();
    }

    // ---- list ------------------------------------------------------------------------------------

    private void buildList() {
        list.removeAllViews();
        int focus = 0;
        // The current wallpaper is whichever file is on the device; the first row turns it off.
        boolean hasWallpaper = wallpaperFile().isFile() && wallpaperFile().length() > 0;

        addRow(getString(R.string.wallpaper_none),
                getString(R.string.wallpaper_none_detail), null, this::applyNone, !hasWallpaper);
        focus = hasWallpaper ? 1 : 0;

        for (String asset : bundled()) {
            Bitmap thumb = decodeAsset(asset, 320);
            addRow(labelFor(asset), getString(R.string.wallpaper_built_in),
                    thumb, () -> applyAsset(asset), false);
        }

        for (File file : userImages()) {
            Bitmap thumb = decodeFile(file, 320);
            addRow(file.getName(), getString(R.string.wallpaper_on_device, folderOf(file)),
                    thumb, () -> applyFile(file), false);
        }

        View first = list.getChildAt(Math.min(focus, Math.max(0, list.getChildCount() - 1)));
        if (first != null) first.requestFocus();
    }

    private void addRow(String name, String detail, Bitmap thumb, Runnable action, boolean selected) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_wallpaper, list, false);
        ((TextView) row.findViewById(R.id.name)).setText(name);
        ((TextView) row.findViewById(R.id.detail)).setText(detail);
        ImageView image = row.findViewById(R.id.thumb);
        if (thumb != null) image.setImageBitmap(thumb);
        row.setSelected(selected);
        row.setOnClickListener(v -> action.run());
        list.addView(row);
    }

    private String[] bundled() {
        try {
            String[] names = getAssets().list(ASSET_DIR);
            if (names == null) return new String[0];
            List<String> jpgs = new ArrayList<>();
            for (String n : names) {
                if (n.toLowerCase().endsWith(".jpg") || n.toLowerCase().endsWith(".jpeg")) {
                    jpgs.add(n);
                }
            }
            jpgs.sort(String::compareToIgnoreCase);
            return jpgs.toArray(new String[0]);
        } catch (Exception e) {
            return new String[0];
        }
    }

    /** Pictures the user dropped on the device, newest first, capped so the list stays navigable. */
    private List<File> userImages() {
        List<File> out = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            return out;
        }
        File root = android.os.Environment.getExternalStorageDirectory();
        for (String dir : USER_DIRS) {
            File[] found = new File(root, dir).listFiles();
            if (found == null) continue;
            Arrays.sort(found, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : found) {
                if (!f.isFile()) continue;
                String lower = f.getName().toLowerCase();
                boolean image = false;
                for (String suffix : IMAGE_SUFFIXES) {
                    if (lower.endsWith(suffix)) image = true;
                }
                if (image && f.length() > 0) out.add(f);
                if (out.size() >= 40) return out;
            }
        }
        return out;
    }

    // ---- applying --------------------------------------------------------------------------------

    private void applyNone() {
        if (wallpaperFile().delete()) Toast.makeText(this, R.string.wallpaper_cleared,
                Toast.LENGTH_SHORT).show();
        done();
    }

    private void applyAsset(String asset) {
        try (InputStream in = getAssets().open(ASSET_DIR + "/" + asset);
             OutputStream out = new FileOutputStream(wallpaperFile())) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            done();
        } catch (Exception e) {
            fail(e);
        }
    }

    private void applyFile(File source) {
        try {
            Bitmap bitmap = decodeFile(source, 1920);
            if (bitmap == null) throw new IllegalStateException("cannot decode " + source);
            writeJpeg(bitmap, 88);
            bitmap.recycle();
            // A phone screenshot is a few megabytes; the TV decodes this on every Home press.
            if (wallpaperFile().length() > 400_000) {
                Bitmap again = decodeFile(wallpaperFile(), 1920);
                if (again != null) {
                    writeJpeg(again, 62);
                    again.recycle();
                }
            }
            done();
        } catch (Exception | OutOfMemoryError e) {
            fail(e);
        }
    }

    private void writeJpeg(Bitmap bitmap, int quality) throws Exception {
        try (OutputStream out = new FileOutputStream(wallpaperFile())) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        }
    }

    private void done() {
        setResult(RESULT_OK);
        finish();
    }

    private void fail(Throwable e) {
        Toast.makeText(this, getString(R.string.wallpaper_failed, String.valueOf(e.getMessage())),
                Toast.LENGTH_LONG).show();
    }

    // ---- files -----------------------------------------------------------------------------------

    private File wallpaperFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = new File(getFilesDir(), "files");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            // Nothing sensible left to do; applyNone and applyFile both surface the failure.
            android.util.Log.w("WallpaperPicker", "cannot create " + dir);
        }
        return new File(dir, Config.WALLPAPER_NAME);
    }

    private static String folderOf(File file) {
        File parent = file.getParentFile();
        return parent == null ? "" : parent.getName();
    }

    private String labelFor(String asset) {
        String base = asset.contains(".") ? asset.substring(0, asset.lastIndexOf('.')) : asset;
        switch (base.toLowerCase()) {
            case "sunrise": return getString(R.string.wallpaper_sunrise);
            case "summit": return getString(R.string.wallpaper_summit);
            case "forest": return getString(R.string.wallpaper_forest);
            case "valley": return getString(R.string.wallpaper_valley);
            case "balloons": return getString(R.string.wallpaper_balloons);
            case "blocks": return getString(R.string.wallpaper_blocks);
            case "lake": return getString(R.string.wallpaper_lake);
            case "moraine": return getString(R.string.wallpaper_moraine);
            case "bloom": return getString(R.string.wallpaper_bloom);
            case "lilac": return getString(R.string.wallpaper_lilac);
            default: return base;
        }
    }

    // ---- decoding --------------------------------------------------------------------------------

    private Bitmap decodeAsset(String asset, int targetWidth) {
        try (InputStream bounds = getAssets().open(ASSET_DIR + "/" + asset)) {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(bounds, null, probe);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(probe.outWidth, targetWidth);
            try (InputStream data = getAssets().open(ASSET_DIR + "/" + asset)) {
                return BitmapFactory.decodeStream(data, null, opts);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap decodeFile(File file, int targetWidth) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), probe);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(probe.outWidth, targetWidth);
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static int sampleSize(int width, int targetWidth) {
        int sample = 1;
        if (width <= 0) return 1;
        while (width / (sample * 2) >= targetWidth) sample *= 2;
        return sample;
    }
}
