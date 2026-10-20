package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * The modules the launcher itself owns, listed and switched in the Module cards panel.
 *
 * <p>A module card in the sense of {@code docs/specs/card.md} is declared by another installed app
 * and the launcher only discovers it: it cannot invent one. The pills under the clock are the
 * launcher's own views, so they cannot be declared that way, and they live in this list instead.
 * One panel then answers the whole question — what can be turned on here — with the discovered
 * cards listed after the built-in modules.
 *
 * <p>An answer is stored per key in the launcher's private settings, next to the tile layout, so it
 * survives a restart and a theme overlay. A key that is absent takes its default, which is on for
 * the three that read something the device already has and off for the one that needs a tick.
 */
final class Modules {

    static final String STORAGE = "storage";
    static final String MEMORY = "memory";
    static final String EXTERNAL = "external";
    static final String NETSPEED = "netspeed";

    /** Panel order. */
    static final String[] ALL = {STORAGE, MEMORY, EXTERNAL, NETSPEED};

    private static final String KEY = "modules";

    private Modules() {
    }

    /** The panel label. */
    static int label(String key) {
        switch (key) {
            case STORAGE:
                return R.string.module_storage;
            case MEMORY:
                return R.string.module_memory;
            case EXTERNAL:
                return R.string.module_external;
            default:
                return R.string.module_netspeed;
        }
    }

    /** The line under the label: what the module shows. */
    static int caption(String key) {
        switch (key) {
            case STORAGE:
                return R.string.module_storage_caption;
            case MEMORY:
                return R.string.module_memory_caption;
            case EXTERNAL:
                return R.string.module_external_caption;
            default:
                return R.string.module_netspeed_caption;
        }
    }

    /** Off for the one that samples on a timer: nothing runs until the owner asks for it. */
    static boolean defaultOn(String key) {
        return !NETSPEED.equals(key);
    }

    /** The modules that are on. A settings file that does not read falls back to the defaults. */
    static Set<String> load(Context ctx) {
        Set<String> on = new HashSet<>();
        String raw = prefs(ctx).getString(KEY, null);
        if (raw != null) {
            try {
                JSONObject json = new JSONObject(raw);
                for (String key : ALL) if (json.optBoolean(key, defaultOn(key))) on.add(key);
                return on;
            } catch (Exception ignored) {
                // A damaged value is the same as no value: every module takes its default.
            }
        }
        for (String key : ALL) if (defaultOn(key)) on.add(key);
        return on;
    }

    static void save(Context ctx, Set<String> on) {
        JSONObject json = new JSONObject();
        try {
            for (String key : ALL) json.put(key, on.contains(key));
        } catch (Exception ignored) {
            return;
        }
        prefs(ctx).edit().putString(KEY, json.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }
}
