package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Dedicated D-pad and remote-friendly settings screen for the Boot & Wake Intro.
 * Supports Left/Right to step values and OK to toggle/activate.
 */
public final class IntroSettingsActivity extends Activity {

    public static final String EXTRA_FOCUS_PREVIEW = "focus_preview";

    private SharedPreferences userPrefs;
    private Config config;

    private TextView bootChoice;
    private TextView reducedMotionNote;
    private TextView wakeChoice;
    private TextView wakeGapChoice;
    private TextView holdChoice;
    private TextView speedChoice;
    private TextView animationChoice;
    private TextView messagesChoice;
    private TextView previewChoice;
    private TextView resetChoice;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_intro_settings);

        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        }

        userPrefs = getSharedPreferences(IntroSettings.PREFS_NAME, MODE_PRIVATE);
        config = Config.load(this);

        bootChoice = findViewById(R.id.introBootChoice);
        reducedMotionNote = findViewById(R.id.introReducedMotionNote);
        wakeChoice = findViewById(R.id.introWakeChoice);
        wakeGapChoice = findViewById(R.id.introWakeGapChoice);
        holdChoice = findViewById(R.id.introHoldChoice);
        speedChoice = findViewById(R.id.introSpeedChoice);
        animationChoice = findViewById(R.id.introAnimationChoice);
        messagesChoice = findViewById(R.id.introMessagesChoice);
        previewChoice = findViewById(R.id.introPreviewChoice);
        resetChoice = findViewById(R.id.introResetChoice);

        setupRowListeners();
        refresh();

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_FOCUS_PREVIEW, false)) {
            previewChoice.requestFocus();
        } else {
            bootChoice.requestFocus();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_FOCUS_PREVIEW, false)) {
            previewChoice.post(() -> previewChoice.requestFocus());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        config = Config.load(this);
        refresh();
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_FOCUS_PREVIEW, false)) {
            previewChoice.post(() -> previewChoice.requestFocus());
        }
    }

    private void setupRowListeners() {
        // 1. Boot Intro toggle
        setupStepperRow(bootChoice, this::toggleBootIntro, this::toggleBootIntro, this::toggleBootIntro);

        // 2. Play on wake toggle
        setupStepperRow(wakeChoice, this::toggleWakeIntro, this::toggleWakeIntro, this::toggleWakeIntro);

        // 3. Wake gap stepper (1, 2, 5, 10, 15, 30, 60 min)
        setupStepperRow(wakeGapChoice,
                () -> stepWakeGap(-1),
                () -> stepWakeGap(1),
                () -> stepWakeGap(1));

        // 4. Hold time stepper (0.5s to 4.0s in 0.5s steps)
        setupStepperRow(holdChoice,
                () -> stepHoldTime(-500),
                () -> stepHoldTime(500),
                () -> stepHoldTime(500));

        // 5. Speed stepper (Slow / Normal / Fast)
        setupStepperRow(speedChoice,
                () -> stepSpeed(-1),
                () -> stepSpeed(1),
                () -> stepSpeed(1));

        // 6. Animation stepper (Random / Pop / Fade / Slide / Letters / Shimmer)
        setupStepperRow(animationChoice,
                () -> stepAnimation(-1),
                () -> stepAnimation(1),
                () -> stepAnimation(1));

        // 7. Messages stepper (Titles and tips / Titles only / Off)
        setupStepperRow(messagesChoice,
                () -> stepMessages(-1),
                () -> stepMessages(1),
                () -> stepMessages(1));

        // 8. Preview intro
        previewChoice.setOnClickListener(v -> playPreview());
        previewChoice.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                v.playSoundEffect(SoundEffectConstants.CLICK);
                playPreview();
                return true;
            }
            return false;
        });

        // 9. Reset to defaults
        resetChoice.setOnClickListener(v -> resetDefaults());
        resetChoice.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                v.playSoundEffect(SoundEffectConstants.CLICK);
                resetDefaults();
                return true;
            }
            return false;
        });
    }

    private void setupStepperRow(TextView row, Runnable onLeft, Runnable onRight, Runnable onSelect) {
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                onLeft.run();
                v.playSoundEffect(SoundEffectConstants.CLICK);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                onRight.run();
                v.playSoundEffect(SoundEffectConstants.CLICK);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                onSelect.run();
                v.playSoundEffect(SoundEffectConstants.CLICK);
                return true;
            }
            return false;
        });
        row.setOnClickListener(v -> onSelect.run());
    }

    private void toggleBootIntro() {
        IntroSettings s = IntroSettings.load(this, config);
        userPrefs.edit().putBoolean(IntroSettings.KEY_BOOT_INTRO, !s.bootIntro).apply();
        refresh();
    }

    private void toggleWakeIntro() {
        IntroSettings s = IntroSettings.load(this, config);
        userPrefs.edit().putBoolean(IntroSettings.KEY_INTRO_ON_WAKE, !s.introOnWake).apply();
        refresh();
    }

    private void stepWakeGap(int delta) {
        IntroSettings s = IntroSettings.load(this, config);
        int current = s.wakeGapMinutes;
        int idx = 0;
        for (int i = 0; i < IntroSettings.WAKE_GAPS.length; i++) {
            if (IntroSettings.WAKE_GAPS[i] == current) {
                idx = i;
                break;
            }
        }
        int next = idx + delta;
        if (next < 0) next = 0;
        else if (next >= IntroSettings.WAKE_GAPS.length) next = delta > 0 ? 0 : IntroSettings.WAKE_GAPS.length - 1;
        userPrefs.edit().putInt(IntroSettings.KEY_WAKE_GAP_MIN, IntroSettings.WAKE_GAPS[next]).apply();
        refresh();
    }

    private void stepHoldTime(int deltaMs) {
        IntroSettings s = IntroSettings.load(this, config);
        int current = s.holdMs;
        int idx = 0;
        for (int i = 0; i < IntroSettings.HOLD_TIMES_MS.length; i++) {
            if (IntroSettings.HOLD_TIMES_MS[i] == current) {
                idx = i;
                break;
            }
        }
        int next = idx + (deltaMs > 0 ? 1 : -1);
        if (next < 0) next = 0;
        else if (next >= IntroSettings.HOLD_TIMES_MS.length) next = deltaMs > 0 ? 0 : IntroSettings.HOLD_TIMES_MS.length - 1;
        userPrefs.edit().putInt(IntroSettings.KEY_HOLD_MS, IntroSettings.HOLD_TIMES_MS[next]).apply();
        refresh();
    }

    private void stepSpeed(int delta) {
        IntroSettings s = IntroSettings.load(this, config);
        int idx = 1; // default normal
        for (int i = 0; i < IntroSettings.SPEEDS.length; i++) {
            if (IntroSettings.SPEEDS[i].equalsIgnoreCase(s.speed)) {
                idx = i;
                break;
            }
        }
        int next = idx + delta;
        if (next < 0) next = 0;
        else if (next >= IntroSettings.SPEEDS.length) next = delta > 0 ? 0 : IntroSettings.SPEEDS.length - 1;
        userPrefs.edit().putString(IntroSettings.KEY_SPEED, IntroSettings.SPEEDS[next]).apply();
        refresh();
    }

    private void stepAnimation(int delta) {
        IntroSettings s = IntroSettings.load(this, config);
        int idx = 0;
        for (int i = 0; i < IntroSettings.ANIMATIONS.length; i++) {
            if (IntroSettings.ANIMATIONS[i].equalsIgnoreCase(s.animation)) {
                idx = i;
                break;
            }
        }
        int next = idx + delta;
        if (next < 0) next = 0;
        else if (next >= IntroSettings.ANIMATIONS.length) next = delta > 0 ? 0 : IntroSettings.ANIMATIONS.length - 1;
        userPrefs.edit().putString(IntroSettings.KEY_ANIMATION, IntroSettings.ANIMATIONS[next]).apply();
        refresh();
    }

    private void stepMessages(int delta) {
        IntroSettings s = IntroSettings.load(this, config);
        int idx = 0;
        for (int i = 0; i < IntroSettings.MESSAGES_MODES.length; i++) {
            if (IntroSettings.MESSAGES_MODES[i].equalsIgnoreCase(s.messagesMode)) {
                idx = i;
                break;
            }
        }
        int next = idx + delta;
        if (next < 0) next = 0;
        else if (next >= IntroSettings.MESSAGES_MODES.length) next = delta > 0 ? 0 : IntroSettings.MESSAGES_MODES.length - 1;
        userPrefs.edit().putString(IntroSettings.KEY_MESSAGES, IntroSettings.MESSAGES_MODES[next]).apply();
        refresh();
    }

    private void playPreview() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(MainActivity.ACTION_PREVIEW_INTRO);
        intent.putExtra("preview_from_settings", true);
        startActivity(intent);
    }

    private void resetDefaults() {
        IntroSettings.resetToDefaults(this);
        refresh();
        Toast.makeText(this, R.string.intro_reset_done, Toast.LENGTH_SHORT).show();
    }

    private void refresh() {
        IntroSettings s = IntroSettings.load(this, config);

        String onStr = getString(R.string.on);
        String offStr = getString(R.string.off);

        // Boot Intro row
        if (config.reducedMotion) {
            bootChoice.setText(getString(R.string.intro_boot_state,
                    (s.bootIntro ? onStr : offStr) + " (" + getString(R.string.intro_reduced_motion_tag) + ")"));
            reducedMotionNote.setVisibility(View.VISIBLE);
        } else {
            bootChoice.setText(getString(R.string.intro_boot_state, s.bootIntro ? onStr : offStr));
            reducedMotionNote.setVisibility(View.GONE);
        }

        // Play on wake
        wakeChoice.setText(getString(R.string.intro_wake_state, s.introOnWake ? onStr : offStr));

        // Wake gap
        wakeGapChoice.setText(getString(R.string.intro_wake_gap_state, s.wakeGapMinutes));

        // Hold time
        float holdSec = s.holdMs / 1000f;
        holdChoice.setText(getString(R.string.intro_hold_state, String.format(Locale.ROOT, "%.1fs", holdSec)));

        // Speed
        String speedLabel = getString(R.string.intro_speed_normal);
        if (IntroSettings.SPEED_SLOW.equalsIgnoreCase(s.speed)) speedLabel = getString(R.string.intro_speed_slow);
        else if (IntroSettings.SPEED_FAST.equalsIgnoreCase(s.speed)) speedLabel = getString(R.string.intro_speed_fast);
        speedChoice.setText(getString(R.string.intro_speed_state, speedLabel));

        // Animation
        String animLabel = formatAnimLabel(s.animation);
        animationChoice.setText(getString(R.string.intro_animation_state, animLabel));

        // Messages
        String msgLabel = getString(R.string.intro_messages_all);
        if (IntroSettings.MESSAGES_TITLES_ONLY.equalsIgnoreCase(s.messagesMode)) {
            msgLabel = getString(R.string.intro_messages_titles);
        } else if (IntroSettings.MESSAGES_OFF.equalsIgnoreCase(s.messagesMode)) {
            msgLabel = getString(R.string.intro_messages_off);
        }
        messagesChoice.setText(getString(R.string.intro_messages_state, msgLabel));
    }

    private String formatAnimLabel(String anim) {
        if (anim == null) return getString(R.string.intro_anim_pop);
        String a = anim.toLowerCase(Locale.ROOT);
        switch (a) {
            case "random": return getString(R.string.intro_anim_random);
            case "fade": return getString(R.string.intro_anim_fade);
            case "slide": return getString(R.string.intro_anim_slide);
            case "letters": return getString(R.string.intro_anim_letters);
            case "shimmer": return getString(R.string.intro_anim_shimmer);
            case "pop":
            default:
                return getString(R.string.intro_anim_pop);
        }
    }
}

