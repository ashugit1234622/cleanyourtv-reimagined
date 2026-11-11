package com.gathin.tvhome;

import android.view.KeyEvent;

/**
 * Pure policy rules for Idle Wallpaper Showcase Mode: timeout clamping, trigger eligibility,
 * remote key classification, and colour alpha masking. Kept Android-framework-light so JVM
 * unit tests can exercise every rule directly.
 */
final class IdleShowcasePolicy {

    static final int MAX_TIMEOUT_SECONDS = 3600;

    private IdleShowcasePolicy() {}

    /** Clamps configured idle showcase timeout to [0, MAX_TIMEOUT_SECONDS] and converts to ms. */
    static long delayMs(int seconds) {
        if (seconds <= 0) return 0L;
        return Math.min(seconds, MAX_TIMEOUT_SECONDS) * 1000L;
    }

    /** True only when Home is foreground, enabled (> 0s), and no modal/intro/reorder overlay is active. */
    static boolean canSchedule(boolean resumed, int idleSeconds, boolean introPlaying,
                               boolean reordering, boolean hasOpenPopup,
                               boolean hasAppLibrary, boolean hasCardPrompt) {
        return resumed
                && idleSeconds > 0
                && !introPlaying
                && !reordering
                && !hasOpenPopup
                && !hasAppLibrary
                && !hasCardPrompt;
    }

    /**
     * Volume, mute, and power keys belong to TV hardware/OSD and must pass through without
     * resetting the showcase timer or waking Showcase Mode.
     */
    static boolean shouldIgnoreForIdleAndWake(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_TV_POWER:
            case KeyEvent.KEYCODE_STB_POWER:
            case KeyEvent.KEYCODE_AVR_POWER:
                return true;
            default:
                return false;
        }
    }

    /** Dedicated screensaver trigger keys can go straight to Dream even while Showcase is active. */
    static boolean isDirectScreensaverKey(int keyCode) {
        return keyCode == 194 || keyCode == KeyEvent.KEYCODE_BUTTON_14;
    }

    /** Replaces the alpha channel of an ARGB colour with {@code alpha255} clamped to [0, 255]. */
    static int withAlpha(int argbColor, int alpha255) {
        int clamped = Math.max(0, Math.min(255, alpha255));
        return (clamped << 24) | (argbColor & 0x00FFFFFF);
    }
}

