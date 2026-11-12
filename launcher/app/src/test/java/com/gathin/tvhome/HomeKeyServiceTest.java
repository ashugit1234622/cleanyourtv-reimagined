package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HomeKeyServiceTest {
    private static final long SOON = 45_000L;

    @Test public void aTapIsAShortPress() {
        assertTrue(HomeKeyService.isShortPress(1_000, 1_120, 400));
    }

    @Test public void holdingPastTheTimeoutIsALongPress() {
        assertFalse(HomeKeyService.isShortPress(1_000, 1_400, 400));
        assertFalse(HomeKeyService.isShortPress(1_000, 3_000, 400));
    }

    @Test public void firstBindOfANewBootProbes() {
        assertTrue(HomeKeyService.firstBindAfterBoot(12, 11, SOON));
        assertTrue(HomeKeyService.firstBindAfterBoot(1, -1, SOON));
    }

    @Test public void aRebindInTheSameBootDoesNot() {
        assertFalse(HomeKeyService.firstBindAfterBoot(12, 12, SOON));
    }

    @Test public void aBindLongAfterBootDoesNot() {
        assertFalse(HomeKeyService.firstBindAfterBoot(12, 11, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void noBootCountMeansNoProbe() {
        assertFalse(HomeKeyService.firstBindAfterBoot(-1, -1, SOON));
    }

    private static final String TV = "com.mediatek.wwtv.tvcenter";

    @Test public void liveTvBeforeAnyKeyGoesHome() {
        assertTrue(HomeKeyService.powerOnRedirect(TV, TV, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void otherAppsAndInputsAreLeftAlone() {
        assertFalse(HomeKeyService.powerOnRedirect(TV, "com.netflix.ninja", SOON, HomeKeyService.BOOT_WINDOW_MS));
        assertFalse(HomeKeyService.powerOnRedirect(TV, null, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void afterAKeyOrTheWindowLiveTvStays() {
        assertFalse(HomeKeyService.powerOnRedirect(TV, TV, SOON, 0));
        assertFalse(HomeKeyService.powerOnRedirect(TV, TV, HomeKeyService.BOOT_WINDOW_MS, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void offMeansNothing() {
        assertFalse(HomeKeyService.powerOnRedirect("", TV, SOON, HomeKeyService.BOOT_WINDOW_MS));
    }

    @Test public void alwaysGoesHomeAfterScreenOnUntilAKey() {
        assertTrue(HomeKeyService.stillArmed(SOON, SOON + HomeKeyService.SCREEN_ON_WINDOW_MS));
        assertFalse(HomeKeyService.stillArmed(SOON, 0));
    }

    @Test public void homeCloseCoverGuardsHomeInternalUiStandbyAndDream() {
        assertTrue(HomeKeyService.shouldUseHomeCloseCover(true, false, true, false, false, true, false));
        assertFalse(HomeKeyService.shouldUseHomeCloseCover(false, false, true, false, false, true, false));
        assertFalse(HomeKeyService.shouldUseHomeCloseCover(true, true, true, false, false, true, false));
        assertFalse(HomeKeyService.shouldUseHomeCloseCover(true, false, false, false, false, true, false));
        assertFalse(HomeKeyService.shouldUseHomeCloseCover(true, false, true, true, false, true, false));
        assertFalse(HomeKeyService.shouldUseHomeCloseCover(true, false, true, false, true, true, false));
        assertFalse(HomeKeyService.shouldUseHomeCloseCover(true, false, true, false, false, false, false));
        assertFalse(HomeKeyService.shouldUseHomeCloseCover(true, false, true, false, false, true, true));
        assertTrue(HomeKeyService.CLOSE_COVER_TIMEOUT_MS >= 1000L);
        assertTrue(HomeKeyService.CLOSE_COVER_WM_SETTLE_MS > 0L
                && HomeKeyService.CLOSE_COVER_WM_SETTLE_MS < HomeKeyService.CLOSE_COVER_TIMEOUT_MS);
    }
}
