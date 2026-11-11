package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    static final long SHOWCASE_FIRST_SWAP_MS = 3_000L;
    static final long SHOWCASE_INTERVAL_MS = 18_000L;
    private static final long SHOWCASE_CROSSFADE_MS = 1_000L;
    private static final float PARALLAX_OVERSCAN = 1.032f;
    private static final float PARALLAX_MAX_DP = 14f;
    private static final long PARALLAX_DURATION_MS = 300L;
    private static final float KEN_BURNS_SCALE = 1.045f;
    private static final float KEN_BURNS_PAN_X_DP = 12f;
    private static final float KEN_BURNS_PAN_Y_DP = 6f;
    private static final long WAKE_RESET_DURATION_MS = 320L;
    private static final DecelerateInterpolator DECEL_OUT = new DecelerateInterpolator(1.6f);
    private static final LinearInterpolator LINEAR = new LinearInterpolator();

    private final Context context;
    private final FrameLayout root;
    private final FrameLayout backdrop;
    private ImageView activeView;
    private ImageView backView;
    private final int backgroundColor;
    private final List<File> files;
    private final long interval;
    private final boolean themeHasRotation;
    private boolean wallpaperEnabled;
    private final boolean animations;
    private final boolean reducedMotion;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    /** Previous bitmap held during an active crossfade, recycled once the fade finishes or is interrupted. */
    private Bitmap fadingOut;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private boolean showcaseRotating;
    private boolean showcaseChangedWallpaper;
    private boolean showcaseActive;
    private float focusParallaxNorm = 0f;
    private final float density;
    private ValueAnimator kenBurnsAnimator;
    private View kenBurnsTarget;
    private int kenBurnsDir = 1;
    private String displayed = "";
    private String pendingTargetName = null;
    private long changeRequestId = 0;
    private final Runnable showcaseTick = this::advanceShowcaseWallpaper;
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.context = context;
        this.root = root;
        backgroundColor = config.backgroundColor;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        themeHasRotation = config.wallpaperRotationMinutes > 0;
        wallpaperEnabled = config.wallpaperEnabled;
        animations = config.animations;
        reducedMotion = config.reducedMotion;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        density = metrics.density;
        backdrop = new FrameLayout(context);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        activeView = makeLayer(context);
        backView = makeLayer(context);
        backView.setVisibility(View.GONE);
        backView.setAlpha(0f);
        backdrop.addView(activeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backdrop.addView(backView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            ensureBackdropAdded();
            showFirst();
        }
    }

    private static ImageView makeLayer(Context context) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private void ensureBackdropAdded() {
        if (backdrop.getParent() == null) {
            root.addView(backdrop, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap, false);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    void start() {
        active = true;
        if (current == null && !files.isEmpty()) {
            ensureBackdropAdded();
            showFirst();
        }
        update();
    }

    boolean isEmpty() {
        return files.isEmpty() || current == null;
    }

    void reload(Config config) {
        stopShowcaseRotation(false);
        wallpaperEnabled = config.wallpaperEnabled;
        files.clear();
        files.addAll(config.wallpapers);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            ensureBackdropAdded();
            showFirst();
        }
    }

    void showNext() {
        cycleWallpaper(true);
    }

    void showPrevious() {
        cycleWallpaper(false);
    }

    String getDisplayedName() {
        if (pendingTargetName != null && !pendingTargetName.isEmpty()) {
            return pendingTargetName;
        }
        return displayed != null ? displayed : "";
    }

    void onWallpaperDeleted(String name) {
        if (closed) return;
        List<File> list = allWallpapers();
        files.clear();
        files.addAll(list);
        if (displayed != null && displayed.equals(name)) {
            if (!list.isEmpty()) {
                showNext();
            } else {
                displayed = "";
                cancelLayerTransitions();
                activeView.setImageDrawable(null);
                backView.setImageDrawable(null);
                backView.setVisibility(View.GONE);
                if (current != null) {
                    current.recycle();
                    current = null;
                }
            }
        }
    }

    private List<File> allWallpapers() {
        File dir = Config.dir(context);
        List<File> list = new ArrayList<>(WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers")));
        if (list.isEmpty()) {
            list = new ArrayList<>(WallpaperRotation.images(new File(context.getFilesDir(), "wallpapers")));
        }
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile() && !list.contains(single)) {
            list.add(0, single);
        }
        if (list.isEmpty() && !files.isEmpty()) {
            list.addAll(files);
        }
        return list;
    }

    private void cycleWallpaper(boolean forward) {
        if (closed) return;
        List<File> list = allWallpapers();
        if (list.isEmpty()) return;

        // Keep files in sync with disk collection
        files.clear();
        files.addAll(list);

        String currentName = pendingTargetName != null ? pendingTargetName
                : (!displayed.isEmpty() ? displayed : preferences.getString("image", ""));
        File target = forward ? WallpaperRotation.next(list, currentName)
                              : WallpaperRotation.previous(list, currentName);
        if (target == null) return;

        displayTarget(target);
    }

    boolean showSpecific(String name) {
        if (closed || name == null || name.isEmpty()) return false;
        List<File> list = allWallpapers();
        if (list.isEmpty()) return false;

        files.clear();
        files.addAll(list);

        File target = null;
        for (File f : list) {
            if (f.getName().equals(name)) {
                target = f;
                break;
            }
        }
        if (target == null) return false;

        displayTarget(target);
        return true;
    }

    private void displayTarget(File target) {
        stopShowcaseRotation(false);
        pendingTargetName = target.getName();
        final String targetName = target.getName();
        final long reqId = ++changeRequestId;

        decoder.execute(() -> {
            Bitmap bitmap = decode(target);
            root.post(() -> {
                if (closed) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (reqId != changeRequestId) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                pendingTargetName = null;
                if (bitmap == null) {
                    Log.w("TvHome.Wallpaper", "Failed to decode wallpaper: " + targetName);
                    return;
                }
                ensureBackdropAdded();
                show(bitmap, animations && !reducedMotion);
                displayed = targetName;
                showCaption(true);
                preferences.edit().putString("image", displayed)
                        .putLong("changed", System.currentTimeMillis()).apply();
                context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(Config.WALLPAPER_CHOICE_KEY, displayed).apply();
            });
        });
    }

    void stop() {
        active = false;
        stopShowcaseRotation(false);
        setShowcaseActive(false, false);
        backdrop.animate().cancel();
        stopKenBurns(false);
    }

    /** Drops the wallpaper texture when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        stopShowcaseRotation(false);
        setShowcaseActive(false, false);
        stopKenBurns(false);
        backdrop.animate().cancel();
        resetViewTransform(backdrop);
        resetViewTransform(activeView);
        resetViewTransform(backView);
        if (current == null && fadingOut == null) return;
        cancelLayerTransitions();
        activeView.setImageDrawable(null);
        backView.setImageDrawable(null);
        backView.setVisibility(View.GONE);
        // The renderer may still have a queued frame that references this hardware bitmap. Drop our references and
        // let Android reclaim its native buffer after rendering releases it instead of recycling it mid-frame.
        current = null;
        fadingOut = null;
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        stopShowcaseRotation(false);
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || showcaseRotating || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active || showcaseRotating) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                ensureBackdropAdded();
                show(bitmap, animations && !reducedMotion);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /** Starts shuffled wallpaper showcase rotation (~3s first swap, then ~18s cadence) when >1 image exists. */
    void startShowcaseRotation() {
        if (closed || !active || !wallpaperEnabled || showcaseRotating) return;
        List<File> list = allWallpapers();
        if (list.size() < 2) return;
        showcaseRotating = true;
        showcaseChangedWallpaper = false;
        backdrop.removeCallbacks(showcaseTick);
        backdrop.postDelayed(showcaseTick, SHOWCASE_FIRST_SWAP_MS);
    }

    /**
     * Stops showcase rotation and cancels any pending decode so a wake-up never switches wallpaper later.
     * When {@code persistFixedChoice} is true and normal timed rotation is off, the currently kept showcase
     * wallpaper is also saved as {@link Config#WALLPAPER_CHOICE_KEY} so rebuilds/reboots preserve it.
     */
    void stopShowcaseRotation(boolean persistFixedChoice) {
        backdrop.removeCallbacks(showcaseTick);
        boolean wasRotating = showcaseRotating;
        showcaseRotating = false;
        if (wasRotating) {
            changeRequestId++;
        }
        if (persistFixedChoice && wasRotating && showcaseChangedWallpaper && !themeHasRotation
                && displayed != null && !displayed.isEmpty()) {
            SharedPreferences choices = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
            if (!"none".equals(choices.getString(Config.WALLPAPER_CHOICE_KEY, null))) {
                choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, displayed).apply();
            }
        }
        showcaseChangedWallpaper = false;
    }

    boolean isShowcaseRotating() {
        return showcaseRotating;
    }

    /** Subtle column-driven horizontal parallax with safe overscan so edges never uncover. */
    void setFocusParallax(float normalizedX) {
        float clamped = Math.max(-1f, Math.min(1f, normalizedX));
        boolean changed = Math.abs(clamped - focusParallaxNorm) >= 0.001f;
        focusParallaxNorm = clamped;
        if (!changed || closed || !active || !wallpaperEnabled || showcaseActive || backdrop.getParent() == null) return;
        if (!animations || reducedMotion) return;
        applyFocusParallax(PARALLAX_DURATION_MS, 0f);
    }

    /** Gentle depth settle when returning to Home after an app or settings screen closes. */
    void playReturnSettle() {
        if (closed || !active || !wallpaperEnabled || showcaseActive || backdrop.getParent() == null) return;
        if (!animations || reducedMotion) return;
        float restScale = Math.abs(focusParallaxNorm) > 0.001f ? PARALLAX_OVERSCAN : 1f;
        float maxShift = LauncherMotion.safeParallaxShiftPx(width, PARALLAX_OVERSCAN, PARALLAX_MAX_DP * density);
        float restTx = -focusParallaxNorm * maxShift;
        backdrop.animate().cancel();
        backdrop.setScaleX(restScale + 0.014f);
        backdrop.setScaleY(restScale + 0.014f);
        backdrop.setTranslationX(restTx);
        backdrop.animate()
                .scaleX(restScale)
                .scaleY(restScale)
                .translationX(restTx)
                .setDuration(240L)
                .setInterpolator(DECEL_OUT)
                .start();
    }

    /** Enables or cleanly unwinds gentle Ken Burns drift for Idle Showcase. */
    void setShowcaseActive(boolean engaged, boolean animate) {
        if (showcaseActive == engaged) return;
        showcaseActive = engaged;
        if (closed) return;
        backdrop.animate().cancel();
        if (engaged) {
            if (animate && animations && !reducedMotion && backdrop.getParent() != null) {
                backdrop.animate().scaleX(1f).scaleY(1f).translationX(0f)
                        .setDuration(WAKE_RESET_DURATION_MS).setInterpolator(DECEL_OUT).start();
            } else {
                resetViewTransform(backdrop);
            }
            if (animations && !reducedMotion && wallpaperEnabled && active) {
                startKenBurnsOn(activeView, true);
            }
        } else {
            stopKenBurns(animate && animations && !reducedMotion);
            if (animations && !reducedMotion && wallpaperEnabled && active && backdrop.getParent() != null
                    && Math.abs(focusParallaxNorm) > 0.001f) {
                applyFocusParallax(animate ? WAKE_RESET_DURATION_MS : 0L, 0f);
            } else if (!animate || !animations || reducedMotion) {
                resetViewTransform(backdrop);
            }
        }
    }

    private void applyFocusParallax(long durationMs, float extraScale) {
        boolean hasShift = Math.abs(focusParallaxNorm) > 0.001f;
        float targetScale = (hasShift ? PARALLAX_OVERSCAN : 1f) + extraScale;
        float maxShift = LauncherMotion.safeParallaxShiftPx(width, PARALLAX_OVERSCAN, PARALLAX_MAX_DP * density);
        float targetTx = -focusParallaxNorm * maxShift;
        backdrop.animate().cancel();
        if (durationMs <= 0L) {
            backdrop.setScaleX(targetScale);
            backdrop.setScaleY(targetScale);
            backdrop.setTranslationX(targetTx);
        } else {
            backdrop.animate()
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .translationX(targetTx)
                    .setDuration(durationMs)
                    .setInterpolator(DECEL_OUT)
                    .start();
        }
    }

    private void startKenBurnsOn(final View layer, final boolean zoomIn) {
        cancelKenBurnsAnimator();
        if (!showcaseActive || closed || !active || !wallpaperEnabled || !animations || reducedMotion || layer == null) {
            return;
        }
        if (zoomIn) {
            kenBurnsDir = -kenBurnsDir;
        }
        final int dir = kenBurnsDir;
        final float maxPanX = LauncherMotion.safeParallaxShiftPx(width, KEN_BURNS_SCALE, KEN_BURNS_PAN_X_DP * density);
        final float maxPanY = LauncherMotion.safeParallaxShiftPx(height, KEN_BURNS_SCALE, KEN_BURNS_PAN_Y_DP * density);
        final float panX = dir * maxPanX;
        final float panY = -dir * maxPanY;
        final float fromScale = zoomIn ? 1f : KEN_BURNS_SCALE;
        final float toScale = zoomIn ? KEN_BURNS_SCALE : 1f;
        final float fromX = zoomIn ? 0f : panX;
        final float toX = zoomIn ? panX : 0f;
        final float fromY = zoomIn ? 0f : panY;
        final float toY = zoomIn ? panY : 0f;

        kenBurnsTarget = layer;
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(SHOWCASE_INTERVAL_MS);
        va.setInterpolator(LINEAR);
        va.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            layer.setScaleX(fromScale + (toScale - fromScale) * t);
            layer.setScaleY(fromScale + (toScale - fromScale) * t);
            layer.setTranslationX(fromX + (toX - fromX) * t);
            layer.setTranslationY(fromY + (toY - fromY) * t);
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (kenBurnsAnimator != va) return;
                kenBurnsAnimator = null;
                if (showcaseActive && active && !closed && layer == activeView && animations && !reducedMotion) {
                    // Single-wallpaper showcase continues gently breathing in/out without jumping.
                    startKenBurnsOn(layer, !zoomIn);
                }
            }
        });
        kenBurnsAnimator = va;
        va.start();
    }

    private void cancelKenBurnsAnimator() {
        if (kenBurnsAnimator != null) {
            kenBurnsAnimator.removeAllListeners();
            kenBurnsAnimator.cancel();
            kenBurnsAnimator = null;
        }
    }

    private void stopKenBurns(boolean animate) {
        cancelKenBurnsAnimator();
        View target = kenBurnsTarget != null ? kenBurnsTarget : activeView;
        kenBurnsTarget = null;
        if (target == null) return;
        target.animate().cancel();
        if (animate && target.getVisibility() == View.VISIBLE) {
            target.animate()
                    .scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                    .setDuration(WAKE_RESET_DURATION_MS)
                    .setInterpolator(DECEL_OUT)
                    .start();
        } else {
            resetViewTransform(target);
        }
        if (target != backView) resetViewTransform(backView);
    }

    private static void resetViewTransform(View v) {
        if (v == null) return;
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
    }

    View getCaptionView() {
        return caption;
    }

    private void advanceShowcaseWallpaper() {
        if (!showcaseRotating || closed || !active || !wallpaperEnabled) return;
        if (loading || pendingTargetName != null) {
            backdrop.postDelayed(showcaseTick, 2_000L);
            return;
        }
        List<File> list = allWallpapers();
        if (list.size() < 2) {
            showcaseRotating = false;
            return;
        }
        String currentName = !displayed.isEmpty() ? displayed : preferences.getString("image", "");
        File selected = WallpaperRotation.pick(list, currentName, WallpaperRotation.ORDER_SHUFFLE, random);
        if (selected == null || selected.getName().equals(currentName)) {
            backdrop.postDelayed(showcaseTick, SHOWCASE_INTERVAL_MS);
            return;
        }
        final File next = selected;
        final String targetName = next.getName();
        final long reqId = ++changeRequestId;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active || !showcaseRotating || reqId != changeRequestId) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    Log.w("TvHome.Wallpaper", "Skipping unreadable showcase image: " + targetName);
                    if (showcaseRotating) backdrop.postDelayed(showcaseTick, 1_000L);
                    return;
                }
                ensureBackdropAdded();
                show(bitmap, animations);
                displayed = targetName;
                showcaseChangedWallpaper = true;
                showCaption(true);
                preferences.edit().putString("image", displayed)
                        .putLong("changed", System.currentTimeMillis()).apply();
                if (showcaseRotating) {
                    backdrop.postDelayed(showcaseTick, SHOWCASE_INTERVAL_MS);
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Cancels an in-flight crossfade and leaves {@code activeView} holding the newest {@code current} bitmap. */
    private void cancelLayerTransitions() {
        cancelKenBurnsAnimator();
        kenBurnsTarget = null;
        activeView.animate().setListener(null).cancel();
        backView.animate().setListener(null).cancel();
        if (backView.getVisibility() == View.VISIBLE && backView.getDrawable() != null) {
            backView.setAlpha(1f);
            ImageView completed = backView;
            ImageView replaced = activeView;
            activeView = completed;
            backView = replaced;
        }
        backView.setVisibility(View.GONE);
        backView.setAlpha(0f);
        backView.setImageDrawable(null);
        resetViewTransform(backView);
        Bitmap oldFading = fadingOut;
        fadingOut = null;
        recycleSafely(oldFading);
    }

    private void recycleSafely(Bitmap bmp) {
        if (bmp != null && bmp != current && !bmp.isRecycled()) {
            backdrop.postDelayed(() -> {
                if (!bmp.isRecycled()) bmp.recycle();
            }, 500L);
        }
    }

    /** Puts a picture on screen (instant or ~1s crossfade) and releases the previous one safely. */
    private void show(Bitmap bitmap, boolean crossfade) {
        cancelLayerTransitions();
        Bitmap old = current;
        current = bitmap;
        // The wallpaper covers the whole root; avoid drawing the fallback colour under it.
        root.setBackground(null);

        long fadeMs = !crossfade || !animations || old == null
                ? 0L
                : (reducedMotion ? 180L : SHOWCASE_CROSSFADE_MS);
        if (fadeMs <= 0L) {
            activeView.setAlpha(1f);
            resetViewTransform(activeView);
            activeView.setVisibility(View.VISIBLE);
            activeView.setImageBitmap(bitmap);
            backView.setVisibility(View.GONE);
            backView.setAlpha(0f);
            backView.setImageDrawable(null);
            resetViewTransform(backView);
            if (showcaseActive && animations && !reducedMotion && active) {
                startKenBurnsOn(activeView, true);
            }
            recycleSafely(old);
            return;
        }

        // Keep under layer opaque while the new layer fades 0 -> 1 on top so brightness never dips mid-transition.
        final ImageView under = activeView;
        final ImageView over = backView;
        fadingOut = old;
        under.setAlpha(1f);
        under.setVisibility(View.VISIBLE);
        resetViewTransform(over);
        over.setImageBitmap(bitmap);
        over.setAlpha(0f);
        over.setVisibility(View.VISIBLE);
        over.bringToFront();
        if (showcaseActive && animations && !reducedMotion && active) {
            startKenBurnsOn(over, true);
        }
        over.animate().alpha(1f).setDuration(fadeMs).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                over.animate().setListener(null);
                if (closed || current != bitmap || over.getDrawable() == null) return;
                activeView = over;
                backView = under;
                under.setVisibility(View.GONE);
                under.setAlpha(0f);
                under.setImageDrawable(null);
                resetViewTransform(under);
                Bitmap previous = fadingOut;
                fadingOut = null;
                recycleSafely(previous);
            }
        }).start();
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
