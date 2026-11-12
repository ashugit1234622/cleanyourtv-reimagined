package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppLaunchSettingsTest {

    @Test
    public void defaultsMatchOriginalIosOpenBehavior() {
        AppLaunchSettings d = AppLaunchSettings.defaults();
        assertTrue(d.enabled);
        assertTrue(d.closeEnabled);
        assertEquals(AppLaunchSettings.SPEED_NORMAL, d.speed);
        assertEquals(AppLaunchSettings.EASING_IOS_IN_OUT, d.easing);
        assertTrue(d.cardMorph);
        assertEquals(AppLaunchSettings.DEPTH_MEDIUM, d.bgDepth);
        assertTrue(d.roundedCorners);
        assertEquals(AppLaunchSettings.REVEAL_ZOOM_FADE, d.appReveal);
        assertEquals(410L, d.sweepDurationMs());
        assertEquals(300L, d.revealDurationMs());
        assertTrue(d.hasBackgroundDepth());
    }

    @Test
    public void fromValuesNormalizesMixedCaseAndFallsBackOnUnknown() {
        AppLaunchSettings clean = AppLaunchSettings.fromValues(
                false, false, "  FAST ", "Strong_In", false, " DEEP ", false, " Fade ");
        assertFalse(clean.enabled);
        assertFalse(clean.closeEnabled);
        assertEquals(AppLaunchSettings.SPEED_FAST, clean.speed);
        assertEquals(AppLaunchSettings.EASING_STRONG_IN, clean.easing);
        assertFalse(clean.cardMorph);
        assertEquals(AppLaunchSettings.DEPTH_DEEP, clean.bgDepth);
        assertFalse(clean.roundedCorners);
        assertEquals(AppLaunchSettings.REVEAL_FADE, clean.appReveal);

        AppLaunchSettings fallback = AppLaunchSettings.fromValues(
                true, "turbo", "bounce", true, "abyss", true, null);
        assertEquals(AppLaunchSettings.SPEED_NORMAL, fallback.speed);
        assertEquals(AppLaunchSettings.EASING_IOS_IN_OUT, fallback.easing);
        assertEquals(AppLaunchSettings.DEPTH_MEDIUM, fallback.bgDepth);
        assertEquals(AppLaunchSettings.REVEAL_ZOOM_FADE, fallback.appReveal);
    }

    @Test
    public void sweepDurationsAreStrictlyIncreasingAcrossSpeeds() {
        long prev = 0L;
        for (String speed : AppLaunchSettings.SPEEDS) {
            AppLaunchSettings s = AppLaunchSettings.fromValues(
                    true, speed, AppLaunchSettings.EASING_IOS_IN_OUT,
                    true, AppLaunchSettings.DEPTH_MEDIUM, true, AppLaunchSettings.REVEAL_ZOOM_FADE);
            long dur = s.sweepDurationMs();
            assertTrue("Expected increasing duration for " + speed + " (" + dur + " > " + prev + ")", dur > prev);
            prev = dur;
        }
        assertEquals(240L, AppLaunchSettings.fromValues(true, AppLaunchSettings.SPEED_VERY_FAST,
                AppLaunchSettings.EASING_IOS_IN_OUT, true, AppLaunchSettings.DEPTH_MEDIUM,
                true, AppLaunchSettings.REVEAL_ZOOM_FADE).sweepDurationMs());
        assertEquals(700L, AppLaunchSettings.fromValues(true, AppLaunchSettings.SPEED_CINEMATIC,
                AppLaunchSettings.EASING_IOS_IN_OUT, true, AppLaunchSettings.DEPTH_MEDIUM,
                true, AppLaunchSettings.REVEAL_ZOOM_FADE).sweepDurationMs());
    }

    @Test
    public void curveControlPointsCoverAllFiveEasings() {
        assertCurve(AppLaunchSettings.EASING_IOS_IN_OUT, new float[] {0.42f, 0.0f, 0.22f, 1.0f});
        assertCurve(AppLaunchSettings.EASING_STRONG_IN, new float[] {0.58f, 0.0f, 0.20f, 1.0f});
        assertCurve(AppLaunchSettings.EASING_BALANCED_IN_OUT, new float[] {0.45f, 0.0f, 0.55f, 1.0f});
        assertCurve(AppLaunchSettings.EASING_EASE_OUT, new float[] {0.18f, 0.0f, 0.06f, 1.0f});
        assertCurve(AppLaunchSettings.EASING_LINEAR, new float[] {0.0f, 0.0f, 1.0f, 1.0f});
    }

    @Test
    public void backgroundDepthScalesAndDimsMonotonically() {
        float prevScale = -1f;
        float prevDim = -1f;
        for (String depth : AppLaunchSettings.BG_DEPTHS) {
            AppLaunchSettings s = AppLaunchSettings.fromValues(
                    true, AppLaunchSettings.SPEED_NORMAL, AppLaunchSettings.EASING_IOS_IN_OUT,
                    true, depth, true, AppLaunchSettings.REVEAL_ZOOM_FADE);
            assertTrue(s.bgScaleDelta() > prevScale);
            assertTrue(s.bgDimFactor() > prevDim);
            assertEquals(!AppLaunchSettings.DEPTH_OFF.equals(depth), s.hasBackgroundDepth());
            prevScale = s.bgScaleDelta();
            prevDim = s.bgDimFactor();
        }
    }

    @Test
    public void revealAndSpeedSelectExpectedTransitionAnimResources() {
        AppLaunchSettings zoomFast = AppLaunchSettings.fromValues(
                true, AppLaunchSettings.SPEED_VERY_FAST, AppLaunchSettings.EASING_IOS_IN_OUT,
                true, AppLaunchSettings.DEPTH_MEDIUM, true, AppLaunchSettings.REVEAL_ZOOM_FADE);
        assertEquals(R.anim.launch_after_curtain_enter_fast, zoomFast.enterAnimResId());
        assertEquals(R.anim.launch_after_curtain_exit_fast, zoomFast.exitAnimResId());

        AppLaunchSettings zoomNormal = AppLaunchSettings.defaults();
        assertEquals(R.anim.launch_after_curtain_enter, zoomNormal.enterAnimResId());
        assertEquals(R.anim.launch_after_curtain_exit, zoomNormal.exitAnimResId());

        AppLaunchSettings zoomSlow = AppLaunchSettings.fromValues(
                true, AppLaunchSettings.SPEED_CINEMATIC, AppLaunchSettings.EASING_IOS_IN_OUT,
                true, AppLaunchSettings.DEPTH_MEDIUM, true, AppLaunchSettings.REVEAL_ZOOM_FADE);
        assertEquals(R.anim.launch_after_curtain_enter_slow, zoomSlow.enterAnimResId());
        assertEquals(R.anim.launch_after_curtain_exit_slow, zoomSlow.exitAnimResId());

        AppLaunchSettings fadeOnly = AppLaunchSettings.fromValues(
                true, AppLaunchSettings.SPEED_SLOW, AppLaunchSettings.EASING_IOS_IN_OUT,
                true, AppLaunchSettings.DEPTH_MEDIUM, true, AppLaunchSettings.REVEAL_FADE);
        assertEquals(R.anim.launch_after_curtain_enter_fade, fadeOnly.enterAnimResId());
        assertEquals(R.anim.launch_after_curtain_exit_fade, fadeOnly.exitAnimResId());
        assertEquals(260L, fadeOnly.revealDurationMs());

        AppLaunchSettings instant = AppLaunchSettings.fromValues(
                true, AppLaunchSettings.SPEED_SLOW, AppLaunchSettings.EASING_IOS_IN_OUT,
                true, AppLaunchSettings.DEPTH_MEDIUM, true, AppLaunchSettings.REVEAL_INSTANT);
        assertEquals(0, instant.enterAnimResId());
        assertEquals(0, instant.exitAnimResId());
        assertEquals(0L, instant.revealDurationMs());
    }

    @Test
    public void stepOptionWrapsForwardAndBackwardCyclically() {
        String[] speeds = AppLaunchSettings.SPEEDS;
        assertEquals(AppLaunchSettings.SPEED_FAST,
                AppLaunchSettings.stepOption(speeds, AppLaunchSettings.SPEED_VERY_FAST, 1));
        assertEquals(AppLaunchSettings.SPEED_CINEMATIC,
                AppLaunchSettings.stepOption(speeds, AppLaunchSettings.SPEED_VERY_FAST, -1));
        assertEquals(AppLaunchSettings.SPEED_VERY_FAST,
                AppLaunchSettings.stepOption(speeds, AppLaunchSettings.SPEED_CINEMATIC, 1));
        assertEquals(AppLaunchSettings.SPEED_FAST,
                AppLaunchSettings.stepOption(speeds, "unknown", 1));
    }

    private static void assertCurve(String easing, float[] expected) {
        AppLaunchSettings s = AppLaunchSettings.fromValues(
                true, AppLaunchSettings.SPEED_NORMAL, easing,
                true, AppLaunchSettings.DEPTH_MEDIUM, true, AppLaunchSettings.REVEAL_ZOOM_FADE);
        assertArrayEquals(expected, s.curveControlPoints(), 0.0001f);
    }

    @Test
    public void closeEnabledCanToggleIndependentlyOfOpenEnabled() {
        AppLaunchSettings closeOnly = AppLaunchSettings.fromValues(
                false, true, AppLaunchSettings.SPEED_FAST, AppLaunchSettings.EASING_IOS_IN_OUT,
                true, AppLaunchSettings.DEPTH_MEDIUM, true, AppLaunchSettings.REVEAL_ZOOM_FADE);
        assertFalse(closeOnly.enabled);
        assertTrue(closeOnly.closeEnabled);

        AppLaunchSettings openOnly = AppLaunchSettings.fromValues(
                true, false, AppLaunchSettings.SPEED_FAST, AppLaunchSettings.EASING_IOS_IN_OUT,
                true, AppLaunchSettings.DEPTH_MEDIUM, true, AppLaunchSettings.REVEAL_ZOOM_FADE);
        assertTrue(openOnly.enabled);
        assertFalse(openOnly.closeEnabled);
    }
}

