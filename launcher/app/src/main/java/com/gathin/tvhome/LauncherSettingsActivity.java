package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;
import android.widget.Toast;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private static final int WALLPAPER_REQUEST = 51;

    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").commit();
            Config current = Config.load(this);
            columns = current.columns;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.wallpaperChoice).setOnClickListener(v ->
                startActivityForResult(new Intent(this, WallpaperPickerActivity.class),
                        WALLPAPER_REQUEST));
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        for (int id : new int[]{R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7,
                R.id.animationsChoice, R.id.advancedChoice, R.id.followThemeChoice,
                R.id.wallpaperChoice, R.id.defaultHomeChoice, R.id.tvSettingsChoice}) {
            liftOnFocus(findViewById(id));
        }
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    /**
     * The same focus weight the tiles have, at row scale: rows grow a hair on a spring and settle
     * back on a plain curve. On a list this is what tells the eye which row the D-pad is on — the
     * old page only changed the row's fill, which is easy to miss on a 54dp bar.
     */
    private void liftOnFocus(final android.view.View row) {
        final android.view.animation.Interpolator spring = new SpringInterpolator();
        final android.view.animation.Interpolator settle =
                new android.view.animation.DecelerateInterpolator(1.8f);
        row.setOnFocusChangeListener((view, focused) -> {
            view.animate().cancel();
            view.animate().scaleX(focused ? 1.02f : 1f).scaleY(focused ? 1.02f : 1f)
                    .setDuration(focused ? 220 : 150)
                    .setInterpolator(focused ? spring : settle)
                    .start();
        });
    }

    /**
     * The wallpaper picker writes the file itself, so the only thing left is to let MainActivity know
     * it has to recreate: it reads the wallpaper once, in onCreate.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WALLPAPER_REQUEST && resultCode == RESULT_OK) setResult(RESULT_OK);
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        animationsChoice.setText(stateText(R.string.animations_state, animations));
        advancedChoice.setText(stateText(R.string.advanced_effects_state, advancedEffects));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    /**
     * "Animations: On" with the value dimmed. tvOS keeps the label and the value on one row but
     * never at the same weight; the split is what makes a row scan as a setting instead of a
     * sentence. The colon is looked up rather than assumed, because the two locales use different
     * ones (":" and "：").
     */
    private CharSequence stateText(int stringRes, boolean on) {
        String text = getString(stringRes, getString(on ? R.string.on : R.string.off));
        int cut = Math.max(text.lastIndexOf('：'), text.lastIndexOf(':'));
        if (cut <= 0) return text;
        SpannableString value = new SpannableString(text);
        value.setSpan(new ForegroundColorSpan(0x99FFFFFF), cut, text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return value;
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("animations")
                || choices.contains("advanced_effects");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
