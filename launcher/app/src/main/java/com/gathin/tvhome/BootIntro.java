package com.gathin.tvhome;

/**
 * Boot intro decision helper: plays once per cold reboot, keyed on Settings.Global.BOOT_COUNT,
 * when boot_intro is enabled in config.xml / theme JSON and reduced_motion is false.
 */
final class BootIntro {
    static final String PREFS_NAME = "boot_intro_prefs";
    static final String KEY_LAST_BOOT = "last_boot_count";
    static final String EXTRA_TEST = "test_boot_intro";

    private BootIntro() {}

    /**
     * Whether the boot intro should play:
     * - forceTest: always plays on request (e.g. adb intent extra test_boot_intro).
     * - Otherwise: requires boot_intro enabled, reduced_motion disabled, a valid boot_count,
     *   and that currentBoot differs from lastBoot.
     */
    static boolean shouldPlay(int currentBoot, int lastBoot, boolean bootIntroEnabled,
                              boolean reducedMotion, boolean forceTest) {
        if (forceTest) return true;
        if (!bootIntroEnabled || reducedMotion) return false;
        return currentBoot >= 0 && currentBoot != lastBoot;
    }
}
