package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IdleShowcasePolicyTest {
    private static final int KEYCODE_BACK = 4;
    private static final int KEYCODE_DPAD_UP = 19;
    private static final int KEYCODE_DPAD_RIGHT = 22;
    private static final int KEYCODE_DPAD_CENTER = 23;
    private static final int KEYCODE_VOLUME_UP = 24;
    private static final int KEYCODE_VOLUME_DOWN = 25;
    private static final int KEYCODE_POWER = 26;
    private static final int KEYCODE_MENU = 82;
    private static final int KEYCODE_MUTE = 91;
    private static final int KEYCODE_VOLUME_MUTE = 164;
    private static final int KEYCODE_BUTTON_14 = 201;
    private static final int KEYCODE_SLEEP = 223;
    private static final int KEYCODE_WAKEUP = 224;

    @Test
    public void delayMsClampsNegativeZeroAndExcessiveValues() {
        assertEquals(0L, IdleShowcasePolicy.delayMs(-5));
        assertEquals(0L, IdleShowcasePolicy.delayMs(0));
        assertEquals(30_000L, IdleShowcasePolicy.delayMs(30));
        assertEquals(60_000L, IdleShowcasePolicy.delayMs(60));
        assertEquals(120_000L, IdleShowcasePolicy.delayMs(120));
        assertEquals(3_600_000L, IdleShowcasePolicy.delayMs(9999));
    }

    @Test
    public void canScheduleRequiresCleanResumedHome() {
        assertTrue(IdleShowcasePolicy.canSchedule(true, 60, false, false, false, false, false));
        assertFalse(IdleShowcasePolicy.canSchedule(false, 60, false, false, false, false, false));
        assertFalse(IdleShowcasePolicy.canSchedule(true, 0, false, false, false, false, false));
        assertFalse(IdleShowcasePolicy.canSchedule(true, 60, true, false, false, false, false));
        assertFalse(IdleShowcasePolicy.canSchedule(true, 60, false, true, false, false, false));
        assertFalse(IdleShowcasePolicy.canSchedule(true, 60, false, false, true, false, false));
        assertFalse(IdleShowcasePolicy.canSchedule(true, 60, false, false, false, true, false));
        assertFalse(IdleShowcasePolicy.canSchedule(true, 60, false, false, false, false, true));
    }

    @Test
    public void volumeMuteAndPowerKeysAreIgnoredWhileNavigationKeysWake() {
        assertTrue(IdleShowcasePolicy.shouldIgnoreForIdleAndWake(KEYCODE_VOLUME_UP));
        assertTrue(IdleShowcasePolicy.shouldIgnoreForIdleAndWake(KEYCODE_VOLUME_DOWN));
        assertTrue(IdleShowcasePolicy.shouldIgnoreForIdleAndWake(KEYCODE_VOLUME_MUTE));
        assertTrue(IdleShowcasePolicy.shouldIgnoreForIdleAndWake(KEYCODE_MUTE));
        assertTrue(IdleShowcasePolicy.shouldIgnoreForIdleAndWake(KEYCODE_POWER));
        assertTrue(IdleShowcasePolicy.shouldIgnoreForIdleAndWake(KEYCODE_SLEEP));
        assertTrue(IdleShowcasePolicy.shouldIgnoreForIdleAndWake(KEYCODE_WAKEUP));

        assertFalse(IdleShowcasePolicy.shouldIgnoreForIdleAndWake(KEYCODE_DPAD_UP));
        assertFalse(IdleShowcasePolicy.shouldIgnoreForIdleAndWake(KEYCODE_DPAD_RIGHT));
        assertFalse(IdleShowcasePolicy.shouldIgnoreForIdleAndWake(KEYCODE_DPAD_CENTER));
        assertFalse(IdleShowcasePolicy.shouldIgnoreForIdleAndWake(KEYCODE_BACK));
        assertFalse(IdleShowcasePolicy.shouldIgnoreForIdleAndWake(KEYCODE_MENU));
    }

    @Test
    public void screensaverHotkeysAreDetected() {
        assertTrue(IdleShowcasePolicy.isDirectScreensaverKey(194));
        assertTrue(IdleShowcasePolicy.isDirectScreensaverKey(KEYCODE_BUTTON_14));
        assertFalse(IdleShowcasePolicy.isDirectScreensaverKey(KEYCODE_DPAD_CENTER));
    }

    @Test
    public void withAlphaReplacesAndClampsAlphaChannelOnly() {
        assertEquals(0x00FFFFFF, IdleShowcasePolicy.withAlpha(0xFFFFFFFF, 0));
        assertEquals(0x80112233, IdleShowcasePolicy.withAlpha(0xFF112233, 0x80));
        assertEquals(0x00112233, IdleShowcasePolicy.withAlpha(0xFF112233, -40));
        assertEquals(0xFF112233, IdleShowcasePolicy.withAlpha(0x10112233, 500));
    }
}

