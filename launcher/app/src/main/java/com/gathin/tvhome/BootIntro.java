package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Boot and wake intro decision helper and message selector.
 * Plays once per cold reboot (keyed on Settings.Global.BOOT_COUNT), and optionally on wake from
 * sleep or screensaver when intro_on_wake is enabled and the wake gap has elapsed.
 */
final class BootIntro {
    static final String PREFS_NAME = "boot_intro_prefs";
    static final String KEY_LAST_BOOT = "last_boot_count";
    static final String KEY_LAST_INTRO_TIME = "last_intro_time";
    static final String KEY_LAST_MESSAGE_INDEX = "last_message_index";

    static final String EXTRA_TEST = "test_boot_intro";
    static final String EXTRA_TEST_MESSAGE = "test_message";
    static final String EXTRA_TEST_ANIMATION = "test_animation";
    static final String EXTRA_TEST_WAKE = "test_wake";

    static final int MAX_DURATION_MS = 5000;
    static final int DEFAULT_HOLD_MS = 2000;

    private static volatile boolean dreamEnded = false;
    private static volatile boolean dreaming = false;

    private BootIntro() {}

    static void notifyDreamStarted() {
        dreaming = true;
        dreamEnded = false;
    }

    static void notifyDreamEnded() {
        dreaming = false;
        dreamEnded = true;
    }

    static boolean consumeDreamEnded() {
        boolean ended = dreamEnded;
        dreamEnded = false;
        return ended;
    }

    static boolean isDreamEnded() {
        return dreamEnded;
    }

    static boolean isDreaming() {
        return dreaming;
    }

    static final class IntroMessage {
        final String title;
        final String subtitle;
        final String when; // "any", "morning", "afternoon", "evening", "night", "wake"

        IntroMessage(String title, String subtitle, String when) {
            this.title = title != null ? title.trim() : "";
            this.subtitle = subtitle != null ? subtitle.trim() : "";
            this.when = when != null ? when.trim().toLowerCase(Locale.ROOT) : "any";
        }

        @Override
        public String toString() {
            return title + "|" + subtitle + "|" + when;
        }
    }

    static List<IntroMessage> parseMessages(String[] raw) {
        if (raw == null || raw.length == 0) return Collections.emptyList();
        List<IntroMessage> list = new ArrayList<>(raw.length);
        for (String s : raw) {
            if (s == null || s.trim().isEmpty()) continue;
            String[] parts = s.split("\\|", -1);
            String title = parts[0];
            String subtitle = parts.length > 1 ? parts[1] : "";
            String when = parts.length > 2 ? parts[2] : "any";
            list.add(new IntroMessage(title, subtitle, when));
        }
        return list;
    }

    static String timeSlot(int hour) {
        if (hour >= 5 && hour < 12) return "morning";
        if (hour >= 12 && hour < 17) return "afternoon";
        if (hour >= 17 && hour < 21) return "evening";
        return "night"; // 21..24 and 0..4
    }

    /**
     * Whether the boot intro should play on boot:
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

    /**
     * Whether the intro should play on wake from sleep or screensaver:
     * - requires introOnWake enabled and reducedMotion disabled.
     * - requires either first wake (lastWakeMs <= 0) or gap elapsed >= minGapMinutes.
     */
    static boolean shouldPlayOnWake(boolean introOnWake, boolean reducedMotion,
                                   int minGapMinutes, long lastWakeMs, long nowMs) {
        if (!introOnWake || reducedMotion) return false;
        if (lastWakeMs <= 0) return true;
        long gapMs = (long) minGapMinutes * 60 * 1000L;
        return (nowMs - lastWakeMs) >= gapMs;
    }

    /**
     * Selects a message index from the list according to time of day, wake state, and no-repeat logic.
     */
    static int selectMessageIndex(List<IntroMessage> messages, int hour, boolean isWake, int lastIndex, Random random) {
        if (messages == null || messages.isEmpty()) return -1;
        if (messages.size() == 1) return 0;

        String slot = timeSlot(hour);
        int[] weights = new int[messages.size()];
        int totalWeight = 0;

        for (int i = 0; i < messages.size(); i++) {
            if (i == lastIndex) {
                weights[i] = 0; // Never repeat immediate previous
                continue;
            }
            IntroMessage msg = messages.get(i);
            int w = 0;
            if (isWake) {
                if ("wake".equals(msg.when)) {
                    w = 4; // wake entries weighted highest on wake
                } else if (slot.equals(msg.when)) {
                    w = 2; // time-of-day match
                } else if ("any".equals(msg.when)) {
                    w = 1;
                }
            } else {
                if ("wake".equals(msg.when)) {
                    w = 0; // do not select wake-specific entries on normal cold boot
                } else if (slot.equals(msg.when)) {
                    w = 3; // time-of-day weighted higher
                } else if ("any".equals(msg.when)) {
                    w = 1;
                }
            }
            weights[i] = w;
            totalWeight += w;
        }

        // If no candidate matched with weight > 0 (fallback)
        if (totalWeight <= 0) {
            List<Integer> valid = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                if (i != lastIndex) valid.add(i);
            }
            if (valid.isEmpty()) return 0;
            return valid.get(random.nextInt(valid.size()));
        }

        int pick = random.nextInt(totalWeight);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (pick < acc) return i;
        }
        return 0;
    }
}
