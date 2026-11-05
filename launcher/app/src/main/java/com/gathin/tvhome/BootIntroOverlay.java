package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

/**
 * Full-screen black overlay for the boot intro.
 *
 * Sequence:
 * 1. Centered Outfit wordmark fades in with a subtle scale (0.92 -> 1.0) over 550ms.
 * 2. Holds steady for 800ms.
 * 3. Reveals the home screen via a hardware-accelerated circular reveal from the center over 600ms,
 *    simultaneously scaling home content from 1.06 to 1.0 and fading it in.
 * 4. Slides the dock grid up slightly (24dp -> 0dp) over 350ms.
 * Total time: ~2.3s (capped at 3.5s). Any key press skips the intro immediately.
 */
final class BootIntroOverlay extends View {
    private final Paint bgPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();

    private final String wordmark;
    private float wordmarkAlpha = 0f;
    private float wordmarkScale = 0.92f;
    private float revealRadius = 0f;
    private float maxRadius = 0f;

    private int cx;
    private int cy;
    private boolean playing = false;
    private boolean revealing = false;

    private ValueAnimator introAnim;
    private ValueAnimator revealAnim;
    private Runnable onComplete;
    private View homeContent;
    private View dockGrid;

    public BootIntroOverlay(Context context) {
        super(context);
        bgPaint.setColor(Color.BLACK);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setLetterSpacing(0.20f);

        Typeface tf = UiFont.get(context, R.string.font_medium);
        if (tf != null) textPaint.setTypeface(tf);

        float density = getResources().getDisplayMetrics().density;
        textPaint.setTextSize(38f * density);

        String title;
        try {
            title = context.getString(R.string.boot_intro_wordmark);
        } catch (Exception e) {
            title = "CleanYourTV";
        }
        wordmark = title;

        setFocusable(false);
        setClickable(false);
    }

    /** Starts the intro sequence over the supplied home content and dock. */
    public void start(View content, View dock, Runnable completeCallback) {
        this.homeContent = content;
        this.dockGrid = dock;
        this.onComplete = completeCallback;
        this.playing = true;
        this.revealing = false;

        float density = getResources().getDisplayMetrics().density;
        if (homeContent != null) {
            homeContent.setAlpha(0f);
            homeContent.setScaleX(1.06f);
            homeContent.setScaleY(1.06f);
        }
        if (dockGrid != null) {
            dockGrid.setTranslationY(24f * density);
        }

        post(() -> {
            if (!playing) return;
            cx = getWidth() / 2;
            cy = getHeight() / 2;
            maxRadius = (float) Math.hypot(cx, cy);
            startEntranceAnimation();
        });
    }

    private void startEntranceAnimation() {
        introAnim = ValueAnimator.ofFloat(0f, 1f);
        introAnim.setDuration(550);
        introAnim.setInterpolator(new DecelerateInterpolator(1.4f));
        introAnim.addUpdateListener(a -> {
            float val = (float) a.getAnimatedValue();
            wordmarkAlpha = val;
            wordmarkScale = 0.92f + 0.08f * val;
            invalidate();
        });
        introAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!playing) return;
                // Hold for 0.8s, then start circular reveal
                postDelayed(() -> {
                    if (playing) startRevealAnimation();
                }, 800);
            }
        });
        introAnim.start();
    }

    private void startRevealAnimation() {
        revealing = true;
        revealAnim = ValueAnimator.ofFloat(0f, 1f);
        revealAnim.setDuration(600);
        revealAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        revealAnim.addUpdateListener(a -> {
            float fraction = (float) a.getAnimatedValue();
            revealRadius = maxRadius * fraction;
            // Wordmark dissolves quickly as reveal opens
            wordmarkAlpha = Math.max(0f, 1f - fraction * 2.5f);
            if (homeContent != null) {
                homeContent.setAlpha(0.5f + 0.5f * fraction);
                homeContent.setScaleX(1.06f - 0.06f * fraction);
                homeContent.setScaleY(1.06f - 0.06f * fraction);
            }
            invalidate();
        });
        revealAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finishSequence();
            }
        });
        revealAnim.start();
    }

    private void finishSequence() {
        if (!playing) return;
        playing = false;
        revealing = false;

        if (homeContent != null) {
            homeContent.setAlpha(1.0f);
            homeContent.setScaleX(1.0f);
            homeContent.setScaleY(1.0f);
        }

        // Slide dock up into place
        if (dockGrid != null) {
            dockGrid.animate()
                    .translationY(0f)
                    .setDuration(350)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .withEndAction(() -> {
                        cleanup();
                    })
                    .start();
        } else {
            cleanup();
        }
    }

    private void cleanup() {
        setVisibility(GONE);
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
        if (onComplete != null) {
            onComplete.run();
        }
    }

    /** Instantly skips the intro and immediately restores home state. */
    public void skip() {
        if (!playing) return;
        playing = false;
        revealing = false;

        removeCallbacks(null);
        if (introAnim != null) introAnim.cancel();
        if (revealAnim != null) revealAnim.cancel();
        if (dockGrid != null) dockGrid.animate().cancel();

        if (homeContent != null) {
            homeContent.setAlpha(1.0f);
            homeContent.setScaleX(1.0f);
            homeContent.setScaleY(1.0f);
        }
        if (dockGrid != null) {
            dockGrid.setTranslationY(0f);
        }

        cleanup();
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isRevealing() {
        return revealing;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!playing) return;
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        if (revealing && revealRadius > 0) {
            clipPath.reset();
            clipPath.addCircle(cx, cy, revealRadius, Path.Direction.CW);
            canvas.save();
            canvas.clipOutPath(clipPath);
            canvas.drawRect(0, 0, w, h, bgPaint);
            canvas.restore();
        } else {
            canvas.drawRect(0, 0, w, h, bgPaint);
        }

        if (wordmarkAlpha > 0.01f) {
            textPaint.setAlpha((int) (wordmarkAlpha * 255));
            canvas.save();
            canvas.scale(wordmarkScale, wordmarkScale, cx, cy);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float y = cy - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(wordmark, cx, y, textPaint);
            canvas.restore();
        }
    }
}
