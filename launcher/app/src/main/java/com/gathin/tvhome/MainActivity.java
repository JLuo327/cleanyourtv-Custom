package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LayoutAnimationController;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    /** The throughput pill samples on its own beat, only while it is on and Home is in front. */
    private static final long NET_MS = 2_000L;
    /** Row tag prefix marking a built-in module in the Module cards list rather than a package. */
    private static final String MODULE_TAG = "module:";
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    /** Dim layer behind an open folder panel or tile menu. */
    private View folderScrim;
    /** The one window a folder's panels share: the apps, the add list and the remove list. */
    private PopupWindow folderPopupWindow;
    private FrameLayout folderHost;
    /** Set while an app out of a folder is starting, so the panel does not refocus on its way out. */
    private boolean folderLaunching;
    /** Set while the owner is carrying a tile: the D-pad walks the grid, OK puts it down. */
    private boolean moving;
    /** Where the carried tile came from, or -1 when nothing is being carried. */
    private int moveFrom = -1;
    /** True once the live drag has swapped views, so Back has an order to put back. */
    private boolean moveDirty;
    /** The layout as it stood when the tile was picked up, for Back to restore. */
    private List<Config.Slot> moveOriginal;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View btnInputs;
    private TextView btnWifi;
    private TextView btnSettings;
    /** Local status pills under the clock (config.xml info_cards), each gated by its Modules key. */
    private TextView infoStorage, infoMem, infoSd, infoNet;
    /** config.xml info_cards: the master switch for the whole row. */
    private boolean infoCardsOn;
    /** The built-in modules the owner has switched on in the Module cards panel. */
    private Set<String> modules;
    /** Throughput baseline for the network pill, and whether its ticker is already posted. */
    private long netRx, netTx, netAt;
    private boolean netTicking;
    private TextView shamsiDate;
    private TextView clock;
    private TextView date;
    private TextView ampm;
    private boolean shamsi;
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    /** Current glass look: true on a light wallpaper (dark glass, white type), false on a dark one. */
    private boolean brightGlass;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /**
     * hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets
     * no card.
     */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Rebuild the tiles on resume: Home was fully hidden by an app, or Home was pressed. Not after a side panel such as Wi-Fi settings. */
    private boolean rebuildOnResume = true;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
            handler.post(MainActivity.this::updateSystemLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
            handler.post(MainActivity.this::updateSystemLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            reloadApps(false);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            updateSystemLabel();
            updateInfoCards();
            sky.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };
    /** The throughput pill's own beat; it reschedules only while the module is on and Home is in front. */
    private final Runnable netTick = new Runnable() {
        @Override
        public void run() {
            netTicking = false;
            if (!infoCardsOn || !modules.contains(Modules.NETSPEED)) return;
            updateNetSpeed();
            netTicking = true;
            handler.postDelayed(this, NET_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        heroName = findViewById(R.id.heroName);
        greeting = findViewById(R.id.greeting);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        ampm = findViewById(R.id.ampm);
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);
        // Anything taken off Home in an earlier build stays off: there is no "Add apps" panel any
        // more, but honouring the old preference keeps those cards from reappearing uninvited.
        hiddenApps.addAll(getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getStringSet(Config.HIDDEN_PREFS_KEY, Collections.emptySet()));

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        if (animations) {
            int enterAnim = reducedMotion ? R.anim.tile_enter_reduced : R.anim.tile_enter;
            float enterDelay = reducedMotion ? 0f : 0.06f;
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, enterAnim), enterDelay));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        adapter.setTakenOff(hiddenApps);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the column count (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(inner, COLUMNS, density)), density);
            int dockRow = itemH + (int) ((6 + 12) * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            int want = Math.max(0, h - dockRow - gridRows - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                // Stiff and almost critically damped: settles in a few frames instead of oscillating,
                // and stops once the highlight is within 1.5 px of its tile.
                s.setSpring(new SpringForce().setStiffness(1400f).setDampingRatio(0.95f));
                s.setMinimumVisibleChange(1.5f);
            } else {
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 320f : 260f)
                        .setDampingRatio(advancedEffects ? 0.76f : 0.58f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        btnSettings = findViewById(R.id.btnSettings);
        infoStorage = findViewById(R.id.infoStorage);
        infoMem = findViewById(R.id.infoMem);
        infoSd = findViewById(R.id.infoSd);
        infoNet = findViewById(R.id.infoNet);
        infoCardsOn = cfg.infoCards;
        modules = Modules.load(this);
        if (!infoCardsOn) findViewById(R.id.infoCards).setVisibility(View.GONE);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> showSystemPanel());
        btnSettings.setOnLongClickListener(v -> {
            showSystemPanel();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            int firstButton = R.id.btnSettings;
            int leftButton = rtlMenuBar ? R.id.btnSettings : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnSettings;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        if (advancedEffects) {
            for (View button : new View[]{btnInputs, btnWifi, btnSettings}) {
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(160).start());
            }
        }

        // Wallpaper is loaded last: it measures luminance and adjusts text colours / pill backgrounds,
        // so every view it touches must already be initialised.
        if (cfg.wallpaper != null) showWallpaper(cfg.wallpaper);
        wallpaperStamp = stampOf(cfg.wallpaper);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
    }

    /** The full-screen picture behind everything, and the stamp of the file it was decoded from. */
    private ImageView wallpaperView;
    private long wallpaperStamp = Long.MIN_VALUE;
    /** Gradient laid over a bright wallpaper so the white type stays readable; null when not needed. */
    private ScrimView scrimView;

    /**
     * The wallpaper picker rewrites wallpaper.jpg while Home is stopped, so the file it decoded in
     * onCreate is stale by the time it comes back. Cheap check first (a stat), Config.load only when
     * the file really changed.
     */
    private void refreshWallpaperIfChanged() {
        File dir = getExternalFilesDir(null);
        File file = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        long stamp = (file != null && file.isFile() && file.length() > 0)
                ? file.lastModified() * 31 + file.length() : 0L;
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        if (wallpaperView != null) {
            root.removeView(wallpaperView);
            wallpaperView = null;
        }
        if (scrimView != null) {
            root.removeView(scrimView);
            scrimView = null;
        }
        if (stamp == 0L) {
            applyContrast(false);                       // back to plain black
            return;
        }
        Config cfg = Config.load(this);
        if (cfg.wallpaper != null) showWallpaper(cfg.wallpaper);
    }

    private static long stampOf(File file) {
        return file == null ? 0L : file.lastModified() * 31 + file.length();
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once, downsampled to the screen so a 4K
     * JPEG costs a 1080p bitmap at most (about 8 MB), which is the price of a wallpaper on a 1 GB TV.
     */
    private void showWallpaper(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= dm.widthPixels && opts.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            wallpaperView = wall;
            // Two mechanisms, picked by how bright the picture actually is:
            //   bright  -> dark type, no scrim, so a near-white "lamp" wallpaper stays white;
            //   mid/dark -> white type over a scrim heavy enough to carry it.
            float lum = averageLuminance(bmp, false);
            float topLum = averageLuminance(bmp, true);
            float dark = percentileLuminance(bmp, 0.10f);
            // Dark type everywhere only works if no part of the picture is genuinely dark: white-on-
            // black is the stock look, and a photo with deep shadows keeps it.
            boolean bright = lum > 150f && dark > 70f;
            applyContrast(bright);
            // The scrim only has to rescue the band the type sits in, so it follows the top third:
            // a blown-out sky is worth dimming, a bright meadow is not. Capped so a photo never turns
            // into the grey wash a full-strength scrim produces.
            float strength = bright ? 0f
                    : Math.min(0.72f, Math.max(0f, (topLum - 95f) / 115f));
            if (strength > 0f) {
                ScrimView scrim = new ScrimView(this);
                scrim.setStrength(strength);
                root.addView(scrim, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                scrimView = scrim;
            }
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    /**
     * Two looks, picked by the wallpaper's luminance.
     *
     * <p>Bright: every header label goes dark so it reads on a near-white picture, and the pills swap
     * to dark glass — a white label on a dark pill is the only combination that survives a white
     * background. No scrim, so a "lamp" wallpaper keeps its full brightness.
     *
     * <p>Otherwise: the stock white labels, with a light shadow that sharpens them over a mid-tone
     * photo, and the original frosted pills.
     */
    private void applyContrast(boolean bright) {
        brightGlass = bright;
        int heroColor = bright ? 0xFF111111 : 0xFFFFFFFF;
        int greetingColor = bright ? 0xFF333333 : 0x8CFFFFFF;
        int clockColor = bright ? 0xFF111111 : 0xFFFFFFFF;
        int ampmColor = bright ? 0xFF555555 : 0xB3FFFFFF;
        int dateColor = bright ? 0xFF555555 : 0xA6FFFFFF;
        int shamsiColor = bright ? 0xFF555555 : 0xE6FFFFFF;
        // A hard dark edge only where the type is white; dark type needs no help on a light picture.
        // Radius is generous on purpose: a photo can have a bright sky right behind the clock, and a
        // tight shadow would be swallowed by it.
        float shadow = bright ? 0f : 11f * density;
        int shadowColor = bright ? 0 : 0xFF000000;

        heroName.setTextColor(heroColor);
        heroName.setShadowLayer(shadow, 0f, 2f * density, shadowColor);
        greeting.setTextColor(greetingColor);
        greeting.setShadowLayer(shadow, 0f, 1.5f * density, shadowColor);
        clock.setTextColor(clockColor);
        clock.setShadowLayer(shadow, 0f, 2f * density, shadowColor);
        ampm.setTextColor(ampmColor);
        ampm.setShadowLayer(shadow, 0f, 1.5f * density, shadowColor);
        date.setTextColor(dateColor);
        date.setShadowLayer(shadow, 0f, 1.5f * density, shadowColor);
        shamsiDate.setTextColor(shamsiColor);
        shamsiDate.setShadowLayer(shadow, 0f, 1.5f * density, shadowColor);

        // No pill has glass behind it any more: the type sits straight on the wallpaper, so the
        // clock pill takes the same colour as the clock itself. No shadow though — the VPN capsule is
        // painted with the pill's own paint, and a shadow layer would lift that capsule off the pill
        // as a floating blob instead of a flat badge.
        int pillBg = bright ? R.drawable.pill_bg_light : R.drawable.pill_bg;
        btnSettings.setTextColor(clockColor);
        btnSettings.setShadowLayer(0f, 0f, 0f, 0);

        btnInputs.setBackgroundResource(pillBg);
        btnWifi.setBackgroundResource(pillBg);
        btnSettings.setBackgroundResource(pillBg);
        infoStorage.setBackgroundResource(pillBg);
        infoMem.setBackgroundResource(pillBg);
        infoSd.setBackgroundResource(pillBg);
        infoNet.setBackgroundResource(pillBg);

        // The label under a tile sits on the wallpaper rather than on the glass, so it follows the
        // same rule as the clock.
        if (adapter != null) adapter.setNameColor(bright ? 0xE6111111 : 0xF2FFFFFF);
    }

    /**
     * Mean perceived luminance of a wallpaper, 0 to 255, on a 32x18 downscale: a few hundred pixels
     * rather than a second full decode. {@code topBand} restricts it to the top third, which is where
     * the greeting, clock and pills sit — a photo can average mid-grey and still have a blown-out sky
     * exactly behind the type.
     */
    private static float averageLuminance(android.graphics.Bitmap bmp, boolean topBand) {
        try {
            android.graphics.Bitmap tiny = android.graphics.Bitmap.createScaledBitmap(bmp, 32, 18, false);
            int[] px = new int[32 * 18];
            tiny.getPixels(px, 0, 32, 0, 0, 32, 18);
            if (tiny != bmp) tiny.recycle();
            int rows = topBand ? 6 : 18;
            double sum = 0;
            for (int i = 0; i < 32 * rows; i++) {
                int c = px[i];
                sum += 0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF);
            }
            return (float) (sum / (32 * rows));
        } catch (Exception | OutOfMemoryError e) {
            return 0f;
        }
    }

    /**
     * The luminance at {@code fraction} of the way up the sorted pixel list, e.g. 0.10 for the darkest
     * tenth. Used to tell a bright picture with deep shadows (white type still works) from one that is
     * bright everywhere (only dark type works).
     */
    private static float percentileLuminance(android.graphics.Bitmap bmp, float fraction) {
        try {
            android.graphics.Bitmap tiny = android.graphics.Bitmap.createScaledBitmap(bmp, 32, 18, false);
            int[] px = new int[32 * 18];
            tiny.getPixels(px, 0, 32, 0, 0, 32, 18);
            if (tiny != bmp) tiny.recycle();
            int[] lum = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                lum[i] = (int) (0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF));
            }
            java.util.Arrays.sort(lum);
            int index = Math.max(0, Math.min(lum.length - 1, (int) (fraction * lum.length)));
            return lum[index];
        } catch (Exception | OutOfMemoryError e) {
            return 0f;
        }
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        rebuildOnResume = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        promptsPaused = false;
        if (rebuildOnResume) {
            tilesReady = false;
            reloadApps(true);
        }
        rebuildOnResume = false;
        refreshWallpaperIfChanged();
        updateGreeting();
        updateWifiLabel();
        updateSystemLabel();
        updateInputsButton();
        applyModules();
        sky.update();
        // Align the first tick to the next minute boundary, so the pill catches up with the TextClock.
        long minutePhase = System.currentTimeMillis() % MINUTE_MS;
        handler.postDelayed(minuteTick, MINUTE_MS - minutePhase);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = animate || grid.hasFocus();
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
        reloadTiles();
        if (animate && animations && !reducedMotion) grid.scheduleLayoutAnimation();

        if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, Cards.load(Cards.storeFile(this))), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none));
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(netTick);
        netTicking = false;
        netAt = 0;
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) {
            // A folder panel has nothing to return to while Home is going away.
            folderLaunching = true;
            openPopup.dismiss();
            hideFolderScrim();
            folderLaunching = false;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(packageReceiver);
        rebuildOnResume = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        showCardPrompt();
        // A panel that closed can leave focus on the grid container itself, where no key reaches a
        // tile and the remote appears dead. Put it back on the tile it came from.
        if (getCurrentFocus() == grid) restoreFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        if (moving) {
            cancelMove();       // Back undoes the drag: every tile goes back where it came from
            return;
        }
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        if (moving && position != moveFrom) dragStep(position);   // the live drag: it follows the cursor
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = adapter.focusScale();
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - (advancedEffects ? 4f * density : 0f);

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (animations) glass.animate().alpha(1f).setDuration(reducedMotion ? 100 : 180).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (!animations || reducedMotion) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, reducedMotion);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            heroName.animate().cancel();
            ambient.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.setAlpha(1f);
            setAmbient(color);
            if (!animations) {
                ambient.setAlpha(1f);
                heroShown = true;
            } else if (!heroShown) {
                ambient.setAlpha(0f);
                ambient.animate().alpha(1f).setDuration(160).start();
                heroShown = true;
            }
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            heroName.animate().cancel();
            ambient.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) heroName.animate().cancel();
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (advancedEffects) {
                transitionAmbient(color);
            } else if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            heroName.setY(y);
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new RadialGradient(
                    w * 0.32f, h * 0.42f, w * 0.62f,
                    new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        heroShown = false;
        if (animations) {
            heroName.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
            ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
        } else {
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        // While carrying, the cursor IS the carried tile, so the walk starts from moveFrom — not
        // from whatever holder happens to hold focus after a swap reshuffled them.
        if (moving) {
            int to = moveFrom;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = moveFrom - 1;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = moveFrom + 1;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = moveFrom - COLUMNS;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) to = moveFrom + COLUMNS;
            else return false;
            if (to < 0 || to >= adapter.getItemCount()) return true;   // stay put
            focusTile(to);
            return true;
        }
        int count = adapter.getItemCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return GridNavigation.atHorizontalEdge(position, count, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) {
            btnWifi.requestFocus();
            return;
        }
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else if (!card.requestFocus()) {
                // Right after a popup closes the window may not accept focus yet; one frame later it does.
                // Without this, focus stays on the grid container itself and every key press is lost.
                grid.postDelayed(() -> {
                    RecyclerView.ViewHolder again = grid.findViewHolderForAdapterPosition(pos);
                    if (again != null) again.itemView.findViewById(R.id.card).requestFocus();
                }, 60);
            }
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            btnWifi.setText(text);
        }
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
    }

    /**
     * Updates the top-right system pill: the current time, plus a VPN badge when a VPN app is
     * configured — green with a lock while the VPN is up, gray while it is down. No VPN app means
     * no badge at all, so the pill is just a clock.
     */
    private void updateSystemLabel() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        String time = sdf.format(new java.util.Date());
        boolean vpn = false, online = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) online = true;
            }
        } catch (Exception ignored) {}
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Plain two-state capsule: green while up, gray while down, no lock, no "off" word.
            VpnBadge span = new VpnBadge(vpn ? 0xFF30D158 : 0xFF8E8E93, density);
            String badge = getString(R.string.vpn);
            SpannableString label = new SpannableString(time + " " + badge);
            label.setSpan(span, time.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnSettings.setText(label);
        } else {
            btnSettings.setText(time);
        }
    }

    /**
     * The local status pills under the clock, each gated by its key in {@link Modules}. The launcher
     * has no network permission, so these are device-local only: internal storage free, RAM used as
     * a percentage, the free space of a removable volume (USB / SD) when the system exposes one, and
     * the throughput of the network interfaces. A pill hides itself when its module is off or when
     * the value cannot be read, so a device without a removable volume shows one fewer.
     */
    private void updateInfoCards() {
        if (!infoCardsOn) return;   // the whole row is already gone
        showPill(infoStorage, Modules.STORAGE, () -> infoStorage.setText(getString(R.string.info_storage,
                Formatter.formatShortFileSize(this, new StatFs(Environment.getDataDirectory()
                        .getAbsolutePath()).getAvailableBytes()))));
        showPill(infoMem, Modules.MEMORY, () -> {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);
            int used = mi.totalMem > 0 ? (int) Math.round(100.0 * (mi.totalMem - mi.availMem) / mi.totalMem) : 0;
            infoMem.setText(getString(R.string.info_mem, used));
        });
        showPill(infoSd, Modules.EXTERNAL, () -> {
            String removable = null;
            File[] dirs = getExternalFilesDirs(null);
            for (int i = 1; i < dirs.length; i++) {
                if (dirs[i] != null) {
                    removable = dirs[i].getAbsolutePath();
                    break;
                }
            }
            // Not an error the owner needs: the pill simply has nothing to say on this device.
            if (removable == null) throw new IllegalStateException("no removable volume");
            infoSd.setText(getString(R.string.info_external, Formatter.formatShortFileSize(this,
                    new StatFs(removable).getAvailableBytes())));
        });
        updateNetSpeed();
    }

    /** Fills one pill, or hides it when its module is off or the read behind it failed. */
    private void showPill(TextView pill, String module, Runnable fill) {
        if (!modules.contains(module)) {
            pill.setVisibility(View.GONE);
            return;
        }
        try {
            fill.run();
            pill.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {
            pill.setVisibility(View.GONE);
        }
    }

    /**
     * The throughput pill. {@code /proc/net/dev} counts every interface and needs no permission, so
     * the launcher can show what a proxy app is pushing without holding a network permission itself.
     * The first sample only takes a baseline; the pill appears on the next one.
     */
    private void updateNetSpeed() {
        if (!infoCardsOn || !modules.contains(Modules.NETSPEED)) {
            infoNet.setVisibility(View.GONE);
            return;
        }
        long rx = 0, tx = 0;
        try (BufferedReader in = new BufferedReader(new FileReader("/proc/net/dev"))) {
            String line;
            while ((line = in.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;                       // the two header lines
                String name = line.substring(0, colon).trim();
                if (name.equals("lo")) continue;               // loopback would count twice
                String[] field = line.substring(colon + 1).trim().split("\\s+");
                if (field.length < 9) continue;
                rx += Long.parseLong(field[0]);                // received bytes
                tx += Long.parseLong(field[8]);                // transmitted bytes
            }
        } catch (Exception ignored) {
            infoNet.setVisibility(View.GONE);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (netAt > 0 && now > netAt) {
            long dt = now - netAt;
            infoNet.setText(getString(R.string.info_net,
                    perSecond(Math.max(0, rx - netRx) * 1000 / dt),
                    perSecond(Math.max(0, tx - netTx) * 1000 / dt)));
            infoNet.setVisibility(View.VISIBLE);
        }
        netRx = rx;
        netTx = tx;
        netAt = now;
    }

    private String perSecond(long bytes) {
        return getString(R.string.info_net_rate, Formatter.formatShortFileSize(this, bytes));
    }

    /**
     * Re-reads every pill after a module was switched, and starts or stops the throughput ticker with
     * it: nothing samples in the background when the module is off.
     */
    private void applyModules() {
        updateInfoCards();
        if (infoCardsOn && modules.contains(Modules.NETSPEED)) {
            if (!netTicking) {
                netTicking = true;
                handler.post(netTick);
            }
        } else {
            handler.removeCallbacks(netTick);
            netTicking = false;
            netAt = 0;   // the next sample after it comes back on is a baseline, not a burst
        }
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** The Inputs pill is always hidden: this projector's user rarely switches sources, and the
     *  Physical "Input" key / the input apps still work without it. */
    private void updateInputsButton() {
        btnInputs.setVisibility(View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        focusFirstRow(rows);
    }

    // ---- folders ---------------------------------------------------------------------------------

    /**
     * Opens a folder tile. The apps inside it are shown as a row of icons — OK on one launches it —
     * with two rows underneath for taking apps in and out of the folder. Back, a click outside or
     * the Home key closes the panel and returns focus to the tile it came from, so Home is never
     * left behind a panel.
     */
    @Override
    public void onFolderOpen(AppAdapter.Entry tile) {
        openFolderPanel(AppAdapter.folderUid(tile));
    }

    /** Index of the layout slot holding this folder, or -1. */
    private int slotOfFolder(String uid) {
        List<Config.Slot> l = adapter.layout();
        for (int i = 0; i < l.size(); i++) {
            Config.Slot s = l.get(i);
            if (s.kind == Config.Slot.FOLDER && s.uid.equals(uid)) return i;
        }
        return -1;
    }

    /**
     * The folder panel: its apps as a square block of icons. The column count follows the app count
     * ({@code ceil(sqrt(n))}), so four apps make a 2x2 and nine a 3x3 — the panel is always as
     * square as the contents allow instead of a strip that scrolls sideways.
     *
     * <p>There is no add/remove menu here any more. An app joins a folder by being carried onto the
     * folder tile, and leaves it by a long OK on its icon, which hands it back to the grid still
     * carried so it can be put down wherever it belongs.
     */
    private void openFolderPanel(final String uid) {
        final AppAdapter.Entry tile = adapter.folderTileFor(uid);
        if (tile == null || tile.children.isEmpty()) return;      // nothing installed of its apps
        final int slot = slotOfFolder(uid);
        if (slot < 0) return;
        folderLaunching = false;
        View panel = getLayoutInflater().inflate(R.layout.popup_folder, root, false);
        LinearLayout grid = panel.findViewById(R.id.iconGrid);
        ScrollView scroll = panel.findViewById(R.id.scroll);
        final int n = tile.children.size();
        // A square block, the way it was: four apps make a 2x2 and nine a 3x3, the panel hugging
        // the block. (A flat spread row was tried here and read wrong inside the popup — the spread
        // belongs on the folder tile's picture, not in the panel.)
        final int side = Math.max(1, Math.min(5, (int) Math.ceil(Math.sqrt(n))));
        View first = null;
        LinearLayout row = null;
        final List<View> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (i % side == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                // Left-aligned, as asked: a short last row starts under the first icon instead of
                // floating to the middle of the panel, which read as a separate row of its own.
                row.setGravity(Gravity.START);
                // Full width of the grid, so the last row has slack to hold its icons at the left
                // edge; a wrap_content row would hug its own icons and gravity would do nothing.
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final AppAdapter.Entry kid = tile.children.get(i);
            View cell = getLayoutInflater().inflate(R.layout.item_folder_icon, row, false);
            cell.setId(View.generateViewId());   // linkTiles() below wires the D-pad by id
            ((ImageView) cell.findViewById(R.id.icon)).setImageDrawable(kid.icon);
            ((TextView) cell.findViewById(R.id.label)).setText(kid.label);
            cell.setOnClickListener(v -> {
                folderLaunching = true;
                folderPopupWindow.dismiss();
                adapter.launch(kid);
            });
            // Long OK takes the app out: the panel closes and the app is already in hand on the grid.
            // The hold is read here rather than left to View's long-click timer: inside a popup the
            // timer never got a chance to fire before the release turned the press into a launch.
            cell.setOnLongClickListener(v -> {
                pullOutOfFolder(uid, kid.pkg);
                return true;
            });
            cell.setOnKeyListener((v, code, e) -> {
                if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN
                        && (e.isLongPress() || e.getRepeatCount() > 1)) {
                    pullOutOfFolder(uid, kid.pkg);
                    return true;
                }
                return false;
            });
            liftOnFocus(cell, 1.12f);
            row.addView(cell, new LinearLayout.LayoutParams(
                    (int) (112 * density), (int) (112 * density)));
            if (first == null) first = cell;
            cells.add(cell);
        }
        // The default focus search inside a popup gives up on this device instead of walking to the
        // next cell (see linkTiles), so the grid is wired by hand — the same treatment the control
        // centre and the menus get. Without it only the first Right ever moved.
        linkTiles(cells, side);
        // No padding out to a square. The blanks used to keep the panel perfectly square, but a
        // folder of six then drew two rows of icons over a third row of nothing, which reads as a
        // rendering fault rather than as tidiness. The panel now stops at the last real row; the
        // last row can be short, and it is centred.
        // Five rows is all a 1080p screen has room for. The column count stops at five too, so a
        // folder of thirty is 5x6: the block scrolls rather than running off the bottom.
        if (n > side * 5) {
            scroll.getLayoutParams().height = (int) (5 * 112 * density);
        }
        showFolderContent(uid, panel, false);
        if (first != null) first.requestFocus();
    }

    /**
     * Takes an app back out of a folder and puts it on the grid right after it, already carried, so
     * the owner can drop it wherever it belongs. An empty folder goes with it: a folder with nothing
     * in it is a dead tile.
     */
    private void pullOutOfFolder(final String uid, final String pkg) {
        final int slot = slotOfFolder(uid);
        if (slot < 0) return;
        List<Config.Slot> next = new ArrayList<>(adapter.layout());
        Config.Slot folder = next.get(slot);
        List<String> apps = new ArrayList<>(folder.apps);
        if (!apps.remove(pkg)) return;      // already out: a repeated hold must not add it twice
        int at;
        if (apps.isEmpty()) {
            next.remove(slot);
            at = slot;
        } else {
            next.set(slot, Config.Slot.folder(uid, folder.name, apps));
            at = slot + 1;
        }
        next.add(at, Config.Slot.app(pkg));
        if (folderPopupWindow != null) folderPopupWindow.dismiss();
        adapter.setLayout(next);
        saveLayout();
        reloadTiles();
        grid.post(() -> startMove(at));
    }

    // ---- rearranging ----------------------------------------------------------------------------

    /** Long OK on a tile: what can be done with it, depending on whether it is an app or a folder. */
    @Override
    public void onTileLongPress(int position) {
        final AppAdapter.Entry tile = adapter.entryAt(position);
        if (tile == null) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        // Carrying is the free-form way to rearrange: pick the tile up, walk anywhere, put it down.
        // Dropping it on a folder is also how an app gets inside one.
        addMenuRow(rows, R.drawable.ic_tv_move, R.string.tile_move, () -> startMove(position));
        if (tile.kind == AppAdapter.Entry.FOLDER) {
            final String uid = AppAdapter.folderUid(tile);
            addMenuRow(rows, R.drawable.ic_tv_rename, R.string.folder_rename, () -> renameFolder(uid));
            addMenuRow(rows, R.drawable.ic_tv_trash, R.string.folder_delete, () -> deleteFolder(uid));
        } else {
            addMenuRow(rows, R.drawable.ic_tv_folder_in, R.string.tile_move_to, () -> showFolderPicker(position));
            addMenuRow(rows, R.drawable.ic_tv_folder_plus, R.string.folder_new, () -> createFolderWith(position));
            // No "delete" here on purpose: this firmware's package installer crashes when asked to
            // uninstall, so the row could only ever take the app off Home. Apps are removed from
            // the system's own app list instead.
            addMenuRow(rows, R.drawable.ic_tv_info, R.string.tile_info, () -> openAppInfo(tile.pkg));
        }
        showMenu(panel, (int) (300 * density), position);
        rows.getChildAt(0).requestFocus();
    }

    /**
     * Carrying mode: the tile is picked up where it stands and the D-pad then drags it through the
     * grid — every focus step swaps the carried tile with the one under the cursor, so the grid
     * reorders live, the way a drag should. OK puts it down where it then sits, or on a folder
     * (the app moves inside it), and Back puts every tile back where it came from.
     */
    private void startMove(int position) {
        if (adapter.getItemCount() < 2) return;
        moving = true;
        moveFrom = position;
        moveDirty = false;
        moveOriginal = new ArrayList<>(adapter.layout());
        adapter.setMovingPosition(position);
        Toast.makeText(this, R.string.move_hint, Toast.LENGTH_LONG).show();
        focusTile(position);
    }

    private void cancelMove() {
        if (!moving) return;
        moving = false;
        moveFrom = -1;
        adapter.setMovingPosition(-1);
        if (moveDirty && moveOriginal != null) {
            // The live drag only moved views: put the tiles back and rebuild from the saved order.
            adapter.setLayout(moveOriginal);
            reloadTiles();
        }
        moveDirty = false;
        moveOriginal = null;
        restoreFocus();
    }

    /**
     * One step of the live drag: the carried tile trades places with the tile the cursor landed
     * on. A folder is the one exception — dropping an app on a folder means "move it inside", not
     * "swap with it", so the carried tile holds its slot there and OK moves it in.
     */
    private void dragStep(int position) {
        final AppAdapter.Entry held = adapter.entryAt(moveFrom);
        final AppAdapter.Entry target = adapter.entryAt(position);
        if (held == null || target == null) return;
        if (target.kind == AppAdapter.Entry.HOME_SETTINGS) return;   // not a layout slot
        if (target.kind == AppAdapter.Entry.FOLDER && held.kind != AppAdapter.Entry.FOLDER) return;
        adapter.swap(moveFrom, position);
        moveFrom = position;
        adapter.setMovingPosition(position);
        moveDirty = true;
        // A swap reshuffles holders, so focus can be left behind on the tile that was displaced.
        // Put it back on the carried tile: it is the cursor, and the next step starts from it.
        grid.post(() -> focusTile(position));
    }

    /** Puts the carried tile down on {@code to}: into a folder, or where the live drag left it. */
    private void dropMove(int to) {
        if (!moving) return;
        final boolean dirty = moveDirty;
        final int from = moveFrom;
        moving = false;
        moveFrom = -1;
        adapter.setMovingPosition(-1);
        moveDirty = false;
        moveOriginal = null;
        final AppAdapter.Entry held = adapter.entryAt(from);
        final AppAdapter.Entry target = adapter.entryAt(to);
        // Work from the order actually on screen, not from the saved layout: an app the layout does
        // not name yet is appended after it, so a drop past that point would otherwise land at the
        // tail instead of where it was dropped.
        List<Config.Slot> next = new ArrayList<>(adapter.snapshot());
        final int fromAt = slotIndexOf(next, held);
        if (held == null || target == null || fromAt < 0) {
            restoreFocus();
            return;
        }
        final int toAt = slotIndexOf(next, target);
        if (toAt < 0) {
            restoreFocus();                                  // e.g. the settings tile: no slot
            return;
        }
        if (toAt == fromAt) {
            // The live drag already brought the carried tile here: the order on screen is the
            // result, so all that is left is to keep it.
            if (dirty) commitMove(next, to);
            else restoreFocus();
            return;
        }
        if (next.get(toAt).kind == Config.Slot.FOLDER && held.kind != AppAdapter.Entry.FOLDER) {
            Config.Slot folder = next.get(toAt);
            List<String> apps = new ArrayList<>(folder.apps);
            if (!apps.contains(held.pkg)) apps.add(held.pkg);
            next.set(toAt, Config.Slot.folder(folder.uid, folder.name, apps));
            next.remove(fromAt);
            commitMove(next, fromAt < toAt ? toAt - 1 : toAt);
            return;
        }
        Config.Slot mine = next.get(fromAt);
        next.remove(fromAt);
        int at = Math.max(0, Math.min(toAt, next.size()));
        next.add(at, mine);
        commitMove(next, at);
    }

    /** Where this tile sits in a layout: by folder uid, or by package for everything else. */
    private static int slotIndexOf(List<Config.Slot> layout, AppAdapter.Entry e) {
        if (e == null) return -1;
        String uid = AppAdapter.folderUid(e);
        for (int i = 0; i < layout.size(); i++) {
            Config.Slot s = layout.get(i);
            if (uid != null) {
                if (s.kind == Config.Slot.FOLDER && uid.equals(s.uid)) return i;
            } else if (s.kind == Config.Slot.APP && e.pkg.equals(s.pkg)) {
                return i;
            }
        }
        return -1;
    }

    private void commitMove(List<Config.Slot> next, int focusAt) {
        adapter.setLayout(next);
        saveLayout();
        reloadTiles();
        final int at = Math.max(0, Math.min(focusAt, adapter.getItemCount() - 1));
        grid.post(() -> focusTile(at));
    }

    /** OK while carrying drops the tile on the one under the cursor instead of opening it. */
    @Override
    public boolean onTileActivated(AppAdapter.Entry tile) {
        if (!moving) return false;
        dropMove(lastFocused);
        return true;
    }

    /** Puts an app into one of the folders that already exist. */
    private void showFolderPicker(final int position) {
        final AppAdapter.Entry tile = adapter.entryAt(position);
        if (tile == null) return;
        List<Config.Slot> foldersOnly = new ArrayList<>();
        for (Config.Slot s : adapter.layout()) {
            if (s.kind == Config.Slot.FOLDER) foldersOnly.add(s);
        }
        if (foldersOnly.isEmpty()) {
            Toast.makeText(this, R.string.folder_none, Toast.LENGTH_SHORT).show();
            return;
        }
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        for (final Config.Slot f : foldersOnly) {
            // The name only: how many apps are inside is the folder tile's business, and in a list
            // of folders it read as noise ("工具 · 4 apps" twice over).
            addMenuRow(rows, R.drawable.ic_tv_folder_in, f.name, () -> {
                List<Config.Slot> next = withoutApp(adapter.layout(), tile.pkg);
                int at = indexOfFolder(next, f.uid);
                if (at < 0) return;
                List<String> apps = new ArrayList<>(next.get(at).apps);
                apps.add(tile.pkg);
                next.set(at, Config.Slot.folder(f.uid, f.name, apps));
                adapter.setLayout(next);
                saveLayout();
                reloadTiles();
                restoreFocus();
            });
        }
        showMenu(panel, (int) (320 * density), position);
        rows.getChildAt(0).requestFocus();
    }

    /** Makes a new folder holding this app, at the end of the grid. */
    private void createFolderWith(int position) {
        AppAdapter.Entry tile = adapter.entryAt(position);
        if (tile == null) return;
        List<Config.Slot> next = withoutApp(adapter.layout(), tile.pkg);
        int n = 1;
        for (Config.Slot s : next) {
            if (s.kind == Config.Slot.FOLDER) n++;
        }
        final String uid = AppAdapter.nextFolderUid(next);
        next.add(Config.Slot.folder(uid, getString(R.string.folder_new_name, n),
                Collections.singletonList(tile.pkg)));
        adapter.setLayout(next);
        saveLayout();
        reloadTiles();
        grid.post(() -> {
            int at = adapter.positionOfFolder(uid);
            if (at >= 0) focusTile(at);
        });
    }

    /** Drops a folder: its apps go back to the end of the grid, ungrouped. */
    private void deleteFolder(String uid) {
        int slot = slotOfFolder(uid);
        if (slot < 0) return;
        List<Config.Slot> next = new ArrayList<>(adapter.layout());
        next.remove(slot);
        adapter.setLayout(next);
        saveLayout();
        reloadTiles();
        restoreFocus();
    }

    // ---- renaming a folder ----------------------------------------------------------------------

    /** Delete and Space have no character of their own, so they get a name and a job of their own. */
    private static final String KEY_DELETE = "\u232B";
    private static final String KEY_SPACE = "\u2423";

    /** The rename keyboard, one string per row. The last row carries the wide Space key. */
    private static final String[][] KEYS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l", KEY_DELETE},
            {"z", "x", "c", "v", "b", "n", "m", ".", KEY_SPACE}
    };

    /**
     * Renames a folder where it stands. A dialog rather than a popup, because it owns the focus for
     * as long as it is up and the remote never has to leave it. Save writes the new label and puts
     * focus back on the same tile; the folder keeps its slot, so nothing jumps around.
     */
    private void renameFolder(String uid) {
        final int slot = slotOfFolder(uid);
        if (slot < 0) return;
        final Config.Slot spec = adapter.layout().get(slot);

        View panel = getLayoutInflater().inflate(R.layout.dialog_folder_name, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(R.string.rename_title);
        final EditText field = panel.findViewById(R.id.name);
        field.setHint(R.string.rename_hint);
        field.setText(spec.name);
        field.setSelection(field.getText().length());
        // The TV has no working text IME, only the air-mouse cursor service, so the keyboard built
        // below does the typing and the system one is kept out of the way.
        field.setShowSoftInputOnFocus(false);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (460 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.12f);
        }

        final View cancel = panel.findViewById(R.id.btnCancel);
        final View save = panel.findViewById(R.id.btnSave);
        final View firstKey = buildKeyboard(panel.findViewById(R.id.keys), field, cancel, save);
        // An EditText swallows the D-pad to move its caret, so Down is taken over to reach the
        // keyboard. Left and Right still move the caret, which is what they should do in a field.
        field.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                firstKey.requestFocus();
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_UP;
        });

        final boolean[] done = {false};
        View.OnClickListener choose = v -> {
            if (done[0]) return;
            done[0] = true;
            final String typed = field.getText().toString().trim();
            final boolean saving = v.getId() == R.id.btnSave;
            dialog.dismiss();
            if (!saving) {
                restoreFocus();
            } else if (typed.isEmpty()) {
                Toast.makeText(this, R.string.rename_empty, Toast.LENGTH_SHORT).show();
                restoreFocus();
            } else {
                applyFolderName(uid, typed);
            }
        };
        cancel.setOnClickListener(choose);
        save.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(cancel));    // Back means Cancel
        dialog.show();
        // Same whisper of frost as the panels: the field and the keys carry their own ink, but a
        // bare transparent block over a bright wallpaper left the white text without footing. A
        // light ink wash over the blurred slice keeps it readable while the dialog still reads as
        // glass rather than as a slab.
        frostPanel(panel, 16f, 0.22f, null);
        field.requestFocus();
        // The whole old name starts selected, so the first key typed replaces it the way a rename
        // field should. Asked for after the dialog is up: a selection made before focus is lost.
        field.post(() -> field.setSelection(0, field.getText().length()));
    }

    /** Writes the new label into the same slot and rebuilds the tiles. */
    private void applyFolderName(String uid, String name) {
        int slot = slotOfFolder(uid);
        if (slot < 0) {
            restoreFocus();
            return;
        }
        List<Config.Slot> next = new ArrayList<>(adapter.layout());
        Config.Slot old = next.get(slot);
        next.set(slot, Config.Slot.folder(uid, name, old.apps));
        adapter.setLayout(next);
        saveLayout();
        reloadTiles();
        grid.post(() -> {
            int at = adapter.positionOfFolder(uid);
            if (at >= 0) focusTile(at);
        });
    }

    /**
     * The keyboard under the name field. It exists because a text field on this TV is otherwise a
     * dead end: the only registered input method is the air-mouse cursor service, so a remote cannot
     * type. Keys are laid out and chained by hand for the same reason the menu rows are.
     */
    private View buildKeyboard(LinearLayout host, EditText field, View cancel, View save) {
        host.setClipChildren(false);
        final List<List<View>> rows = new ArrayList<>();
        for (int r = 0; r < KEYS.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) rlp.topMargin = (int) (4 * density);
            host.addView(row, rlp);

            List<View> line = new ArrayList<>();
            for (final String label : KEYS[r]) {
                final boolean space = KEY_SPACE.equals(label);
                final boolean erase = KEY_DELETE.equals(label);
                TextView key = new TextView(this);
                key.setId(View.generateViewId());
                key.setText(space ? getString(R.string.rename_space) : label);
                key.setGravity(Gravity.CENTER);
                key.setTextColor(0xFFFFFFFF);
                key.setShadowLayer(6f * density, 0f, 1f * density, 0xCC000000);
                key.setTextSize(space ? 12 : 15);
                key.setBackgroundResource(R.drawable.pill_bg);
                key.setFocusable(true);
                key.setClickable(true);
                key.setFocusableInTouchMode(true);
                key.setContentDescription(space ? getString(R.string.rename_space) : label);
                keyPress(key, () -> typeInto(field, erase ? null : space ? " " : label));
                liftOnFocus(key);

                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0,
                        (int) (38 * density), space ? 2f : 1f);
                if (!line.isEmpty()) klp.setMarginStart((int) (4 * density));
                row.addView(key, klp);
                line.add(key);
            }
            rows.add(line);
        }

        // Chain the keys: left and right along a row, up and down to the nearest key of the row
        // above or below. The focus finder on its own stops dead at the first key it sees.
        for (int r = 0; r < rows.size(); r++) {
            List<View> line = rows.get(r);
            for (int c = 0; c < line.size(); c++) {
                View key = line.get(c);
                key.setNextFocusLeftId(c > 0 ? line.get(c - 1).getId() : key.getId());
                key.setNextFocusRightId(c < line.size() - 1 ? line.get(c + 1).getId() : key.getId());
                key.setNextFocusUpId(r > 0 ? nearest(line, rows.get(r - 1), c).getId() : field.getId());
                key.setNextFocusDownId(r < rows.size() - 1
                        ? nearest(line, rows.get(r + 1), c).getId() : save.getId());
            }
        }
        cancel.setNextFocusUpId(field.getId());
        save.setNextFocusUpId(field.getId());
        field.setNextFocusDownId(rows.get(0).get(0).getId());
        return rows.get(0).get(0);
    }

    /** The key in {@code target} that sits closest to column {@code c} of {@code from}. */
    private static View nearest(List<View> from, List<View> target, int c) {
        int at = Math.round((float) c * (target.size() - 1) / Math.max(1, from.size() - 1));
        return target.get(Math.max(0, Math.min(target.size() - 1, at)));
    }

    /** Types one key into the field: {@code insert} is the text, or null to delete backwards. */
    private static void typeInto(EditText field, String insert) {
        String text = field.getText().toString();
        int a = Math.min(Math.max(field.getSelectionStart(), 0), text.length());
        int b = Math.min(Math.max(field.getSelectionEnd(), 0), text.length());
        int from = Math.min(a, b);
        int to = Math.max(a, b);
        if (insert == null) {
            // With a selection the key clears it, the way a real keyboard does.
            if (from == to && from > 0) from--;
            field.setText(text.substring(0, from) + text.substring(to));
            field.setSelection(from);
            return;
        }
        field.setText(text.substring(0, from) + insert + text.substring(to));
        field.setSelection(from + insert.length());
    }

    /** OK on a key. The action runs on the way up, so a held key does not type a row of characters. */
    private void keyPress(View key, Runnable action) {
        key.setOnClickListener(v -> action.run());
        key.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) action.run();
            return true;
        });
    }

    private void openAppInfo(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** The layout with this app taken out of wherever it is: a tile of its own, or inside a folder. */
    private static List<Config.Slot> withoutApp(List<Config.Slot> layout, String pkg) {
        List<Config.Slot> out = new ArrayList<>();
        for (Config.Slot s : layout) {
            if (s.kind == Config.Slot.APP) {
                if (!s.pkg.equals(pkg)) out.add(s);
                continue;
            }
            if (!s.apps.contains(pkg)) {
                out.add(s);
                continue;
            }
            List<String> apps = new ArrayList<>(s.apps);
            apps.remove(pkg);
            if (!apps.isEmpty()) out.add(Config.Slot.folder(s.uid, s.name, apps));
        }
        return out;
    }

    private static int indexOfFolder(List<Config.Slot> layout, String uid) {
        for (int i = 0; i < layout.size(); i++) {
            Config.Slot s = layout.get(i);
            if (s.kind == Config.Slot.FOLDER && s.uid.equals(uid)) return i;
        }
        return -1;
    }

    /** Adds one row to a menu, with the glyph that says what the row does. */
    private void addMenuRow(LinearLayout rows, int iconRes, int title, Runnable action) {
        addMenuRow(rows, iconRes, getString(title), action);
    }

    private void addMenuRow(LinearLayout rows, int iconRes, String title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        menuRow(rows, row, title, action);
    }

    /** The same row wearing the app's own artwork: the swap list shows what you are picking. */
    private void addMenuRow(LinearLayout rows, Drawable artwork, String title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ImageView icon = row.findViewById(R.id.icon);
        icon.setImageDrawable(artwork);
        icon.setImageTintList(null);      // app artwork keeps its own colours
        menuRow(rows, row, title, action);
    }

    /** Shared wiring for a menu row: identity, focus chain, and both the touch and the D-pad path. */
    private void menuRow(LinearLayout rows, View row, String title, Runnable action) {
        row.setId(View.generateViewId());
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        popupItem(row, action);
        liftOnFocus(row);
        rows.addView(row);
        linkRows(rows);
    }

    /**
     * The tvOS 27 way of showing focus: nothing fills in, the item just lifts. The background
     * stays exactly what it was, so a menu over a photo never turns into a stack of white bars.
     */
    private void liftOnFocus(View item) {
        liftOnFocus(item, 1.04f);
    }

    private void liftOnFocus(View item, final float scale) {
        final Interpolator spring = new SpringInterpolator();
        final Interpolator settle = new DecelerateInterpolator(1.8f);
        item.setOnFocusChangeListener((view, focused) -> {
            view.animate().cancel();
            // Growing is a spring, shrinking is not: a spring on the way out makes the tile wobble
            // as the focus ring leaves it, which is the bounce tvOS never has.
            view.animate().scaleX(focused ? scale : 1f).scaleY(focused ? scale : 1f)
                    .setDuration(focused ? 220 : 150)
                    .setInterpolator(focused ? spring : settle)
                    .start();
        });
    }

    /**
     * Both input paths for anything inside a popup: a plain click for touch and air-mouse, and OK
     * for the remote. The OK that opened the popup must not also activate the item under it, hence
     * the press tracking.
     */
    private void popupItem(View item, Runnable action) {
        item.setOnClickListener(v -> {
            if (openPopup != null) openPopup.dismiss();
            action.run();
        });
        final boolean[] pressed = {false};
        item.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                // Every item here replaces the popup: leaving it up would eat the D-pad presses that
                // move the tile afterwards, and the panel would sit over the grid it is rearranging.
                if (openPopup != null) openPopup.dismiss();
                action.run();
            }
            return true;
        });
    }

    /**
     * Chains menu rows top-to-bottom for the D-pad. Without explicit links the focus search inside
     * a popup stops dead on the first row, so Down did nothing and only a tap could reach row two.
     */
    private void linkRows(LinearLayout rows) {
        int n = rows.getChildCount();
        for (int i = 0; i < n; i++) {
            View row = rows.getChildAt(i);
            row.setNextFocusUpId(i > 0 ? rows.getChildAt(i - 1).getId() : row.getId());
            row.setNextFocusDownId(i < n - 1 ? rows.getChildAt(i + 1).getId() : row.getId());
        }
    }

    /**
     * A long-press menu over the home screen. Bare and transparent, like the folder panel: no
     * frosted slab and no drop shadow. It opens at the top-right of the tile it belongs to, the way
     * the folder unfolds next to its icon, so it never covers the grid or floats in the middle of
     * the screen. The 10% scrim below it is all that marks a layer has opened.
     */
    private void showMenu(View panel, int width, int tilePosition) {
        if (openPopup != null) openPopup.dismiss();
        showFolderScrim();
        final PopupWindow popup = glassPopup(panel, width, this::hideFolderScrim);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        // The panel hugs its rows now, so the menu is only as long as its longest row — the compact
        // tvOS shape — instead of a fixed 300dp slab with empty glass on the right. The width the
        // caller passes stays as the ceiling, so a long folder name still ellipsises instead of
        // running off the screen.
        int ceiling = Math.max(width, root.getWidth() - (int) (40 * density));
        popup.setWidth(Math.min(panel.getMeasuredWidth(), ceiling));
        panel.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.96f);
        // A whisper of frost rather than a bare transparent panel: the rows only carry their own
        // light ink, so over a bright wallpaper the white labels lost their footing. A small ink
        // wash over the blurred slice (tint ~0.22) gives them something to sit on while the panel
        // still reads as glass, not as a slab. No drop shadow (elevation 0) — that was the floaty
        // halo the bare version was asked to drop.
        panel.setElevation(0f);
        int[] spot = spotNearTile(tileRect(tilePosition), popup.getWidth(), panel.getMeasuredHeight(), tilePosition);
        popup.showAtLocation(root, Gravity.NO_GRAVITY, spot[0], spot[1]);
        frostPanel(panel, 12f, 0.22f, null);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    /** Screen rectangle of the grid tile at this adapter position, or null when it has no view. */
    private Rect rectAt(int position) {
        if (position < 0) return null;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        return holder == null ? null : rectOf(holder.itemView);
    }

    /**
     * Screen rectangle of the tile holding focus, or null. A tile pressed a moment ago normally
     * still owns focus, so when the wanted tile has no view this instant — a scroll in flight
     * detaches holders for a frame or two — its corner is the next best corner to hug, and far
     * better than dropping the panel at a screen corner unrelated to the press.
     */
    private Rect focusedTileRect() {
        return rectOf(grid.getFocusedChild());
    }

    private static Rect rectOf(View tile) {
        if (tile == null || tile.getWidth() <= 0 || tile.getHeight() <= 0) return null;
        // The card, not the item view: the item adds room around the card for the focus zoom, so
        // its rectangle floats a few dp off the tile the eye actually sees. Panels hug the card.
        View card = tile.findViewById(R.id.card);
        if (card != null && card.getWidth() > 0 && card.getHeight() > 0) tile = card;
        int[] at = new int[2];
        tile.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + tile.getWidth(), at[1] + tile.getHeight());
    }

    /** Screen rectangle of a grid tile by position, or null when it is scrolled off screen. */
    private Rect tileRect(int position) {
        Rect r = rectAt(position);
        return r != null ? r : focusedTileRect();
    }

    /**
     * Top-left corner for a panel that belongs to a tile: the panel's bottom-right corner meets
     * the tile's top-right corner, so the panel unfolds up and to the left out of the very corner
     * it came from.
     *
     * <p>Placing it up-and-to-the-RIGHT instead (the panel's bottom-left at the tile's top-right)
     * left panel and tile touching at a single point across a gap, and the panel read as floating
     * free of its tile — on a bottom-row tile it sat half a screen away, which looked cheap.
     * Hugging the corner keeps the panel's whole bottom edge right above the tile, the way a tvOS
     * menu grows out of the icon it belongs to.
     *
     * <p>Every row gets the same slide now — two app-icon widths right, giving back two thirds of
     * an icon for the slimmer menu (net 96px at this density). The dock used to slide three, which
     * made row two's menu sit a different distance from its tile than row one's; one offset for the
     * whole grid is what makes the panel read as growing out of the tile on every row.</p>
     *
     * <p>Every edge meets its tile edge flush (gap 0), not floating a few pixels off it. A flip
     * keeps the panel on screen when there is no room above, and a panel wider than the room left
     * of the tile slides to the screen margin instead of hanging off the left edge.
     */
    private int[] spotNearTile(Rect tile, int width, int height, int position) {
        int margin = (int) (22 * density);
        int gap = 0;                                     // flush: the panel edge meets the tile edge
        int icon = (int) (48 * density);
        int nudge = icon * 2 / 3;                       // the slimmer menu sits 2/3 icon left
        int slide = icon * 2 - nudge;                  // every row: two icons right, minus nudge
        int back = icon * 2;                            // two app-icon widths left (last column)
        int screenW = root.getWidth();
        int screenH = root.getHeight();
        int x;
        int y;
        if (tile != null) {
            x = tile.right - width + gap + slide;       // right edges line up, then the slide
            y = tile.top - height - gap;                // every row: bottom edge flush above tile
            if (y < margin) y = tile.bottom + gap;      // no room above: open downwards
            if (x > screenW - margin - width) {
                x = tile.right - width + gap - back;    // right-most column: two icons left instead
            }
            if (x < margin) x = margin;                 // wider than the room to the left
        } else {
            x = screenW - margin - width;               // unknown tile: top right, clear of the pills
            y = (int) (150 * density);
        }
        x = Math.min(x, Math.max(margin, screenW - margin - width));
        y = Math.min(y, Math.max(margin, screenH - margin - height));
        return new int[]{Math.max(margin, x), Math.max(margin, y)};
    }

    /**
     * Puts a panel inside the one folder window, opening that window the first time. Reusing the
     * window is what keeps the folder panel and the add/remove list from stacking: two popups would
     * leave Back closing only the top one, with the other still over the home screen.
     */
    private void showFolderContent(final String uid, View panel, boolean chooser) {
        // A real surface plus a light scrim. Left transparent it was only readable by darkening the
        // whole picture to 20% — which read as the screen failing every time a folder opened.
        showFolderScrim();
        if (folderHost == null) {
            folderHost = new FrameLayout(this);
            folderHost.setClipChildren(false);
            folderPopupWindow = glassPopup(folderHost, 0, () -> {
                folderHost = null;
                folderPopupWindow = null;
                if (folderLaunching) return;      // an app is starting: Home is going away anyway
                hideFolderScrim();
                final int at = adapter.positionOfFolder(uid);
                if (at >= 0) grid.post(() -> focusTile(at));
            });
        } else {
            folderHost.removeAllViews();
        }
        folderHost.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int room = Math.max((int) (280 * density), root.getWidth() - (int) (40 * density));
        int want = chooser ? (int) (320 * density) : panel.getMeasuredWidth();
        int width = Math.min(room, Math.max((int) (280 * density), want));
        // A whisper of frost, like the long-press menu: the cells carry their own ink but a bare
        // transparent block over a bright wallpaper left the white labels without footing. A small
        // ink wash over the blurred slice gives them one while the panel still reads as glass.
        panel.setElevation(0f);
        if (folderPopupWindow.isShowing()) {
            folderPopupWindow.update(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            frostPanel(panel, 12f, 0.22f, null);
            return;
        }
        folderPopupWindow.setWidth(width);
        panel.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.96f);
        // Anchored to its own tile, not to the middle of the screen: the panel opens just above and
        // to the right of the folder it belongs to, the way a folder unfolds next to its icon. A
        // centred panel covered the grid and gave no hint which folder it had come from.
        int[] spot = folderSpot(uid, width, panel.getMeasuredHeight());
        folderPopupWindow.showAtLocation(root, Gravity.NO_GRAVITY, spot[0], spot[1]);
        frostPanel(panel, 12f, 0.22f, null);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    /**
     * The screen rectangle of a folder tile, or null when it is not on screen — a folder that is
     * scrolled out of the grid has no view to measure, and the caller then falls back to a fixed
     * spot instead of trusting a stale rectangle.
     */
    private Rect folderTileRect(String uid) {
        Rect r = rectAt(adapter.positionOfFolder(uid));
        return r != null ? r : focusedTileRect();
    }

    /**
     * Top-left corner for the folder panel: up and to the right of the tile that opened it.
     *
     * <p>The panel is placed by hand because a folder can sit anywhere in the grid, so neither a
     * centred window nor a drop-down under the tile works for every case. Above is tried first
     * (the tile stays visible and the panel grows into open space); when the tile is near the top
     * of the grid the panel flips below it. Both axes are then clamped to the screen, so a folder
     * in the last column still leaves its panel fully on screen instead of half off the edge.
     */
    /** Top-left corner for the folder panel: up and to the right of the folder tile it came from. */
    private int[] folderSpot(String uid, int width, int height) {
        return spotNearTile(folderTileRect(uid), width, height, adapter.positionOfFolder(uid));
    }

    private void showFolderScrim() {
        if (folderScrim != null) return;
        // A 10% dim. The first byte of the colour is opacity, so larger = darker: 0x1A over black
        // means "ten percent black ink over Home", not "ten percent see-through". Home stays almost
        // untouched; the panel's own frost is what separates it from the desktop.
        folderScrim = new View(this);
        folderScrim.setBackgroundColor(0x1A000000);
        root.addView(folderScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Puts real frost inside a panel: the slice of Home behind it, blurred, drawn inside the panel's
     * rounded shape.
     *
     * <p>Android 9 cannot blur the content behind a window, so the panel carries its own copy of it.
     * The panel has to be showing and laid out first — its screen rectangle is what decides which
     * slice to copy — so callers show it with alpha 0 and pass the entrance animation as
     * {@code then}, which runs in the same frame the frost lands. Otherwise the first frame would
     * be a flat grey box snapping into glass.
     */
    private void frostPanel(final View panel, final float radiusDp, final Runnable then) {
        frostPanel(panel, radiusDp, 0f, then);
    }

    /**
     * Frosted background captured from the slice of Home under the panel. {@code tint} (0..1) lays a
     * uniform ink wash over the frost — left at 0 the panel is the bright, untinted glass used by
     * the control centre; a small value keeps white labels readable over a bright wallpaper.
     */
    private void frostPanel(final View panel, final float radiusDp, final float tint, final Runnable then) {
        final int[] attempts = {0};
        panel.post(new Runnable() {
            @Override
            public void run() {
                if (panel.getWidth() > 0 && panel.getHeight() > 0) {
                    int[] at = new int[2];
                    panel.getLocationOnScreen(at);
                    Rect where = new Rect(at[0], at[1], at[0] + panel.getWidth(), at[1] + panel.getHeight());
                    // The scrim is a child of root, so it would be baked into the capture and dim the
                    // glass a second time on top of the panel's own tint. Lift it for the one draw
                    // that makes the frost and put it straight back — the frame on screen never sees
                    // the lift.
                    float scrimAlpha = folderScrim != null ? folderScrim.getAlpha() : 1f;
                    if (folderScrim != null) folderScrim.setAlpha(0f);
                    // Heavier frost than the first pass (0.5 / r=2). The glass is now lifted above
                    // the desktop's brightness, so anything still legible inside it — an app icon,
                    // a rim — competes with the white labels for attention. At this weight the
                    // picture behind reads as colour and light only.
                    Bitmap shot = Frost.ofRegion(root, where, 0.34f, 4);
                    if (folderScrim != null) folderScrim.setAlpha(scrimAlpha);
                    if (shot != null) {
                        panel.setBackground(new FrostDrawable(shot, radiusDp * density, density, tint));
                    }
                    if (then != null) then.run();
                } else if (attempts[0]++ < 6) {
                    panel.postDelayed(this, 16);
                } else if (then != null) {
                    then.run();
                }
            }
        });
    }

    private void hideFolderScrim() {
        if (folderScrim == null) return;
        root.removeView(folderScrim);
        folderScrim = null;
    }

    /** Writes the tile order back, so it survives a restart and a theme's config.json. */
    private void saveLayout() {
        String json = Config.layoutToJson(adapter.layout());
        if (json == null) return;
        getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .edit().putString(Config.LAYOUT_PREFS_KEY, json).apply();
    }

    private PopupWindow glassPopup(View panel, int width) {
        return glassPopup(panel, width, null);
    }

    /**
     * A glass panel floating above the home screen. {@code onDismiss} runs after the bookkeeping,
     * whether the panel closed on Back, on a click outside, or because an app is starting.
     */
    private PopupWindow glassPopup(View panel, int width, Runnable onDismiss) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
            if (onDismiss != null) onDismiss.run();
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and slide down from under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        dropIn(popup, panel, anchor, gravity, true);
    }

    /**
     * Drop in: fade and slide down from under the anchor pill.
     *
     * <p>{@code frosted} decides whether the panel wears glass. Without it the panel is a bare
     * layer: no blurred copy of Home inside it, no slab, no rim — the controls sit straight on the
     * wallpaper and the only thing marking them as a layer is the drop itself.
     */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity, boolean frosted) {
        panel.setAlpha(0f);
        // A slide, not a squash. The old unfold scaled Y from 0.92, which on a wide panel read as the
        // panel stretching out of the pill; tvOS just brings the layer down and fades it in.
        panel.setTranslationY(-14 * density);
        if (!frosted) {
            // Clear the slab before it is shown, so not a single frame flashes it.
            panel.setBackground(null);
            panel.setElevation(0f);
        }
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        final Runnable enter = () -> panel.animate().alpha(1f).translationY(0f).setDuration(240)
                .setInterpolator(new SpringInterpolator()).start();
        if (frosted) {
            // Frost first, then the entrance -- so the first frame the eye sees is already glass.
            frostPanel(panel, 24f, enter);
        } else {
            enter.run();
        }
    }

    /**
     * Puts the D-pad on the first row of a popup that was just shown. Asking on the same pass
     * returns false — {@code showAsDropDown} has not attached the panel yet, so the row is not
     * shown and refuses focus — and the menu is then unusable from a remote. Asking once the panel
     * has been attached is what makes the rows reachable.
     */
    private void focusFirstRow(ViewGroup rows) {
        if (rows == null || rows.getChildCount() == 0) return;
        final View first = rows.getChildAt(0);
        first.postDelayed(first::requestFocus, 150);
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
        linkRows(rows);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /**
     * tvOS-style control centre: a framed 2 x 2 grid of glass cells, Wi-Fi first, hanging off the
     * top-right pill.
     */
    private void showSystemPanel() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_system, root, false);
        LinearLayout grid = panel.findViewById(R.id.grid);
        // A scrim, the same one the folder and the menus use. Without one the panel sat on the
        // wallpaper like another piece of Home, and the eye had no cue that a layer had opened.
        showFolderScrim();
        PopupWindow popup = glassPopup(panel, (int) (380 * density), this::hideFolderScrim);

        List<View> tiles = new ArrayList<>();
        addSystemTile(grid, tiles, R.drawable.ic_tv_wifi, getWifiSsid(), () ->
                open(systemIntent(Settings.ACTION_WIFI_SETTINGS)));
        addSystemTile(grid, tiles, R.drawable.ic_tv_gear, getString(R.string.settings), () ->
                open(systemIntent(Settings.ACTION_SETTINGS)));
        addSystemTile(grid, tiles, R.drawable.ic_tv_home, getString(R.string.launcher_settings), () ->
                startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        if (moduleCards) addSystemTile(grid, tiles, R.drawable.ic_tv_modules,
                getString(R.string.module_cards), this::showModuleCards);
        // Two columns, so left/right walk a row and up/down step between rows.
        linkTiles(tiles, 2);

        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(panel.getMeasuredWidth());
        // Bare, as asked: no frosted slab and no blurred ghost of Home inside it. The four cells
        // already carry their own ink, so they read as framed controls on their own; the panel is
        // only the thing that holds them, and it draws nothing.
        dropIn(popup, panel, anchor, Gravity.END, false);
        if (!tiles.isEmpty()) {
            final View first = tiles.get(0);
            first.postDelayed(first::requestFocus, 150);
        }
    }

    /** Appends one control-centre cell, starting a new two-up row every other one. */
    private void addSystemTile(LinearLayout grid, List<View> into, int iconRes, String title, Runnable action) {
        LinearLayout row = grid.getChildCount() == 0 ? null
                : (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
        if (row == null || row.getChildCount() >= 2) {
            row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        View tile = getLayoutInflater().inflate(R.layout.item_control_tile, row, false);
        tile.setId(View.generateViewId());
        ((ImageView) tile.findViewById(R.id.icon)).setImageResource(iconRes);
        TextView label = tile.findViewById(R.id.title);
        label.setText(title);
        label.setTextColor(0xFFFFFFFF);
        // On glass brighter than the desktop white type has nothing to stand against. A wide, soft
        // shadow only greys the background behind the label; what actually keeps the glyph edges
        // readable is a tight, near-opaque halo — close enough to read as an outline, so the white
        // never dissolves into the frost.
        label.setShadowLayer(4f * density, 0f, 1f * density, 0xE6000000);
        popupItem(tile, action);
        liftOnFocus(tile, 1.06f);
        row.addView(tile);
        into.add(tile);
    }

    /**
     * Wires a grid of tiles for the D-pad, two columns. Same reason {@link #linkRows} exists: the
     * focus search inside a popup gives up instead of walking to the next tile.
     */
    private void linkTiles(List<View> tiles, int columns) {
        int n = tiles.size();
        for (int i = 0; i < n; i++) {
            View t = tiles.get(i);
            boolean hasLeft = i % columns > 0;
            boolean hasRight = i % columns < columns - 1 && i + 1 < n;
            t.setNextFocusLeftId(hasLeft ? tiles.get(i - 1).getId() : t.getId());
            t.setNextFocusRightId(hasRight ? tiles.get(i + 1).getId() : t.getId());
            t.setNextFocusUpId(i >= columns ? tiles.get(i - columns).getId() : t.getId());
            t.setNextFocusDownId(i + columns < n ? tiles.get(i + columns).getId() : t.getId());
        }
    }

    /** Returns the current Wi-Fi SSID, or a fallback label. */
    private String getWifiSsid() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return getString(R.string.ethernet);
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                    String ssid = info != null ? info.getSSID() : null;
                    if (ssid != null) {
                        ssid = ssid.replace("\"", "").trim();
                        if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) return ssid;
                    }
                }
            }
        } catch (Exception ignored) {}
        return getString(R.string.wifi);
    }

    private void addSystemRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setId(View.generateViewId());
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
        linkRows(rows);
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
        linkRows(rows);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        frostPanel(panel, 24f, null);
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /**
     * Dropdown under the Settings pill (hold OK). The built-in modules come first: they are the
     * launcher's own pills, switched here because a module card in the spec's sense has to be
     * declared by another installed app. The cards other apps declared follow, each with its
     * package and signing key. OK toggles whichever row it is on.
     */
    private void showModuleCards() {
        // The built-in half needs no card subsystem, so a failed one still leaves the panel usable.
        if (CardFeature.working()) CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
        else showModuleCardsNow();
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));

        for (final String key : Modules.ALL) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(MODULE_TAG + key);
            ((TextView) row.findViewById(R.id.title)).setText(Modules.label(key));
            ((TextView) row.findViewById(R.id.app)).setText(Modules.caption(key));
            row.findViewById(R.id.pkg).setVisibility(View.GONE);
            row.findViewById(R.id.digest).setVisibility(View.GONE);
            row.setOnClickListener(v -> {
                if (!modules.remove(key)) modules.add(key);
                Modules.save(this, modules);
                applyModules();
                updateCardStates(rows);
            });
            rows.addView(row);
        }

        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        if (!listed.isEmpty()) addSeparator(rows);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setId(View.generateViewId());
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        linkRows(rows);
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        focusFirstRow(rows);
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list, built-in modules and cards alike. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            TextView state = row.findViewById(R.id.state);
            if (state == null) continue;   // the separator between the two groups
            Object tag = row.getTag();
            if (tag instanceof String && ((String) tag).startsWith(MODULE_TAG)) {
                String key = ((String) tag).substring(MODULE_TAG.length());
                state.setText(modules.contains(key) ? R.string.module_state_on : R.string.module_state_off);
                continue;
            }
            Cards.Card card = tag instanceof String ? cardFor((String) tag) : null;
            state.setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
