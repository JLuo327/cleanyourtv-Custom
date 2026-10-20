package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The launcher's settings: the values in res/values/config.xml, with an optional overlay read from
 * {@code config.json} in the app's external files directory
 * ({@code /sdcard/Android/data/com.gathin.tvhome/files/}). Every key in the JSON is optional and an
 * unknown or badly typed key is ignored, so a theme can change the dock and the look without a rebuild:
 * {@code tools/theme.sh themes/<name>} pushes a theme folder there and restarts Home.
 *
 * Read once in {@link MainActivity#onCreate}; nothing here touches the network.
 *
 * <p>A folder is one tile standing in for several apps:
 * <pre>{"folders": [{"name": "Tools", "apps": ["com.android.tv", "com.example.mh"]}]}</pre>
 * The built-in default collects the TV's rarely used system tools; an overlay with a different
 * {@code folders} array replaces it, and {@code "folders": []} turns folders off.
 */
final class Config {
    private static final String TAG = "TvHome.Config";
    static final String FILE_NAME = "config.json";
    static final String WALLPAPER_NAME = "wallpaper.jpg";
    static final String SETTINGS_NAME = "launcher_settings";
    private static final String THEME_REVISION_NAME = "theme_revision";
    private static final String THEME_SIGNATURE_KEY = "theme_signature";
    /** Where the owner's own tile order lives (SharedPreferences in launcher_settings). */
    static final String LAYOUT_PREFS_KEY = "layout";
    /** Apps the owner took off the home screen from the tile menu (same SharedPreferences). */
    static final String HIDDEN_PREFS_KEY = "taken_off";

    /**
     * Apps the built-in folder collects: the TV's own Live TV app (the HDMI list lives behind it),
     * the two screen-mirroring receivers, the remote-mouse server and the subscription helper.
     * Overridden wholesale by a "folders" array in config.json.
     */
    private static final List<String> DEFAULT_FOLDER_APPS = Arrays.asList(
            "com.android.tv",
            "com.maxhub.screenshare.server.tv",
            "com.droidlogic.mediacenter",
            "com.vonchange.marmot.cursor",
            "com.wukongdaily.myclashsub");

    /** One folder tile on the home screen: a label and the packages it opens. */
    static final class Folder {
        final String name;
        final List<String> apps;

        Folder(String name, List<String> apps) {
            this.name = name;
            this.apps = Collections.unmodifiableList(apps);
        }
    }

    /**
     * One entry in the owner's layout. The layout is a plain ordered list of slots: the first
     * {@code columns} slots are the dock, the rest are the grid, and an app the owner never placed
     * (a new install) is appended after them. That single rule is what makes an app movable to the
     * dock, into a folder, or anywhere else — it is only its position in this list.
     */
    static final class Slot {
        static final int APP = 0, FOLDER = 1;

        final int kind;
        /** Package name, for an app slot. */
        final String pkg;
        /** Tile label, for a folder slot. */
        final String name;
        /** Packages inside a folder, in the order the folder panel lists them. */
        final List<String> apps;
        /** Stable identity for a folder tile, so it keeps its place while apps come and go. */
        final String uid;

        static Slot app(String pkg) {
            return new Slot(APP, pkg, "", null, "");
        }

        static Slot folder(String uid, String name, List<String> apps) {
            return new Slot(FOLDER, "", name, apps, uid);
        }

        private Slot(int kind, String pkg, String name, List<String> apps, String uid) {
            this.kind = kind;
            this.pkg = pkg;
            this.name = name;
            this.apps = apps == null ? Collections.<String>emptyList() : Collections.unmodifiableList(apps);
            this.uid = uid;
        }
    }

    final List<String> dockApps;
    final List<String> hiddenApps;
    /** Folders named in config.json (or the built-in default); they seed the layout on first run. */
    final List<Folder> folders;
    /**
     * The owner's tile order, or null until the first run builds one. Null means "lay it out the
     * built-in way": the dock, then everything alphabetically, then the configured folders.
     */
    final List<Slot> layout;
    final String liveTvPackage;
    final String liveTvAction;
    final String sourcePanelService;
    final String sourcePanelAction;
    final String inputAction;
    final int columns;
    final boolean animations;
    final boolean advancedEffects;
    final int rowsBelowDock;
    final boolean shamsiDate;
    final boolean moduleCards;
    /** Local status cards (storage / RAM / removable) under the clock; config.xml info_cards. */
    final boolean infoCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    final boolean reducedMotion;
    final String vpnApp;
    final boolean showHomeSettingsShortcut;
    /** Background colour behind everything, #RRGGBB; black unless a theme says otherwise. */
    final int backgroundColor;
    /** Time-of-day backdrop, 0 to 100 (config.xml, sky_intensity); 0 keeps the plain background. */
    final int skyIntensity;
    /** Full-screen picture behind the launcher, or null for the plain colour. */
    final File wallpaper;
    /** Whether the overlay file was present and parsed. */
    final boolean overlayApplied;

    private Config(List<String> dockApps, List<String> hiddenApps, List<Folder> folders, List<Slot> layout,
                   String liveTvPackage, String liveTvAction,
                   String sourcePanelService, String sourcePanelAction, String inputAction,
                   int columns, int rowsBelowDock, boolean animations, boolean advancedEffects,
                   boolean shamsiDate, boolean moduleCards, boolean infoCards, boolean reducedMotion, String vpnApp,
                   boolean showHomeSettingsShortcut, int backgroundColor, int skyIntensity,
                   File wallpaper, boolean overlayApplied) {
        this.dockApps = Collections.unmodifiableList(dockApps);
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.folders = Collections.unmodifiableList(folders);
        this.layout = layout == null ? null : Collections.unmodifiableList(layout);
        this.liveTvPackage = liveTvPackage;
        this.liveTvAction = liveTvAction;
        this.sourcePanelService = sourcePanelService;
        this.sourcePanelAction = sourcePanelAction;
        this.inputAction = inputAction;
        this.columns = columns;
        this.rowsBelowDock = rowsBelowDock;
        this.animations = animations;
        this.advancedEffects = advancedEffects;
        this.shamsiDate = shamsiDate;
        this.moduleCards = moduleCards;
        this.infoCards = infoCards;
        this.reducedMotion = reducedMotion;
        this.vpnApp = vpnApp;
        this.showHomeSettingsShortcut = showHomeSettingsShortcut;
        this.backgroundColor = backgroundColor;
        this.skyIntensity = skyIntensity;
        this.wallpaper = wallpaper;
        this.overlayApplied = overlayApplied;
    }

    /** The folder the theme tools push into; null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    static Config load(Context ctx) {
        Resources res = ctx.getResources();
        List<String> dock = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.dock_apps)));
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        String liveTv = res.getString(R.string.live_tv_package).trim();
        String liveTvAction = res.getString(R.string.live_tv_action).trim();
        String panelService = res.getString(R.string.source_panel_service).trim();
        String panelAction = res.getString(R.string.source_panel_action).trim();
        String inputAction = res.getString(R.string.input_action).trim();
        int columns = res.getInteger(R.integer.columns);
        int rowsBelow = res.getInteger(R.integer.rows_below_dock);
        boolean animations = res.getBoolean(R.bool.animations);
        boolean advancedEffects = res.getBoolean(R.bool.advanced_effects);
        boolean shamsi = res.getBoolean(R.bool.shamsi_date);
        boolean moduleCards = res.getBoolean(R.bool.module_cards);
        boolean infoCards = res.getBoolean(R.bool.info_cards);
        boolean reducedMotion = res.getBoolean(R.bool.reduced_motion);
        String vpn = res.getString(R.string.vpn_app).trim();
        boolean homeSettings = res.getBoolean(R.bool.show_home_settings_shortcut);
        int background = 0xFF000000;
        int skyIntensity = res.getInteger(R.integer.sky_intensity);
        String folderName = res.getString(R.string.folder_default_name);
        List<Folder> folders = new ArrayList<>();
        folders.add(new Folder(folderName, DEFAULT_FOLDER_APPS));
        boolean applied = false;

        File dir = dir(ctx);
        File file = dir != null ? new File(dir, FILE_NAME) : null;
        JSONObject json = read(file);
        if (json != null) {
            applied = true;
            dock = stringList(json, "dock_apps", dock);
            hidden = stringList(json, "hidden_apps", hidden);
            liveTv = string(json, "live_tv_package", liveTv);
            liveTvAction = string(json, "live_tv_action", liveTvAction);
            panelService = string(json, "source_panel_service", panelService);
            panelAction = string(json, "source_panel_action", panelAction);
            inputAction = string(json, "input_action", inputAction);
            columns = integer(json, "columns", columns, 1, 8);
            rowsBelow = integer(json, "rows_below_dock", rowsBelow, 0, 3);
            animations = bool(json, "animations", animations);
            advancedEffects = bool(json, "advanced_effects", advancedEffects);
            shamsi = bool(json, "shamsi_date", shamsi);
            moduleCards = bool(json, "module_cards", moduleCards);
            infoCards = bool(json, "info_cards", infoCards);
            reducedMotion = bool(json, "reduced_motion", reducedMotion);
            vpn = string(json, "vpn_app", vpn);
            homeSettings = bool(json, "show_home_settings_shortcut", homeSettings);
            background = color(json, "background_color", background);
            skyIntensity = integer(json, "sky_intensity", skyIntensity, 0, 100);
            // Absent keeps the built-in folder; present replaces it, so "folders": [] folds nothing.
            if (json.opt("folders") instanceof JSONArray) {
                folders = folderList((JSONArray) json.opt("folders"), folderName);
            }
        }
        // A theme push or reset invalidates choices made on the TV. On first use, remember the
        // current theme without clearing choices from an older launcher version.
        SharedPreferences choices = ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
        syncThemeChoices(choices, dir, file);
        int chosenColumns = choices.getInt("columns", columns);
        if (chosenColumns >= 4 && chosenColumns <= 7) columns = chosenColumns;
        animations = choices.getBoolean("animations", animations);
        advancedEffects = choices.getBoolean("advanced_effects", advancedEffects);
        // The owner's own tile order, written whenever a tile is moved or a folder is made or
        // dropped. It outranks the built-in layout until the next theme push clears it.
        List<Slot> layout = layoutFromJson(choices.getString(LAYOUT_PREFS_KEY, null));
        File wallpaper = dir != null ? new File(dir, WALLPAPER_NAME) : null;
        if (wallpaper != null && !(wallpaper.isFile() && wallpaper.length() > 0)) wallpaper = null;
        // A theme can also switch the wallpaper off while the file is still there.
        if (json != null && json.has("wallpaper") && !bool(json, "wallpaper", true)) wallpaper = null;

        return new Config(dock, hidden, folders, layout, liveTv, liveTvAction, panelService, panelAction, inputAction,
                Math.max(1, columns), Math.max(0, rowsBelow), animations, advancedEffects,
                shamsi, moduleCards, infoCards, reducedMotion, vpn, homeSettings, background,
                Math.max(0, Math.min(100, skyIntensity)), wallpaper, applied);
    }

    private static void syncThemeChoices(SharedPreferences choices, File dir, File config) {
        File revision = dir == null ? null : new File(dir, THEME_REVISION_NAME);
        String token = revisionToken(revision);
        String signature = fileStamp(config) + ":" + token;
        String seen = choices.getString(THEME_SIGNATURE_KEY, null);
        if (seen == null) {
            // Existing preferences survive an app upgrade, unless the first launch follows an
            // explicit theme push or reset by the updated tools.
            SharedPreferences.Editor editor = choices.edit();
            if (!"absent".equals(token)) editor.remove("columns").remove("animations")
                    .remove("advanced_effects");
            editor.putString(THEME_SIGNATURE_KEY, signature).commit();
        } else if (!seen.equals(signature)) {
            choices.edit().remove("columns").remove("animations").remove("advanced_effects")
                    .remove(LAYOUT_PREFS_KEY)
                    .putString(THEME_SIGNATURE_KEY, signature).commit();
        }
    }

    private static String fileStamp(File file) {
        return file != null && file.isFile() ? file.length() + "@" + file.lastModified() : "absent";
    }

    private static String revisionToken(File file) {
        if (file == null || !file.isFile() || file.length() > 64) return "absent";
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int n = in.read(bytes);
            return n > 0 ? new String(bytes, 0, n, StandardCharsets.US_ASCII) : "absent";
        } catch (Exception ignored) {
            return "absent";
        }
    }

    /** Reads and parses the overlay; any problem means "no overlay", never a crash on the home screen. */
    private static JSONObject read(File file) {
        if (file == null || !file.isFile()) return null;
        if (file.length() > 64 * 1024) {
            Log.w(TAG, "ignoring " + file + ": larger than 64 KB");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);   // Windows editors add a BOM
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static String string(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        return v instanceof String ? ((String) v).trim() : fallback;
    }

    private static boolean bool(JSONObject o, String key, boolean fallback) {
        Object v = o.opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static int integer(JSONObject o, String key, int fallback, int min, int max) {
        Object v = o.opt(key);
        if (!(v instanceof Number)) return fallback;
        int i = ((Number) v).intValue();
        return i < min || i > max ? fallback : i;
    }

    private static int color(JSONObject o, String key, int fallback) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return fallback;
        try {
            int c = Color.parseColor(((String) v).trim());
            return c | 0xFF000000;   // always opaque: the window behind is black anyway
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static List<String> stringList(JSONObject o, String key, List<String> fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (item instanceof String && !((String) item).trim().isEmpty()) out.add(((String) item).trim());
        }
        return out;
    }

    /**
     * Parses a "folders" array: a list of {@code {"name": ..., "apps": [...]}} objects. An entry
     * that is not an object, or that names no app, is dropped rather than failing the whole overlay;
     * an entry with no name takes the built-in folder's name.
     */
    private static List<Folder> folderList(JSONArray a, String defaultName) {
        List<Folder> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Object n = o.opt("name");
            String name = n instanceof String ? ((String) n).trim() : "";
            if (name.isEmpty()) name = defaultName;
            List<String> apps = stringList(o, "apps", Collections.<String>emptyList());
            if (!apps.isEmpty()) out.add(new Folder(name, apps));
        }
        return out;
    }

    /**
     * The owner's layout as stored in SharedPreferences, or null when it has never been written.
     * A layout that does not parse is treated as absent, so a corrupt preference falls back to the
     * built-in order instead of an empty home screen.
     */
    static List<Slot> layoutFromJson(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            JSONArray a = new JSONArray(text);
            List<Slot> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Object app = o.opt("app");
                if (app instanceof String && !((String) app).trim().isEmpty()) {
                    out.add(Slot.app(((String) app).trim()));
                    continue;
                }
                Object uid = o.opt("folder");
                if (!(uid instanceof String) || ((String) uid).trim().isEmpty()) continue;
                Object name = o.opt("name");
                List<String> apps = stringList(o, "apps", Collections.<String>emptyList());
                if (apps.isEmpty()) continue;
                out.add(Slot.folder(((String) uid).trim(),
                        name instanceof String && !((String) name).trim().isEmpty()
                                ? ((String) name).trim() : "Folder",
                        apps));
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "ignoring saved layout: " + e.getMessage());
            return null;
        }
    }

    /** Serialises the layout for SharedPreferences; null when it cannot be written. */
    static String layoutToJson(List<Slot> layout) {
        JSONArray a = new JSONArray();
        for (Slot s : layout) {
            JSONObject o = new JSONObject();
            try {
                if (s.kind == Slot.APP) {
                    o.put("app", s.pkg);
                } else {
                    JSONArray apps = new JSONArray();
                    for (String pkg : s.apps) apps.put(pkg);
                    o.put("folder", s.uid);
                    o.put("name", s.name);
                    o.put("apps", apps);
                }
            } catch (Exception ignored) {
                return null;
            }
            a.put(o);
        }
        return a.toString();
    }
}
