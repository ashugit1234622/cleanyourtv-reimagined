package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.Locale;
import java.util.Random;

/**
 * Full-screen black overlay for the boot and wake intro.
 *
 * Sequence:
 * 1. Centered message (title and optional subtitle) in Outfit font animates in using one of 5 styles
 *    (pop, fade, slide, letters, shimmer) over ~550ms.
 * 2. Holds steady and readable for boot_intro_hold_ms (Auto = 2500ms..4500ms, or chosen 1.5s..5.0s).
 *    Counted from the exact moment the last text element reaches full visibility, measured in real elapsed time.
 * 3. Reveals the home screen via a hardware-accelerated circular reveal from the center over ~600ms,
 *    simultaneously scaling home content from 1.06 to 1.0 and fading it in.
 * 4. Slides the dock grid up slightly (24dp -> 0dp) over ~350ms.
 * Total time: capped at 7.0s. Any key press skips the intro immediately.
 */
final class BootIntroOverlay extends View {
    static final String ANIM_RANDOM = "random";
    static final String ANIM_POP = "pop";
    static final String ANIM_FADE = "fade";
    static final String ANIM_SLIDE = "slide";
    static final String ANIM_LETTERS = "letters";
    static final String ANIM_SHIMMER = "shimmer";

    public interface OnRevealListener {
        void onRevealStart();
    }

    private final Paint bgPaint = new Paint();
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subtitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();

    private final Matrix shimmerMatrix = new Matrix();
    private LinearGradient shimmerShader;

    private String title = "Welcome";
    private String subtitle = "";
    private String animStyle = ANIM_FADE;
    private int holdMs = 2500;
    private int effectiveHoldMs = 2500;
    private long holdStartRealtime = 0;
    private boolean textFullyVisible = false;

    private long entranceDurationMs = 550;
    private long revealDurationMs = 600;
    private long dockDurationMs = 350;
    private boolean suppressMessages = false;

    private float titleAlpha = 0f;
    private float titleScale = 1.0f;
    private float titleTranslateY = 0f;

    private float subtitleAlpha = 0f;
    private float subtitleTranslateY = 0f;

    private float revealRadius = 0f;
    private float maxRadius = 0f;
    private float revealScale = 1.0f;

    private int cx;
    private int cy;
    private float titleY;
    private float subtitleY;
    private float titleY1;
    private float titleY2;
    private String titleLine1 = "";
    private String titleLine2 = "";
    private boolean isTwoLineTitle = false;

    // For "letters" animation: precomputed positions and characters
    private boolean isLetters = false;
    private char[] charArray = new char[0];
    private float[] charX = new float[0];
    private float letterAnimProgress = 0f;
    private float letterRiseDistance = 0f;

    // For "shimmer" animation
    private boolean isShimmer = false;
    private float shimmerProgress = 0f;
    private float shimmerStart = 0f;
    private float shimmerEnd = 0f;

    private boolean playing = false;
    private boolean revealing = false;

    private ValueAnimator introAnim;
    private ValueAnimator subtitleAnim;
    private ValueAnimator shimmerAnim;
    private ValueAnimator revealAnim;
    private Runnable onComplete;
    private OnRevealListener onRevealListener;
    private View homeContent;
    private View dockGrid;

    public BootIntroOverlay(Context context) {
        super(context);
        bgPaint.setColor(Color.BLACK);

        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setLetterSpacing(0.08f);

        subtitlePaint.setColor(0xB3FFFFFF); // ~70% white
        subtitlePaint.setTextAlign(Paint.Align.CENTER);
        subtitlePaint.setLetterSpacing(0.04f);

        Typeface tfMedium = UiFont.get(context, R.string.font_medium);
        if (tfMedium != null) titlePaint.setTypeface(tfMedium);

        Typeface tfRegular = UiFont.get(context, R.string.font_regular);
        if (tfRegular != null) {
            subtitlePaint.setTypeface(tfRegular);
        } else if (tfMedium != null) {
            subtitlePaint.setTypeface(tfMedium);
        }

        setFocusable(false);
        setClickable(false);
    }

    public void setOnRevealListener(OnRevealListener listener) {
        this.onRevealListener = listener;
    }

    public void setIntroSettings(IntroSettings settings, String title, String subtitle, String animStyle) {
        if (settings != null) {
            if (IntroSettings.MESSAGES_OFF.equals(settings.messagesMode) && (title == null || title.isEmpty())) {
                this.title = "";
                this.subtitle = "";
                this.suppressMessages = true;
            } else if (IntroSettings.MESSAGES_TITLES_ONLY.equals(settings.messagesMode)) {
                if (title != null && !title.trim().isEmpty()) {
                    this.title = title.trim();
                }
                this.subtitle = "";
                this.suppressMessages = false;
            } else {
                if (title != null && !title.trim().isEmpty()) {
                    this.title = title.trim();
                }
                this.subtitle = subtitle != null ? subtitle.trim() : "";
                this.suppressMessages = false;
            }

            this.holdMs = settings.holdMs;
            this.effectiveHoldMs = IntroSettings.computeHoldMs(settings.holdMs, this.title, this.subtitle);

            if (settings.durations != null) {
                this.entranceDurationMs = settings.durations.entranceMs;
                this.revealDurationMs = settings.durations.revealMs;
                this.dockDurationMs = settings.durations.dockMs;
            }
        } else {
            if (title != null && !title.trim().isEmpty()) this.title = title.trim();
            this.subtitle = subtitle != null ? subtitle.trim() : "";
            this.effectiveHoldMs = IntroSettings.computeHoldMs(this.holdMs, this.title, this.subtitle);
        }

        if (animStyle == null || ANIM_RANDOM.equalsIgnoreCase(animStyle.trim())) {
            String[] styles = {ANIM_POP, ANIM_FADE, ANIM_SLIDE, ANIM_LETTERS, ANIM_SHIMMER};
            this.animStyle = styles[new Random().nextInt(styles.length)];
        } else {
            String s = animStyle.trim().toLowerCase(Locale.ROOT);
            if (ANIM_POP.equals(s) || ANIM_FADE.equals(s) || ANIM_SLIDE.equals(s)
                    || ANIM_LETTERS.equals(s) || ANIM_SHIMMER.equals(s)) {
                this.animStyle = s;
            } else {
                this.animStyle = ANIM_FADE;
            }
        }
    }

    public void setIntroData(String title, String subtitle, String animStyle, int holdMs) {
        if (title != null && !title.trim().isEmpty()) {
            this.title = title.trim();
        }
        this.subtitle = subtitle != null ? subtitle.trim() : "";
        this.suppressMessages = false;

        if (animStyle == null || ANIM_RANDOM.equalsIgnoreCase(animStyle.trim())) {
            String[] styles = {ANIM_POP, ANIM_FADE, ANIM_SLIDE, ANIM_LETTERS, ANIM_SHIMMER};
            this.animStyle = styles[new Random().nextInt(styles.length)];
        } else {
            String s = animStyle.trim().toLowerCase(Locale.ROOT);
            if (ANIM_POP.equals(s) || ANIM_FADE.equals(s) || ANIM_SLIDE.equals(s)
                    || ANIM_LETTERS.equals(s) || ANIM_SHIMMER.equals(s)) {
                this.animStyle = s;
            } else {
                this.animStyle = ANIM_FADE;
            }
        }

        this.holdMs = holdMs;
        this.effectiveHoldMs = IntroSettings.computeHoldMs(holdMs, this.title, this.subtitle);
    }

    /** Starts the intro sequence over the supplied home content and dock. */
    public void start(View content, View dock, Runnable completeCallback) {
        this.homeContent = content;
        this.dockGrid = dock;
        this.onComplete = completeCallback;
        this.playing = true;
        this.revealing = false;
        this.textFullyVisible = false;
        this.holdStartRealtime = 0;

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
            setupMetrics();
            startEntranceAnimation();
        });
    }

    private void setupMetrics() {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        cx = w / 2;
        cy = h / 2;
        maxRadius = (float) Math.hypot(cx, cy);

        float density = getResources().getDisplayMetrics().density;
        float maxTextWidth = w * 0.85f;

        if (suppressMessages || title.isEmpty()) {
            titleY = cy;
            subtitleY = cy;
            isLetters = false;
            isShimmer = false;
            isTwoLineTitle = false;
            return;
        }

        boolean hasSubtitle = !subtitle.isEmpty();
        isTwoLineTitle = false;
        titleLine1 = "";
        titleLine2 = "";

        if (!hasSubtitle) {
            float initialTitleSize = 38f * density;
            titlePaint.setTextSize(initialTitleSize);
            boolean isLong = title.length() >= 40 || titlePaint.measureText(title) > maxTextWidth;
            if (isLong) {
                String[] lines = splitTitleIntoTwoLines(title);
                if (lines[1] != null && !lines[1].isEmpty()) {
                    isTwoLineTitle = true;
                    titleLine1 = lines[0];
                    titleLine2 = lines[1];
                }
            }
        }

        if (isTwoLineTitle) {
            float titleSize = 34f * density;
            titlePaint.setTextSize(titleSize);
            while ((titlePaint.measureText(titleLine1) > maxTextWidth || titlePaint.measureText(titleLine2) > maxTextWidth)
                    && titleSize > 18f * density) {
                titleSize -= 1f * density;
                titlePaint.setTextSize(titleSize);
            }
            Paint.FontMetrics tfm = titlePaint.getFontMetrics();
            float lineHeight = tfm.descent - tfm.ascent;
            float lineSpacing = 10f * density;
            float totalTextHeight = lineHeight * 2 + lineSpacing;
            float topY = cy - (totalTextHeight / 2f) - tfm.ascent;
            titleY1 = topY;
            titleY2 = topY + lineHeight + lineSpacing;
            titleY = cy;
        } else {
            // Auto-shrink title to fit exactly on one line without wrapping or clipping
            float titleSize = 40f * density;
            titlePaint.setTextSize(titleSize);
            while (titlePaint.measureText(title) > maxTextWidth && titleSize > 18f * density) {
                titleSize -= 1f * density;
                titlePaint.setTextSize(titleSize);
            }

            // Auto-shrink subtitle if present
            if (hasSubtitle) {
                float subSize = 19f * density;
                subtitlePaint.setTextSize(subSize);
                while (subtitlePaint.measureText(subtitle) > maxTextWidth && subSize > 12f * density) {
                    subSize -= 1f * density;
                    subtitlePaint.setTextSize(subSize);
                }
            }

            Paint.FontMetrics tfm = titlePaint.getFontMetrics();
            float titleCenterOffset = (tfm.ascent + tfm.descent) / 2f;
            if (hasSubtitle) {
                titleY = cy - titleCenterOffset - (16f * density);
                Paint.FontMetrics sfm = subtitlePaint.getFontMetrics();
                float subCenterOffset = (sfm.ascent + sfm.descent) / 2f;
                subtitleY = cy - subCenterOffset + (26f * density);
            } else {
                titleY = cy - titleCenterOffset;
            }
        }

        // Check for surrogate pairs (emojis)
        boolean hasSurrogates = false;
        for (int i = 0; i < title.length(); i++) {
            if (Character.isSurrogate(title.charAt(i))) {
                hasSurrogates = true;
                break;
            }
        }

        // Setup specific animation precomputations
        isLetters = ANIM_LETTERS.equals(animStyle);
        if (isLetters && (hasSurrogates || isTwoLineTitle)) {
            // Fall back to ANIM_FADE for multi-line or surrogate-bearing text
            this.animStyle = ANIM_FADE;
            isLetters = false;
        }

        if (isLetters) {
            titlePaint.setTextAlign(Paint.Align.LEFT);
            charArray = title.toCharArray();
            int count = charArray.length;
            charX = new float[count];
            float[] widths = new float[count];
            titlePaint.getTextWidths(title, widths);
            float totalW = titlePaint.measureText(title);
            float startX = cx - totalW / 2f;
            for (int i = 0; i < count; i++) {
                charX[i] = startX;
                startX += widths[i];
            }
            letterRiseDistance = 24f * density;
        } else {
            titlePaint.setTextAlign(Paint.Align.CENTER);
        }

        isShimmer = ANIM_SHIMMER.equals(animStyle);
        if (isShimmer) {
            float totalW = isTwoLineTitle
                    ? Math.max(titlePaint.measureText(titleLine1), titlePaint.measureText(titleLine2))
                    : titlePaint.measureText(title);
            float sweepW = Math.max(120f * density, totalW * 0.45f);
            shimmerShader = new LinearGradient(
                    0, 0, sweepW, 0,
                    new int[]{0x66FFFFFF, 0xFFFFFFFF, 0x66FFFFFF},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP
            );
            shimmerStart = cx - totalW / 2f - sweepW;
            shimmerEnd = cx + totalW / 2f;
        }
    }

    private void onTextFullyVisible() {
        if (textFullyVisible || !playing || revealing) return;
        textFullyVisible = true;
        holdStartRealtime = SystemClock.elapsedRealtime();
        android.util.Log.d("BootIntro", "text fully visible, hold: " + effectiveHoldMs + "ms (t=" + holdStartRealtime + ")");
        scheduleRevealAfterHold();
    }

    private void scheduleRevealAfterHold() {
        if (!playing || revealing) return;
        long elapsed = SystemClock.elapsedRealtime() - holdStartRealtime;
        long remaining = effectiveHoldMs - elapsed;
        if (remaining <= 0) {
            startRevealAnimation();
        } else {
            postDelayed(() -> {
                if (playing && !revealing) {
                    long el = SystemClock.elapsedRealtime() - holdStartRealtime;
                    if (el >= effectiveHoldMs) {
                        startRevealAnimation();
                    } else {
                        scheduleRevealAfterHold();
                    }
                }
            }, Math.max(16, remaining));
        }
    }

    private void startEntranceAnimation() {
        float density = getResources().getDisplayMetrics().density;
        boolean hasSubtitle = !subtitle.isEmpty() && !suppressMessages;

        if (suppressMessages || title.isEmpty()) {
            onTextFullyVisible();
            return;
        }

        if (ANIM_POP.equals(animStyle)) {
            introAnim = ValueAnimator.ofFloat(0f, 1f);
            introAnim.setDuration(entranceDurationMs);
            introAnim.setInterpolator(new OvershootInterpolator(1.3f));
            introAnim.addUpdateListener(a -> {
                float val = (float) a.getAnimatedValue();
                titleScale = 0.6f + 0.4f * val;
                titleAlpha = Math.min(1f, val * 1.5f);
                invalidate();
            });
        } else if (ANIM_SLIDE.equals(animStyle)) {
            introAnim = ValueAnimator.ofFloat(0f, 1f);
            introAnim.setDuration(entranceDurationMs);
            introAnim.setInterpolator(new DecelerateInterpolator(1.4f));
            introAnim.addUpdateListener(a -> {
                float val = (float) a.getAnimatedValue();
                titleAlpha = val;
                titleScale = 1.0f;
                titleTranslateY = (1f - val) * 30f * density;
                invalidate();
            });
        } else if (ANIM_LETTERS.equals(animStyle)) {
            introAnim = ValueAnimator.ofFloat(0f, 1f);
            introAnim.setDuration(entranceDurationMs);
            introAnim.setInterpolator(new DecelerateInterpolator(1.3f));
            introAnim.addUpdateListener(a -> {
                letterAnimProgress = (float) a.getAnimatedValue();
                titleAlpha = Math.min(1f, letterAnimProgress * 1.4f);
                titleScale = 1.0f;
                invalidate();
            });
        } else if (ANIM_SHIMMER.equals(animStyle)) {
            introAnim = ValueAnimator.ofFloat(0f, 1f);
            introAnim.setDuration((long) (entranceDurationMs * 0.8f));
            introAnim.setInterpolator(new DecelerateInterpolator(1.4f));
            introAnim.addUpdateListener(a -> {
                float val = (float) a.getAnimatedValue();
                titleAlpha = val;
                titleScale = 0.98f + 0.02f * val;
                invalidate();
            });
            introAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!playing || isRevealing()) return;
                    startShimmerSweep();
                }
            });
        } else {
            // Default "fade"
            introAnim = ValueAnimator.ofFloat(0f, 1f);
            introAnim.setDuration(entranceDurationMs);
            introAnim.setInterpolator(new DecelerateInterpolator(1.4f));
            introAnim.addUpdateListener(a -> {
                float val = (float) a.getAnimatedValue();
                titleAlpha = val;
                titleScale = 0.96f + 0.04f * val;
                invalidate();
            });
        }

        // Subtitle entrance: starts ~250ms after title with small slide-up and fade
        if (hasSubtitle) {
            subtitleAnim = ValueAnimator.ofFloat(0f, 1f);
            subtitleAnim.setStartDelay((long) (entranceDurationMs * 0.45f));
            subtitleAnim.setDuration((long) (entranceDurationMs * 0.8f));
            subtitleAnim.setInterpolator(new DecelerateInterpolator(1.3f));
            subtitleAnim.addUpdateListener(a -> {
                float val = (float) a.getAnimatedValue();
                subtitleAlpha = val;
                subtitleTranslateY = (1f - val) * 12f * density;
                invalidate();
            });
            subtitleAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    onTextFullyVisible();
                }
            });
            subtitleAnim.start();
        }

        introAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!hasSubtitle) {
                    onTextFullyVisible();
                }
            }
        });
        introAnim.start();
    }

    private void startShimmerSweep() {
        if (shimmerShader == null) return;
        titlePaint.setShader(shimmerShader);
        shimmerAnim = ValueAnimator.ofFloat(0f, 1f);
        shimmerAnim.setDuration(750);
        shimmerAnim.setInterpolator(new DecelerateInterpolator(1.2f));
        shimmerAnim.addUpdateListener(a -> {
            shimmerProgress = (float) a.getAnimatedValue();
            float tx = shimmerStart + (shimmerEnd - shimmerStart) * shimmerProgress;
            shimmerMatrix.setTranslate(tx, 0);
            shimmerShader.setLocalMatrix(shimmerMatrix);
            invalidate();
        });
        shimmerAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                titlePaint.setShader(null);
                invalidate();
            }
        });
        shimmerAnim.start();
    }

    private void startRevealAnimation() {
        revealing = true;
        long now = SystemClock.elapsedRealtime();
        long actualHold = (holdStartRealtime > 0) ? (now - holdStartRealtime) : 0;
        android.util.Log.d("BootIntro", "reveal start, hold elapsed: " + actualHold + "ms (target: " + effectiveHoldMs + "ms)");

        if (onRevealListener != null) {
            onRevealListener.onRevealStart();
        }

        revealAnim = ValueAnimator.ofFloat(0f, 1f);
        revealAnim.setDuration(revealDurationMs);
        revealAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        revealAnim.addUpdateListener(a -> {
            float fraction = (float) a.getAnimatedValue();
            revealRadius = maxRadius * fraction;
            revealScale = 1.0f + 0.04f * fraction;

            // Dissolve title and subtitle quickly as reveal expands
            float textFade = Math.max(0f, 1f - fraction * 2.5f);
            titleAlpha = textFade;
            subtitleAlpha = textFade;

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
                    .setDuration(dockDurationMs)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .withEndAction(this::cleanup)
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

        if (onRevealListener != null) {
            onRevealListener.onRevealStart();
        }

        removeCallbacks(null);
        if (introAnim != null) introAnim.cancel();
        if (subtitleAnim != null) subtitleAnim.cancel();
        if (shimmerAnim != null) shimmerAnim.cancel();
        if (revealAnim != null) revealAnim.cancel();
        if (dockGrid != null) dockGrid.animate().cancel();
        titlePaint.setShader(null);

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

        if (!suppressMessages && !title.isEmpty() && titleAlpha > 0.01f) {
            canvas.save();
            float effectiveScale = titleScale * revealScale;
            canvas.scale(effectiveScale, effectiveScale, cx, cy);

            if (isTwoLineTitle) {
                titlePaint.setAlpha((int) (titleAlpha * 255));
                canvas.drawText(titleLine1, cx, titleY1 + titleTranslateY, titlePaint);
                canvas.drawText(titleLine2, cx, titleY2 + titleTranslateY, titlePaint);
            } else if (isLetters && letterAnimProgress < 1.0f && !revealing) {
                int count = charArray.length;
                float step = 0.5f / Math.max(1, count);
                for (int i = 0; i < count; i++) {
                    float start = i * step;
                    float end = start + 0.45f;
                    float charProgress = Math.max(0f, Math.min(1f, (letterAnimProgress - start) / (end - start)));
                    int charA = (int) (charProgress * titleAlpha * 255);
                    if (charA > 0) {
                        titlePaint.setAlpha(charA);
                        float rise = (1f - charProgress) * letterRiseDistance;
                        canvas.drawText(charArray, i, 1, charX[i], titleY + rise, titlePaint);
                    }
                }
            } else if (isLetters) {
                // Fully entered or revealing with left alignment
                titlePaint.setAlpha((int) (titleAlpha * 255));
                if (charArray.length > 0) {
                    canvas.drawText(charArray, 0, charArray.length, charX[0], titleY, titlePaint);
                }
            } else {
                // Aligned center: handles all styles including RTL seamlessly
                titlePaint.setAlpha((int) (titleAlpha * 255));
                canvas.drawText(title, cx, titleY + titleTranslateY, titlePaint);
            }
            canvas.restore();
        }

        if (!suppressMessages && !subtitle.isEmpty() && subtitleAlpha > 0.01f) {
            subtitlePaint.setAlpha((int) (subtitleAlpha * 255));
            canvas.save();
            canvas.scale(revealScale, revealScale, cx, cy);
            canvas.drawText(subtitle, cx, subtitleY + subtitleTranslateY, subtitlePaint);
            canvas.restore();
        }
    }

    /**
     * Splits long single-sentence or multi-clause titles cleanly across two lines.
     * Uses natural clause punctuation (!, ?, ., :, ,) or space closest to the middle.
     */
    static String[] splitTitleIntoTwoLines(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new String[]{"", ""};
        }
        text = text.trim();
        if (text.length() < 35) {
            return new String[]{text, ""};
        }

        int mid = text.length() / 2;
        int bestIdx = -1;
        int bestDist = Integer.MAX_VALUE;

        // Punctuation candidates in order of preference
        String[] puncts = {"! ", "? ", ". ", ": ", ", "};
        for (String p : puncts) {
            int idx = text.indexOf(p);
            while (idx != -1) {
                int splitPoint = idx + 1; // include punctuation mark in line 1
                if (splitPoint >= 10 && splitPoint <= text.length() - 10) {
                    int dist = Math.abs(splitPoint - mid);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestIdx = splitPoint;
                    }
                }
                idx = text.indexOf(p, idx + 1);
            }
            if (bestIdx != -1) break;
        }

        // If no punctuation split point found, split by space closest to mid
        if (bestIdx == -1) {
            int idx = text.indexOf(' ');
            while (idx != -1) {
                if (idx >= 10 && idx <= text.length() - 10) {
                    int dist = Math.abs(idx - mid);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestIdx = idx;
                    }
                }
                idx = text.indexOf(' ', idx + 1);
            }
        }

        if (bestIdx != -1) {
            String l1 = text.substring(0, bestIdx).trim();
            String l2 = text.substring(bestIdx).trim();
            if (!l1.isEmpty() && !l2.isEmpty()) {
                return new String[]{l1, l2};
            }
        }

        return new String[]{text, ""};
    }
}
