package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates Idle Wallpaper Showcase Mode on Home:
 * after the configured period of inactivity while Home is clean and resumed, fades out the clock,
 * greeting, system stats, race panel, app dock/grid, glow, and extra top-bar icons while smoothly
 * morphing Inputs, Wi-Fi/VPN, and Settings pills into compact dimmed icon circles (~60% opacity),
 * and starts shuffled full-screen wallpaper crossfades in {@link WallpaperPlayer}.
 *
 * <p>Any navigation/OK/Back/Menu remote press triggers wake-only restoration (consuming both its
 * DOWN and matching UP events so nothing launches or shifts focus), while Volume/Mute/Power keys
 * pass through silently without disrupting Showcase Mode.
 */
final class IdleShowcaseController {

    private enum State { NORMAL, ENTERING, ACTIVE, WAKING }

    interface HostState {
        boolean isResumed();
        boolean isIntroPlaying();
        boolean isReordering();
        boolean hasOpenPopup();
        boolean hasAppLibrary();
        boolean hasCardPrompt();
        View getFocusedView();
        void onShowcaseWoke();
    }

    private static final float DIMMED_PILL_ALPHA = 0.6f;
    private static final long ENTER_DURATION_MS = 480L;
    private static final long WAKE_DURATION_MS = 320L;
    private static final long REDUCED_DURATION_MS = 90L;

    private static final Interpolator EASE_OUT = new PathInterpolator(0.16f, 1f, 0.3f, 1f);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int idleSeconds;
    private final long delayMs;
    private final boolean animations;
    private final boolean reducedMotion;
    private final float density;
    private final int circlePx;
    private final int circlePadPx;
    private final HostState host;
    private final WallpaperPlayer wallpaperPlayer;
    private final HeaderFocusIndicator headerFocus;
    private final View grid;
    private final View leftBlock;
    private final List<View> fadeStageViews = new ArrayList<>();
    private final List<TextView> keepPills = new ArrayList<>();
    private final List<View> hideButtons = new ArrayList<>();
    private TextView btnWifi;
    private TextView btnVpn;

    private final List<FadeSnapshot> fadeSnapshots = new ArrayList<>();
    private final List<PillSnapshot> pillSnapshots = new ArrayList<>();
    private final List<HideButtonSnapshot> hideSnapshots = new ArrayList<>();

    private State state = State.NORMAL;
    private float morphProgress = 0f; // 0 = NORMAL UI, 1 = FULL SHOWCASE
    private ValueAnimator animator;
    private int consumedWakeKeyCode = -1;
    private boolean hardwareLayersActive = false;

    private final Runnable enterRunnable = this::enterShowcase;

    IdleShowcaseController(int idleSeconds,
                           boolean animations,
                           boolean reducedMotion,
                           float density,
                           HostState host,
                           WallpaperPlayer wallpaperPlayer,
                           HeaderFocusIndicator headerFocus,
                           View leftBlock,
                           View grid,
                           List<View> stageViewsToFade,
                           List<TextView> pillsToMorph,
                           List<View> buttonsToHide,
                           TextView btnWifi,
                           TextView btnVpn) {
        this.idleSeconds = Math.max(0, idleSeconds);
        this.delayMs = IdleShowcasePolicy.delayMs(this.idleSeconds);
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.density = density;
        this.circlePx = Math.round(42f * density);
        this.circlePadPx = Math.round(10f * density);
        this.host = host;
        this.wallpaperPlayer = wallpaperPlayer;
        this.headerFocus = headerFocus;
        this.leftBlock = leftBlock;
        this.grid = grid;
        if (stageViewsToFade != null) {
            for (View v : stageViewsToFade) {
                if (v != null) this.fadeStageViews.add(v);
            }
        }
        if (pillsToMorph != null) {
            for (TextView tv : pillsToMorph) {
                if (tv != null) this.keepPills.add(tv);
            }
        }
        if (buttonsToHide != null) {
            for (View v : buttonsToHide) {
                if (v != null) this.hideButtons.add(v);
            }
        }
        this.btnWifi = btnWifi;
        this.btnVpn = btnVpn;
    }

    boolean isEngaged() {
        return state == State.ENTERING || state == State.ACTIVE;
    }

    boolean isUiCollapsed() {
        return state == State.ENTERING || state == State.ACTIVE;
    }

    /** Schedules or resets the inactivity timer when in NORMAL/WAKING state and Home is clean. */
    void scheduleIfEligible() {
        handler.removeCallbacks(enterRunnable);
        if (state == State.ENTERING || state == State.ACTIVE) return;
        if (delayMs <= 0L) return;
        if (!IdleShowcasePolicy.canSchedule(
                host.isResumed(),
                idleSeconds,
                host.isIntroPlaying(),
                host.isReordering(),
                host.hasOpenPopup(),
                host.hasAppLibrary(),
                host.hasCardPrompt())) {
            return;
        }
        handler.postDelayed(enterRunnable, delayMs);
    }

    /** Stops the inactivity countdown without forcing an already-normal UI state change. */
    void cancelTimer() {
        handler.removeCallbacks(enterRunnable);
    }

    /** Called on Home resume; refreshes eligibility timer cleanly. */
    void onResume() {
        scheduleIfEligible();
    }

    /** Non-key user interaction (e.g. pointer/touch): wakes if engaged, or resets countdown. */
    void onNonKeyInteraction() {
        if (isEngaged()) {
            wake(true);
        } else {
            scheduleIfEligible();
        }
    }

    /**
     * Handles a {@link KeyEvent} from {@code MainActivity.dispatchKeyEvent}.
     *
     * @return {@code true} if Showcase consumed this key as wake-only (either initial press, key repeat,
     *         or matching key-up). Volume/power keys and dedicated screensaver keys are never consumed
     *         and do not wake or delay Showcase.
     */
    boolean handleDispatchKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        // Always consume repeats and ACTION_UP matching a swallowed wake key press.
        if (consumedWakeKeyCode != -1 && keyCode == consumedWakeKeyCode) {
            if (action == KeyEvent.ACTION_UP || event.isCanceled()) {
                consumedWakeKeyCode = -1;
            }
            return true;
        }

        // Volume / mute / hardware power and dedicated Screensaver keys never wake or delay Showcase.
        if (IdleShowcasePolicy.shouldIgnoreForIdleAndWake(keyCode)
                || IdleShowcasePolicy.isDirectScreensaverKey(keyCode)) {
            return false;
        }

        if (isEngaged()) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    consumedWakeKeyCode = keyCode;
                    wake(true);
                }
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                wake(true);
                return true;
            }
            return true;
        }

        // While UI is normal (or finishing wake), any navigation/action key resets the idle timer.
        if (action == KeyEvent.ACTION_DOWN) {
            scheduleIfEligible();
        }
        return false;
    }

    /**
     * Intercepts async Wi-Fi pill label updates while collapsed so SSID words never re-appear inside
     * the shrunk icon circle. The latest label is saved and shown upon wake.
     */
    boolean interceptWifiTextUpdate(CharSequence targetLabel) {
        if (!isUiCollapsed() || btnWifi == null) return false;
        PillSnapshot snap = findPillSnapshot(btnWifi);
        if (snap == null) return false;
        snap.text = targetLabel;
        snap.contentDescription = targetLabel;
        btnWifi.setContentDescription(targetLabel);
        return true;
    }

    /**
     * Intercepts async VPN pill updates while collapsed so text never re-appears inside the shrunk circle.
     */
    boolean interceptVpnTextUpdate(CharSequence targetLabel, ColorStateList inkColors) {
        if (!isUiCollapsed() || btnVpn == null) return false;
        PillSnapshot snap = findPillSnapshot(btnVpn);
        if (snap == null) return false;
        snap.text = targetLabel;
        snap.contentDescription = targetLabel;
        snap.textColors = inkColors;
        snap.drawableTint = inkColors;
        btnVpn.setContentDescription(targetLabel);
        return true;
    }

    /** Restores normal UI immediately without animation (e.g. onPause / onTrimMemory / reload). */
    void exitImmediate() {
        handler.removeCallbacks(enterRunnable);
        consumedWakeKeyCode = -1;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (wallpaperPlayer != null) {
            wallpaperPlayer.stopShowcaseRotation(true);
            wallpaperPlayer.setShowcaseActive(false, false);
        }
        if (state != State.NORMAL || morphProgress > 0f) {
            applyProgress(0f);
            finishWakeToNormal();
        } else {
            state = State.NORMAL;
            morphProgress = 0f;
            setHardwareLayers(false);
        }
    }

    /** Wakes Showcase cleanly (called by key events or Phone Bridge wallpaper changes). */
    void wake(boolean persistKeptWallpaper) {
        handler.removeCallbacks(enterRunnable);
        if (wallpaperPlayer != null) {
            wallpaperPlayer.stopShowcaseRotation(persistKeptWallpaper);
            wallpaperPlayer.setShowcaseActive(false, animations && !reducedMotion);
        }
        if (state == State.NORMAL && morphProgress <= 0f) {
            scheduleIfEligible();
            return;
        }
        if (state == State.WAKING) {
            return;
        }
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        restoreHiddenButtonsForExpansion();

        long durationMs = animations ? (reducedMotion ? REDUCED_DURATION_MS : WAKE_DURATION_MS) : 0L;
        if (durationMs <= 0L || morphProgress <= 0.001f) {
            applyProgress(0f);
            finishWakeToNormal();
            scheduleIfEligible();
            return;
        }

        state = State.WAKING;
        setHardwareLayers(true);
        final float startProgress = morphProgress;
        ValueAnimator va = ValueAnimator.ofFloat(startProgress, 0f);
        va.setDuration(Math.max(1L, Math.round(durationMs * startProgress)));
        va.setInterpolator(EASE_OUT);
        va.addUpdateListener(anim -> {
            morphProgress = (float) anim.getAnimatedValue();
            applyProgress(morphProgress);
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator != va) return;
                animator = null;
                applyProgress(0f);
                finishWakeToNormal();
                scheduleIfEligible();
            }
        });
        animator = va;
        va.start();
    }

    private void enterShowcase() {
        if (state != State.NORMAL) return;
        if (!IdleShowcasePolicy.canSchedule(
                host.isResumed(),
                idleSeconds,
                host.isIntroPlaying(),
                host.isReordering(),
                host.hasOpenPopup(),
                host.hasAppLibrary(),
                host.hasCardPrompt())) {
            return;
        }

        captureSnapshots();

        // Hide top-bar bright selector pill cleanly before shrinking pills.
        if (headerFocus != null) {
            headerFocus.setSuppressed(true);
        }
        if (wallpaperPlayer != null) {
            wallpaperPlayer.setShowcaseActive(true, animations && !reducedMotion);
        }

        long durationMs = animations ? (reducedMotion ? REDUCED_DURATION_MS : ENTER_DURATION_MS) : 0L;
        if (durationMs <= 0L) {
            state = State.ACTIVE;
            morphProgress = 1f;
            applyProgress(1f);
            setHardwareLayers(false);
            if (wallpaperPlayer != null) wallpaperPlayer.startShowcaseRotation();
            return;
        }

        state = State.ENTERING;
        setHardwareLayers(true);
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(durationMs);
        va.setInterpolator(EASE_OUT);
        va.addUpdateListener(anim -> {
            morphProgress = (float) anim.getAnimatedValue();
            applyProgress(morphProgress);
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator != va) return;
                animator = null;
                state = State.ACTIVE;
                morphProgress = 1f;
                applyProgress(1f);
                setHardwareLayers(false);
                if (wallpaperPlayer != null) wallpaperPlayer.startShowcaseRotation();
            }
        });
        animator = va;
        va.start();
    }

    private void captureSnapshots() {
        fadeSnapshots.clear();
        pillSnapshots.clear();
        hideSnapshots.clear();

        for (View view : fadeStageViews) {
            if (view.getVisibility() == View.VISIBLE && view.getAlpha() > 0f) {
                fadeSnapshots.add(new FadeSnapshot(view, view.getAlpha()));
            }
        }
        View captionView = wallpaperPlayer != null ? wallpaperPlayer.getCaptionView() : null;
        if (captionView != null && captionView.getVisibility() == View.VISIBLE && captionView.getAlpha() > 0f) {
            fadeSnapshots.add(new FadeSnapshot(captionView, captionView.getAlpha()));
        }

        for (TextView pill : keepPills) {
            if (pill.getVisibility() != View.VISIBLE) continue;
            PillSnapshot snap = new PillSnapshot();
            snap.view = pill;
            snap.hadFocus = pill.hasFocus();
            snap.textColors = pill.getTextColors();
            snap.drawableTint = pill.getCompoundDrawableTintList();
            snap.baseTextColorRgb = snap.hadFocus ? 0xFFFFFFFF : pill.getCurrentTextColor();
            snap.startAlpha = pill.getAlpha();
            snap.startScaleX = pill.getScaleX();
            snap.startScaleY = pill.getScaleY();
            snap.text = pill.getText();
            snap.contentDescription = pill.getContentDescription();
            ViewGroup.LayoutParams lp = pill.getLayoutParams();
            snap.layoutWidthParam = lp != null ? lp.width : ViewGroup.LayoutParams.WRAP_CONTENT;
            snap.measuredStartWidth = Math.max(circlePx, pill.getWidth());
            snap.paddingStart = pill.getPaddingStart();
            snap.paddingTop = pill.getPaddingTop();
            snap.paddingEnd = pill.getPaddingEnd();
            snap.paddingBottom = pill.getPaddingBottom();
            snap.drawablePadding = pill.getCompoundDrawablePadding();
            snap.maxWidth = pill.getMaxWidth();
            snap.needsShapeMorph = !TextUtils.isEmpty(snap.text) || snap.measuredStartWidth > (circlePx + 2);
            pillSnapshots.add(snap);
        }

        for (View btn : hideButtons) {
            if (btn.getVisibility() != View.VISIBLE) continue;
            HideButtonSnapshot snap = new HideButtonSnapshot();
            snap.view = btn;
            snap.startAlpha = btn.getAlpha();
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) btn.getLayoutParams();
            snap.layoutWidthParam = mlp.width;
            snap.measuredStartWidth = Math.max(circlePx, btn.getWidth());
            snap.startMarginStart = mlp.getMarginStart();
            hideSnapshots.add(snap);
        }
    }

    private void restoreHiddenButtonsForExpansion() {
        for (HideButtonSnapshot snap : hideSnapshots) {
            if (snap.view.getVisibility() != View.VISIBLE) {
                snap.view.setVisibility(View.VISIBLE);
            }
        }
    }

    private void finishWakeToNormal() {
        setHardwareLayers(false);
        for (FadeSnapshot snap : fadeSnapshots) {
            snap.view.setAlpha(snap.startAlpha);
        }
        for (HideButtonSnapshot snap : hideSnapshots) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) snap.view.getLayoutParams();
            mlp.width = snap.layoutWidthParam;
            mlp.setMarginStart(snap.startMarginStart);
            snap.view.setLayoutParams(mlp);
            snap.view.setAlpha(snap.startAlpha);
            snap.view.setVisibility(View.VISIBLE);
        }
        for (PillSnapshot snap : pillSnapshots) {
            TextView tv = snap.view;
            ViewGroup.LayoutParams lp = tv.getLayoutParams();
            if (lp != null) {
                lp.width = snap.layoutWidthParam;
                tv.setLayoutParams(lp);
            }
            tv.setMaxWidth(snap.maxWidth);
            tv.setPaddingRelative(snap.paddingStart, snap.paddingTop, snap.paddingEnd, snap.paddingBottom);
            tv.setCompoundDrawablePadding(snap.drawablePadding);
            if (!TextUtils.equals(tv.getText(), snap.text)) {
                tv.setText(snap.text);
            }
            tv.setContentDescription(snap.contentDescription);
            if (snap.textColors != null) tv.setTextColor(snap.textColors);
            if (snap.drawableTint != null) tv.setCompoundDrawableTintList(snap.drawableTint);
            tv.setAlpha(snap.startAlpha);
            tv.setScaleX(snap.startScaleX);
            tv.setScaleY(snap.startScaleY);
        }
        fadeSnapshots.clear();
        pillSnapshots.clear();
        hideSnapshots.clear();

        if (headerFocus != null) {
            headerFocus.setSuppressed(false);
            View focused = host.getFocusedView();
            if (focused != null && keepPillsContain(focused)) {
                headerFocus.onButtonFocused(focused);
            }
        }
        state = State.NORMAL;
        morphProgress = 0f;
        host.onShowcaseWoke();
    }

    private boolean keepPillsContain(View v) {
        for (TextView tv : keepPills) {
            if (tv == v) return true;
        }
        return false;
    }

    private PillSnapshot findPillSnapshot(TextView tv) {
        for (PillSnapshot snap : pillSnapshots) {
            if (snap.view == tv) return snap;
        }
        return null;
    }

    private void applyProgress(float t) {
        // Stage views fade out smoothly across the entire transition.
        float stageAlphaFactor = Math.max(0f, 1f - t);
        for (FadeSnapshot snap : fadeSnapshots) {
            snap.view.setAlpha(snap.startAlpha * stageAlphaFactor);
        }

        // Hidden top-bar buttons (e.g. reorder / library / top-bar apps) fade first then collapse smoothly.
        float hideAlphaFrac = Math.max(0f, 1f - (t / 0.45f));
        float hideWidthFrac = 1f - smoothStep(clamp((t - 0.15f) / 0.85f));
        for (HideButtonSnapshot snap : hideSnapshots) {
            snap.view.setAlpha(snap.startAlpha * hideAlphaFrac);
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) snap.view.getLayoutParams();
            mlp.width = Math.round(snap.measuredStartWidth * hideWidthFrac);
            mlp.setMarginStart(Math.round(snap.startMarginStart * hideWidthFrac));
            snap.view.setLayoutParams(mlp);
            if (t >= 0.999f) {
                snap.view.setVisibility(View.GONE);
            }
        }

        // Kept pills dim cleanly to 0.6f alpha; labelled pills dissolve text before morphing width -> 42dp circle.
        for (PillSnapshot snap : pillSnapshots) {
            TextView tv = snap.view;
            tv.setAlpha(snap.startAlpha + (DIMMED_PILL_ALPHA - snap.startAlpha) * t);
            tv.setScaleX(snap.startScaleX + (1f - snap.startScaleX) * t);
            tv.setScaleY(snap.startScaleY + (1f - snap.startScaleY) * t);
            if (!snap.needsShapeMorph) continue;

            if (t < 0.32f) {
                float wordAlpha = 1f - (t / 0.32f);
                if (!TextUtils.isEmpty(snap.text) && !TextUtils.equals(tv.getText(), snap.text)) {
                    tv.setCompoundDrawablePadding(snap.drawablePadding);
                    tv.setMaxWidth(snap.maxWidth);
                    tv.setText(snap.text);
                }
                tv.setTextColor(ColorStateList.valueOf(
                        IdleShowcasePolicy.withAlpha(snap.baseTextColorRgb, Math.round(255f * wordAlpha))));
            } else {
                if (!TextUtils.isEmpty(tv.getText())) {
                    tv.setText(null);
                    tv.setCompoundDrawablePadding(0);
                    tv.setMaxWidth(Integer.MAX_VALUE);
                }
            }

            float shrinkFrac = smoothStep(clamp((t - 0.22f) / 0.78f));
            ViewGroup.LayoutParams lp = tv.getLayoutParams();
            if (lp != null) {
                lp.width = Math.round(snap.measuredStartWidth + (circlePx - snap.measuredStartWidth) * shrinkFrac);
                tv.setLayoutParams(lp);
            }
            int pStart = Math.round(snap.paddingStart + (circlePadPx - snap.paddingStart) * shrinkFrac);
            int pEnd = Math.round(snap.paddingEnd + (circlePadPx - snap.paddingEnd) * shrinkFrac);
            tv.setPaddingRelative(pStart, snap.paddingTop, pEnd, snap.paddingBottom);
        }
    }

    private void setHardwareLayers(boolean enabled) {
        if (hardwareLayersActive == enabled) return;
        hardwareLayersActive = enabled;
        int layerType = enabled ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (grid != null) grid.setLayerType(layerType, null);
        if (leftBlock != null) leftBlock.setLayerType(layerType, null);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float smoothStep(float v) {
        return v * v * (3f - 2f * v);
    }

    private static final class FadeSnapshot {
        final View view;
        final float startAlpha;

        FadeSnapshot(View view, float startAlpha) {
            this.view = view;
            this.startAlpha = startAlpha;
        }
    }

    private static final class PillSnapshot {
        TextView view;
        boolean hadFocus;
        ColorStateList textColors;
        ColorStateList drawableTint;
        int baseTextColorRgb;
        float startAlpha;
        float startScaleX;
        float startScaleY;
        CharSequence text;
        CharSequence contentDescription;
        int layoutWidthParam;
        int measuredStartWidth;
        int paddingStart;
        int paddingTop;
        int paddingEnd;
        int paddingBottom;
        int drawablePadding;
        int maxWidth;
        boolean needsShapeMorph;
    }

    private static final class HideButtonSnapshot {
        View view;
        float startAlpha;
        int layoutWidthParam;
        int measuredStartWidth;
        int startMarginStart;
    }
}

