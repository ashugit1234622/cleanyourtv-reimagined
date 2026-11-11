package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    private static final int[] SHOWCASE_SECONDS = {0, 30, 60, 120};
    private final TextView[] showcaseChoices = new TextView[SHOWCASE_SECONDS.length];
    private TextView wallpaperChoice;
    private TextView wallpaperRotationChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView appLaunchChoice;
    private TextView introSettingsChoice;
    private TextView recentAppsHomeChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private int idleShowcaseSeconds;
    private String clockFormat;
    private boolean animations;
    private boolean advancedEffects;
    private boolean reducedMotion;
    private boolean wallpaperRotation;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        idleShowcaseSeconds = config.idleShowcaseSeconds;
        clockFormat = config.clockFormat;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        reducedMotion = config.reducedMotion;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] clockIds = {R.id.clockSystem, R.id.clock12, R.id.clock24};
        for (int i = 0; i < clockIds.length; i++) {
            final String value = CLOCK_FORMATS[i];
            clockChoices[i] = findViewById(clockIds[i]);
            clockChoices[i].setOnClickListener(v -> {
                if (value.equals(clockFormat)) return;
                clockFormat = value;
                choices.edit().putString(Config.CLOCK_FORMAT_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] showcaseIds = {R.id.showcaseOff, R.id.showcase30s, R.id.showcase1m, R.id.showcase2m};
        for (int i = 0; i < showcaseIds.length; i++) {
            final int value = SHOWCASE_SECONDS[i];
            showcaseChoices[i] = findViewById(showcaseIds[i]);
            showcaseChoices[i].setOnClickListener(v -> {
                if (idleShowcaseSeconds == value) return;
                idleShowcaseSeconds = value;
                choices.edit().putInt(Config.IDLE_SHOWCASE_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
            reverseRow(clockChoices);
            reverseRow(showcaseChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        wallpaperRotationChoice = findViewById(R.id.wallpaperRotationChoice);
        wallpaperRotation = choices.contains(Config.WALLPAPER_ROTATION_KEY)
                ? choices.getBoolean(Config.WALLPAPER_ROTATION_KEY, false)
                : config.wallpaperRotationMinutes > 0;
        wallpaperRotationChoice.setOnClickListener(v -> {
            wallpaperRotation = !wallpaperRotation;
            choices.edit().putBoolean(Config.WALLPAPER_ROTATION_KEY, wallpaperRotation).apply();
            changed();
            refresh();
        });
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
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
        appLaunchChoice = findViewById(R.id.appLaunchChoice);
        appLaunchChoice.setOnClickListener(v -> open(new Intent(this, AppLaunchSettingsActivity.class)));
        appLaunchChoice.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if ((keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    && animations && !reducedMotion) {
                AppLaunchSettings current = AppLaunchSettings.load(this);
                int delta = keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                String nextSpeed = AppLaunchSettings.stepOption(
                        AppLaunchSettings.SPEEDS, current.speed, delta);
                choices.edit()
                        .putBoolean(AppLaunchSettings.KEY_ENABLED, true)
                        .putString(AppLaunchSettings.KEY_SPEED, nextSpeed)
                        .apply();
                v.playSoundEffect(android.view.SoundEffectConstants.CLICK);
                changed();
                refresh();
                return true;
            }
            return false;
        });
        introSettingsChoice = findViewById(R.id.introSettingsChoice);
        introSettingsChoice.setOnClickListener(v -> open(new Intent(this, IntroSettingsActivity.class)));
        introSettingsChoice.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                Config c = Config.load(this);
                IntroSettings s = IntroSettings.load(this, c);
                boolean newVal = !s.bootIntro;
                getSharedPreferences(IntroSettings.PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(IntroSettings.KEY_BOOT_INTRO, newVal).apply();
                v.playSoundEffect(android.view.SoundEffectConstants.CLICK);
                changed();
                refresh();
                return true;
            }
            return false;
        });

        recentAppsHomeChoice = findViewById(R.id.recentAppsHomeChoice);
        recentAppsHomeChoice.setOnClickListener(v -> toggleRecentAppsHome());
        recentAppsHomeChoice.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                toggleRecentAppsHome();
                v.playSoundEffect(android.view.SoundEffectConstants.CLICK);
                return true;
            }
            return false;
        });

        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            android.content.SharedPreferences.Editor resetEditor = choices.edit()
                    .remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.WALLPAPER_ROTATION_KEY).remove(Config.IDLE_SHOWCASE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY).remove(OpenOnStart.KEY);
            AppLaunchSettings.clearKeys(resetEditor);
            RecentsPreferences.clearChoice(resetEditor);
            resetEditor.commit();
            getSharedPreferences(IntroSettings.PREFS_NAME, MODE_PRIVATE).edit().clear().apply();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            idleShowcaseSeconds = current.idleShowcaseSeconds;
            clockFormat = current.clockFormat;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            wallpaperRotation = choices.contains(Config.WALLPAPER_ROTATION_KEY)
                    ? choices.getBoolean(Config.WALLPAPER_ROTATION_KEY, false)
                    : current.wallpaperRotationMinutes > 0;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v ->
                open(new Intent(this, HomeSettingsActivity.class)));
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        findViewById(R.id.phoneBridgeChoice).setOnClickListener(v ->
                open(new Intent(this, com.gathin.tvhome.bridge.BridgeSettingsActivity.class)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").commit();
                        new File(getFilesDir(), Config.WALLPAPER_NAME).delete();
                    } else {
                        File picked = images.get(which);
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                picked.getName()).commit();
                        copyToInternal(picked, new File(getFilesDir(), Config.WALLPAPER_NAME));
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).commit();
                    new File(getFilesDir(), Config.WALLPAPER_NAME).delete();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void copyToInternal(File src, File dst) {
        new Thread(() -> {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (Exception ignored) {
            }
        }, "CopyWallpaper").start();
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        for (int i = 0; i < clockChoices.length; i++) {
            clockChoices[i].setSelected(CLOCK_FORMATS[i].equals(clockFormat));
        }
        for (int i = 0; i < showcaseChoices.length; i++) {
            showcaseChoices[i].setSelected(idleShowcaseSeconds == SHOWCASE_SECONDS[i]);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        AppLaunchSettings launchCfg = AppLaunchSettings.load(this);
        appLaunchChoice.setText(getString(R.string.launch_anim_settings_state,
                formatAppLaunchSummary(launchCfg, animations, reducedMotion)));
        Config currentConfig = Config.load(this);
        IntroSettings intro = IntroSettings.load(this, currentConfig);
        introSettingsChoice.setText(getString(R.string.intro_settings_state,
                getString(intro.bootIntro ? R.string.on : R.string.off)));
        boolean recentsHome = RecentsPreferences.isEnabled(this);
        recentAppsHomeChoice.setText(getString(R.string.recents_home_setting_state,
                getString(recentsHome ? R.string.on : R.string.off)));
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        wallpaperRotationChoice.setText(getString(R.string.wallpaper_rotation_state,
                getString(wallpaperRotation ? R.string.on : R.string.off)));
        openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.WALLPAPER_ROTATION_KEY)
                || choices.contains(Config.IDLE_SHOWCASE_KEY)
                || choices.contains(Config.DOCK_OPACITY_KEY) || choices.contains(Config.CLOCK_FORMAT_KEY)
                || choices.contains(OpenOnStart.KEY)
                || AppLaunchSettings.hasCustomChoices(choices)
                || RecentsPreferences.hasCustomChoice(choices)
                || !getSharedPreferences(IntroSettings.PREFS_NAME, MODE_PRIVATE).getAll().isEmpty();
    }

    private void toggleRecentAppsHome() {
        boolean next = !RecentsPreferences.isEnabled(this);
        RecentsPreferences.setEnabled(this, next);
        changed();
        refresh();
    }

    private String formatAppLaunchSummary(AppLaunchSettings s, boolean animationsOn, boolean reduced) {
        if (!animationsOn) return getString(R.string.launch_anim_summary_global_off);
        if (reduced) return getString(R.string.launch_anim_summary_reduced);
        if (!s.enabled) return getString(R.string.off);
        return getString(R.string.launch_anim_summary,
                speedShortLabel(s.speed), easingSummaryLabel(s.easing));
    }

    private String speedShortLabel(String speed) {
        switch (speed) {
            case AppLaunchSettings.SPEED_VERY_FAST:
                return getString(R.string.launch_anim_speed_very_fast_short);
            case AppLaunchSettings.SPEED_FAST:
                return getString(R.string.launch_anim_speed_fast_short);
            case AppLaunchSettings.SPEED_SLOW:
                return getString(R.string.launch_anim_speed_slow_short);
            case AppLaunchSettings.SPEED_CINEMATIC:
                return getString(R.string.launch_anim_speed_cinematic_short);
            case AppLaunchSettings.SPEED_NORMAL:
            default:
                return getString(R.string.launch_anim_speed_normal_short);
        }
    }

    private String easingSummaryLabel(String easing) {
        switch (easing) {
            case AppLaunchSettings.EASING_STRONG_IN:
                return getString(R.string.launch_anim_easing_strong_in);
            case AppLaunchSettings.EASING_BALANCED_IN_OUT:
                return getString(R.string.launch_anim_easing_balanced_in_out);
            case AppLaunchSettings.EASING_EASE_OUT:
                return getString(R.string.launch_anim_easing_ease_out);
            case AppLaunchSettings.EASING_LINEAR:
                return getString(R.string.launch_anim_easing_linear);
            case AppLaunchSettings.EASING_IOS_IN_OUT:
            default:
                return getString(R.string.launch_anim_easing_ios_in_out);
        }
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
            LauncherMotion.applySubScreenOpenTransition(this, animations, reducedMotion);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void finish() {
        super.finish();
        LauncherMotion.applySubScreenCloseTransition(this, animations, reducedMotion);
    }
}
