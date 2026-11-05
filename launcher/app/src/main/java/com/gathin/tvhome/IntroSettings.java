package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Encapsulates settings, precedence, validation, and duration clamping for the Boot & Wake Intro.
 *
 * Precedence rule:
 *   User preference (boot_intro_user_prefs) > Theme config.json > config.xml default.
 * A setting never touched by the user keeps following the theme/default.
 *
 * Total duration cap:
 *   Entrance + Hold + Reveal + Dock is strictly clamped to at most 5000ms.
 */
public final class IntroSettings {
    public static final String PREFS_NAME = "boot_intro_user_prefs";

    public static final String KEY_BOOT_INTRO = "boot_intro";
    public static final String KEY_INTRO_ON_WAKE = "intro_on_wake";
    public static final String KEY_WAKE_GAP_MIN = "intro_wake_min_gap_minutes";
    public static final String KEY_HOLD_MS = "boot_intro_hold_ms";
    public static final String KEY_SPEED = "boot_intro_speed";
    public static final String KEY_ANIMATION = "boot_intro_animation";
    public static final String KEY_MESSAGES = "boot_intro_messages";

    public static final int[] WAKE_GAPS = {1, 2, 5, 10, 15, 30, 60};
    public static final int[] HOLD_TIMES_MS = {500, 1000, 1500, 2000, 2500, 3000, 3500, 4000};
    public static final String[] SPEEDS = {"slow", "normal", "fast"};
    public static final String[] ANIMATIONS = {"random", "pop", "fade", "slide", "letters", "shimmer"};
    public static final String[] MESSAGES_MODES = {"titles_and_tips", "titles_only", "off"};

    public static final int MAX_TOTAL_DURATION_MS = 5000;
    public static final int BASE_ENTRANCE_MS = 550;
    public static final int BASE_REVEAL_MS = 600;
    public static final int BASE_DOCK_MS = 350;

    public static final String SPEED_SLOW = "slow";
    public static final String SPEED_NORMAL = "normal";
    public static final String SPEED_FAST = "fast";

    public static final String MESSAGES_ALL = "titles_and_tips";
    public static final String MESSAGES_TITLES_ONLY = "titles_only";
    public static final String MESSAGES_OFF = "off";

    public final boolean bootIntro;
    public final boolean introOnWake;
    public final int wakeGapMinutes;
    public final int holdMs;
    public final String speed;
    public final String animation;
    public final String messagesMode;

    public final Durations durations;

    public static final class Durations {
        public final int holdMs;
        public final int entranceMs;
        public final int revealMs;
        public final int dockMs;
        public final int totalMs;

        public Durations(int holdMs, int entranceMs, int revealMs, int dockMs) {
            this.holdMs = holdMs;
            this.entranceMs = entranceMs;
            this.revealMs = revealMs;
            this.dockMs = dockMs;
            this.totalMs = holdMs + entranceMs + revealMs + dockMs;
        }
    }

    public IntroSettings(boolean bootIntro, boolean introOnWake, int wakeGapMinutes,
                         int holdMs, String speed, String animation, String messagesMode,
                         Durations durations) {
        this.bootIntro = bootIntro;
        this.introOnWake = introOnWake;
        this.wakeGapMinutes = wakeGapMinutes;
        this.holdMs = holdMs;
        this.speed = speed;
        this.animation = animation;
        this.messagesMode = messagesMode;
        this.durations = durations;
    }

    /**
     * Computes the animation durations scaled by speed, and clamps holdMs so total duration
     * never exceeds 5000ms.
     */
    public static Durations computeDurations(int requestedHoldMs, String speed) {
        float multiplier = 1.0f;
        if (SPEED_SLOW.equalsIgnoreCase(speed)) {
            multiplier = 1.4f;
        } else if (SPEED_FAST.equalsIgnoreCase(speed)) {
            multiplier = 0.7f;
        }

        int entranceMs = Math.round(BASE_ENTRANCE_MS * multiplier);
        int revealMs = Math.round(BASE_REVEAL_MS * multiplier);
        int dockMs = Math.round(BASE_DOCK_MS * multiplier);
        int animTotal = entranceMs + revealMs + dockMs;

        // If animations alone somehow exceed 5000ms, clamp them defensively
        if (animTotal > MAX_TOTAL_DURATION_MS) {
            float scale = (float) MAX_TOTAL_DURATION_MS / animTotal;
            entranceMs = Math.round(entranceMs * scale);
            revealMs = Math.round(revealMs * scale);
            dockMs = Math.round(dockMs * scale);
            animTotal = entranceMs + revealMs + dockMs;
        }

        int maxAllowedHold = Math.max(0, MAX_TOTAL_DURATION_MS - animTotal);
        int effectiveHold = Math.min(requestedHoldMs, maxAllowedHold);
        if (effectiveHold < 0) effectiveHold = 0;

        return new Durations(effectiveHold, entranceMs, revealMs, dockMs);
    }

    /**
     * Resolves settings using: User Setting > Theme config.json > Default config.xml.
     * Pure Java implementation for 100% testability.
     */
    public static IntroSettings resolve(
            Boolean userBootIntro, Boolean userIntroOnWake, Integer userWakeGap, Integer userHoldMs,
            String userSpeed, String userAnimation, String userMessages,
            Boolean themeBootIntro, Boolean themeIntroOnWake, Integer themeWakeGap, Integer themeHoldMs,
            String themeSpeed, String themeAnimation,
            boolean defaultBootIntro, boolean defaultIntroOnWake, int defaultWakeGap, int defaultHoldMs,
            String defaultSpeed, String defaultAnimation
    ) {
        // 1. boot_intro
        boolean bootIntro;
        if (userBootIntro != null) {
            bootIntro = userBootIntro;
        } else if (themeBootIntro != null) {
            bootIntro = themeBootIntro;
        } else {
            bootIntro = defaultBootIntro;
        }

        // 2. intro_on_wake
        boolean introOnWake;
        if (userIntroOnWake != null) {
            introOnWake = userIntroOnWake;
        } else if (themeIntroOnWake != null) {
            introOnWake = themeIntroOnWake;
        } else {
            introOnWake = defaultIntroOnWake;
        }

        // 3. intro_wake_min_gap_minutes
        int wakeGap;
        if (userWakeGap != null) {
            wakeGap = isValidWakeGap(userWakeGap) ? userWakeGap : (themeWakeGap != null ? themeWakeGap : defaultWakeGap);
        } else if (themeWakeGap != null) {
            wakeGap = isValidWakeGap(themeWakeGap) ? themeWakeGap : defaultWakeGap;
        } else {
            wakeGap = defaultWakeGap;
        }
        if (wakeGap <= 0) wakeGap = 5;

        // 4. boot_intro_hold_ms
        int holdMs;
        if (userHoldMs != null) {
            holdMs = isValidHoldMs(userHoldMs) ? userHoldMs : (themeHoldMs != null ? themeHoldMs : defaultHoldMs);
        } else if (themeHoldMs != null) {
            holdMs = isValidHoldMs(themeHoldMs) ? themeHoldMs : defaultHoldMs;
        } else {
            holdMs = defaultHoldMs;
        }
        if (holdMs < 500 || holdMs > 4000) holdMs = 2000;

        // 5. boot_intro_speed
        String speed;
        if (userSpeed != null) {
            speed = isValidSpeed(userSpeed) ? userSpeed.toLowerCase(Locale.ROOT) : (themeSpeed != null ? themeSpeed : defaultSpeed);
        } else if (themeSpeed != null) {
            speed = isValidSpeed(themeSpeed) ? themeSpeed.toLowerCase(Locale.ROOT) : defaultSpeed;
        } else {
            speed = defaultSpeed;
        }
        if (!isValidSpeed(speed)) speed = SPEED_NORMAL;

        // 6. boot_intro_animation
        String animation;
        if (userAnimation != null) {
            animation = isValidAnimation(userAnimation) ? userAnimation.toLowerCase(Locale.ROOT) : (themeAnimation != null ? themeAnimation : defaultAnimation);
        } else if (themeAnimation != null) {
            animation = isValidAnimation(themeAnimation) ? themeAnimation.toLowerCase(Locale.ROOT) : defaultAnimation;
        } else {
            animation = defaultAnimation;
        }
        if (!isValidAnimation(animation)) animation = "pop";

        // 7. boot_intro_messages
        String messagesMode;
        if (userMessages != null) {
            messagesMode = isValidMessagesMode(userMessages) ? userMessages.toLowerCase(Locale.ROOT) : MESSAGES_ALL;
        } else {
            messagesMode = MESSAGES_ALL;
        }

        Durations durations = computeDurations(holdMs, speed);

        return new IntroSettings(bootIntro, introOnWake, wakeGap, holdMs, speed, animation, messagesMode, durations);
    }

    public static boolean isValidWakeGap(int minutes) {
        for (int gap : WAKE_GAPS) {
            if (gap == minutes) return true;
        }
        return false;
    }

    public static boolean isValidHoldMs(int ms) {
        return ms >= 500 && ms <= 4000;
    }

    public static boolean isValidSpeed(String speed) {
        if (speed == null) return false;
        String s = speed.trim().toLowerCase(Locale.ROOT);
        return SPEED_SLOW.equals(s) || SPEED_NORMAL.equals(s) || SPEED_FAST.equals(s);
    }

    public static boolean isValidAnimation(String anim) {
        if (anim == null) return false;
        String a = anim.trim().toLowerCase(Locale.ROOT);
        for (String valid : ANIMATIONS) {
            if (valid.equals(a)) return true;
        }
        return false;
    }

    public static boolean isValidMessagesMode(String mode) {
        if (mode == null) return false;
        String m = mode.trim().toLowerCase(Locale.ROOT);
        return MESSAGES_ALL.equals(m) || MESSAGES_TITLES_ONLY.equals(m) || MESSAGES_OFF.equals(m);
    }

    /**
     * Loads effective IntroSettings given an Android Context and Config.
     */
    public static IntroSettings load(Context context, Config cfg) {
        SharedPreferences userPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        Boolean userBootIntro = userPrefs.contains(KEY_BOOT_INTRO) ? userPrefs.getBoolean(KEY_BOOT_INTRO, true) : null;
        Boolean userIntroOnWake = userPrefs.contains(KEY_INTRO_ON_WAKE) ? userPrefs.getBoolean(KEY_INTRO_ON_WAKE, true) : null;
        Integer userWakeGap = userPrefs.contains(KEY_WAKE_GAP_MIN) ? userPrefs.getInt(KEY_WAKE_GAP_MIN, 5) : null;
        Integer userHoldMs = userPrefs.contains(KEY_HOLD_MS) ? userPrefs.getInt(KEY_HOLD_MS, 2000) : null;
        String userSpeed = userPrefs.contains(KEY_SPEED) ? userPrefs.getString(KEY_SPEED, SPEED_NORMAL) : null;
        String userAnimation = userPrefs.contains(KEY_ANIMATION) ? userPrefs.getString(KEY_ANIMATION, "pop") : null;
        String userMessages = userPrefs.contains(KEY_MESSAGES) ? userPrefs.getString(KEY_MESSAGES, MESSAGES_ALL) : null;

        return resolve(
                userBootIntro, userIntroOnWake, userWakeGap, userHoldMs, userSpeed, userAnimation, userMessages,
                null, null, null, null, null, null, // Theme is already merged into cfg
                cfg.bootIntro, cfg.introOnWake, cfg.introWakeMinGapMinutes, cfg.bootIntroHoldMs,
                cfg.bootIntroSpeed, cfg.bootIntroAnimation
        );
    }

    /** Clears all user-defined choices in SharedPreferences, reverting to defaults. */
    public static void resetToDefaults(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }
}

