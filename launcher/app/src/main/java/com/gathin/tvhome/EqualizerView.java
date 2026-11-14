package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * Animated 4-bar equalizer visualizer view for the Now Playing card.
 */
public class EqualizerView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private ValueAnimator animator;
    private float animProgress = 0f;
    private boolean isPlaying = false;

    // Relative height multipliers per bar [0..1]
    private final float[] barPhases = {0.0f, 0.35f, 0.7f, 0.2f};
    private final float[] barSpeeds = {1.2f, 1.6f, 1.0f, 1.4f};

    public EqualizerView(Context context) {
        super(context);
        init();
    }

    public EqualizerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EqualizerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            if (isPlaying) {
                animProgress = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
    }

    public void setPlaying(boolean playing) {
        if (this.isPlaying == playing) return;
        this.isPlaying = playing;
        if (playing) {
            if (!animator.isRunning()) {
                animator.start();
            }
        } else {
            animator.cancel();
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isPlaying && !animator.isRunning()) {
            animator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        animator.cancel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        int numBars = 4;
        float barWidth = Math.max(2f, w / 7f);
        float gap = (w - (numBars * barWidth)) / (numBars - 1);
        float corner = barWidth / 2f;

        for (int i = 0; i < numBars; i++) {
            float frac;
            if (isPlaying) {
                float phase = (animProgress * barSpeeds[i] + barPhases[i]) % 1.0f;
                // Triangle wave [0.2 .. 1.0]
                float wave = Math.abs(phase * 2f - 1f);
                frac = 0.25f + 0.75f * wave;
            } else {
                frac = 0.25f; // Resting position
            }

            float barH = Math.max(corner * 2f, h * frac);
            float left = i * (barWidth + gap);
            float top = h - barH;
            float right = left + barWidth;
            float bottom = h;

            barRect.set(left, top, right, bottom);
            canvas.drawRoundRect(barRect, corner, corner, paint);
        }
    }
}

