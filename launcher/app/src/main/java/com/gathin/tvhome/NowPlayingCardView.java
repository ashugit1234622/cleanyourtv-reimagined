package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
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

/**
 * Frosted glass Now Playing media widget matching the iOS/Apple TV dynamic style.
 * Displays album artwork, animated equalizer badge, title, and source app metadata.
 * Purely ambient and non-interactable (no focus, no touch).
 * Supports two layout modes:
 * 1. Big Card Mode: docked on the right side above the app dock when at Home.
 * 2. Small Pill Mode: docked at the top middle of the screen when the app drawer is open.
 */
public class NowPlayingCardView extends FrameLayout {
    // Big Card Container Views
    private LinearLayout bigContainer;
    private ImageView albumArt;
    private EqualizerView equalizerView;
    private TextView titleText;
    private ImageView appIconView;
    private TextView subtitleText;

    // Small Pill Container Views
    private LinearLayout pillContainer;
    private ImageView pillAlbumArt;
    private EqualizerView pillEqualizerView;
    private TextView pillTitleText;

    private boolean isCompact = false;
    private float density = 1.5f;

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
        density = getResources().getDisplayMetrics().density;

        // Purely ambient display widget: completely non-focusable and non-clickable
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setBackgroundResource(R.drawable.now_playing_card_bg);

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float cornerPx = isCompact ? (19f * density) : (22f * density);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx);
            }
        });
        setClipToOutline(true);

        // -------------------------------------------------------------
        // 1. Big Card Container (Dock Mode)
        // -------------------------------------------------------------
        bigContainer = new LinearLayout(context);
        bigContainer.setOrientation(LinearLayout.HORIZONTAL);
        bigContainer.setGravity(Gravity.CENTER_VERTICAL);
        int padStart = (int) (12 * density);
        int padTop = (int) (10 * density);
        int padEnd = (int) (18 * density);
        int padBottom = (int) (10 * density);
        bigContainer.setPadding(padStart, padTop, padEnd, padBottom);
        LayoutParams bigLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        addView(bigContainer, bigLp);

        // Big Artwork container (60x60dp) with 12dp rounded corners
        FrameLayout artHolder = new FrameLayout(context);
        int artSize = (int) (60 * density);
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
        bigContainer.addView(artHolder);

        // Big Text layout (Title and Subtitle)
        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                (int) (220 * density), LinearLayout.LayoutParams.WRAP_CONTENT);
        textLp.setMarginStart((int) (14 * density));
        textCol.setLayoutParams(textLp);

        titleText = new TextView(context);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(18f);
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
        bigContainer.addView(textCol);

        // -------------------------------------------------------------
        // 2. Small Pill Container (App Drawer / Top Center Mode)
        // -------------------------------------------------------------
        pillContainer = new LinearLayout(context);
        pillContainer.setOrientation(LinearLayout.HORIZONTAL);
        pillContainer.setGravity(Gravity.CENTER_VERTICAL);
        int pillPadStart = (int) (8 * density);
        int pillPadTop = (int) (5 * density);
        int pillPadEnd = (int) (14 * density);
        int pillPadBottom = (int) (5 * density);
        pillContainer.setPadding(pillPadStart, pillPadTop, pillPadEnd, pillPadBottom);
        LayoutParams pillLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        addView(pillContainer, pillLp);

        // Pill compact artwork (26x26dp) with 8dp rounded corners
        FrameLayout pillArtHolder = new FrameLayout(context);
        int pillArtSize = (int) (26 * density);
        LinearLayout.LayoutParams pillArtLp = new LinearLayout.LayoutParams(pillArtSize, pillArtSize);
        pillArtHolder.setLayoutParams(pillArtLp);
        final float pillArtCornerPx = 8f * density;
        pillArtHolder.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), pillArtCornerPx);
            }
        });
        pillArtHolder.setClipToOutline(true);
        pillArtHolder.setBackgroundColor(0x33000000);

        pillAlbumArt = new ImageView(context);
        pillAlbumArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pillAlbumArt.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        pillArtHolder.addView(pillAlbumArt);
        pillContainer.addView(pillArtHolder);

        // Pill Equalizer (12x9dp)
        pillEqualizerView = new EqualizerView(context);
        LinearLayout.LayoutParams pillEqLp = new LinearLayout.LayoutParams(
                (int) (12 * density), (int) (9 * density));
        pillEqLp.setMarginStart((int) (8 * density));
        pillEqLp.gravity = Gravity.CENTER_VERTICAL;
        pillEqualizerView.setLayoutParams(pillEqLp);
        pillContainer.addView(pillEqualizerView);

        // Pill single-line title marquee
        pillTitleText = new TextView(context);
        pillTitleText.setTextColor(Color.WHITE);
        pillTitleText.setTextSize(13f);
        pillTitleText.setSingleLine(true);
        pillTitleText.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        pillTitleText.setMarqueeRepeatLimit(-1);
        pillTitleText.setSelected(true);
        pillTitleText.setMaxWidth((int) (220 * density));
        pillTitleText.setTypeface(UiFont.get(context, R.string.font_medium));
        LinearLayout.LayoutParams pillTitleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pillTitleLp.setMarginStart((int) (8 * density));
        pillTitleText.setLayoutParams(pillTitleLp);
        pillContainer.addView(pillTitleText);

        // Default: Big container visible, pill container hidden
        pillContainer.setVisibility(View.GONE);
        pillContainer.setAlpha(0f);
        bigContainer.setVisibility(View.VISIBLE);
        bigContainer.setAlpha(1f);
    }

    /**
     * Switches between Big Card mode (dock/home) and Compact Pill mode (app drawer).
     */
    public void setCompactMode(boolean compact, boolean animate) {
        if (this.isCompact == compact) return;
        this.isCompact = compact;
        setBackgroundResource(compact ? R.drawable.now_playing_pill_bg : R.drawable.now_playing_card_bg);
        invalidateOutline();

        if (animate) {
            if (compact) {
                bigContainer.animate().cancel();
                pillContainer.animate().cancel();
                bigContainer.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                    bigContainer.setVisibility(View.GONE);
                    pillContainer.setVisibility(View.VISIBLE);
                    pillContainer.setAlpha(0f);
                    pillContainer.animate().alpha(1f).setDuration(160).start();
                }).start();
            } else {
                bigContainer.animate().cancel();
                pillContainer.animate().cancel();
                pillContainer.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                    pillContainer.setVisibility(View.GONE);
                    bigContainer.setVisibility(View.VISIBLE);
                    bigContainer.setAlpha(0f);
                    bigContainer.animate().alpha(1f).setDuration(160).start();
                }).start();
            }
        } else {
            bigContainer.animate().cancel();
            pillContainer.animate().cancel();
            if (compact) {
                bigContainer.setVisibility(View.GONE);
                bigContainer.setAlpha(0f);
                pillContainer.setVisibility(View.VISIBLE);
                pillContainer.setAlpha(1f);
            } else {
                pillContainer.setVisibility(View.GONE);
                pillContainer.setAlpha(0f);
                bigContainer.setVisibility(View.VISIBLE);
                bigContainer.setAlpha(1f);
            }
        }
    }

    public boolean isCompact() {
        return isCompact;
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
            pillEqualizerView.setPlaying(false);
            return;
        }

        // 1. Bind Big Container
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

            pillAlbumArt.setPadding(0, 0, 0, 0);
            pillAlbumArt.setImageBitmap(art);
            pillAlbumArt.setBackgroundColor(Color.TRANSPARENT);
        } else {
            if (model.getAppIcon() != null) {
                int pad = (int) (14 * density);
                albumArt.setPadding(pad, pad, pad, pad);
                albumArt.setImageDrawable(model.getAppIcon());

                int pillPad = (int) (5 * density);
                pillAlbumArt.setPadding(pillPad, pillPad, pillPad, pillPad);
                pillAlbumArt.setImageDrawable(model.getAppIcon());
            } else {
                albumArt.setImageDrawable(null);
                pillAlbumArt.setImageDrawable(null);
            }
            albumArt.setBackgroundColor(0x33000000);
            pillAlbumArt.setBackgroundColor(0x33000000);
        }

        equalizerView.setPlaying(model.isPlaying());
        pillEqualizerView.setPlaying(model.isPlaying());

        // 2. Bind Small Pill Container
        String pillText = model.getTitle();
        if (!model.getArtist().isEmpty()) {
            pillText = model.getTitle() + " \u2022 " + model.getArtist();
        }
        pillTitleText.setText(pillText);
        pillTitleText.setSelected(true);
    }
}
