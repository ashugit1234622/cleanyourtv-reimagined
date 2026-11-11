package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Dedicated settings screen for configuring the iOS-style app launch animation
 * (enable toggle, sweep duration speed, easing curve, card artwork morph, Home
 * background depth, rounded-corner morphing, app reveal transition, live preview,
 * and reset to defaults).
 */
public final class AppLaunchSettingsActivity extends Activity {

    private static final long PREVIEW_HOLD_MS = 520L;
    private static final long PREVIEW_EXIT_FADE_MS = 220L;

    private ScrollView scroll;
    private TextView enabledChoice;
    private TextView noteView;
    private TextView speedChoice;
    private TextView easingChoice;
    private TextView cardMorphChoice;
    private TextView bgDepthChoice;
    private TextView cornersChoice;
    private TextView revealChoice;
    private TextView previewChoice;
    private TextView resetChoice;

    private View previewBgDim;
    private FrameLayout previewCurtain;
    private ImageView previewCurtainArt;
    private TextView previewAppUi;
    private Bitmap previewBitmap;

    private ValueAnimator sweepAnimator;
    private final float[] previewCornerRadius = new float[1];
    private boolean animations = true;
    private boolean reducedMotion = false;
    private boolean previewRunning = false;
    private boolean previewCancelled = false;
    private boolean swallowingBack = false;

    private final Runnable endPreviewRunnable = new Runnable() {
        @Override
        public void run() {
            if (!previewRunning || previewCancelled) return;
            finishPreviewFadeOut();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_launch_settings);

        Config cfg = Config.load(this);
        animations = cfg.animations;
        reducedMotion = cfg.reducedMotion;

        scroll = findViewById(R.id.launchSettingsScroll);
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        }

        enabledChoice = findViewById(R.id.launchAnimEnabledChoice);
        noteView = findViewById(R.id.launchAnimNote);
        speedChoice = findViewById(R.id.launchSpeedChoice);
        easingChoice = findViewById(R.id.launchEasingChoice);
        cardMorphChoice = findViewById(R.id.launchCardMorphChoice);
        bgDepthChoice = findViewById(R.id.launchBgDepthChoice);
        cornersChoice = findViewById(R.id.launchCornersChoice);
        revealChoice = findViewById(R.id.launchRevealChoice);
        previewChoice = findViewById(R.id.launchPreviewChoice);
        resetChoice = findViewById(R.id.launchResetChoice);

        previewBgDim = findViewById(R.id.previewBgDim);
        previewCurtain = findViewById(R.id.previewCurtain);
        previewCurtainArt = findViewById(R.id.previewCurtainArt);
        previewAppUi = findViewById(R.id.previewAppUi);

        previewCurtain.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float r = previewCornerRadius[0];
                if (r > 0.25f) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                } else {
                    outline.setRect(0, 0, view.getWidth(), view.getHeight());
                }
            }
        });

        setupBooleanRow(enabledChoice, AppLaunchSettings.KEY_ENABLED, true);
        setupStepperRow(speedChoice, AppLaunchSettings.KEY_SPEED, AppLaunchSettings.SPEEDS, AppLaunchSettings.SPEED_NORMAL);
        setupStepperRow(easingChoice, AppLaunchSettings.KEY_EASING, AppLaunchSettings.EASINGS, AppLaunchSettings.EASING_IOS_IN_OUT);
        setupBooleanRow(cardMorphChoice, AppLaunchSettings.KEY_CARD_MORPH, true);
        setupStepperRow(bgDepthChoice, AppLaunchSettings.KEY_BG_DEPTH, AppLaunchSettings.BG_DEPTHS, AppLaunchSettings.DEPTH_MEDIUM);
        setupBooleanRow(cornersChoice, AppLaunchSettings.KEY_ROUNDED_CORNERS, true);
        setupStepperRow(revealChoice, AppLaunchSettings.KEY_APP_REVEAL, AppLaunchSettings.APP_REVEALS, AppLaunchSettings.REVEAL_ZOOM_FADE);

        previewChoice.setOnClickListener(v -> playPreview());
        resetChoice.setOnClickListener(v -> {
            cancelPreview(false);
            AppLaunchSettings.resetToDefaults(this);
            setResult(RESULT_OK);
            refresh();
            Toast.makeText(this, R.string.launch_anim_reset_done, Toast.LENGTH_SHORT).show();
        });

        refresh();
        enabledChoice.requestFocus();
    }

    @Override
    protected void onPause() {
        cancelPreview(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelPreview(false);
        if (previewCurtainArt != null) {
            previewCurtainArt.setImageDrawable(null);
        }
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        LauncherMotion.applySubScreenCloseTransition(this, animations, reducedMotion);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && previewRunning) {
                swallowingBack = true;
                cancelPreview(true);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && swallowingBack) {
                swallowingBack = false;
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void setupBooleanRow(TextView row, String prefKey, boolean defaultValue) {
        row.setOnClickListener(v -> toggleBoolean(prefKey, defaultValue));
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                v.playSoundEffect(SoundEffectConstants.CLICK);
                toggleBoolean(prefKey, defaultValue);
                return true;
            }
            return false;
        });
    }

    private void toggleBoolean(String prefKey, boolean defaultValue) {
        SharedPreferences prefs = AppLaunchSettings.prefs(this);
        boolean next = !prefs.getBoolean(prefKey, defaultValue);
        prefs.edit().putBoolean(prefKey, next).apply();
        AppLaunchSettings.bumpSettingsRevision(this);
        setResult(RESULT_OK);
        refresh();
    }

    private void setupStepperRow(TextView row, String prefKey, String[] options, String defaultVal) {
        row.setOnClickListener(v -> stepOption(prefKey, options, defaultVal, 1));
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                v.playSoundEffect(SoundEffectConstants.CLICK);
                stepOption(prefKey, options, defaultVal, -1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                v.playSoundEffect(SoundEffectConstants.CLICK);
                stepOption(prefKey, options, defaultVal, 1);
                return true;
            }
            return false;
        });
    }

    private void stepOption(String prefKey, String[] options, String defaultVal, int delta) {
        SharedPreferences prefs = AppLaunchSettings.prefs(this);
        String current = prefs.getString(prefKey, defaultVal);
        String next = AppLaunchSettings.stepOption(options, current, delta);
        prefs.edit().putString(prefKey, next).apply();
        AppLaunchSettings.bumpSettingsRevision(this);
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        AppLaunchSettings s = AppLaunchSettings.load(this);

        enabledChoice.setText(getString(R.string.launch_anim_enabled_state,
                getString(s.enabled ? R.string.on : R.string.off)));

        if (!animations) {
            noteView.setText(R.string.launch_anim_global_off_note);
            noteView.setVisibility(View.VISIBLE);
        } else if (reducedMotion) {
            noteView.setText(R.string.launch_anim_reduced_note);
            noteView.setVisibility(View.VISIBLE);
        } else {
            noteView.setVisibility(View.GONE);
        }

        speedChoice.setText(getString(R.string.launch_anim_speed_state, speedLabel(s.speed)));
        easingChoice.setText(getString(R.string.launch_anim_easing_state, easingLabel(s.easing)));
        cardMorphChoice.setText(getString(R.string.launch_anim_card_morph_state,
                getString(s.cardMorph ? R.string.on : R.string.off)));
        bgDepthChoice.setText(getString(R.string.launch_anim_bg_depth_state, depthLabel(s.bgDepth)));
        cornersChoice.setText(getString(R.string.launch_anim_corners_state,
                getString(s.roundedCorners ? R.string.launch_anim_corners_rounded : R.string.launch_anim_corners_square)));
        revealChoice.setText(getString(R.string.launch_anim_reveal_state, revealLabel(s.appReveal)));

        boolean active = s.enabled && animations && !reducedMotion;
        setRowActive(speedChoice, active);
        setRowActive(easingChoice, active);
        setRowActive(cardMorphChoice, active);
        setRowActive(bgDepthChoice, active);
        setRowActive(cornersChoice, active);
        setRowActive(revealChoice, active);
        setRowActive(previewChoice, active);
    }

    private static void setRowActive(TextView row, boolean active) {
        row.setEnabled(active);
        row.setAlpha(active ? 1f : 0.5f);
    }

    private String speedLabel(String speed) {
        switch (speed) {
            case AppLaunchSettings.SPEED_VERY_FAST:
                return getString(R.string.launch_anim_speed_very_fast);
            case AppLaunchSettings.SPEED_FAST:
                return getString(R.string.launch_anim_speed_fast);
            case AppLaunchSettings.SPEED_SLOW:
                return getString(R.string.launch_anim_speed_slow);
            case AppLaunchSettings.SPEED_CINEMATIC:
                return getString(R.string.launch_anim_speed_cinematic);
            case AppLaunchSettings.SPEED_NORMAL:
            default:
                return getString(R.string.launch_anim_speed_normal);
        }
    }

    private String easingLabel(String easing) {
        switch (easing) {
            case AppLaunchSettings.EASING_STRONG_IN:
                return getString(R.string.launch_anim_easing_strong_in);
            case AppLaunchSettings.EASING_BALANCED_IN_OUT:
                return getString(R.string.launch_anim_easing_balanced_in_out);
            case AppLaunchSettings.EASING_EASE_OUT:
                return getString(R.string.launch_anim_easing_ease_out);
            case AppLaunchSettings.EASING_LINEAR:
                return getString(R.string.launch_anim_easing_linear);
            case AppLaunchSettings.EASING_IOS_IN_OUT:
            default:
                return getString(R.string.launch_anim_easing_ios_in_out);
        }
    }

    private String depthLabel(String depth) {
        switch (depth) {
            case AppLaunchSettings.DEPTH_OFF:
                return getString(R.string.launch_anim_depth_off);
            case AppLaunchSettings.DEPTH_SUBTLE:
                return getString(R.string.launch_anim_depth_subtle);
            case AppLaunchSettings.DEPTH_DEEP:
                return getString(R.string.launch_anim_depth_deep);
            case AppLaunchSettings.DEPTH_MEDIUM:
            default:
                return getString(R.string.launch_anim_depth_medium);
        }
    }

    private String revealLabel(String reveal) {
        switch (reveal) {
            case AppLaunchSettings.REVEAL_FADE:
                return getString(R.string.launch_anim_reveal_fade);
            case AppLaunchSettings.REVEAL_INSTANT:
                return getString(R.string.launch_anim_reveal_instant);
            case AppLaunchSettings.REVEAL_ZOOM_FADE:
            default:
                return getString(R.string.launch_anim_reveal_zoom_fade);
        }
    }

    private void playPreview() {
        cancelPreview(false);

        AppLaunchSettings s = AppLaunchSettings.load(this);
        if (!s.enabled || !animations || reducedMotion) return;

        int rootW = previewCurtain.getWidth();
        int rootH = previewCurtain.getHeight();
        if (rootW <= 0 || rootH <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        float tileW = Math.min(rootW * 0.40f, 180f * density);
        float tileH = Math.min(rootH * 0.40f, 101f * density);
        float cx = Math.min(rootW * 0.50f, 72f * density + tileW * 0.5f);
        float cy = Math.max(rootH * 0.50f, rootH - 56f * density - tileH * 0.5f);

        LauncherMotion.LaunchCurtainPose pose =
                LauncherMotion.computeCurtainPose(rootW, rootH, cx, cy, tileW, tileH);
        float startCornerPx = s.roundedCorners ? LauncherMotion.curtainCornerRadiusPx(rootH) : 0f;

        previewRunning = true;
        previewCancelled = false;

        previewAppUi.animate().cancel();
        previewAppUi.setVisibility(View.INVISIBLE);
        previewAppUi.setAlpha(0f);
        previewAppUi.setScaleX(1f);
        previewAppUi.setScaleY(1f);

        if (s.cardMorph) {
            previewCurtainArt.setImageBitmap(ensurePreviewBitmap(Math.round(tileW), Math.round(tileH)));
            previewCurtainArt.setVisibility(View.VISIBLE);
            previewCurtainArt.setAlpha(1f);
        } else {
            previewCurtainArt.setVisibility(View.GONE);
        }

        previewCurtain.animate().cancel();
        previewCurtain.setAlpha(1f);
        previewCurtain.setClipToOutline(s.roundedCorners);
        previewCurtain.setPivotX(rootW * 0.5f);
        previewCurtain.setPivotY(rootH * 0.5f);
        applyCurtainTransform(pose, startCornerPx, s, 0f);
        previewCurtain.setVisibility(View.VISIBLE);

        scroll.animate().cancel();
        scroll.setPivotX(scroll.getWidth() * 0.5f);
        scroll.setPivotY(scroll.getHeight() * 0.5f);
        scroll.setScaleX(1f);
        scroll.setScaleY(1f);

        previewBgDim.animate().cancel();
        if (s.hasBackgroundDepth()) {
            previewBgDim.setAlpha(0f);
            previewBgDim.setVisibility(View.VISIBLE);
        } else {
            previewBgDim.setVisibility(View.GONE);
        }

        sweepAnimator = ValueAnimator.ofFloat(0f, 1f);
        sweepAnimator.setDuration(s.sweepDurationMs());
        sweepAnimator.setInterpolator(s.createInterpolator());
        sweepAnimator.addUpdateListener(anim -> {
            if (previewCancelled) return;
            float u = Math.max(0f, Math.min(1f, (Float) anim.getAnimatedValue()));
            applyCurtainTransform(pose, startCornerPx, s, u);
        });
        sweepAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                sweepAnimator = null;
                if (previewCancelled || !previewRunning) return;
                applyCurtainTransform(pose, startCornerPx, s, 1f);
                previewCurtainArt.setVisibility(View.GONE);
                playPreviewRevealPhase(s);
            }
        });
        sweepAnimator.start();
    }

    private void applyCurtainTransform(LauncherMotion.LaunchCurtainPose pose,
                                       float startCornerPx,
                                       AppLaunchSettings s,
                                       float u) {
        previewCurtain.setTranslationX(pose.tx * (1f - u));
        previewCurtain.setTranslationY(pose.ty * (1f - u));
        previewCurtain.setScaleX(pose.startScaleX + (pose.fullScaleX - pose.startScaleX) * u);
        previewCurtain.setScaleY(pose.startScaleY + (pose.fullScaleY - pose.startScaleY) * u);

        if (s.roundedCorners) {
            previewCornerRadius[0] = LauncherMotion.curtainCornerRadiusAt(startCornerPx, u);
            previewCurtain.invalidateOutline();
        } else if (previewCornerRadius[0] != 0f) {
            previewCornerRadius[0] = 0f;
            previewCurtain.invalidateOutline();
        }

        if (s.cardMorph) {
            previewCurtainArt.setAlpha(LauncherMotion.curtainArtAlphaAt(u));
        }

        if (s.hasBackgroundDepth()) {
            float bgScale = 1f - s.bgScaleDelta() * u;
            scroll.setScaleX(bgScale);
            scroll.setScaleY(bgScale);
            previewBgDim.setAlpha(s.bgDimFactor() * u);
        }
    }

    private void playPreviewRevealPhase(AppLaunchSettings s) {
        long revealMs = s.revealDurationMs();
        previewAppUi.setVisibility(View.VISIBLE);
        if (AppLaunchSettings.REVEAL_INSTANT.equals(s.appReveal) || revealMs <= 0L) {
            previewAppUi.setAlpha(1f);
            previewAppUi.setScaleX(1f);
            previewAppUi.setScaleY(1f);
            previewCurtain.postDelayed(endPreviewRunnable, PREVIEW_HOLD_MS);
            return;
        }

        if (AppLaunchSettings.REVEAL_FADE.equals(s.appReveal)) {
            previewAppUi.setAlpha(0f);
            previewAppUi.setScaleX(1f);
            previewAppUi.setScaleY(1f);
            previewAppUi.animate()
                    .alpha(1f)
                    .setDuration(revealMs)
                    .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                    .withEndAction(() -> {
                        if (!previewCancelled && previewRunning) {
                            previewCurtain.postDelayed(endPreviewRunnable, PREVIEW_HOLD_MS);
                        }
                    })
                    .start();
            return;
        }

        float startScale = (AppLaunchSettings.SPEED_VERY_FAST.equals(s.speed)
                || AppLaunchSettings.SPEED_FAST.equals(s.speed)) ? 0.95f
                : (AppLaunchSettings.SPEED_SLOW.equals(s.speed)
                || AppLaunchSettings.SPEED_CINEMATIC.equals(s.speed) ? 0.91f : 0.93f);
        previewAppUi.setAlpha(0f);
        previewAppUi.setScaleX(startScale);
        previewAppUi.setScaleY(startScale);
        previewAppUi.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(revealMs)
                .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .withEndAction(() -> {
                    if (!previewCancelled && previewRunning) {
                        previewCurtain.postDelayed(endPreviewRunnable, PREVIEW_HOLD_MS);
                    }
                })
                .start();
    }

    private void finishPreviewFadeOut() {
        previewCurtain.animate()
                .alpha(0f)
                .setDuration(PREVIEW_EXIT_FADE_MS)
                .withEndAction(() -> resetPreviewViews())
                .start();
        if (previewBgDim.getVisibility() == View.VISIBLE) {
            previewBgDim.animate().alpha(0f).setDuration(PREVIEW_EXIT_FADE_MS).start();
        }
        scroll.animate().scaleX(1f).scaleY(1f).setDuration(PREVIEW_EXIT_FADE_MS).start();
    }

    private void cancelPreview(boolean smoothFade) {
        previewCancelled = true;
        if (previewCurtain != null) {
            previewCurtain.removeCallbacks(endPreviewRunnable);
        }
        if (sweepAnimator != null) {
            sweepAnimator.cancel();
            sweepAnimator = null;
        }
        if (!previewRunning) {
            return;
        }
        if (previewAppUi != null) {
            previewAppUi.animate().cancel();
        }
        if (smoothFade && previewCurtain != null) {
            previewRunning = false;
            finishPreviewFadeOut();
        } else {
            resetPreviewViews();
        }
    }

    private void resetPreviewViews() {
        previewRunning = false;
        if (previewCurtain != null) {
            previewCurtain.animate().cancel();
            previewCurtain.setVisibility(View.INVISIBLE);
            previewCurtain.setAlpha(1f);
            previewCurtain.setTranslationX(0f);
            previewCurtain.setTranslationY(0f);
            previewCurtain.setScaleX(1f);
            previewCurtain.setScaleY(1f);
            previewCurtain.setClipToOutline(false);
        }
        if (previewCurtainArt != null) {
            previewCurtainArt.setVisibility(View.GONE);
            previewCurtainArt.setAlpha(1f);
        }
        if (previewAppUi != null) {
            previewAppUi.animate().cancel();
            previewAppUi.setVisibility(View.INVISIBLE);
            previewAppUi.setAlpha(0f);
            previewAppUi.setScaleX(1f);
            previewAppUi.setScaleY(1f);
        }
        if (previewBgDim != null) {
            previewBgDim.animate().cancel();
            previewBgDim.setVisibility(View.GONE);
            previewBgDim.setAlpha(0f);
        }
        if (scroll != null) {
            scroll.animate().cancel();
            scroll.setScaleX(1f);
            scroll.setScaleY(1f);
        }
    }

    private Bitmap ensurePreviewBitmap(int widthPx, int heightPx) {
        int w = Math.max(120, widthPx * 2);
        int h = Math.max(68, heightPx * 2);
        if (previewBitmap != null && !previewBitmap.isRecycled()
                && previewBitmap.getWidth() == w && previewBitmap.getHeight() == h) {
            return previewBitmap;
        }
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0f, 0f, w, h,
                Color.parseColor("#FF243B55"),
                Color.parseColor("#FF141E30"),
                Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, w, h, bg);

        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(Color.parseColor("#334FB3FF"));
        canvas.drawCircle(w * 0.25f, h * 0.30f, w * 0.38f, glow);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(2f, h * 0.02f));
        border.setColor(Color.parseColor("#44FFFFFF"));
        canvas.drawRoundRect(new RectF(border.getStrokeWidth(), border.getStrokeWidth(),
                w - border.getStrokeWidth(), h - border.getStrokeWidth()),
                h * 0.12f, h * 0.12f, border);

        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(Color.WHITE);
        label.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(h * 0.22f);
        Paint.FontMetrics fm = label.getFontMetrics();
        float baseline = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(getString(R.string.launch_anim_preview_title), w * 0.5f, baseline, label);

        previewBitmap = bmp;
        return bmp;
    }
}

