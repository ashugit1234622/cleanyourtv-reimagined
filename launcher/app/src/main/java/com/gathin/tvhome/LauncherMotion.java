package com.gathin.tvhome;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared pure motion math and lightweight ViewPropertyAnimator helpers for Home transitions,
 * hero swaps, parallax bounds clamping, and popup row staggers.
 */
public final class LauncherMotion {

    private static final DecelerateInterpolator DECEL_OUT = new DecelerateInterpolator(1.6f);
    private static final OvershootInterpolator PICKUP_OVERSHOOT = new OvershootInterpolator(1.2f);
    private static final long PRESS_DIP_MS = 55L;
    private static final long PRESS_RELEASE_MS = 110L;
    /** Duration of the continuous iOS-style ease-in tile-to-fullscreen launch sweep. */
    public static final long CURTAIN_SWEEP_MS = 410L;
    /** Marks an internal handoff whose curtain sweep already expanded in the previous overlay. */
    public static final String EXTRA_SKIP_LAUNCH_CURTAIN = "com.gathin.tvhome.extra.SKIP_LAUNCH_CURTAIN";
    /** Extra sent by HomeKeyService when short-pressing Home while an external app is foregrounded. */
    public static final String EXTRA_HOME_CLOSE_RETURN = "com.gathin.tvhome.extra.HOME_CLOSE_RETURN";
    /** Foreground package captured right before HomeKeyService navigated Home. */
    public static final String EXTRA_CLOSE_RETURN_PACKAGE = "com.gathin.tvhome.extra.CLOSE_RETURN_PACKAGE";
    private static final float CURTAIN_CORNER_HEIGHT_FRACTION = 0.155f;

    private LauncherMotion() {
    }

    /**
     * Computes visual travel direction for hero swaps between {@code prevPos} and {@code nextPos}.
     * Returns -1 when moving visually left, +1 when moving visually right, or 0 when indeterminate/unchanged.
     */
    public static int heroSlideDirection(int prevPos, int nextPos, boolean rtl) {
        if (prevPos < 0 || nextPos < 0 || prevPos == nextPos) return 0;
        int logical = nextPos > prevPos ? 1 : -1;
        return rtl ? -logical : logical;
    }

    /**
     * Maps a tile column index in [0, columns - 1] to a normalized [-1, +1] horizontal coordinate.
     * In RTL grids, column 0 sits on the visual right (+1).
     */
    public static float parallaxFraction(int column, int columns, boolean rtl) {
        if (columns <= 1 || column < 0) return 0f;
        int clamped = Math.min(column, columns - 1);
        float center = (columns - 1) * 0.5f;
        float raw = (clamped - center) / center;
        if (raw < -1f) raw = -1f;
        if (raw > 1f) raw = 1f;
        float value = rtl ? -raw : raw;
        return Math.abs(value) < 0.0001f ? 0f : value;
    }

    /**
     * Computes maximum horizontal shift in pixels guaranteed never to expose view edges
     * for a centered scale transform {@code overscanScale}.
     */
    public static float safeParallaxShiftPx(int viewSizePx, float overscanScale, float requestedMaxShiftPx) {
        if (viewSizePx <= 0 || overscanScale <= 1f || requestedMaxShiftPx <= 0f) return 0f;
        float safeBudget = viewSizePx * (overscanScale - 1f) * 0.425f;
        return Math.min(requestedMaxShiftPx, safeBudget);
    }

    /**
     * Computes Ken Burns pan amplitude at normalized progress {@code linearFraction} in [0, 1],
     * coupled to scale growth so pan is zero at scale 1.0 and always strictly inside overscan margin.
     */
    public static float showcasePanProgressPx(float linearFraction, int viewSizePx, float targetScale, float requestedMaxPanPx) {
        float t = Math.max(0f, Math.min(1f, linearFraction));
        float maxSafe = safeParallaxShiftPx(viewSizePx, targetScale, requestedMaxPanPx);
        return maxSafe * t;
    }

    /** Computes capped per-row stagger delay for short glass dropdowns and panels. */
    public static long staggerDelayMs(int index, int stepMs, int maxStaggeredItems, boolean reducedMotion) {
        if (reducedMotion || index <= 0 || stepMs <= 0 || maxStaggeredItems <= 0) return 0L;
        return (long) Math.min(index, maxStaggeredItems) * stepMs;
    }

    /** Precomputed transform values to expand a fullscreen launch overlay from a card's bounds to full screen. */
    public static final class LaunchCurtainPose {
        public final float tx;
        public final float ty;
        public final float startScaleX;
        public final float startScaleY;
        public final float fullScaleX;
        public final float fullScaleY;

        LaunchCurtainPose(float tx, float ty, float startScaleX, float startScaleY,
                          float fullScaleX, float fullScaleY) {
            this.tx = tx;
            this.ty = ty;
            this.startScaleX = startScaleX;
            this.startScaleY = startScaleY;
            this.fullScaleX = fullScaleX;
            this.fullScaleY = fullScaleY;
        }
    }

    /** Corner radius for the fullscreen launch overlay so scaled-down starts match TV tile roundness. */
    public static float curtainCornerRadiusPx(int rootHeightPx) {
        if (rootHeightPx <= 0) return 0f;
        return rootHeightPx * CURTAIN_CORNER_HEIGHT_FRACTION;
    }

    /**
     * Dynamically un-rounds the launch overlay corners as the eased sweep reaches fullscreen so
     * the window settles flush at exact 1.0x display size with zero offscreen corner gaps.
     */
    public static float curtainCornerRadiusAt(float startCornerRadiusPx, float easedProgress) {
        if (startCornerRadiusPx <= 0f) return 0f;
        float u = Math.max(0f, Math.min(1f, easedProgress));
        if (u >= 1f) return 0f;
        return startCornerRadiusPx * (float) Math.pow(1f - u, 1.35f);
    }

    /**
     * Smoothstepped alpha for the captured card artwork inside the expanding launch window: holds
     * briefly during initial lift, dissolves organically into pure black across the mid-sweep.
     */
    public static float curtainArtAlphaAt(float easedProgress) {
        float u = Math.max(0f, Math.min(1f, easedProgress));
        final float fadeStart = 0.06f;
        final float fadeEnd = 0.72f;
        if (u <= fadeStart) return 1f;
        if (u >= fadeEnd) return 0f;
        float p = (u - fadeStart) / (fadeEnd - fadeStart);
        return 1f - (p * p * (3f - 2f * p));
    }

    /**
     * Computes initial tile-matched transforms and flush fullscreen target scales for the
     * continuous iOS-style app-open expansion.
     */
    public static LaunchCurtainPose computeCurtainPose(
            int rootW, int rootH,
            float sourceCenterX, float sourceCenterY,
            float sourceW, float sourceH) {
        int safeW = Math.max(1, rootW);
        int safeH = Math.max(1, rootH);
        float safeSrcW = Float.isFinite(sourceW) && sourceW > 0f ? sourceW : safeW * 0.12f;
        float safeSrcH = Float.isFinite(sourceH) && sourceH > 0f ? sourceH : safeH * 0.12f;
        float cx = Float.isFinite(sourceCenterX) ? sourceCenterX : safeW * 0.5f;
        float cy = Float.isFinite(sourceCenterY) ? sourceCenterY : safeH * 0.5f;

        float startScaleX = Math.max(0.02f, Math.min(1f, safeSrcW / safeW));
        float startScaleY = Math.max(0.02f, Math.min(1f, safeSrcH / safeH));

        float tx = cx - safeW * 0.5f;
        float ty = cy - safeH * 0.5f;
        return new LaunchCurtainPose(tx, ty, startScaleX, startScaleY, 1f, 1f);
    }

    /** Fluid ease-in / pillowy ease-out iOS-like curve for the tile-to-fullscreen launch sweep. */
    public static PathInterpolator curtainSweepEase() {
        return AppLaunchSettings.defaults().createInterpolator();
    }

    /** Visual pixel extent of a view dimension adjusted for its current scale transform. */
    public static int scaledDimensionPx(int sizePx, float scale) {
        if (sizePx <= 0) return 0;
        float safeScale = Float.isFinite(scale) && scale > 0f ? scale : 1f;
        return Math.max(1, Math.round(sizePx * safeScale));
    }

    /**
     * Captures the visual screen-space rectangle of {@code view} as {@code {cx, cy, width, height}}
     * prior to dismissing a popup or dialog. Returns {@code null} when unavailable.
     */
    public static float[] captureScreenBounds(View view) {
        if (view == null || !view.isAttachedToWindow()) return null;
        int w = scaledDimensionPx(view.getWidth(), view.getScaleX());
        int h = scaledDimensionPx(view.getHeight(), view.getScaleY());
        if (w <= 0 || h <= 0) return null;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return new float[] { loc[0] + w * 0.5f, loc[1] + h * 0.5f, w, h };
    }

    /** Translates captured screen-space {@code {cx, cy, w, h}} bounds into container-local coordinates. */
    public static float[] screenToLocalBounds(float[] screenBounds, int containerScreenX, int containerScreenY) {
        if (screenBounds == null || screenBounds.length < 4) return null;
        float cx = screenBounds[0];
        float cy = screenBounds[1];
        float w = screenBounds[2];
        float h = screenBounds[3];
        if (!Float.isFinite(cx) || !Float.isFinite(cy) || !Float.isFinite(w) || !Float.isFinite(h)
                || w <= 0f || h <= 0f) {
            return null;
        }
        return new float[] { cx - containerScreenX, cy - containerScreenY, w, h };
    }

    /** View overload of {@link #screenToLocalBounds(float[], int, int)}. */
    public static float[] screenToLocalBounds(float[] screenBounds, View container) {
        if (screenBounds == null || container == null) return null;
        int[] containerLoc = new int[2];
        container.getLocationOnScreen(containerLoc);
        return screenToLocalBounds(screenBounds, containerLoc[0], containerLoc[1]);
    }

    /** Centered subtle expansion origin when an interactive launch has no attached source view or bounds. */
    public static float[] computeFallbackBounds(int rootW, int rootH) {
        if (rootW <= 0 || rootH <= 0) return null;
        return new float[] {
                rootW * 0.5f,
                rootH * 0.5f,
                Math.max(1f, rootW * 0.14f),
                Math.max(1f, rootH * 0.12f)
        };
    }

    /**
     * Compact dock-band fallback target {@code {cx, cy, width, height}} used when a closed app
     * has no matching visible tile or anchor pill on Home (e.g. unpinned app from Recents).
     */
    public static float[] computeCloseFallbackBounds(int rootW, int rootH) {
        if (rootW <= 0 || rootH <= 0) return null;
        return new float[] {
                rootW * 0.5f,
                rootH * 0.74f,
                Math.max(96f, rootW * 0.115f),
                Math.max(54f, rootH * 0.115f)
        };
    }

    /**
     * Evaluates whether a closing app animation can play on return to Home, excluding translucent
     * resumes, wakes/screensavers, internal launcher screens (e.g. Settings/Clock), and paused prefs.
     */
    public static boolean shouldPlayCloseCurtain(boolean stoppedBeforeResume,
                                                 boolean hasCloseTarget,
                                                 boolean closeAnimEnabled,
                                                 boolean animations,
                                                 boolean reducedMotion,
                                                 boolean suppressWakeOrDream,
                                                 boolean introPlaying,
                                                 boolean popupOpen,
                                                 boolean showcaseEngaged,
                                                 boolean internalReturn) {
        return stoppedBeforeResume
                && hasCloseTarget
                && closeAnimEnabled
                && animations
                && !reducedMotion
                && !suppressWakeOrDream
                && !introPlaying
                && !popupOpen
                && !showcaseEngaged
                && !internalReturn;
    }

    /**
     * Additional wait (ms) before starting the close-curtain shrink after {@code MainActivity}
     * draws Frame 1 while the fullscreen black close hold is active. Home-key returns are already
     * masked by {@link HomeKeyService}'s cover ({@code 0 ms}), whereas Back-returns allow the
     * exiting external app's WM fade transition (~240 ms) to clear into black before the shrink
     * curve accelerates.
     */
    public static long closeScheduleSettleDelayMs(boolean homeCloseReturn) {
        return homeCloseReturn ? 0L : 240L;
    }

    /** Identifies screensaver/dream window packages where Home returns must never play close shrink. */
    public static boolean isDreamWindowPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String lower = pkg.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("com.neilturner.aerialviews")
                || lower.startsWith("com.android.dreams")
                || lower.contains("screensaver");
    }

    private static final AtomicBoolean internalTrackerInstalled = new AtomicBoolean(false);
    private static final Set<String> activeInternalUis = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean internalOpaqueReturnPending = false;

    static void ensureInternalUiTracker(Application app) {
        if (app == null || !internalTrackerInstalled.compareAndSet(false, true)) return;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

            @Override
            public void onActivityStarted(Activity activity) {}

            @Override
            public void onActivityResumed(Activity activity) {
                if (isOverlayOrSubScreenActivity(activity)) {
                    activeInternalUis.add(activity.getClass().getName());
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {
                activeInternalUis.remove(activity.getClass().getName());
                if (isOpaqueSubScreenActivity(activity)) {
                    internalOpaqueReturnPending = true;
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
                activeInternalUis.remove(activity.getClass().getName());
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {
                activeInternalUis.remove(activity.getClass().getName());
            }
        });
    }

    private static boolean isOpaqueSubScreenActivity(Activity activity) {
        return activity instanceof LauncherSettingsActivity
                || activity instanceof HomeSettingsActivity
                || activity instanceof AppLaunchSettingsActivity
                || activity instanceof IntroSettingsActivity
                || activity instanceof ClockActivity;
    }

    private static boolean isOverlayOrSubScreenActivity(Activity activity) {
        return isOpaqueSubScreenActivity(activity)
                || activity instanceof QuickSettingsActivity
                || activity instanceof RecentsActivity;
    }

    static boolean hasActiveInternalUi() {
        return !activeInternalUis.isEmpty();
    }

    static void clearInternalOpaqueReturn() {
        internalOpaqueReturnPending = false;
    }

    static boolean consumeInternalOpaqueReturn() {
        boolean v = internalOpaqueReturnPending;
        internalOpaqueReturnPending = false;
        return v;
    }

    /** Applies the seamless black-to-app reveal transition after the local curtain fills the screen. */
    public static void applyCurtainOpenTransition(Activity activity, boolean animations, boolean reducedMotion) {
        if (activity == null) return;
        applyCurtainOpenTransition(activity, animations, reducedMotion, AppLaunchSettings.load(activity));
    }

    /** Applies the configured black-to-app reveal transition from {@code settings}. */
    @SuppressWarnings("deprecation")
    public static void applyCurtainOpenTransition(Activity activity, boolean animations, boolean reducedMotion,
                                                  AppLaunchSettings settings) {
        if (activity != null && animations && !reducedMotion) {
            AppLaunchSettings resolved = settings != null ? settings : AppLaunchSettings.defaults();
            activity.overridePendingTransition(resolved.enterAnimResId(), resolved.exitAnimResId());
        }
    }

    @SuppressWarnings("deprecation")
    public static void applySubScreenOpenTransition(Activity activity, boolean animations, boolean reducedMotion) {
        if (activity != null && animations && !reducedMotion) {
            activity.overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
        }
    }

    @SuppressWarnings("deprecation")
    public static void applySubScreenCloseTransition(Activity activity, boolean animations, boolean reducedMotion) {
        if (activity != null && animations && !reducedMotion) {
            activity.overridePendingTransition(R.anim.sub_close_enter, R.anim.sub_close_exit);
        }
    }

    /** Convenience overload for callers outside {@code com.gathin.tvhome} where {@link Config} is package-private. */
    public static void applySubScreenCloseTransition(Activity activity) {
        if (activity == null) return;
        Config cfg = Config.load(activity);
        applySubScreenCloseTransition(activity, cfg.animations, cfg.reducedMotion);
    }

    /** Suppresses Android's default window transition for translucent overlays such as Quick Settings. */
    @SuppressWarnings("deprecation")
    public static void clearWindowTransition(Activity activity) {
        if (activity != null) {
            activity.overridePendingTransition(0, 0);
        }
    }

    /**
     * Runs Home's return-to-rest depth settle only when Home had actually stopped behind an opaque
     * screen, so closing a translucent overlay (such as Quick Settings) leaves Home still.
     */
    public static boolean shouldPlayReturnSettle(boolean stoppedBeforeResume, boolean rebuildingTiles,
                                                 boolean animations, boolean reducedMotion,
                                                 boolean introPlaying, boolean popupOpen,
                                                 boolean showcaseEngaged) {
        return stoppedBeforeResume
                && !rebuildingTiles
                && animations
                && !reducedMotion
                && !introPlaying
                && !popupOpen
                && !showcaseEngaged;
    }

    /** Tactile inward press dip when OK/Enter goes down on a tile card. */
    public static void pressDip(View view, float baseScale, boolean animations, boolean reducedMotion) {
        if (!animations || reducedMotion || view == null) return;
        float dipScale = baseScale * 0.96f;
        view.animate().scaleX(dipScale).scaleY(dipScale)
                .setDuration(PRESS_DIP_MS)
                .setInterpolator(DECEL_OUT)
                .start();
    }

    /** Smoothly returns a pressed card back to its resting focused scale. */
    public static void restoreFocusScale(View view, float targetScale, boolean animations, boolean reducedMotion) {
        if (view == null) return;
        if (!animations) {
            view.setScaleX(1f);
            view.setScaleY(1f);
            return;
        }
        view.animate().scaleX(targetScale).scaleY(targetScale)
                .setDuration(reducedMotion ? Motion.REDUCED_MS : PRESS_RELEASE_MS)
                .setInterpolator(DECEL_OUT)
                .start();
    }

    /** Crisp grab pop + slight elevation lift when picking up a tile to reorder. */
    public static void pulseReorderPickup(View card, float focusScale, float density, boolean animations, boolean reducedMotion) {
        if (card == null) return;
        if (!animations) return;
        card.setTranslationZ(14f * density);
        if (reducedMotion) return;
        float peakScale = focusScale * 1.04f;
        card.animate().scaleX(peakScale).scaleY(peakScale)
                .setDuration(90L)
                .setInterpolator(DECEL_OUT)
                .withEndAction(() -> {
                    if (!card.isAttachedToWindow()) return;
                    card.animate().scaleX(focusScale).scaleY(focusScale)
                            .setDuration(130L)
                            .setInterpolator(PICKUP_OVERSHOOT)
                            .start();
                })
                .start();
    }

    /** Restores tile elevation after dropping a carried tile. */
    public static void clearReorderElevation(View card, boolean advancedEffects, boolean focused, float density) {
        if (card == null) return;
        card.setTranslationZ(focused && advancedEffects ? 8f * density : 0f);
    }

    /**
     * Staggers visible children of a glass popup menu container in opacity and vertical settle.
     * Note: relies on {@link QuickSettingsFocusIndicator} measuring un-transformed layout coordinates
     * when inside the same glass panel.
     */
    public static void staggerRows(ViewGroup rows, boolean animations, boolean reducedMotion, float density) {
        if (rows == null) return;
        int visibleIndex = 0;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            child.animate().cancel();
            if (!animations) {
                child.setAlpha(1f);
                child.setTranslationY(0f);
                visibleIndex++;
                continue;
            }
            if (reducedMotion) {
                child.setTranslationY(0f);
                child.setAlpha(0f);
                child.animate().alpha(1f).setDuration(Motion.REDUCED_MS).setInterpolator(DECEL_OUT).start();
                visibleIndex++;
                continue;
            }
            long delay = staggerDelayMs(visibleIndex, 22, 7, false);
            child.setAlpha(0f);
            child.setTranslationY(5f * density);
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(delay)
                    .setDuration(155L)
                    .setInterpolator(DECEL_OUT)
                    .start();
            visibleIndex++;
        }
    }
}

