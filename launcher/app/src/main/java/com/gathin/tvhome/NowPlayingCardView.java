package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Frosted glass Now Playing media card matching the iOS/Apple TV dynamic style.
 * Displays album artwork, animated equalizer badge, title, and source app metadata.
 * D-pad focusable; clicking toggles Play/Pause.
 */
public class NowPlayingCardView extends FrameLayout {
    private ImageView albumArt;
    private EqualizerView equalizerView;
    private TextView titleText;
    private ImageView appIconView;
    private TextView subtitleText;
    private FrameLayout artHolder;

    private boolean isFocusedState = false;
    private ValueAnimator focusAnimator;

    public NowPlayingCardView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public NowPlayingCardView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public NowPlayingCardView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setFocusable(true);
        setClickable(true);
        setBackgroundResource(R.drawable.now_playing_card_bg);

        float density = getResources().getDisplayMetrics().density;
        final float cornerPx = 22f * density;

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx);
            }
        });
        setClipToOutline(true);

        int padStart = (int) (12 * density);
        int padTop = (int) (10 * density);
        int padEnd = (int) (18 * density);
        int padBottom = (int) (10 * density);
        setPadding(padStart, padTop, padEnd, padBottom);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams rowLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rowLp.gravity = Gravity.CENTER_VERTICAL;
        addView(row, rowLp);

        // 1. Artwork container (64x64dp) with 12dp rounded corners
        artHolder = new FrameLayout(context);
        int artSize = (int) (64 * density);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(artSize, artSize);
        artHolder.setLayoutParams(artLp);
        final float artCornerPx = 12f * density;
        artHolder.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), artCornerPx);
            }
        });
        artHolder.setClipToOutline(true);
        artHolder.setBackgroundColor(0x33000000);

        albumArt = new ImageView(context);
        albumArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        albumArt.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        artHolder.addView(albumArt);

        // Equalizer badge at bottom-right corner of album art
        FrameLayout eqBadge = new FrameLayout(context);
        eqBadge.setBackgroundResource(R.drawable.now_playing_eq_badge_bg);
        LayoutParams badgeLp = new LayoutParams((int) (22 * density), (int) (16 * density));
        badgeLp.gravity = Gravity.BOTTOM | Gravity.END;
        badgeLp.setMargins(0, 0, (int) (3 * density), (int) (3 * density));
        eqBadge.setLayoutParams(badgeLp);

        equalizerView = new EqualizerView(context);
        LayoutParams eqLp = new LayoutParams((int) (14 * density), (int) (10 * density));
        eqLp.gravity = Gravity.CENTER;
        equalizerView.setLayoutParams(eqLp);
        eqBadge.addView(equalizerView);

        artHolder.addView(eqBadge);
        row.addView(artHolder);

        // 2. Text layout (Title and Subtitle)
        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                (int) (220 * density), LinearLayout.LayoutParams.WRAP_CONTENT);
        textLp.setMarginStart((int) (14 * density));
        textCol.setLayoutParams(textLp);

        titleText = new TextView(context);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(19f);
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        titleText.setMarqueeRepeatLimit(-1);
        titleText.setSelected(true);
        titleText.setTypeface(UiFont.get(context, R.string.font_medium));
        textCol.addView(titleText);

        LinearLayout subRow = new LinearLayout(context);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams subRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subRowLp.setMargins(0, (int) (4 * density), 0, 0);
        subRow.setLayoutParams(subRowLp);

        appIconView = new ImageView(context);
        int iconSize = (int) (15 * density);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd((int) (6 * density));
        appIconView.setLayoutParams(iconLp);
        subRow.addView(appIconView);

        subtitleText = new TextView(context);
        subtitleText.setTextColor(0xB3FFFFFF);
        subtitleText.setTextSize(13f);
        subtitleText.setSingleLine(true);
        subtitleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitleText.setTypeface(UiFont.get(context, R.string.font_regular));
        subRow.addView(subtitleText);

        textCol.addView(subRow);
        row.addView(textCol);

        // User preference: OK toggles play/pause
        setOnClickListener(v -> {
            boolean handled = NowPlayingController.getInstance().togglePlayback();
            playClickPulse();
        });

        // Long press opens the app directly
        setOnLongClickListener(v -> {
            return NowPlayingController.getInstance().openCurrentApp(getContext());
        });
    }

    private void playClickPulse() {
        animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction(() -> {
            float targetScale = isFocusedState ? 1.04f : 1.0f;
            animate().scaleX(targetScale).scaleY(targetScale).setDuration(120).start();
        }).start();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, @Nullable android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        this.isFocusedState = gainFocus;

        if (focusAnimator != null) focusAnimator.cancel();

        float startScale = getScaleX();
        float endScale = gainFocus ? 1.04f : 1.0f;
        titleText.setSelected(gainFocus);

        focusAnimator = ValueAnimator.ofFloat(startScale, endScale);
        focusAnimator.setDuration(180);
        focusAnimator.setInterpolator(new DecelerateInterpolator(1.4f));
        focusAnimator.addUpdateListener(anim -> {
            float s = (float) anim.getAnimatedValue();
            setScaleX(s);
            setScaleY(s);
        });
        focusAnimator.start();

        if (gainFocus) {
            setBackgroundResource(R.drawable.now_playing_card_bg_focused);
            setElevation(getResources().getDisplayMetrics().density * 8f);
        } else {
            setBackgroundResource(R.drawable.now_playing_card_bg);
            setElevation(0f);
        }
    }

    public void bind(@Nullable NowPlayingModel model) {
        if (model == null) {
            if (getVisibility() == View.VISIBLE) {
                animate().cancel();
                animate()
                        .alpha(0f)
                        .setDuration(220)
                        .setInterpolator(new DecelerateInterpolator(1.4f))
                        .withEndAction(() -> setVisibility(View.GONE))
                        .start();
            }
            equalizerView.setPlaying(false);
            return;
        }

        titleText.setText(model.getTitle());
        titleText.setSelected(true);
        subtitleText.setText(model.getFormattedSubtitle());

        if (model.getAppIcon() != null) {
            appIconView.setImageDrawable(model.getAppIcon());
            appIconView.setVisibility(View.VISIBLE);
        } else {
            appIconView.setVisibility(View.GONE);
        }

        Bitmap art = model.getArtBitmap();
        if (art != null) {
            albumArt.setPadding(0, 0, 0, 0);
            albumArt.setImageBitmap(art);
            albumArt.setBackgroundColor(Color.TRANSPARENT);
        } else {
            if (model.getAppIcon() != null) {
                int pad = (int) (14 * getResources().getDisplayMetrics().density);
                albumArt.setPadding(pad, pad, pad, pad);
                albumArt.setImageDrawable(model.getAppIcon());
            } else {
                albumArt.setImageDrawable(null);
            }
            albumArt.setBackgroundColor(0x33000000);
        }

        equalizerView.setPlaying(model.isPlaying());

        if (getVisibility() != View.VISIBLE) {
            setAlpha(0f);
            setVisibility(View.VISIBLE);
            animate().cancel();
            animate()
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .start();
        }
    }
}
