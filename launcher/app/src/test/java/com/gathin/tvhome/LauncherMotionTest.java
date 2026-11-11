package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LauncherMotionTest {

    @Test
    public void heroSlideDirectionFollowsVisualDirection() {
        assertEquals(0, LauncherMotion.heroSlideDirection(-1, 0, false));
        assertEquals(0, LauncherMotion.heroSlideDirection(2, 2, false));
        assertEquals(1, LauncherMotion.heroSlideDirection(0, 1, false));
        assertEquals(-1, LauncherMotion.heroSlideDirection(3, 2, false));
        // Mirrored RTL dock reverses visual travel direction
        assertEquals(-1, LauncherMotion.heroSlideDirection(0, 1, true));
        assertEquals(1, LauncherMotion.heroSlideDirection(3, 2, true));
    }

    @Test
    public void parallaxFractionSymmetryAndRtl() {
        assertEquals(0f, LauncherMotion.parallaxFraction(0, 1, false), 0.0001f);
        assertEquals(-1f, LauncherMotion.parallaxFraction(0, 4, false), 0.0001f);
        assertEquals(-0.3333f, LauncherMotion.parallaxFraction(1, 4, false), 0.001f);
        assertEquals(0.3333f, LauncherMotion.parallaxFraction(2, 4, false), 0.001f);
        assertEquals(1f, LauncherMotion.parallaxFraction(3, 4, false), 0.0001f);
        // Odd column count hits exact center at middle index
        assertEquals(0f, LauncherMotion.parallaxFraction(2, 5, false), 0.0001f);
        // RTL flips side sign
        assertEquals(1f, LauncherMotion.parallaxFraction(0, 4, true), 0.0001f);
        assertEquals(-1f, LauncherMotion.parallaxFraction(3, 4, true), 0.0001f);
    }

    @Test
    public void safeParallaxShiftNeverExceedsOverscanHalfMargin() {
        float scale = 1.032f;
        int width = 1920;
        float halfMargin = width * (scale - 1f) * 0.5f;
        // Requesting huge shift must clamp well inside overscan margin
        float clamped = LauncherMotion.safeParallaxShiftPx(width, scale, 120f);
        assertTrue(clamped > 0f && clamped < halfMargin);
        // Zero shift without overscan
        assertEquals(0f, LauncherMotion.safeParallaxShiftPx(width, 1.0f, 30f), 0.0001f);
        // Small request respects request when smaller than overscan margin
        assertEquals(14f, LauncherMotion.safeParallaxShiftPx(width, scale, 14f), 0.0001f);
    }

    @Test
    public void showcasePanZeroAtStartAndSafeAtEnd() {
        float targetScale = 1.045f;
        int width = 1920;
        float halfMargin = width * (targetScale - 1f) * 0.5f;
        assertEquals(0f, LauncherMotion.showcasePanProgressPx(0f, width, targetScale, 24f), 0.0001f);
        float endPan = LauncherMotion.showcasePanProgressPx(1f, width, targetScale, 24f);
        assertTrue(endPan > 0f && endPan < halfMargin);
    }

    @Test
    public void staggerDelayCapsAndRespectsReducedMotion() {
        assertEquals(0L, LauncherMotion.staggerDelayMs(0, 22, 7, false));
        assertEquals(66L, LauncherMotion.staggerDelayMs(3, 22, 7, false));
        assertEquals(154L, LauncherMotion.staggerDelayMs(20, 22, 7, false));
        assertEquals(0L, LauncherMotion.staggerDelayMs(4, 22, 7, true));
    }

    @Test
    public void scaledDimensionPxAppliesTransformAndGuardsEdges() {
        assertEquals(0, LauncherMotion.scaledDimensionPx(0, 1.2f));
        assertEquals(0, LauncherMotion.scaledDimensionPx(-10, 1.2f));
        assertEquals(200, LauncherMotion.scaledDimensionPx(200, 0f));
        assertEquals(200, LauncherMotion.scaledDimensionPx(200, Float.NaN));
        assertEquals(240, LauncherMotion.scaledDimensionPx(200, 1.2f));
        assertEquals(1, LauncherMotion.scaledDimensionPx(1, 0.2f));
    }

    @Test
    public void computeCurtainPoseAlignsTileAndLandsFlushAtFullscreen() {
        int w = 1920, h = 1080;
        // Tile at bottom-left: center (200, 820), visual size 200x112
        LauncherMotion.LaunchCurtainPose pose =
                LauncherMotion.computeCurtainPose(w, h, 200f, 820f, 200f, 112f);
        assertEquals(-760f, pose.tx, 0.01f);
        assertEquals(280f, pose.ty, 0.01f);
        assertEquals(200f / 1920f, pose.startScaleX, 0.0001f);
        assertEquals(112f / 1080f, pose.startScaleY, 0.0001f);
        assertEquals(1f, pose.fullScaleX, 0.0001f);
        assertEquals(1f, pose.fullScaleY, 0.0001f);
    }

    @Test
    public void curtainCornerRadiusAndArtAlphaMorphSmoothlyThroughSweep() {
        float startRadius = LauncherMotion.curtainCornerRadiusPx(1080);
        assertTrue(startRadius > 140f);
        assertEquals(startRadius, LauncherMotion.curtainCornerRadiusAt(startRadius, 0f), 0.001f);
        float midRadius = LauncherMotion.curtainCornerRadiusAt(startRadius, 0.5f);
        assertTrue(midRadius > 0f && midRadius < startRadius);
        assertEquals(0f, LauncherMotion.curtainCornerRadiusAt(startRadius, 1f), 0.0001f);

        // Captured artwork holds briefly on lift, dissolves across middle, hits 0 before fullscreen
        assertEquals(1f, LauncherMotion.curtainArtAlphaAt(0f), 0.0001f);
        assertEquals(1f, LauncherMotion.curtainArtAlphaAt(0.05f), 0.0001f);
        float midAlpha = LauncherMotion.curtainArtAlphaAt(0.4f);
        assertTrue(midAlpha > 0f && midAlpha < 1f);
        assertEquals(0f, LauncherMotion.curtainArtAlphaAt(0.75f), 0.0001f);
        assertEquals(0f, LauncherMotion.curtainArtAlphaAt(1f), 0.0001f);
    }

    @Test
    public void returnSettleSkipsTranslucentOverlayPauseWithoutStop() {
        // Translucent overlays (e.g. Quick Settings) pause Home without stopping it: must not jitter.
        assertFalse(LauncherMotion.shouldPlayReturnSettle(false, false, true, false, false, false, false));
        // Full-screen screen exit (after onStop) plays return settle when eligible.
        assertTrue(LauncherMotion.shouldPlayReturnSettle(true, false, true, false, false, false, false));
        // Suppressors block return settle even after onStop.
        assertFalse(LauncherMotion.shouldPlayReturnSettle(true, true, true, false, false, false, false));
        assertFalse(LauncherMotion.shouldPlayReturnSettle(true, false, false, false, false, false, false));
        assertFalse(LauncherMotion.shouldPlayReturnSettle(true, false, true, true, false, false, false));
        assertFalse(LauncherMotion.shouldPlayReturnSettle(true, false, true, false, true, false, false));
        assertFalse(LauncherMotion.shouldPlayReturnSettle(true, false, true, false, false, true, false));
        assertFalse(LauncherMotion.shouldPlayReturnSettle(true, false, true, false, false, false, true));
    }
}

