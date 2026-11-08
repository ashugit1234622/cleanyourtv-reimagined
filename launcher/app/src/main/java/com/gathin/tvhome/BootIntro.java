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
    static final String KEY_PENDING_WAKE = "wake_pending";
    static final String KEY_WAKE_REASON = "wake_reason";
    static final String KEY_DREAMING_PERSISTED = "dreaming_persisted";
    static final String KEY_SCREEN_OFF_PERSISTED = "screen_off_persisted";
    static final String KEY_APP_LAUNCHED = "app_launched_persisted";
    static final String KEY_LAST_STOP_TIME = "last_stop_time";
    static final String KEY_LAST_STOP_WALL_TIME = "last_stop_wall_time";
    static final String KEY_GREETING_HISTORY = "greeting_history_ids";
    static final String KEY_WAS_NON_INTERACTIVE = "was_non_interactive";
    static final String KEY_TEST_WAKE_GAP = "test_wake_gap";

    static final String EXTRA_TEST = "test_boot_intro";
    static final String EXTRA_TEST_MESSAGE = "test_message";
    static final String EXTRA_TEST_ANIMATION = "test_animation";
    static final String EXTRA_TEST_WAKE = "test_wake";

    static final int MAX_DURATION_MS = 7000;
    static final int DEFAULT_HOLD_MS = 2500;

    private static volatile boolean dreamEnded = false;
    private static volatile boolean dreaming = false;
    private static volatile long dreamStartRealtime = 0;

    private BootIntro() {}

    static void notifyDreamStarted() {
        dreaming = true;
        dreamEnded = false;
        try {
            dreamStartRealtime = android.os.SystemClock.elapsedRealtime();
        } catch (Throwable ignored) {
            dreamStartRealtime = System.currentTimeMillis();
        }
    }

    static void notifyDreamEnded() {
        dreaming = false;
        dreamEnded = true;
    }

    static long getScreensaverDurationMinutes() {
        if (dreamStartRealtime <= 0) return 0;
        try {
            long elapsed = android.os.SystemClock.elapsedRealtime() - dreamStartRealtime;
            return elapsed > 0 ? (elapsed / 60_000L) : 0;
        } catch (Throwable ignored) {
            long elapsed = System.currentTimeMillis() - dreamStartRealtime;
            return elapsed > 0 ? (elapsed / 60_000L) : 0;
        }
    }

    static List<String> getGreetingHistory(android.content.Context ctx) {
        if (ctx == null) return Collections.emptyList();
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_GREETING_HISTORY, "");
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        String[] parts = raw.split(",");
        List<String> list = new ArrayList<>(parts.length);
        for (String p : parts) {
            String id = p.trim();
            if (!id.isEmpty()) list.add(id);
        }
        return list;
    }

    static void recordGreetingId(android.content.Context ctx, String id) {
        if (ctx == null || id == null || id.trim().isEmpty()) return;
        List<String> history = new ArrayList<>(getGreetingHistory(ctx));
        history.remove(id);
        history.add(0, id);
        if (history.size() > 30) {
            history = history.subList(0, 30);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(history.get(i));
        }
        ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY_GREETING_HISTORY, sb.toString()).apply();
    }

    static String evaluateBootClass(android.content.Context ctx, java.time.LocalDateTime now) {
        if (ctx == null || now == null) return "other";
        int hour = now.getHour();
        int minute = now.getMinute();
        int totalMinutes = hour * 60 + minute;
        // early_morning: 04:00 (240m) to 07:00 (420m)
        // morning: 07:00 (420m) to 12:00 (720m)
        boolean isMorningBucket = totalMinutes >= 240 && totalMinutes < 720;
        if (!isMorningBucket) return "other";

        android.content.SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        long lastStopWallTime = prefs.getLong(KEY_LAST_STOP_WALL_TIME, 0);
        long nowWall = System.currentTimeMillis();
        // If unknown (0 or future) or gap >= 240 min (4 hours)
        if (lastStopWallTime <= 0 || nowWall < lastStopWallTime) {
            return "overnight_morning";
        }
        long diffMinutes = (nowWall - lastStopWallTime) / 60_000L;
        return diffMinutes >= 240 ? "overnight_morning" : "other";
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

    static void clearDreamState() {
        dreaming = false;
        dreamEnded = false;
        dreamStartRealtime = 0;
    }

    static final class WakeDecision {
        final boolean woke;
        final String reason;

        WakeDecision(boolean woke, String reason) {
            this.woke = woke;
            this.reason = reason;
        }
    }

    /**
     * Combines all independent wake signals with an OR:
     * (was dreaming) OR (screen was off) OR (PowerManager.isInteractive was false at last stop)
     * OR (launcher was stopped for longer than the wake gap WITHOUT any launcher-initiated app launch).
     * Normal returns from an app launched by the launcher are suppressed.
     */
    static WakeDecision evaluateWakeState(
            boolean isDreaming,
            boolean wasNonInteractive,
            boolean screenWokeUp,
            boolean dreamEnded,
            boolean persistedPendingWake,
            boolean persistedDreaming,
            boolean persistedScreenOff,
            boolean persistedNonInteractive,
            boolean appLaunched,
            long stoppedDurationMs,
            long gapMs) {
        if (appLaunched) {
            return new WakeDecision(false, "app_return");
        }
        if (isDreaming || persistedDreaming) {
            return new WakeDecision(true, "dreaming");
        }
        if (dreamEnded) {
            return new WakeDecision(true, "dream_ended");
        }
        if (screenWokeUp || persistedScreenOff) {
            return new WakeDecision(true, "screen_off_or_wake");
        }
        if (wasNonInteractive || persistedNonInteractive) {
            return new WakeDecision(true, "non_interactive_at_stop");
        }
        if (persistedPendingWake) {
            return new WakeDecision(true, "pending_wake_persisted");
        }
        if (stoppedDurationMs >= gapMs && gapMs > 0) {
            return new WakeDecision(true, "idle_duration_exceeded");
        }
        return new WakeDecision(false, "normal_home");
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
     * - requires either first wake (lastWakeMs <= 0), reboot detected (nowMs < lastWakeMs),
     *   gapMs <= 0 (Always), or gap elapsed >= gapMs.
     */
    static boolean shouldPlayOnWakeMs(boolean introOnWake, boolean reducedMotion,
                                     long gapMs, long lastWakeMs, long nowMs) {
        if (!introOnWake || reducedMotion) return false;
        if (gapMs <= 0) return true;
        if (lastWakeMs <= 0 || nowMs < lastWakeMs) return true;
        return (nowMs - lastWakeMs) >= gapMs;
    }

    static boolean shouldPlayOnWake(boolean introOnWake, boolean reducedMotion,
                                   int minGapMinutes, long lastWakeMs, long nowMs) {
        return shouldPlayOnWakeMs(introOnWake, reducedMotion, (long) minGapMinutes * 60 * 1000L, lastWakeMs, nowMs);
    }

    /**
     * Evaluates whether the sleep/away duration meets the wake gap.
     * gapMs <= 0 means Always (every wake plays).
     */
    static boolean isWakeGapMet(long gapMs, long stoppedDurationMs, long lastStopTime, long nowMs) {
        if (gapMs <= 0) return true; // Always (no minimum sleep required)
        if (lastStopTime <= 0 || nowMs < lastStopTime) return true; // first run or reboot occurred
        return stoppedDurationMs >= gapMs;
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
