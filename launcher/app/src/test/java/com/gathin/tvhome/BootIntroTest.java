package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
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
}
