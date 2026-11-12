package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

/**
 * Shared iOS-style launch curtain animator for tiles, header pills, popup menu rows, and Quick Settings
 * options.
 *
 * <p>Expands a rounded-corner {@code #FF000000} sheet (plus captured artwork when eligible) from the
 * clicked control's bounds to fullscreen while optionally receding the host content in depth, holds
 * solid black across {@code startActivity} so cold starts never stutter on screen, and tears down
 * transient bitmaps/layers deterministically on completion, stop, pause, or resume.
 */
final class LaunchCurtainController {

    private static final String TAG = "LaunchCurtain";
    private static final long DEAD_LAUNCH_TIMEOUT_MS = 6000L;
    private static final long RESUME_FADE_MS = 140L;
    private static final long TRANSLUCENT_FADE_MS = 180L;

    interface Host {
        boolean animationsEnabled();

        boolean reducedMotionEnabled();

        default boolean isResumed() {
            return true;
        }

        default boolean hasWindowFocus() {
            return true;
        }

        default void onBeforeCurtainExpand(View sourceView, int rootW, int rootH, AppLaunchSettings cfg) {
        }

        default void onCurtainDepthProgress(float easedProgress, AppLaunchSettings cfg) {
        }

        default void onRestoreBackground() {
        }

        default boolean shouldCaptureArtBitmap(View sourceView) {
            return sourceView != null && sourceView.getId() == R.id.card;
        }

        default View fallbackAnchorView() {
            return null;
        }

        default void onTranslucentFinish() {
        }

        default long translucentFinishDelayMs(AppLaunchSettings cfg) {
            return defaultTranslucentFinishDelayMs(cfg);
        }
    }

    static long defaultTranslucentFinishDelayMs(AppLaunchSettings cfg) {
        return translucentOverlayFinishDelayMs(cfg, true);
    }

    static long translucentOverlayFinishDelayMs(AppLaunchSettings cfg, boolean backedByHomeCurtain) {
        long reveal = cfg != null ? cfg.revealDurationMs() : 340L;
        if (backedByHomeCurtain) {
            return Math.max(480L, reveal + 140L);
        }
        // Opened over another app rather than Home: hold the curtain long enough for cold-start
        // settings pages to finish initializing before tearing down the overlay.
        return Math.max(2000L, reveal + 1400L);
    }

    /** Single-container background depth delegate for settings screens and overlay panels. */
    static final class PanelDepthDelegate {
        private final View panel;
        private final boolean hidePanelAtCommit;
        private boolean applied;
        private float preAlpha = 1f;

        PanelDepthDelegate(View panel, boolean hidePanelAtCommit) {
            this.panel = panel;
            this.hidePanelAtCommit = hidePanelAtCommit;
        }

        void prepare(int rootW, int rootH, AppLaunchSettings cfg) {
            restore();
            if (panel == null) return;
            preAlpha = panel.getAlpha();
            if (cfg != null && cfg.hasBackgroundDepth()) {
                applied = true;
                panel.animate().cancel();
                panel.setPivotX(rootW * 0.5f - panel.getX());
                panel.setPivotY(rootH * 0.5f - panel.getY());
                panel.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
        }

        void update(float u, AppLaunchSettings cfg) {
            if (!applied || panel == null || cfg == null) return;
            float bgScale = 1f - cfg.bgScaleDelta() * u;
            panel.setScaleX(bgScale);
            panel.setScaleY(bgScale);
            panel.setAlpha(preAlpha * (1f - cfg.bgDimFactor() * u));
        }

        void commitHide() {
            if (hidePanelAtCommit && panel != null) {
                panel.animate().cancel();
                panel.setVisibility(View.INVISIBLE);
                panel.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        }

        void restore() {
            if (panel == null) {
                applied = false;
                return;
            }
            if (hidePanelAtCommit && panel.getVisibility() != View.VISIBLE) {
                panel.setVisibility(View.VISIBLE);
            }
            if (applied) {
                applied = false;
                panel.animate().cancel();
                panel.setScaleX(1f);
                panel.setScaleY(1f);
                panel.setAlpha(preAlpha);
                panel.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        }
    }

    private final Activity activity;
    private final Handler handler;
    private final View root;
    private final View curtain;
    private final ImageView curtainArt;
    private final Host host;
    private boolean translucentHost;

    private Bitmap bitmap;
    private ValueAnimator animator;
    private float cornerPx;
    private boolean pending;
    private boolean committed;
    private boolean lostFocus;
    private boolean skipNextResumeCleanup;
    private boolean closing;
    private boolean closingWaitingStart;
    private int gen;
    private AppLaunchSettings lastSettings = AppLaunchSettings.defaults();

    private final Runnable clearLaunchPulse = this::settleIfResumed;
    private final Runnable translucentFinishFallback = this::fadeOutTranslucentOverlay;

    LaunchCurtainController(Activity activity, Handler handler, View root,
                            View curtain, ImageView curtainArt, Host host) {
        this.activity = activity;
        this.handler = handler;
        this.root = root;
        this.curtain = curtain;
        this.curtainArt = curtainArt;
        this.host = host;
        if (this.curtainArt != null) {
            this.curtainArt.forceHasOverlappingRendering(false);
        }
        if (this.curtain != null) {
            this.curtain.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w <= 0 || h <= 0) return;
                    if (cornerPx < 0.5f) {
                        outline.setRect(0, 0, w, h);
                    } else {
                        outline.setRoundRect(0, 0, w, h, cornerPx);
                    }
                }
            });
            this.curtain.setClipToOutline(true);
        }
    }

    void setTranslucentHost(boolean translucentHost) {
        this.translucentHost = translucentHost;
    }

    boolean isPending() {
        return pending;
    }

    boolean isCommitted() {
        return committed;
    }

    boolean isBusy() {
        return pending || committed;
    }

    boolean isClosing() {
        return closing;
    }

    boolean isClosingWaitingStart() {
        return closing && closingWaitingStart;
    }

    AppLaunchSettings lastSettings() {
        return lastSettings;
    }

    void launch(View sourceView, float[] relBounds, AppAdapter.LaunchCommit commit) {
        launch(sourceView, relBounds, false, commit);
    }

    void launchFromScreenBounds(float[] screenBounds, boolean allowFallbackOrigin,
                                AppAdapter.LaunchCommit commit) {
        float[] relBounds = LauncherMotion.screenToLocalBounds(screenBounds, root);
        launch(null, relBounds, allowFallbackOrigin, commit);
    }

    void launch(View sourceView, float[] relBounds, boolean allowFallbackOrigin,
                AppAdapter.LaunchCommit commit) {
        AppLaunchSettings launchCfg = AppLaunchSettings.load(activity);
        lastSettings = launchCfg;
        if (!host.animationsEnabled() || host.reducedMotionEnabled() || !launchCfg.enabled
                || curtain == null || root == null || root.getWidth() <= 0 || root.getHeight() <= 0) {
            commit.run(false);
            return;
        }
        if (sourceView != null && !sourceView.isAttachedToWindow()) {
            commit.run(false);
            return;
        }
        if (closing) {
            clearImmediate();
        }
        if (pending || committed) return;

        final int rootW = root.getWidth();
        final int rootH = root.getHeight();
        View effectiveSource = sourceView;
        float[] resolvedRel = relBounds;
        if (effectiveSource == null && resolvedRel == null && allowFallbackOrigin) {
            View fallback = host.fallbackAnchorView();
            if (fallback != null && fallback.isAttachedToWindow()
                    && fallback.getVisibility() == View.VISIBLE
                    && fallback.getWidth() > 0 && fallback.getHeight() > 0) {
                effectiveSource = fallback;
            } else {
                resolvedRel = LauncherMotion.computeFallbackBounds(rootW, rootH);
            }
        }

        final float cx;
        final float cy;
        final float srcW;
        final float srcH;
        if (effectiveSource != null) {
            int[] srcLoc = new int[2];
            int[] rootLoc = new int[2];
            effectiveSource.getLocationInWindow(srcLoc);
            root.getLocationInWindow(rootLoc);
            srcW = LauncherMotion.scaledDimensionPx(effectiveSource.getWidth(), effectiveSource.getScaleX());
            srcH = LauncherMotion.scaledDimensionPx(effectiveSource.getHeight(), effectiveSource.getScaleY());
            cx = (srcLoc[0] - rootLoc[0]) + srcW * 0.5f;
            cy = (srcLoc[1] - rootLoc[1]) + srcH * 0.5f;
        } else if (resolvedRel != null && resolvedRel.length >= 4) {
            cx = resolvedRel[0];
            cy = resolvedRel[1];
            srcW = resolvedRel[2];
            srcH = resolvedRel[3];
        } else {
            commit.run(false);
            return;
        }
        if (srcW <= 0f || srcH <= 0f) {
            commit.run(false);
            return;
        }

        final LauncherMotion.LaunchCurtainPose pose = LauncherMotion.computeCurtainPose(
                rootW, rootH, cx, cy, srcW, srcH);
        final float startCornerPx = launchCfg.roundedCorners
                ? LauncherMotion.curtainCornerRadiusPx(rootH) : 0f;

        cancelAnimator();
        final Bitmap cardBmp = (launchCfg.cardMorph && host.shouldCaptureArtBitmap(effectiveSource))
                ? captureCardBitmap(effectiveSource) : null;
        replaceBitmap(cardBmp);

        final int animGen = ++gen;
        pending = true;
        committed = false;
        lostFocus = false;
        skipNextResumeCleanup = false;
        handler.removeCallbacks(clearLaunchPulse);
        handler.removeCallbacks(translucentFinishFallback);

        host.onBeforeCurtainExpand(effectiveSource, rootW, rootH, launchCfg);

        curtain.animate().setListener(null).cancel();
        curtain.setLayerType(View.LAYER_TYPE_NONE, null);
        cornerPx = startCornerPx;
        curtain.invalidateOutline();
        curtain.setPivotX(rootW * 0.5f);
        curtain.setPivotY(rootH * 0.5f);
        curtain.setTranslationX(pose.tx);
        curtain.setTranslationY(pose.ty);
        curtain.setScaleX(pose.startScaleX);
        curtain.setScaleY(pose.startScaleY);

        final boolean hasArt = cardBmp != null && curtainArt != null;
        if (hasArt) {
            curtain.setAlpha(1f);
            curtainArt.setImageBitmap(cardBmp);
            curtainArt.setPivotX(rootW * 0.5f);
            curtainArt.setPivotY(rootH * 0.5f);
            curtainArt.setScaleX(1f);
            curtainArt.setScaleY(1f);
            curtainArt.setAlpha(1f);
            curtainArt.setVisibility(View.VISIBLE);
        } else {
            curtain.setAlpha(0f);
            if (curtainArt != null) {
                curtainArt.setImageDrawable(null);
                curtainArt.setVisibility(View.INVISIBLE);
            }
        }
        curtain.setVisibility(View.VISIBLE);

        ValueAnimator valueAnimator = ValueAnimator.ofFloat(0f, 1f);
        valueAnimator.setDuration(launchCfg.sweepDurationMs());
        valueAnimator.setInterpolator(launchCfg.createInterpolator());
        valueAnimator.addUpdateListener(a -> {
            if (animGen != gen || !pending || curtain == null) return;
            float u = (float) a.getAnimatedValue();
            applyFrame(u, pose, startCornerPx, hasArt, launchCfg);
        });
        valueAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator == animation) {
                    animator = null;
                }
                if (cancelled || animGen != gen || !pending
                        || activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                applyFrame(1f, pose, startCornerPx, hasArt, launchCfg);
                disposeArt();
                commitAfterExpand(animGen, commit);
            }
        });
        animator = valueAnimator;
        valueAnimator.start();
    }

    /**
     * Holds the curtain solid opaque black across an immediate handoff between two launcher activities
     * (such as Quick Settings handing off Launcher settings through MainActivity), skipping the transient
     * {@code onResume} cleanup that fires before the target activity pauses this host.
     */
    void holdForHandoff(AppLaunchSettings launchCfg) {
        activateSolidCurtain(launchCfg, true, false);
    }

    /**
     * Holds the host curtain solid opaque black while a translucent overlay above it (Quick Settings)
     * launches another window. Unlike {@link #holdForHandoff}, the host is already paused and unfocused,
     * so returning later via Back/Home clears the curtain normally on {@code onResume} / focus return.
     */
    void holdForPausedOverlayLaunch(AppLaunchSettings launchCfg) {
        activateSolidCurtain(launchCfg, false, true);
    }

    private void activateSolidCurtain(AppLaunchSettings launchCfg,
                                      boolean skipNextResume, boolean markLostFocus) {
        lastSettings = launchCfg != null ? launchCfg : AppLaunchSettings.load(activity);
        if (!host.animationsEnabled() || host.reducedMotionEnabled() || !lastSettings.enabled
                || curtain == null) {
            return;
        }
        gen++;
        cancelAnimator();
        disposeArt();
        host.onRestoreBackground();
        handler.removeCallbacks(clearLaunchPulse);
        handler.removeCallbacks(translucentFinishFallback);

        pending = false;
        committed = true;
        lostFocus = markLostFocus;
        skipNextResumeCleanup = skipNextResume;

        curtain.animate().setListener(null).cancel();
        curtain.setLayerType(View.LAYER_TYPE_NONE, null);
        curtain.setTranslationX(0f);
        curtain.setTranslationY(0f);
        curtain.setScaleX(1f);
        curtain.setScaleY(1f);
        cornerPx = 0f;
        curtain.invalidateOutline();
        curtain.setAlpha(1f);
        curtain.setVisibility(View.VISIBLE);

        handler.postDelayed(clearLaunchPulse, DEAD_LAUNCH_TIMEOUT_MS);
    }

    /** Handles D-pad / Back key suppression during an active curtain sweep or handoff. */
    boolean handleDispatchKeyEvent(KeyEvent event, boolean isOkKey) {
        if (event == null || !isBusy()) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                host.onRestoreBackground();
                fadeOutForResume();
                if (translucentHost) {
                    host.onTranslucentFinish();
                }
            }
            return true;
        }
        return code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || isOkKey;
    }

    void onResume() {
        if (skipNextResumeCleanup) {
            skipNextResumeCleanup = false;
            return;
        }
        handler.removeCallbacks(translucentFinishFallback);
        host.onRestoreBackground();
        if (pending) {
            clearImmediate();
        } else if (committed) {
            fadeOutForResume();
        } else if (curtain != null && curtain.getVisibility() == View.VISIBLE) {
            clearImmediate();
        }
    }

    void onPause() {
        handler.removeCallbacks(clearLaunchPulse);
        if (translucentHost && committed) {
            lostFocus = true;
            long delayMs = Math.max(480L, host.translucentFinishDelayMs(lastSettings));
            handler.removeCallbacks(translucentFinishFallback);
            handler.postDelayed(translucentFinishFallback, delayMs);
            return;
        }
        host.onRestoreBackground();
        if (committed) {
            lostFocus = true;
        } else {
            clearImmediate();
        }
    }

    void onStop() {
        clearImmediate();
    }

    void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) {
            if (host.isResumed() && lostFocus && isBusy() && !skipNextResumeCleanup) {
                host.onRestoreBackground();
                fadeOutForResume();
            }
        } else {
            handler.removeCallbacks(clearLaunchPulse);
            if (committed) {
                lostFocus = true;
            }
        }
    }

    void destroy() {
        host.onRestoreBackground();
        clearImmediate();
    }

    void resetAll() {
        host.onRestoreBackground();
        clearImmediate();
    }

    void fadeOutForResume() {
        gen++;
        pending = false;
        committed = false;
        lostFocus = false;
        skipNextResumeCleanup = false;
        handler.removeCallbacks(clearLaunchPulse);
        handler.removeCallbacks(translucentFinishFallback);
        cancelAnimator();
        disposeArt();
        if (curtain == null || curtain.getVisibility() != View.VISIBLE) return;
        if (!host.animationsEnabled() || host.reducedMotionEnabled()) {
            clearImmediate();
            return;
        }
        final int animGen = gen;
        curtain.animate().setListener(null).cancel();
        curtain.animate()
                .alpha(0f)
                .setDuration(RESUME_FADE_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> {
                    if (animGen == gen) clearImmediate();
                })
                .start();
    }

    void clearImmediate() {
        boolean wasClosing = closing;
        gen++;
        pending = false;
        committed = false;
        lostFocus = false;
        skipNextResumeCleanup = false;
        closing = false;
        closingWaitingStart = false;
        handler.removeCallbacks(clearLaunchPulse);
        handler.removeCallbacks(translucentFinishFallback);
        cancelAnimator();
        disposeArt();
        if (curtain != null) {
            curtain.animate().setListener(null).cancel();
            curtain.setVisibility(View.INVISIBLE);
            curtain.setAlpha(0f);
            curtain.setTranslationX(0f);
            curtain.setTranslationY(0f);
            curtain.setScaleX(1f);
            curtain.setScaleY(1f);
            cornerPx = 0f;
            curtain.invalidateOutline();
            curtain.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        if (wasClosing) {
            host.onRestoreBackground();
        }
    }

    /**
     * Immediately covers Home with a fullscreen opaque black curtain and recessed background depth
     * before the first resumed frame renders, masking closed apps until {@link #playCloseCurtain} runs.
     */
    void holdForCloseReturn(AppLaunchSettings launchCfg) {
        if (curtain == null || root == null) return;
        if (pending || committed || closing) {
            clearImmediate();
        }
        AppLaunchSettings cfg = launchCfg != null ? launchCfg : AppLaunchSettings.defaults();
        lastSettings = cfg;
        closing = true;
        closingWaitingStart = true;

        int rootW = root.getWidth();
        int rootH = root.getHeight();
        host.onBeforeCurtainExpand(null, rootW, rootH, cfg);

        if (rootW > 0 && rootH > 0) {
            curtain.setPivotX(rootW * 0.5f);
            curtain.setPivotY(rootH * 0.5f);
        }
        curtain.animate().setListener(null).cancel();
        curtain.setTranslationX(0f);
        curtain.setTranslationY(0f);
        curtain.setScaleX(1f);
        curtain.setScaleY(1f);
        curtain.setAlpha(1f);
        cornerPx = 0f;
        curtain.invalidateOutline();
        curtain.setVisibility(View.VISIBLE);
        curtain.bringToFront();

        disposeArt();
        host.onCurtainDepthProgress(1f, cfg);
    }

    void restoreDepthForCloseMeasurement(AppLaunchSettings launchCfg) {
        if (!closing) return;
        AppLaunchSettings cfg = launchCfg != null ? launchCfg : lastSettings;
        host.onCurtainDepthProgress(0f, cfg != null ? cfg : AppLaunchSettings.defaults());
    }

    /**
     * Reverses the iOS open-curtain sweep from fullscreen ({@code u = 1}) down to {@code targetView}
     * (or {@code relFallbackBounds}) while restoring Home background depth to {@code 1.0x}.
     */
    void playCloseCurtain(View targetView, float[] relFallbackBounds,
                          AppLaunchSettings launchCfg, Runnable onFinished) {
        if (!closing || curtain == null || root == null) {
            finishCloseCleanup(onFinished);
            return;
        }
        int rootW = root.getWidth();
        int rootH = root.getHeight();
        if (rootW <= 0 || rootH <= 0) {
            finishCloseCleanup(onFinished);
            return;
        }

        AppLaunchSettings cfg = launchCfg != null ? launchCfg : AppLaunchSettings.defaults();
        lastSettings = cfg;
        closingWaitingStart = false;

        // holdForCloseReturn() recessed homeContent to 1.0f depth; measure target bounds at 1.0x
        // while the fullscreen black curtain still covers Home, before applying frame u=1f below.
        host.onCurtainDepthProgress(0f, cfg);

        boolean validTargetView = false;
        float[] targetBounds = null;
        if (targetView != null && targetView.isAttachedToWindow()
                && targetView.getWidth() > 0 && targetView.getHeight() > 0) {
            float[] screen = LauncherMotion.captureScreenBounds(targetView);
            float[] local = LauncherMotion.screenToLocalBounds(screen, root);
            if (local != null
                    && local[0] >= -local[2] * 0.5f && local[0] <= rootW + local[2] * 0.5f
                    && local[1] >= -local[3] * 0.5f && local[1] <= rootH + local[3] * 0.5f) {
                targetBounds = local;
                validTargetView = true;
            }
        }
        if (targetBounds == null && relFallbackBounds != null && relFallbackBounds.length >= 4
                && relFallbackBounds[2] > 0f && relFallbackBounds[3] > 0f) {
            targetBounds = relFallbackBounds;
        }
        if (targetBounds == null) {
            targetBounds = LauncherMotion.computeCloseFallbackBounds(rootW, rootH);
        }
        if (targetBounds == null) {
            finishCloseCleanup(onFinished);
            return;
        }

        final LauncherMotion.LaunchCurtainPose pose = LauncherMotion.computeCurtainPose(
                rootW, rootH, targetBounds[0], targetBounds[1], targetBounds[2], targetBounds[3]);
        final float startCornerPx = cfg.roundedCorners
                ? LauncherMotion.curtainCornerRadiusPx(rootH) : 0f;

        cancelAnimator();
        final Bitmap cardBmp = (validTargetView && cfg.cardMorph && host.shouldCaptureArtBitmap(targetView))
                ? captureCardBitmap(targetView) : null;
        replaceBitmap(cardBmp);
        final boolean hasArt = cardBmp != null && curtainArt != null;
        Log.d(TAG, "playCloseCurtain start gen=" + (gen + 1) + " validTarget=" + validTargetView
                + " bounds=[" + targetBounds[0] + "," + targetBounds[1] + "," + targetBounds[2] + "," + targetBounds[3] + "]"
                + " hasArt=" + hasArt + " durMs=" + cfg.sweepDurationMs());

        final int animGen = ++gen;
        curtain.setPivotX(rootW * 0.5f);
        curtain.setPivotY(rootH * 0.5f);
        curtain.setAlpha(1f);
        curtain.setVisibility(View.VISIBLE);
        curtain.bringToFront();

        if (hasArt) {
            curtainArt.setImageBitmap(cardBmp);
            curtainArt.setPivotX(rootW * 0.5f);
            curtainArt.setPivotY(rootH * 0.5f);
            curtainArt.setScaleX(1f);
            curtainArt.setScaleY(1f);
            curtainArt.setAlpha(0f);
            curtainArt.setVisibility(View.INVISIBLE);
        } else {
            disposeArt();
        }
        applyFrame(1f, pose, startCornerPx, hasArt, cfg);

        ValueAnimator valueAnimator = ValueAnimator.ofFloat(0f, 1f);
        valueAnimator.setDuration(cfg.sweepDurationMs());
        valueAnimator.setInterpolator(cfg.createInterpolator());
        valueAnimator.addUpdateListener(anim -> {
            if (gen != animGen || !closing) return;
            float t = (float) anim.getAnimatedValue();
            float u = Math.max(0f, Math.min(1f, 1f - t));
            applyFrame(u, pose, startCornerPx, hasArt, cfg);
        });
        valueAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator == valueAnimator) animator = null;
                if (cancelled || gen != animGen || !closing) return;
                applyFrame(0f, pose, startCornerPx, hasArt, cfg);
                if (hasArt && curtain != null) {
                    curtain.animate()
                            .alpha(0f)
                            .setDuration(45L)
                            .setInterpolator(new DecelerateInterpolator(1.3f))
                            .withEndAction(() -> {
                                if (gen == animGen && closing) {
                                    finishCloseCleanup(onFinished);
                                }
                            })
                            .start();
                } else {
                    finishCloseCleanup(onFinished);
                }
            }
        });
        animator = valueAnimator;
        valueAnimator.start();
    }

    private void finishCloseCleanup(Runnable onFinished) {
        clearImmediate();
        if (onFinished != null) {
            onFinished.run();
        }
    }

    private void fadeOutTranslucentOverlay() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        gen++;
        pending = false;
        committed = false;
        lostFocus = false;
        handler.removeCallbacks(clearLaunchPulse);
        cancelAnimator();
        disposeArt();
        if (curtain == null || curtain.getVisibility() != View.VISIBLE || !host.animationsEnabled()
                || host.reducedMotionEnabled()) {
            clearImmediate();
            host.onTranslucentFinish();
            return;
        }
        final int animGen = gen;
        curtain.animate().setListener(null).cancel();
        curtain.animate()
                .alpha(0f)
                .setDuration(TRANSLUCENT_FADE_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> {
                    if (animGen == gen) {
                        clearImmediate();
                        host.onTranslucentFinish();
                    }
                })
                .start();
    }

    private void applyFrame(float u, LauncherMotion.LaunchCurtainPose pose,
                            float startCornerPx, boolean hasArt, AppLaunchSettings launchCfg) {
        if (curtain == null) return;
        curtain.setTranslationX(pose.tx * (1f - u));
        curtain.setTranslationY(pose.ty * (1f - u));
        curtain.setScaleX(pose.startScaleX + (pose.fullScaleX - pose.startScaleX) * u);
        curtain.setScaleY(pose.startScaleY + (pose.fullScaleY - pose.startScaleY) * u);
        cornerPx = startCornerPx > 0f
                ? LauncherMotion.curtainCornerRadiusAt(startCornerPx, u) : 0f;
        curtain.invalidateOutline();

        if (hasArt && curtainArt != null) {
            float artAlpha = LauncherMotion.curtainArtAlphaAt(u);
            if (artAlpha > 0.001f) {
                if (curtainArt.getVisibility() != View.VISIBLE) {
                    curtainArt.setVisibility(View.VISIBLE);
                }
                curtainArt.setAlpha(artAlpha);
                float artScale = 1f + 0.10f * u;
                curtainArt.setScaleX(artScale);
                curtainArt.setScaleY(artScale);
            } else if (curtainArt.getVisibility() == View.VISIBLE) {
                curtainArt.setAlpha(0f);
                curtainArt.setVisibility(View.INVISIBLE);
            }
        } else {
            curtain.setAlpha(Math.min(1f, u / 0.28f));
        }

        host.onCurtainDepthProgress(u, launchCfg);
    }

    private void commitAfterExpand(int animGen, AppAdapter.LaunchCommit commit) {
        if (animGen != gen || !pending || activity.isFinishing() || activity.isDestroyed()) {
            clearImmediate();
            return;
        }
        pending = false;
        committed = true;
        boolean launched = commit.run(true);
        if (launched) {
            handler.removeCallbacks(clearLaunchPulse);
            handler.postDelayed(clearLaunchPulse, DEAD_LAUNCH_TIMEOUT_MS);
        } else {
            host.onRestoreBackground();
            fadeOutForResume();
        }
    }

    private void settleIfResumed() {
        // Never remove the curtain while paused or unfocused: the target app is still loading
        // and either onStop() (once covered) or onResume() / focus return will clear it safely.
        if (!host.isResumed() || !host.hasWindowFocus()) return;
        host.onRestoreBackground();
        fadeOutForResume();
    }

    private Bitmap captureCardBitmap(View card) {
        int w = card.getWidth();
        int h = card.getHeight();
        if (w <= 0 || h <= 0) return null;
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            card.draw(canvas);
            return bmp;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void replaceBitmap(Bitmap next) {
        Bitmap prev = bitmap;
        bitmap = next;
        if (prev != null && prev != next && !prev.isRecycled()) {
            prev.recycle();
        }
    }

    private void disposeArt() {
        if (curtainArt != null) {
            curtainArt.setImageDrawable(null);
            curtainArt.setScaleX(1f);
            curtainArt.setScaleY(1f);
            curtainArt.setAlpha(0f);
            curtainArt.setVisibility(View.INVISIBLE);
        }
        replaceBitmap(null);
    }

    private void cancelAnimator() {
        ValueAnimator current = animator;
        animator = null;
        if (current != null) {
            current.cancel();
        }
    }
}

