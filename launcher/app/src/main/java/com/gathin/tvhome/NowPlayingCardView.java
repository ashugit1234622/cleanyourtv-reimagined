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
 *
 * Supports three layout modes:
 * 1. Big Card Mode (MODE_DOCK): docked on the right side above the app dock when at Home.
 * 2. Small Pill Mode (MODE_PILL): docked at the top middle of the screen when the app drawer is open.
 * 3. Showcase Mode (MODE_SHOWCASE - Option B): center-stage expansive floating glass canvas with
 *    rich frosted blur backdrop when Idle Wallpaper Showcase is active.
 */
public class NowPlayingCardView extends FrameLayout {

    public static final int MODE_DOCK = 0;
    public static final int MODE_PILL = 1;
    public static final int MODE_SHOWCASE = 2;

    // 1. Big Card Container Views (Dock Mode)
    private LinearLayout bigContainer;
    private ImageView albumArt;
    private EqualizerView equalizerView;
    private TextView titleText;
    private ImageView appIconView;
    private TextView subtitleText;

    // 2. Small Pill Container Views (App Drawer Mode)
    private LinearLayout pillContainer;
    private ImageView pillAlbumArt;
    private EqualizerView pillEqualizerView;
    private TextView pillTitleText;

    // 3. Showcase Container Views (Option B: Center-Stage Wallpaper Showcase)
    private LinearLayout showcaseContainer;
    private ImageView showcaseAlbumArt;
    private TextView showcaseTitleText;
    private TextView showcaseArtistText;
    private ImageView showcaseAppIcon;
    private TextView showcaseAppName;
    private EqualizerView showcaseEqualizerView;

    private int currentMode = MODE_DOCK;
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
                float cornerPx;
                switch (currentMode) {
                    case MODE_SHOWCASE:
                        cornerPx = 28f * density;
                        break;
                    case MODE_PILL:
                        cornerPx = 19f * density;
                        break;
                    case MODE_DOCK:
                    default:
                        cornerPx = 22f * density;
                        break;
                }
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
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
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

        // Pill single-line static title
        pillTitleText = new TextView(context);
        pillTitleText.setTextColor(Color.WHITE);
        pillTitleText.setTextSize(13f);
        pillTitleText.setSingleLine(true);
        pillTitleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pillTitleText.setMaxWidth((int) (220 * density));
        pillTitleText.setTypeface(UiFont.get(context, R.string.font_medium));
        LinearLayout.LayoutParams pillTitleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pillTitleLp.setMarginStart((int) (8 * density));
        pillTitleText.setLayoutParams(pillTitleLp);
        pillContainer.addView(pillTitleText);

        // -------------------------------------------------------------
        // 3. Showcase Container (Option B: Center-Stage Wallpaper Showcase)
        // -------------------------------------------------------------
        showcaseContainer = new LinearLayout(context);
        showcaseContainer.setOrientation(LinearLayout.HORIZONTAL);
        showcaseContainer.setGravity(Gravity.CENTER_VERTICAL);
        int scPadStart = (int) (18 * density);
        int scPadTop = (int) (18 * density);
        int scPadEnd = (int) (26 * density);
        int scPadBottom = (int) (18 * density);
        showcaseContainer.setPadding(scPadStart, scPadTop, scPadEnd, scPadBottom);
        LayoutParams showcaseLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        addView(showcaseContainer, showcaseLp);

        // Showcase Large Artwork (140x140dp) with 18dp rounded corners
        FrameLayout scArtHolder = new FrameLayout(context);
        int scArtSize = (int) (140 * density);
        LinearLayout.LayoutParams scArtLp = new LinearLayout.LayoutParams(scArtSize, scArtSize);
        scArtHolder.setLayoutParams(scArtLp);
        final float scArtCornerPx = 18f * density;
        scArtHolder.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), scArtCornerPx);
            }
        });
        scArtHolder.setClipToOutline(true);
        scArtHolder.setBackgroundColor(0x33000000);

        showcaseAlbumArt = new ImageView(context);
        showcaseAlbumArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        showcaseAlbumArt.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        scArtHolder.addView(showcaseAlbumArt);
        showcaseContainer.addView(scArtHolder);

        // Showcase Metadata Column
        LinearLayout scTextCol = new LinearLayout(context);
        scTextCol.setOrientation(LinearLayout.VERTICAL);
        scTextCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams scTextColLp = new LinearLayout.LayoutParams(
                (int) (330 * density), LinearLayout.LayoutParams.WRAP_CONTENT);
        scTextColLp.setMarginStart((int) (22 * density));
        scTextCol.setLayoutParams(scTextColLp);

        // Showcase Large Title (24sp Semi-Bold)
        showcaseTitleText = new TextView(context);
        showcaseTitleText.setTextColor(Color.WHITE);
        showcaseTitleText.setTextSize(24f);
        showcaseTitleText.setSingleLine(true);
        showcaseTitleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        showcaseTitleText.setTypeface(UiFont.get(context, R.string.font_medium));
        showcaseTitleText.setShadowLayer(5f * density, 0, 1.5f * density, 0xB3000000);
        scTextCol.addView(showcaseTitleText);

        // Showcase Artist (15sp Regular)
        showcaseArtistText = new TextView(context);
        showcaseArtistText.setTextColor(0xE6FFFFFF);
        showcaseArtistText.setTextSize(15f);
        showcaseArtistText.setSingleLine(true);
        showcaseArtistText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        showcaseArtistText.setTypeface(UiFont.get(context, R.string.font_regular));
        showcaseArtistText.setShadowLayer(4f * density, 0, 1f * density, 0x80000000);
        LinearLayout.LayoutParams scArtistLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scArtistLp.setMargins(0, (int) (6 * density), 0, 0);
        showcaseArtistText.setLayoutParams(scArtistLp);
        scTextCol.addView(showcaseArtistText);

        // Showcase Bottom Row: App Source Tag + Expanded Live Equalizer
        LinearLayout scMetaRow = new LinearLayout(context);
        scMetaRow.setOrientation(LinearLayout.HORIZONTAL);
        scMetaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams scMetaRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scMetaRowLp.setMargins(0, (int) (14 * density), 0, 0);
        scMetaRow.setLayoutParams(scMetaRowLp);

        // Frosted Source Pill Badge
        LinearLayout sourceTag = new LinearLayout(context);
        sourceTag.setOrientation(LinearLayout.HORIZONTAL);
        sourceTag.setGravity(Gravity.CENTER_VERTICAL);
        sourceTag.setBackgroundResource(R.drawable.now_playing_source_badge_bg);
        sourceTag.setPadding((int) (10 * density), (int) (4 * density), (int) (12 * density), (int) (4 * density));

        showcaseAppIcon = new ImageView(context);
        int scIconSize = (int) (16 * density);
        LinearLayout.LayoutParams scIconLp = new LinearLayout.LayoutParams(scIconSize, scIconSize);
        scIconLp.setMarginEnd((int) (6 * density));
        showcaseAppIcon.setLayoutParams(scIconLp);
        sourceTag.addView(showcaseAppIcon);

        showcaseAppName = new TextView(context);
        showcaseAppName.setTextColor(0xCCFFFFFF);
        showcaseAppName.setTextSize(12f);
        showcaseAppName.setSingleLine(true);
        showcaseAppName.setTypeface(UiFont.get(context, R.string.font_medium));
        sourceTag.addView(showcaseAppName);
        scMetaRow.addView(sourceTag);

        // Expanded Live Equalizer
        showcaseEqualizerView = new EqualizerView(context);
        LinearLayout.LayoutParams scEqLp = new LinearLayout.LayoutParams(
                (int) (22 * density), (int) (15 * density));
        scEqLp.setMarginStart((int) (16 * density));
        scEqLp.gravity = Gravity.CENTER_VERTICAL;
        showcaseEqualizerView.setLayoutParams(scEqLp);
        scMetaRow.addView(showcaseEqualizerView);

        scTextCol.addView(scMetaRow);
        showcaseContainer.addView(scTextCol);

        // Default visibility: Big container visible, others hidden
        pillContainer.setVisibility(View.GONE);
        pillContainer.setAlpha(0f);
        showcaseContainer.setVisibility(View.GONE);
        showcaseContainer.setAlpha(0f);
        bigContainer.setVisibility(View.VISIBLE);
        bigContainer.setAlpha(1f);
    }

    private View getContainerForMode(int mode) {
        switch (mode) {
            case MODE_SHOWCASE:
                return showcaseContainer;
            case MODE_PILL:
                return pillContainer;
            case MODE_DOCK:
            default:
                return bigContainer;
        }
    }

    /**
     * Switches between Dock Mode, Compact Pill Mode, and Option B Showcase Mode.
     */
    public void setMode(int mode, boolean animate) {
        if (this.currentMode == mode) return;
        int previousMode = this.currentMode;
        this.currentMode = mode;

        int bgRes;
        switch (mode) {
            case MODE_SHOWCASE:
                bgRes = R.drawable.now_playing_showcase_bg;
                break;
            case MODE_PILL:
                bgRes = R.drawable.now_playing_pill_bg;
                break;
            case MODE_DOCK:
            default:
                bgRes = R.drawable.now_playing_card_bg;
                break;
        }
        setBackgroundResource(bgRes);
        invalidateOutline();

        View outView = getContainerForMode(previousMode);
        View inView = getContainerForMode(mode);

        if (animate) {
            if (outView != null) {
                outView.animate().cancel();
                outView.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                    outView.setVisibility(View.GONE);
                    if (inView != null) {
                        inView.setVisibility(View.VISIBLE);
                        inView.setAlpha(0f);
                        inView.animate().alpha(1f).setDuration(160).start();
                    }
                }).start();
            } else if (inView != null) {
                inView.setVisibility(View.VISIBLE);
                inView.setAlpha(1f);
            }
        } else {
            if (outView != null) {
                outView.animate().cancel();
                outView.setVisibility(View.GONE);
                outView.setAlpha(0f);
            }
            if (inView != null) {
                inView.animate().cancel();
                inView.setVisibility(View.VISIBLE);
                inView.setAlpha(1f);
            }
        }
    }

    public void setCompactMode(boolean compact, boolean animate) {
        setMode(compact ? MODE_PILL : MODE_DOCK, animate);
    }

    public boolean isCompact() {
        return currentMode == MODE_PILL;
    }

    public boolean isShowcase() {
        return currentMode == MODE_SHOWCASE;
    }

    public int getMode() {
        return currentMode;
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
            showcaseEqualizerView.setPlaying(false);
            return;
        }

        // 1. Bind Big Container
        titleText.setText(model.getTitle());
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

            showcaseAlbumArt.setPadding(0, 0, 0, 0);
            showcaseAlbumArt.setImageBitmap(art);
            showcaseAlbumArt.setBackgroundColor(Color.TRANSPARENT);
        } else {
            if (model.getAppIcon() != null) {
                int pad = (int) (14 * density);
                albumArt.setPadding(pad, pad, pad, pad);
                albumArt.setImageDrawable(model.getAppIcon());

                int pillPad = (int) (5 * density);
                pillAlbumArt.setPadding(pillPad, pillPad, pillPad, pillPad);
                pillAlbumArt.setImageDrawable(model.getAppIcon());

                int scPad = (int) (32 * density);
                showcaseAlbumArt.setPadding(scPad, scPad, scPad, scPad);
                showcaseAlbumArt.setImageDrawable(model.getAppIcon());
            } else {
                albumArt.setImageDrawable(null);
                pillAlbumArt.setImageDrawable(null);
                showcaseAlbumArt.setImageDrawable(null);
            }
            albumArt.setBackgroundColor(0x33000000);
            pillAlbumArt.setBackgroundColor(0x33000000);
            showcaseAlbumArt.setBackgroundColor(0x33000000);
        }

        equalizerView.setPlaying(model.isPlaying());
        pillEqualizerView.setPlaying(model.isPlaying());
        showcaseEqualizerView.setPlaying(model.isPlaying());

        // 2. Bind Small Pill Container
        String pillText = model.getTitle();
        if (!model.getArtist().isEmpty()) {
            pillText = model.getTitle() + " \u2022 " + model.getArtist();
        }
        pillTitleText.setText(pillText);

        // 3. Bind Showcase Container (Option B)
        showcaseTitleText.setText(model.getTitle());
        String artist = model.getArtist();
        if (artist.isEmpty()) {
            artist = model.getFormattedSubtitle();
        }
        showcaseArtistText.setText(artist);
        showcaseAppName.setText(model.getAppName());

        if (model.getAppIcon() != null) {
            showcaseAppIcon.setImageDrawable(model.getAppIcon());
            showcaseAppIcon.setVisibility(View.VISIBLE);
        } else {
            showcaseAppIcon.setVisibility(View.GONE);
        }
    }
}
