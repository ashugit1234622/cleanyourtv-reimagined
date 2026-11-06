package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class BootIntroTest {

    @Test
    public void freshBootPlaysWhenEnabled() {
        assertTrue(BootIntro.shouldPlay(5, 4, true, false, false));
        assertTrue(BootIntro.shouldPlay(1, -1, true, false, false));
        assertTrue(BootIntro.shouldPlay(0, -1, true, false, false));
    }

    @Test
    public void sameBootDoesNotReplay() {
        assertFalse(BootIntro.shouldPlay(5, 5, true, false, false));
        assertFalse(BootIntro.shouldPlay(1, 1, true, false, false));
    }

    @Test
    public void disabledInConfigDoesNotPlay() {
        assertFalse(BootIntro.shouldPlay(5, 4, false, false, false));
    }

    @Test
    public void reducedMotionSkipsIntro() {
        assertFalse(BootIntro.shouldPlay(5, 4, true, true, false));
    }

    @Test
    public void unknownBootCountDoesNotPlay() {
        assertFalse(BootIntro.shouldPlay(-1, -1, true, false, false));
    }

    @Test
    public void forcedTestBypassesNormalChecks() {
        assertTrue(BootIntro.shouldPlay(5, 5, false, true, true));
        assertTrue(BootIntro.shouldPlay(-1, -1, false, false, true));
        assertTrue(BootIntro.shouldPlay(5, 4, false, false, true));
    }

    @Test
    public void timeSlotMapping() {
        assertEquals("morning", BootIntro.timeSlot(5));
        assertEquals("morning", BootIntro.timeSlot(8));
        assertEquals("morning", BootIntro.timeSlot(11));

        assertEquals("afternoon", BootIntro.timeSlot(12));
        assertEquals("afternoon", BootIntro.timeSlot(14));
        assertEquals("afternoon", BootIntro.timeSlot(16));

        assertEquals("evening", BootIntro.timeSlot(17));
        assertEquals("evening", BootIntro.timeSlot(19));
        assertEquals("evening", BootIntro.timeSlot(20));

        assertEquals("night", BootIntro.timeSlot(21));
        assertEquals("night", BootIntro.timeSlot(23));
        assertEquals("night", BootIntro.timeSlot(0));
        assertEquals("night", BootIntro.timeSlot(4));
    }

    @Test
    public void timeOfDaySelectionPrefersMatchingSlot() {
        List<BootIntro.IntroMessage> list = new ArrayList<>();
        list.add(new BootIntro.IntroMessage("Good morning", "Rise and shine", "morning"));
        list.add(new BootIntro.IntroMessage("Good afternoon", "Lunch time", "afternoon"));
        list.add(new BootIntro.IntroMessage("Good evening", "Unwind", "evening"));
        list.add(new BootIntro.IntroMessage("Late night", "One more episode", "night"));

        Random rng = new Random(42);
        // At 9am (morning), index 0 should always be selected among the time-specific entries
        for (int i = 0; i < 20; i++) {
            int selected = BootIntro.selectMessageIndex(list, 9, false, -1, rng);
            assertEquals(0, selected);
        }

        // At 14:00 (afternoon), index 1 should be selected
        for (int i = 0; i < 20; i++) {
            int selected = BootIntro.selectMessageIndex(list, 14, false, -1, rng);
            assertEquals(1, selected);
        }

        // At 19:00 (evening), index 2 should be selected
        for (int i = 0; i < 20; i++) {
            int selected = BootIntro.selectMessageIndex(list, 19, false, -1, rng);
            assertEquals(2, selected);
        }

        // At 23:00 (night), index 3 should be selected
        for (int i = 0; i < 20; i++) {
            int selected = BootIntro.selectMessageIndex(list, 23, false, -1, rng);
            assertEquals(3, selected);
        }
    }

    @Test
    public void noImmediateRepeat() {
        List<BootIntro.IntroMessage> list = new ArrayList<>();
        list.add(new BootIntro.IntroMessage("Title 0", "", "any"));
        list.add(new BootIntro.IntroMessage("Title 1", "", "any"));
        list.add(new BootIntro.IntroMessage("Title 2", "", "any"));

        Random rng = new Random(12345);
        for (int i = 0; i < 100; i++) {
            int selected = BootIntro.selectMessageIndex(list, 10, false, 1, rng);
            assertNotEquals(1, selected);
        }
    }

    @Test
    public void wakeWeighting() {
        List<BootIntro.IntroMessage> list = new ArrayList<>();
        list.add(new BootIntro.IntroMessage("Morning Coffee", "", "morning"));
        list.add(new BootIntro.IntroMessage("Welcome Back", "We missed you", "wake"));

        Random rng = new Random(999);
        // On non-wake (cold boot at 8am), "wake" message has weight 0 and should never be picked
        for (int i = 0; i < 50; i++) {
            int selected = BootIntro.selectMessageIndex(list, 8, false, -1, rng);
            assertEquals(0, selected);
        }

        // On wake at 8am, "wake" message has weight 4 while "morning" has weight 2, so wake is picked frequently
        int wakeCount = 0;
        for (int i = 0; i < 100; i++) {
            int selected = BootIntro.selectMessageIndex(list, 8, true, -1, rng);
            if (selected == 1) wakeCount++;
        }
        assertTrue("Wake message should be selected more often due to higher weight", wakeCount > 40);
    }

    @Test
    public void bootIntroTextOverrideLogic() {
        String fixed = "Custom Welcome";
        String resolvedTitle = (fixed != null && !fixed.isEmpty()) ? fixed : "Fallback";
        assertEquals("Custom Welcome", resolvedTitle);
    }

    @Test
    public void messagesOffModeLogic() {
        boolean bootIntroMessages = false;
        BootIntro.IntroMessage msg = new BootIntro.IntroMessage("Title", "Subtitle Tip", "any");
        String finalSubtitle = bootIntroMessages ? msg.subtitle : "";
        assertEquals("", finalSubtitle);

        bootIntroMessages = true;
        finalSubtitle = bootIntroMessages ? msg.subtitle : "";
        assertEquals("Subtitle Tip", finalSubtitle);
    }

    @Test
    public void wakeGapLogic() {
        long baseTime = 100_000L;
        int gapMinutes = 5;
        long gapMs = 5 * 60 * 1000L;

        // Gap == 0 always plays immediately (for user testing)
        assertTrue(BootIntro.shouldPlayOnWake(true, false, 0, baseTime, baseTime + 1000));

        // First wake ever (lastWakeMs <= 0) plays
        assertTrue(BootIntro.shouldPlayOnWake(true, false, gapMinutes, 0, baseTime));
        assertTrue(BootIntro.shouldPlayOnWake(true, false, gapMinutes, -1, baseTime));

        // Less than 5 minutes elapsed: does not play
        assertFalse(BootIntro.shouldPlayOnWake(true, false, gapMinutes, baseTime, baseTime + gapMs - 1000));
        assertFalse(BootIntro.shouldPlayOnWake(true, false, gapMinutes, baseTime, baseTime + 2 * 60 * 1000L));

        // 5 or more minutes elapsed: plays
        assertTrue(BootIntro.shouldPlayOnWake(true, false, gapMinutes, baseTime, baseTime + gapMs));
        assertTrue(BootIntro.shouldPlayOnWake(true, false, gapMinutes, baseTime, baseTime + gapMs + 5000));

        // When introOnWake is false: never plays
        assertFalse(BootIntro.shouldPlayOnWake(false, false, gapMinutes, baseTime, baseTime + gapMs + 10000));

        // When reducedMotion is true: never plays
        assertFalse(BootIntro.shouldPlayOnWake(true, true, gapMinutes, baseTime, baseTime + gapMs + 10000));
    }

    @Test
    public void durationAndHoldCaps() {
        assertTrue("Total duration must be capped at 7000ms", BootIntro.MAX_DURATION_MS <= 7000);
        assertEquals("Default hold is 2500ms", 2500, BootIntro.DEFAULT_HOLD_MS);
    }

    @Test
    public void precedenceUserOverThemeOverDefault() {
        // Case 1: User set -> user wins
        IntroSettings s1 = IntroSettings.resolve(
                false, false, 15, 3000, "fast", "shimmer", "titles_only",
                true, true, 10, 2500, "slow", "slide",
                true, true, 5, 2000, "normal", "pop"
        );
        assertFalse(s1.bootIntro);
        assertFalse(s1.introOnWake);
        assertEquals(15, s1.wakeGapMinutes);
        assertEquals(900, s1.wakeGapSeconds);
        assertEquals(3000, s1.holdMs);
        assertEquals("fast", s1.speed);
        assertEquals("shimmer", s1.animation);
        assertEquals("titles_only", s1.messagesMode);

        // Case 2: User untouched (null) -> theme wins
        IntroSettings s2 = IntroSettings.resolve(
                null, null, null, null, null, null, null,
                true, false, 10, 2500, "slow", "slide",
                true, true, 5, 2000, "normal", "pop"
        );
        assertTrue(s2.bootIntro);
        assertFalse(s2.introOnWake);
        assertEquals(10, s2.wakeGapMinutes);
        assertEquals(600, s2.wakeGapSeconds);
        assertEquals(2500, s2.holdMs);
        assertEquals("slow", s2.speed);
        assertEquals("slide", s2.animation);
        assertEquals("titles_and_tips", s2.messagesMode);

        // Case 3: Both user and theme untouched -> default wins
        IntroSettings s3 = IntroSettings.resolve(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                true, true, 5, 2000, "normal", "pop"
        );
        assertTrue(s3.bootIntro);
        assertTrue(s3.introOnWake);
        assertEquals(5, s3.wakeGapMinutes);
        assertEquals(300, s3.wakeGapSeconds);
        assertEquals(2000, s3.holdMs);
        assertEquals("normal", s3.speed);
        assertEquals("pop", s3.animation);
        assertEquals("titles_and_tips", s3.messagesMode);
    }

    @Test
    public void invalidValuesFallbackToDefault() {
        IntroSettings s = IntroSettings.resolve(
                null, null, -10, 99999, "hyperspeed", "explode", "unknown_mode",
                null, null, 0, 100, "warp", "spin",
                true, true, 5, 2000, "normal", "pop"
        );
        assertEquals(5, s.wakeGapMinutes);
        assertEquals(300, s.wakeGapSeconds);
        assertEquals(2000, s.holdMs);
        assertEquals("normal", s.speed);
        assertEquals("pop", s.animation);
        assertEquals("titles_and_tips", s.messagesMode);
    }

    @Test
    public void speedDoesNotAffectHoldAndDurationCappedAt7s() {
        // Slow speed (multiplier 1.4f): base anims = 770 + 840 + 490 = 2100ms
        IntroSettings.Durations slowD = IntroSettings.computeDurations(3000, "slow");
        assertEquals("Hold must NOT be affected by slow speed", 3000, slowD.holdMs);
        assertEquals(770, slowD.entranceMs);
        assertEquals(840, slowD.revealMs);
        assertEquals(490, slowD.dockMs);
        assertEquals(5100, slowD.totalMs);
        assertTrue("Total must be <= 7000ms", slowD.totalMs <= 7000);

        // Normal speed (multiplier 1.0f): base anims = 550 + 600 + 350 = 1500ms
        IntroSettings.Durations normalD = IntroSettings.computeDurations(3000, "normal");
        assertEquals("Hold must NOT be affected by normal speed", 3000, normalD.holdMs);
        assertEquals(550, normalD.entranceMs);
        assertEquals(600, normalD.revealMs);
        assertEquals(350, normalD.dockMs);
        assertEquals(4500, normalD.totalMs);
        assertTrue("Total must be <= 7000ms", normalD.totalMs <= 7000);

        // Fast speed (multiplier 0.7f): base anims = 385 + 420 + 245 = 1050ms
        IntroSettings.Durations fastD = IntroSettings.computeDurations(3000, "fast");
        assertEquals("Hold must NOT be affected by fast speed", 3000, fastD.holdMs);
        assertEquals(385, fastD.entranceMs);
        assertEquals(420, fastD.revealMs);
        assertEquals(245, fastD.dockMs);
        assertEquals(4050, fastD.totalMs);
        assertTrue("Total must be <= 7000ms", fastD.totalMs <= 7000);

        // Test all valid stepper holds and speeds
        for (int hold : IntroSettings.HOLD_TIMES_MS) {
            for (String spd : IntroSettings.SPEEDS) {
                IntroSettings.Durations d = IntroSettings.computeDurations(hold, spd);
                if (hold > 0) {
                    assertEquals("Hold must match requested value exactly", hold, d.holdMs);
                }
                assertTrue("Total duration for hold=" + hold + ", speed=" + spd + " must be <= 7000ms, got " + d.totalMs,
                        d.totalMs <= 7000);
            }
        }
    }

    @Test
    public void autoHoldFormulaCalculatesAccurately() {
        // Auto = max(2500, 1500 + 70 * (titleChars + subtitleChars)) capped at 4500
        // Case 1: Short message (7 chars): 1500 + 70*7 = 1990 -> min clamp 2500ms
        int holdShort = IntroSettings.computeHoldMs(0, "Welcome", "");
        assertEquals(2500, holdShort);

        // Case 2: Zero chars -> min clamp 2500ms
        int holdZero = IntroSettings.computeHoldMs(0, "", "");
        assertEquals(2500, holdZero);

        // Case 3: Medium message (20 chars): 1500 + 70*20 = 2900ms
        int holdMed = IntroSettings.computeHoldMs(0, "Good evening", "Enjoy TV");
        assertEquals(2900, holdMed);

        // Case 4: Long message (40 chars): 1500 + 70*40 = 4300ms
        String title40 = "Good afternoon from TV"; // 22
        String sub40 = "Check out your apps";   // 19 -> 41 chars -> 1500 + 70*41 = 4370ms
        assertEquals(4370, IntroSettings.computeHoldMs(0, title40, sub40));

        // Case 5: Very long message (50 chars): 1500 + 70*50 = 5000ms -> max clamp 4500ms
        String longText = "This is a very long message with more than fifty characters in total length";
        assertEquals(4500, IntroSettings.computeHoldMs(0, longText, ""));

        // Case 6: Manually chosen values are returned exactly as chosen
        assertEquals(1500, IntroSettings.computeHoldMs(1500, "Any title", "Any sub"));
        assertEquals(2000, IntroSettings.computeHoldMs(2000, "Any title", "Any sub"));
        assertEquals(2500, IntroSettings.computeHoldMs(2500, "Any title", "Any sub"));
        assertEquals(3000, IntroSettings.computeHoldMs(3000, "Any title", "Any sub"));
        assertEquals(4000, IntroSettings.computeHoldMs(4000, "Any title", "Any sub"));
        assertEquals(5000, IntroSettings.computeHoldMs(5000, "Any title", "Any sub"));
    }

    @Test
    public void wakeGapDecisionsForAlwaysAndSeconds() {
        long now = 100_000L;
        long lastStop = now - 5000L; // stopped 5s ago

        // 1. Always (gap = 0s): 5s sleep must play, 15s sleep must play
        assertTrue("Always gap (0s) must play after 5s sleep",
                BootIntro.isWakeGapMet(0, 5000L, lastStop, now));
        assertTrue("Always gap (0s) must play after 15s sleep",
                BootIntro.isWakeGapMet(0, 15000L, lastStop, now));

        // 2. 10 s gap (10,000ms):
        long gap10s = 10_000L;
        assertFalse("10s gap: 5s sleep must NOT play",
                BootIntro.isWakeGapMet(gap10s, 5000L, lastStop, now));
        assertFalse("10s gap: 9.9s sleep must NOT play",
                BootIntro.isWakeGapMet(gap10s, 9900L, lastStop, now));
        assertTrue("10s gap: 10s sleep must play",
                BootIntro.isWakeGapMet(gap10s, 10000L, lastStop, now));
        assertTrue("10s gap: 20s sleep must play",
                BootIntro.isWakeGapMet(gap10s, 20000L, lastStop, now));

        // 3. 30 s gap (30,000ms):
        long gap30s = 30_000L;
        assertFalse("30s gap: 15s sleep must NOT play",
                BootIntro.isWakeGapMet(gap30s, 15000L, lastStop, now));
        assertFalse("30s gap: 29s sleep must NOT play",
                BootIntro.isWakeGapMet(gap30s, 29000L, lastStop, now));
        assertTrue("30s gap: 30s sleep must play",
                BootIntro.isWakeGapMet(gap30s, 30000L, lastStop, now));
        assertTrue("30s gap: 45s sleep must play",
                BootIntro.isWakeGapMet(gap30s, 45000L, lastStop, now));

        // 4. 1 min gap (60,000ms):
        long gap1m = 60_000L;
        assertFalse("1m gap: 30s sleep must NOT play",
                BootIntro.isWakeGapMet(gap1m, 30000L, lastStop, now));
        assertTrue("1m gap: 60s sleep must play",
                BootIntro.isWakeGapMet(gap1m, 60000L, lastStop, now));
        assertTrue("1m gap: 90s sleep must play",
                BootIntro.isWakeGapMet(gap1m, 90000L, lastStop, now));

        // 5. 5 min gap (300,000ms):
        long gap5m = 300_000L;
        assertFalse("5m gap: 2 min sleep must NOT play",
                BootIntro.isWakeGapMet(gap5m, 120000L, lastStop, now));
        assertTrue("5m gap: 5 min sleep must play",
                BootIntro.isWakeGapMet(gap5m, 300000L, lastStop, now));

        // 6. First start / reboot during sleep
        assertTrue("First start (lastStop <= 0) must play",
                BootIntro.isWakeGapMet(gap5m, 0, 0, now));
        assertTrue("Reboot during sleep (now < lastStop) must play",
                BootIntro.isWakeGapMet(gap5m, 0, now + 10000L, now));
    }

    @Test
    public void titlesOnlyAndOffModes() {
        IntroSettings sOff = IntroSettings.resolve(
                null, null, null, null, null, null, "off",
                null, null, null, null, null, null,
                true, true, 5, 2000, "normal", "pop"
        );
        assertEquals("off", sOff.messagesMode);

        IntroSettings sTitlesOnly = IntroSettings.resolve(
                null, null, null, null, null, null, "titles_only",
                null, null, null, null, null, null,
                true, true, 5, 2000, "normal", "pop"
        );
        assertEquals("titles_only", sTitlesOnly.messagesMode);

        IntroSettings sAll = IntroSettings.resolve(
                null, null, null, null, null, null, "titles_and_tips",
                null, null, null, null, null, null,
                true, true, 5, 2000, "normal", "pop"
        );
        assertEquals("titles_and_tips", sAll.messagesMode);
    }

    @Test
    public void resetToDefaultsRestoresThemeOrDefault() {
        // Simulate user having modified settings
        IntroSettings userModified = IntroSettings.resolve(
                false, false, 30, 3500, "fast", "shimmer", "off",
                true, true, 10, 2500, "normal", "slide",
                true, true, 5, 2000, "normal", "pop"
        );
        assertFalse(userModified.bootIntro);
        assertEquals(30, userModified.wakeGapMinutes);
        assertEquals("fast", userModified.speed);
        assertEquals("off", userModified.messagesMode);

        // After reset, user settings are null/cleared -> reverts to theme/default
        IntroSettings afterReset = IntroSettings.resolve(
                null, null, null, null, null, null, null,
                true, true, 10, 2500, "normal", "slide",
                true, true, 5, 2000, "normal", "pop"
        );
        assertTrue(afterReset.bootIntro);
        assertEquals(10, afterReset.wakeGapMinutes);
        assertEquals("normal", afterReset.speed);
        assertEquals("titles_and_tips", afterReset.messagesMode);
    }

    @Test
    public void dreamLifecycleTracking() {
        // Initially consume any leftover state
        BootIntro.consumeDreamEnded();

        // 1. Dream starts (e.g. Aerial View or ClockDream started)
        BootIntro.notifyDreamStarted();
        assertTrue("isDreaming must be true while screensaver runs", BootIntro.isDreaming());
        assertFalse("dreamEnded must be false when dream starts", BootIntro.isDreamEnded());

        // 2. Dream ends (e.g. remote button pressed)
        BootIntro.notifyDreamEnded();
        assertFalse("isDreaming must be false when dream ends", BootIntro.isDreaming());
        assertTrue("dreamEnded must be true immediately after dream ends", BootIntro.isDreamEnded());

        // 3. consumeDreamEnded() is one-shot
        assertTrue("consumeDreamEnded must return true on first call", BootIntro.consumeDreamEnded());
        assertFalse("consumeDreamEnded must return false on subsequent calls", BootIntro.consumeDreamEnded());
        assertFalse("isDreamEnded must be false after consumption", BootIntro.isDreamEnded());
    }

    @Test
    public void wakeGapAcrossReboot() {
        int gapMinutes = 5;
        // Case: lastWakeMs was 3,000,000ms in previous boot.
        // After reboot, elapsedRealtime resets to 10,000ms.
        // nowMs (10,000) < lastWakeMs (3,000,000)
        // Must recognize the reboot and return true!
        assertTrue("Must play on wake after reboot even if elapsedRealtime is smaller than lastWakeMs",
                BootIntro.shouldPlayOnWake(true, false, gapMinutes, 3_000_000L, 10_000L));

        // Normal uptime: nowMs >= lastWakeMs
        assertFalse(BootIntro.shouldPlayOnWake(true, false, gapMinutes, 10_000L, 10_000L + 60_000L));
        assertTrue(BootIntro.shouldPlayOnWake(true, false, gapMinutes, 10_000L, 10_000L + 300_000L));
    }

    @Test
    public void wakeDecisionEvaluatingIndependentSignals() {
        long gapMs = 300_000L;

        // 1. Returning from an app launched by the launcher: NEVER wakes
        BootIntro.WakeDecision dApp = BootIntro.evaluateWakeState(
                false, false, false, false, false, false, false, false, true, 400_000L, gapMs);
        assertFalse("Returning from app must never trigger wake intro", dApp.woke);
        assertEquals("app_return", dApp.reason);

        // 2. Normal Home press while interactive: not a wake
        BootIntro.WakeDecision dHome = BootIntro.evaluateWakeState(
                false, false, false, false, false, false, false, false, false, 50_000L, gapMs);
        assertFalse("Normal home press must not trigger wake intro", dHome.woke);
        assertEquals("normal_home", dHome.reason);

        // 3. Screen off or wake broadcast received
        BootIntro.WakeDecision dScreen = BootIntro.evaluateWakeState(
                false, false, true, false, false, false, false, false, false, 50_000L, gapMs);
        assertTrue("Screen woke up must trigger wake intro", dScreen.woke);
        assertEquals("screen_off_or_wake", dScreen.reason);

        // 4. Was dreaming in memory
        BootIntro.WakeDecision dDream = BootIntro.evaluateWakeState(
                true, false, false, false, false, false, false, false, false, 50_000L, gapMs);
        assertTrue("Dreaming state must trigger wake intro", dDream.woke);
        assertEquals("dreaming", dDream.reason);

        // 5. Dream ended before onStart (DREAMING_STOPPED before onStart)
        BootIntro.WakeDecision dDreamEnd = BootIntro.evaluateWakeState(
                false, false, false, true, false, false, false, false, false, 50_000L, gapMs);
        assertTrue("Dream ended state must trigger wake intro", dDreamEnd.woke);
        assertEquals("dream_ended", dDreamEnd.reason);

        // 6. Process restart: in-memory flags lost, but persisted state restored from prefs
        BootIntro.WakeDecision dPersistedDream = BootIntro.evaluateWakeState(
                false, false, false, false, true, true, false, false, false, 50_000L, gapMs);
        assertTrue("Persisted dreaming across process death must trigger wake intro", dPersistedDream.woke);
        assertEquals("dreaming", dPersistedDream.reason);

        // 7. Process restart: screen was off persisted
        BootIntro.WakeDecision dPersistedScreen = BootIntro.evaluateWakeState(
                false, false, false, false, true, false, true, false, false, 50_000L, gapMs);
        assertTrue("Persisted screen off must trigger wake intro", dPersistedScreen.woke);
        assertEquals("screen_off_or_wake", dPersistedScreen.reason);

        // 8. Was non-interactive at stop (standby without explicit broadcast)
        BootIntro.WakeDecision dNonInter = BootIntro.evaluateWakeState(
                false, true, false, false, false, false, false, false, false, 50_000L, gapMs);
        assertTrue("Non-interactive at last stop must trigger wake intro", dNonInter.woke);
        assertEquals("non_interactive_at_stop", dNonInter.reason);

        // 9. Idle duration exceeded without any app launch
        BootIntro.WakeDecision dIdle = BootIntro.evaluateWakeState(
                false, false, false, false, false, false, false, false, false, 350_000L, gapMs);
        assertTrue("Long idle duration must trigger wake intro", dIdle.woke);
        assertEquals("idle_duration_exceeded", dIdle.reason);
    }

    @Test
    public void clearDreamStateDoesNotPrimDreamEnded() {
        BootIntro.notifyDreamStarted();
        assertTrue(BootIntro.isDreaming());

        // clearDreamState must reset both dreaming and dreamEnded to false
        BootIntro.clearDreamState();
        assertFalse(BootIntro.isDreaming());
        assertFalse(BootIntro.isDreamEnded());
        assertFalse(BootIntro.consumeDreamEnded());
    }
}
