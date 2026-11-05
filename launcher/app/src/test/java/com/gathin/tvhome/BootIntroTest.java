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
        assertTrue("Total duration must be capped at 5000ms", BootIntro.MAX_DURATION_MS <= 5000);
        assertEquals("Default hold is 2000ms", 2000, BootIntro.DEFAULT_HOLD_MS);
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
        assertEquals(2000, s.holdMs);
        assertEquals("normal", s.speed);
        assertEquals("pop", s.animation);
        assertEquals("titles_and_tips", s.messagesMode);
    }

    @Test
    public void holdAndSpeedClampedTo5sCap() {
        // Slow (multiplier 1.4f): base anims = 770 + 840 + 490 = 2100ms
        // Hold 4000ms: 4000 + 2100 = 6100 > 5000 -> clamped hold = 5000 - 2100 = 2900ms
        IntroSettings.Durations slowClamped = IntroSettings.computeDurations(4000, "slow");
        assertEquals(2900, slowClamped.holdMs);
        assertTrue("Total must be <= 5000ms", slowClamped.totalMs <= 5000);
        assertEquals(5000, slowClamped.totalMs);

        // Normal (multiplier 1.0f): base anims = 550 + 600 + 350 = 1500ms
        // Hold 4000ms: 4000 + 1500 = 5500 > 5000 -> clamped hold = 5000 - 1500 = 3500ms
        IntroSettings.Durations normalClamped = IntroSettings.computeDurations(4000, "normal");
        assertEquals(3500, normalClamped.holdMs);
        assertTrue("Total must be <= 5000ms", normalClamped.totalMs <= 5000);
        assertEquals(5000, normalClamped.totalMs);

        // Fast (multiplier 0.7f): base anims = 385 + 420 + 245 = 1050ms
        // Hold 4000ms: 4000 + 1050 = 5050 > 5000 -> clamped hold = 5000 - 1050 = 3950ms
        IntroSettings.Durations fastClamped = IntroSettings.computeDurations(4000, "fast");
        assertEquals(3950, fastClamped.holdMs);
        assertTrue("Total must be <= 5000ms", fastClamped.totalMs <= 5000);
        assertEquals(5000, fastClamped.totalMs);

        // Test all valid stepper holds and speeds
        for (int hold : IntroSettings.HOLD_TIMES_MS) {
            for (String spd : IntroSettings.SPEEDS) {
                IntroSettings.Durations d = IntroSettings.computeDurations(hold, spd);
                assertTrue("Total duration for hold=" + hold + ", speed=" + spd + " must be <= 5000ms, got " + d.totalMs,
                        d.totalMs <= 5000);
            }
        }
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
}
