package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.animation.PathInterpolator;

import java.util.Locale;

/**
 * User-configurable speed, easing curve, card morph, background depth, corner style, and reveal
 * behavior for the iOS-style app opening animation. Saved in {@link Config#SETTINGS_NAME} so
 * Launcher Settings and "Follow theme" manage it alongside other launcher preferences.
 */
public final class AppLaunchSettings {

    public static final String KEY_ENABLED = "app_launch_anim_enabled";
    public static final String KEY_SPEED = "app_launch_speed";
    public static final String KEY_EASING = "app_launch_easing";
    public static final String KEY_CARD_MORPH = "app_launch_card_morph";
    public static final String KEY_BG_DEPTH = "app_launch_bg_depth";
    public static final String KEY_ROUNDED_CORNERS = "app_launch_rounded_corners";
    public static final String KEY_APP_REVEAL = "app_launch_app_reveal";

    public static final String SPEED_VERY_FAST = "very_fast";
    public static final String SPEED_FAST = "fast";
    public static final String SPEED_NORMAL = "normal";
    public static final String SPEED_SLOW = "slow";
    public static final String SPEED_CINEMATIC = "cinematic";
    public static final String[] SPEEDS = {
            SPEED_VERY_FAST, SPEED_FAST, SPEED_NORMAL, SPEED_SLOW, SPEED_CINEMATIC
    };

    public static final String EASING_IOS_IN_OUT = "ios_in_out";
    public static final String EASING_STRONG_IN = "strong_in";
    public static final String EASING_BALANCED_IN_OUT = "balanced_in_out";
    public static final String EASING_EASE_OUT = "ease_out";
    public static final String EASING_LINEAR = "linear";
    public static final String[] EASINGS = {
            EASING_IOS_IN_OUT, EASING_STRONG_IN, EASING_BALANCED_IN_OUT, EASING_EASE_OUT, EASING_LINEAR
    };

    public static final String DEPTH_OFF = "off";
    public static final String DEPTH_SUBTLE = "subtle";
    public static final String DEPTH_MEDIUM = "medium";
    public static final String DEPTH_DEEP = "deep";
    public static final String[] BG_DEPTHS = {
            DEPTH_OFF, DEPTH_SUBTLE, DEPTH_MEDIUM, DEPTH_DEEP
    };

    public static final String REVEAL_ZOOM_FADE = "zoom_fade";
    public static final String REVEAL_FADE = "fade";
    public static final String REVEAL_INSTANT = "instant";
    public static final String[] APP_REVEALS = {
            REVEAL_ZOOM_FADE, REVEAL_FADE, REVEAL_INSTANT
    };

    public final boolean enabled;
    public final String speed;
    public final String easing;
    public final boolean cardMorph;
    public final String bgDepth;
    public final boolean roundedCorners;
    public final String appReveal;

    private AppLaunchSettings(boolean enabled, String speed, String easing, boolean cardMorph,
                              String bgDepth, boolean roundedCorners, String appReveal) {
        this.enabled = enabled;
        this.speed = normalize(SPEEDS, speed, SPEED_NORMAL);
        this.easing = normalize(EASINGS, easing, EASING_IOS_IN_OUT);
        this.cardMorph = cardMorph;
        this.bgDepth = normalize(BG_DEPTHS, bgDepth, DEPTH_MEDIUM);
        this.roundedCorners = roundedCorners;
        this.appReveal = normalize(APP_REVEALS, appReveal, REVEAL_ZOOM_FADE);
    }

    public static AppLaunchSettings defaults() {
        return new AppLaunchSettings(true, SPEED_NORMAL, EASING_IOS_IN_OUT, true,
                DEPTH_MEDIUM, true, REVEAL_ZOOM_FADE);
    }

    public static AppLaunchSettings fromValues(boolean enabled, String speed, String easing,
                                             boolean cardMorph, String bgDepth,
                                             boolean roundedCorners, String appReveal) {
        return new AppLaunchSettings(enabled, speed, easing, cardMorph, bgDepth,
                roundedCorners, appReveal);
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    public static AppLaunchSettings load(Context ctx) {
        if (ctx == null) return defaults();
        SharedPreferences p = prefs(ctx);
        return fromValues(
                p.getBoolean(KEY_ENABLED, true),
                p.getString(KEY_SPEED, SPEED_NORMAL),
                p.getString(KEY_EASING, EASING_IOS_IN_OUT),
                p.getBoolean(KEY_CARD_MORPH, true),
                p.getString(KEY_BG_DEPTH, DEPTH_MEDIUM),
                p.getBoolean(KEY_ROUNDED_CORNERS, true),
                p.getString(KEY_APP_REVEAL, REVEAL_ZOOM_FADE));
    }

    /** Duration (ms) of the tile-to-fullscreen launch expansion for {@link #speed}. */
    public long sweepDurationMs() {
        switch (speed) {
            case SPEED_VERY_FAST:
                return 240L;
            case SPEED_FAST:
                return 320L;
            case SPEED_SLOW:
                return 540L;
            case SPEED_CINEMATIC:
                return 700L;
            case SPEED_NORMAL:
            default:
                return LauncherMotion.CURTAIN_SWEEP_MS;
        }
    }

    /** Duration (ms) used for previewing the opened-app reveal phase. */
    public long revealDurationMs() {
        if (REVEAL_INSTANT.equals(appReveal)) return 0L;
        if (REVEAL_FADE.equals(appReveal)) return 260L;
        switch (speed) {
            case SPEED_VERY_FAST:
            case SPEED_FAST:
                return 190L;
            case SPEED_SLOW:
            case SPEED_CINEMATIC:
                return 420L;
            case SPEED_NORMAL:
            default:
                return 300L;
        }
    }

    /** Cubic Bezier control points {@code {x1, y1, x2, y2}} for {@link #easing}. */
    public float[] curveControlPoints() {
        switch (easing) {
            case EASING_STRONG_IN:
                return new float[] {0.58f, 0.0f, 0.20f, 1.0f};
            case EASING_BALANCED_IN_OUT:
                return new float[] {0.45f, 0.0f, 0.55f, 1.0f};
            case EASING_EASE_OUT:
                return new float[] {0.18f, 0.0f, 0.06f, 1.0f};
            case EASING_LINEAR:
                return new float[] {0.0f, 0.0f, 1.0f, 1.0f};
            case EASING_IOS_IN_OUT:
            default:
                return new float[] {0.42f, 0.0f, 0.22f, 1.0f};
        }
    }

    /** Builds the {@link PathInterpolator} matching {@link #curveControlPoints()}. */
    public PathInterpolator createInterpolator() {
        float[] c = curveControlPoints();
        return new PathInterpolator(c[0], c[1], c[2], c[3]);
    }

    /** Inward background scale shrinkage (e.g. {@code 0.055f} shrinks Home to {@code 0.945f}). */
    public float bgScaleDelta() {
        switch (bgDepth) {
            case DEPTH_OFF:
                return 0f;
            case DEPTH_SUBTLE:
                return 0.035f;
            case DEPTH_DEEP:
                return 0.090f;
            case DEPTH_MEDIUM:
            default:
                return 0.055f;
        }
    }

    /** Opacity drop fraction applied to receding Home elements (0 = no dim, 0.85 = deep dim). */
    public float bgDimFactor() {
        switch (bgDepth) {
            case DEPTH_OFF:
                return 0f;
            case DEPTH_SUBTLE:
                return 0.50f;
            case DEPTH_DEEP:
                return 0.85f;
            case DEPTH_MEDIUM:
            default:
                return 0.72f;
        }
    }

    /** True when the Home desktop recedes or dims behind the expanding launch card. */
    public boolean hasBackgroundDepth() {
        return bgScaleDelta() > 0f || bgDimFactor() > 0f;
    }

    /** Animation resource id for incoming launched app window, or {@code 0} for instant reveal. */
    public int enterAnimResId() {
        if (REVEAL_INSTANT.equals(appReveal)) return 0;
        if (REVEAL_FADE.equals(appReveal)) return R.anim.launch_after_curtain_enter_fade;
        switch (speed) {
            case SPEED_VERY_FAST:
            case SPEED_FAST:
                return R.anim.launch_after_curtain_enter_fast;
            case SPEED_SLOW:
            case SPEED_CINEMATIC:
                return R.anim.launch_after_curtain_enter_slow;
            case SPEED_NORMAL:
            default:
                return R.anim.launch_after_curtain_enter;
        }
    }

    /** Animation resource id holding the Home curtain steady while the app window enters. */
    public int exitAnimResId() {
        if (REVEAL_INSTANT.equals(appReveal)) return 0;
        if (REVEAL_FADE.equals(appReveal)) return R.anim.launch_after_curtain_exit_fade;
        switch (speed) {
            case SPEED_VERY_FAST:
            case SPEED_FAST:
                return R.anim.launch_after_curtain_exit_fast;
            case SPEED_SLOW:
            case SPEED_CINEMATIC:
                return R.anim.launch_after_curtain_exit_slow;
            case SPEED_NORMAL:
            default:
                return R.anim.launch_after_curtain_exit;
        }
    }

    /** Steps cyclically through {@code options} by {@code delta} (+1 or -1). */
    public static String stepOption(String[] options, String current, int delta) {
        if (options == null || options.length == 0) return current;
        int idx = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equalsIgnoreCase(current)) {
                idx = i;
                break;
            }
        }
        int next = (idx + delta) % options.length;
        if (next < 0) next += options.length;
        return options[next];
    }

    public static boolean hasCustomChoices(SharedPreferences choices) {
        if (choices == null) return false;
        return choices.contains(KEY_ENABLED)
                || choices.contains(KEY_SPEED)
                || choices.contains(KEY_EASING)
                || choices.contains(KEY_CARD_MORPH)
                || choices.contains(KEY_BG_DEPTH)
                || choices.contains(KEY_ROUNDED_CORNERS)
                || choices.contains(KEY_APP_REVEAL);
    }

    public static void clearKeys(SharedPreferences.Editor editor) {
        if (editor == null) return;
        editor.remove(KEY_ENABLED)
                .remove(KEY_SPEED)
                .remove(KEY_EASING)
                .remove(KEY_CARD_MORPH)
                .remove(KEY_BG_DEPTH)
                .remove(KEY_ROUNDED_CORNERS)
                .remove(KEY_APP_REVEAL);
    }

    public static void resetToDefaults(Context ctx) {
        if (ctx == null) return;
        SharedPreferences p = prefs(ctx);
        SharedPreferences.Editor editor = p.edit();
        clearKeys(editor);
        editor.putLong(Config.SETTINGS_REVISION_KEY,
                p.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L);
        editor.apply();
    }

    public static void bumpSettingsRevision(Context ctx) {
        if (ctx == null) return;
        SharedPreferences p = prefs(ctx);
        p.edit().putLong(Config.SETTINGS_REVISION_KEY,
                p.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L).apply();
    }

    private static String normalize(String[] valid, String raw, String fallback) {
        if (raw == null) return fallback;
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        for (String v : valid) {
            if (v.equals(clean)) return v;
        }
        return fallback;
    }
}

