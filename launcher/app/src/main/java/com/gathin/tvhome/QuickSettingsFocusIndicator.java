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
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Fluid focus selector for Quick Settings rows.
 * Smoothly glides vertically between rows and morphs its bounds with an Apple tvOS fluid easing curve.
 */
public class QuickSettingsFocusIndicator extends View {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private final float cornerRadius;

    private float currentX = 0f;
    private float currentY = 0f;
    private float currentW = 0f;
    private float currentH = 0f;
    private float currentAlpha = 0f;
    private float currentScaleY = 1f;

    private ValueAnimator moveAnimator;
    private ValueAnimator fadeAnimator;
    private ValueAnimator textAnimator;

    private View currentFocusedRow;
    private boolean isVisible = false;
    private boolean reducedMotion = false;

    // Organic fluid curve: rapid start, gentle cushion into target
    private static final Interpolator FLUID_INTERPOLATOR =
            new PathInterpolator(0.16f, 1.0f, 0.3f, 1.0f);

    public QuickSettingsFocusIndicator(Context context) {
        this(context, null);
    }

    public QuickSettingsFocusIndicator(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QuickSettingsFocusIndicator(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        cornerRadius = 13f * density;

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0xF5FFFFFF);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setColor(0x25FFFFFF);

        setWillNotDraw(false);
    }

    public void setReducedMotion(boolean reduced) {
        this.reducedMotion = reduced;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(widthMeasureSpec) : 0;
        int h = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightMeasureSpec) : 0;
        setMeasuredDimension(w, h);
    }

    public void reset() {
        if (moveAnimator != null) { moveAnimator.removeAllListeners(); moveAnimator.cancel(); }
        if (fadeAnimator != null) { fadeAnimator.removeAllListeners(); fadeAnimator.cancel(); }
        if (textAnimator != null) { textAnimator.removeAllListeners(); textAnimator.cancel(); }
        if (currentFocusedRow != null) {
            applyColor(currentFocusedRow, 0xFFFFFFFF, 0x73FFFFFF);
        }
        isVisible = false;
        currentFocusedRow = null;
        currentAlpha = 0f;
        invalidate();
    }

    public void onRowFocused(View row) {
        if (row == null || row.getVisibility() != View.VISIBLE) return;
        if (currentFocusedRow == row && isVisible) return;

        row.post(() -> {
            if (!row.isAttachedToWindow() || !row.isFocused()) return;
            float targetX;
            float targetY;
            if (row.getParent() instanceof View && ((View) row.getParent()).getParent() == getParent()) {
                // Sibling container aligned to the indicator: read layout bounds directly so panel scale
                // or row entrance translation never distorts the pill's initial frame.
                View rowParent = (View) row.getParent();
                targetX = rowParent.getLeft() - getLeft() + row.getLeft();
                targetY = rowParent.getTop() - getTop() + row.getTop();
            } else {
                int[] rowLoc = new int[2];
                row.getLocationInWindow(rowLoc);
                int[] myLoc = new int[2];
                getLocationInWindow(myLoc);
                targetX = rowLoc[0] - myLoc[0];
                targetY = rowLoc[1] - myLoc[1];
            }
            float targetW = row.getWidth();
            float targetH = row.getHeight();

            if (targetW <= 0 || targetH <= 0) {
                row.post(() -> onRowFocused(row));
                return;
            }

            View oldRow = currentFocusedRow;
            currentFocusedRow = row;

            if (!isVisible) {
                // First appearance on opening panel: snap and fade/scale in
                snapTo(targetX, targetY, targetW, targetH);
                fadeIn();
                transitionTextColor(null, row);
            } else {
                // Smooth glide to new row
                animateTo(targetX, targetY, targetW, targetH);
                transitionTextColor(oldRow, row);
            }
        });
    }

    private void snapTo(float x, float y, float w, float h) {
        if (moveAnimator != null) moveAnimator.cancel();
        currentX = x;
        currentY = y;
        currentW = w;
        currentH = h;
        currentScaleY = 0.94f;
    }

    private void animateTo(float toX, float toY, float toW, float toH) {
        if (moveAnimator != null) moveAnimator.cancel();

        final float fromX = currentX;
        final float fromY = currentY;
        final float fromW = currentW;
        final float fromH = currentH;

        moveAnimator = ValueAnimator.ofFloat(0f, 1f);
        moveAnimator.setDuration(reducedMotion ? 80 : 190);
        moveAnimator.setInterpolator(FLUID_INTERPOLATOR);
        moveAnimator.addUpdateListener(animation -> {
            float t = animation.getAnimatedFraction();
            currentX = fromX + (toX - fromX) * t;
            currentY = fromY + (toY - fromY) * t;
            currentW = fromW + (toW - fromW) * t;
            currentH = fromH + (toH - fromH) * t;
            // Subtle fluid stretch during vertical motion
            currentScaleY = 1f + 0.03f * (float) Math.sin(t * Math.PI);
            invalidate();
        });
        moveAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                currentX = toX;
                currentY = toY;
                currentW = toW;
                currentH = toH;
                currentScaleY = 1f;
                invalidate();
            }
        });
        moveAnimator.start();
    }

    private void fadeIn() {
        if (fadeAnimator != null) fadeAnimator.cancel();
        isVisible = true;

        fadeAnimator = ValueAnimator.ofFloat(currentAlpha, 1f);
        fadeAnimator.setDuration(reducedMotion ? 80 : 160);
        fadeAnimator.setInterpolator(FLUID_INTERPOLATOR);
        fadeAnimator.addUpdateListener(a -> {
            currentAlpha = (float) a.getAnimatedValue();
            currentScaleY = 0.94f + 0.06f * currentAlpha;
            invalidate();
        });
        fadeAnimator.start();
    }

    private void transitionTextColor(View oldRow, View newRow) {
        if (textAnimator != null) textAnimator.cancel();

        final ArgbEvaluator evaluator = new ArgbEvaluator();
        final int darkColor = 0xFF111111;
        final int whiteColor = 0xFFFFFFFF;
        final int darkDimColor = 0x99111111;
        final int whiteDimColor = 0x73FFFFFF;

        textAnimator = ValueAnimator.ofFloat(0f, 1f);
        textAnimator.setDuration(reducedMotion ? 100 : 170);
        textAnimator.setInterpolator(FLUID_INTERPOLATOR);
        textAnimator.addUpdateListener(anim -> {
            float t = anim.getAnimatedFraction();
            if (oldRow != null) {
                int oldC = (Integer) evaluator.evaluate(t, darkColor, whiteColor);
                int oldDim = (Integer) evaluator.evaluate(t, darkDimColor, whiteDimColor);
                applyColor(oldRow, oldC, oldDim);
            }
            if (newRow != null) {
                int newC = (Integer) evaluator.evaluate(t, whiteColor, darkColor);
                int newDim = (Integer) evaluator.evaluate(t, whiteDimColor, darkDimColor);
                applyColor(newRow, newC, newDim);
            }
        });
        textAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (oldRow != null) applyColor(oldRow, whiteColor, whiteDimColor);
                if (newRow != null) applyColor(newRow, darkColor, darkDimColor);
            }
        });
        textAnimator.start();
    }

    private void applyColor(View row, int color, int dimColor) {
        ColorStateList csl = ColorStateList.valueOf(color);
        TextView title = row.findViewById(R.id.title);
        if (title != null) {
            title.setTextColor(csl);
        }
        ImageView icon = row.findViewById(R.id.icon);
        if (icon != null) {
            icon.setImageTintList(csl);
        }
        TextView status = row.findViewById(R.id.status);
        if (status != null) {
            status.setTextColor(ColorStateList.valueOf(dimColor));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (currentAlpha <= 0.001f || currentW <= 0 || currentH <= 0) return;

        fillPaint.setAlpha(Math.round(0xF5 * currentAlpha));

        float cx = currentX + currentW * 0.5f;
        float cy = currentY + currentH * 0.5f;
        float halfW = currentW * 0.5f;
        float halfH = (currentH * 0.5f) * currentScaleY;

        rect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);

        // Subtle outer ambient glow
        if (currentAlpha > 0.05f) {
            float glowInset = 2f * density;
            glowPaint.setStrokeWidth(2f * density);
            glowPaint.setColor((Math.round(0x28 * currentAlpha) << 24) | 0xFFFFFF);
            canvas.drawRoundRect(rect.left - glowInset, rect.top - glowInset,
                    rect.right + glowInset, rect.bottom + glowInset,
                    cornerRadius + glowInset, cornerRadius + glowInset, glowPaint);
        }

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint);
    }
}

