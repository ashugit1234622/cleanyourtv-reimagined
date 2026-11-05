package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/**
 * Fluid morphing focus selector for the top header pill buttons.
 * Smoothly glides between buttons and morphs its shape (width and corner radius)
 * to match whatever button is focused (e.g. from wide capsule to 42dp circle to wide capsule).
 */
public class HeaderFocusIndicator extends View {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    private float currentX = 0f;
    private float currentY = 0f;
    private float currentW = 0f;
    private float currentH = 0f;
    private float currentAlpha = 0f;
    private float currentScale = 1f;

    private ValueAnimator moveAnimator;
    private ValueAnimator fadeAnimator;
    private ValueAnimator textAnimator;

    private View currentFocusedView;
    private boolean isVisible = false;
    private boolean reducedMotion = false;
    private boolean suppressed = false;

    // Organic fluid curve: rapid start, smooth glide, gentle cushion into target
    private static final Interpolator FLUID_INTERPOLATOR =
            new PathInterpolator(0.16f, 1.0f, 0.3f, 1.0f);

    public HeaderFocusIndicator(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0xF5FFFFFF);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setColor(0x25FFFFFF);

        setWillNotDraw(false);
    }

    public void setReducedMotion(boolean reduced) {
        this.reducedMotion = reduced;
    }

    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
        if (suppressed) {
            reset();
        }
    }

    public void reset() {
        removeCallbacks(null);
        if (moveAnimator != null) moveAnimator.cancel();
        if (fadeAnimator != null) fadeAnimator.cancel();
        if (textAnimator != null) textAnimator.cancel();
        if (currentFocusedView instanceof TextView) {
            applyColor((TextView) currentFocusedView, 0xFFFFFFFF);
        }
        currentFocusedView = null;
        isVisible = false;
        currentAlpha = 0f;
        currentScale = 1f;
        invalidate();
    }

    public void onButtonFocused(View button) {
        if (suppressed || button == null || button.getVisibility() != View.VISIBLE) return;

        button.post(() -> {
            if (suppressed || !button.isFocused() || !button.isAttachedToWindow()) return;
            int[] buttonLoc = new int[2];
            button.getLocationInWindow(buttonLoc);
            int[] myLoc = new int[2];
            getLocationInWindow(myLoc);

            float targetX = buttonLoc[0] - myLoc[0];
            float targetY = buttonLoc[1] - myLoc[1];
            float targetW = button.getWidth();
            float targetH = button.getHeight();

            if (targetW <= 0 || targetH <= 0) return;

            View oldFocus = currentFocusedView;
            currentFocusedView = button;

            if (!isVisible) {
                // First entry from dock: snap position and smoothly fade/scale in
                snapTo(targetX, targetY, targetW, targetH);
                fadeIn();
                transitionTextColor(null, button);
            } else {
                // Morph and glide to target button
                animateTo(targetX, targetY, targetW, targetH);
                transitionTextColor(oldFocus, button);
            }
        });
    }

    public void onFocusLost() {
        if (!isVisible && currentFocusedView == null) return;
        View old = currentFocusedView;
        currentFocusedView = null;
        fadeOut(old);
    }

    private void snapTo(float x, float y, float w, float h) {
        if (moveAnimator != null) moveAnimator.cancel();
        currentX = x;
        currentY = y;
        currentW = w;
        currentH = h;
        currentScale = 0.94f;
    }

    private void animateTo(float toX, float toY, float toW, float toH) {
        if (moveAnimator != null) moveAnimator.cancel();

        final float fromX = currentX;
        final float fromY = currentY;
        final float fromW = currentW;
        final float fromH = currentH;

        int duration = reducedMotion ? 120 : 220;

        moveAnimator = ValueAnimator.ofFloat(0f, 1f);
        moveAnimator.setDuration(duration);
        moveAnimator.setInterpolator(FLUID_INTERPOLATOR);
        moveAnimator.addUpdateListener(animation -> {
            float t = animation.getAnimatedFraction();
            currentX = fromX + (toX - fromX) * t;
            currentY = fromY + (toY - fromY) * t;
            currentW = fromW + (toW - fromW) * t;
            currentH = fromH + (toH - fromH) * t;
            // Subtle fluid stretch/breath during the morph
            currentScale = 1f + 0.04f * (float) Math.sin(t * Math.PI);
            invalidate();
        });
        moveAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                currentX = toX;
                currentY = toY;
                currentW = toW;
                currentH = toH;
                currentScale = 1.04f;
                invalidate();
            }
        });
        moveAnimator.start();
    }

    private void fadeIn() {
        if (fadeAnimator != null) fadeAnimator.cancel();
        isVisible = true;

        fadeAnimator = ValueAnimator.ofFloat(currentAlpha, 1f);
        fadeAnimator.setDuration(reducedMotion ? 80 : 180);
        fadeAnimator.setInterpolator(FLUID_INTERPOLATOR);
        fadeAnimator.addUpdateListener(a -> {
            currentAlpha = (float) a.getAnimatedValue();
            currentScale = 0.94f + 0.10f * currentAlpha; // scales smoothly up to 1.04
            invalidate();
        });
        fadeAnimator.start();
    }

    private void fadeOut(View lastFocused) {
        if (fadeAnimator != null) fadeAnimator.cancel();
        if (moveAnimator != null) moveAnimator.cancel();
        if (textAnimator != null) textAnimator.cancel();

        if (lastFocused instanceof TextView) {
            applyColor((TextView) lastFocused, 0xFFFFFFFF);
        }

        fadeAnimator = ValueAnimator.ofFloat(currentAlpha, 0f);
        fadeAnimator.setDuration(reducedMotion ? 80 : 160);
        fadeAnimator.setInterpolator(FLUID_INTERPOLATOR);
        fadeAnimator.addUpdateListener(a -> {
            currentAlpha = (float) a.getAnimatedValue();
            currentScale = 0.94f + 0.10f * currentAlpha;
            invalidate();
        });
        fadeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isVisible = false;
                invalidate();
            }
        });
        fadeAnimator.start();
    }

    private void transitionTextColor(View oldView, View newView) {
        if (textAnimator != null) textAnimator.cancel();

        final ArgbEvaluator evaluator = new ArgbEvaluator();
        final int darkColor = 0xFF111111;
        final int whiteColor = 0xFFFFFFFF;

        textAnimator = ValueAnimator.ofFloat(0f, 1f);
        textAnimator.setDuration(reducedMotion ? 120 : 180);
        textAnimator.setInterpolator(FLUID_INTERPOLATOR);
        textAnimator.addUpdateListener(anim -> {
            float t = anim.getAnimatedFraction();
            if (oldView instanceof TextView) {
                int oldC = (Integer) evaluator.evaluate(t, darkColor, whiteColor);
                applyColor((TextView) oldView, oldC);
            }
            if (newView instanceof TextView) {
                int newC = (Integer) evaluator.evaluate(t, whiteColor, darkColor);
                applyColor((TextView) newView, newC);
            }
        });
        textAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (oldView instanceof TextView) {
                    applyColor((TextView) oldView, whiteColor);
                }
                if (newView instanceof TextView) {
                    applyColor((TextView) newView, darkColor);
                }
            }
        });
        textAnimator.start();
    }

    private void applyColor(TextView tv, int color) {
        ColorStateList csl = ColorStateList.valueOf(color);
        tv.setTextColor(csl);
        tv.setCompoundDrawableTintList(csl);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (currentAlpha <= 0.001f || currentW <= 0 || currentH <= 0) return;

        fillPaint.setAlpha(Math.round(0xF5 * currentAlpha));

        float cx = currentX + currentW * 0.5f;
        float cy = currentY + currentH * 0.5f;
        float halfW = (currentW * 0.5f) * currentScale;
        float halfH = (currentH * 0.5f) * currentScale;

        rect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);

        // Corner radius: half of height, clamped to half of width
        // For 42dp height, this is 21dp. When width is 42dp, this is 21dp (circle).
        // When width is 140dp, this is 21dp (pill).
        float radius = Math.min(halfW, halfH);

        // Subtle outer ambient glow
        if (currentAlpha > 0.05f) {
            float glowInset = 2.5f * density;
            glowPaint.setStrokeWidth(2.5f * density);
            glowPaint.setColor((Math.round(0x28 * currentAlpha) << 24) | 0xFFFFFF);
            canvas.drawRoundRect(rect.left - glowInset, rect.top - glowInset,
                    rect.right + glowInset, rect.bottom + glowInset,
                    radius + glowInset, radius + glowInset, glowPaint);
        }

        canvas.drawRoundRect(rect, radius, radius, fillPaint);
    }
}

