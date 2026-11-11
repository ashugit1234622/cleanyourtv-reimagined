package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService):
 * a few glass rows under the Settings pill, over Home or whatever app is playing. Translucent and in a
 * task of its own, so the app behind keeps running and Back returns to it. Pressing Settings again
 * closes the panel.
 */
public final class QuickSettingsActivity extends Activity {
    private static final String CACHE_PREFS = "quick_settings_cache";
    private static ClassLoader sMitvClassLoader;
    private QuickSettingsFocusIndicator focusIndicator;
    private boolean animations = true;
    private boolean reducedMotion = false;
    private boolean panelEntered = false;

    private ClassLoader getMitvClassLoader() {
        if (sMitvClassLoader == null) {
            try {
                sMitvClassLoader = new dalvik.system.PathClassLoader(
                        "/system/framework/mitvmiddlewareimpl.jar:/vendor/framework/com.mitv.mtvapiclientwrapper.jar",
                        getClassLoader()
                );
            } catch (Throwable t) {
                sMitvClassLoader = getClassLoader();
            }
        }
        return sMitvClassLoader;
    }

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Intent.ACTION_CREATE_SHORTCUT.equals(getIntent().getAction())) {
            Intent shortcutIntent = new Intent(this, QuickSettingsActivity.class);
            Intent intent = new Intent();
            intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
            intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.quick_settings_title));
            intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(this, R.drawable.ic_settings));
            setResult(RESULT_OK, intent);
            finish();
            return;
        }
        setContentView(R.layout.activity_quick_settings);
        Config cfg = Config.load(this);
        animations = cfg.animations;
        reducedMotion = cfg.reducedMotion;
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            View panel = findViewById(R.id.panel);
            android.widget.FrameLayout.LayoutParams position =
                    (android.widget.FrameLayout.LayoutParams) panel.getLayoutParams();
            position.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            int inset = position.getMarginEnd();
            position.setMarginEnd(0);
            position.setMargins(inset, position.topMargin, 0, position.bottomMargin);
            panel.setLayoutParams(position);
        }
        View scrim = findViewById(R.id.scrim);
        scrim.setOnClickListener(v -> finish());
        scrim.setFocusable(false);
        scrim.setFocusableInTouchMode(false);
        focusIndicator = findViewById(R.id.focusIndicator);
        if (focusIndicator != null) {
            focusIndicator.setReducedMotion(reducedMotion);
        }
        buildRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildRows();
    }

    private void buildRows() {
        LinearLayout rows = findViewById(R.id.rows);
        if (rows == null) return;
        if (focusIndicator != null) focusIndicator.reset();
        rows.removeAllViews();

        String picTitle = getPictureModeTitle();
        String soundTitle = getSoundModeOrDtsTitle();
        String btTitle = getConnectedBluetoothDeviceTitle();

        addRow(rows, R.drawable.ic_settings, getString(R.string.tv_settings),
                SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        addRow(rows, R.drawable.ic_picture, picTitle,
                SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS));
        addRow(rows, R.drawable.ic_sound, soundTitle,
                SystemSettings.intent(this, Settings.ACTION_SOUND_SETTINGS));
        addRow(rows, R.drawable.ic_wifi, getString(R.string.quick_network),
                SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        addRow(rows, R.drawable.ic_bluetooth, btTitle,
                SystemSettings.bluetoothSettingsIntent(this));
        addRow(rows, R.drawable.ic_apps, getString(R.string.quick_apps),
                SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        addRow(rows, R.drawable.ic_home_settings, getString(R.string.launcher_settings),
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));

        if (!panelEntered) {
            panelEntered = true;
            View panel = findViewById(R.id.panel);
            if (panel != null) {
                panel.animate().cancel();
                if (!animations) {
                    panel.setAlpha(1f);
                    panel.setScaleY(1f);
                    panel.setTranslationY(0f);
                } else {
                    float density = getResources().getDisplayMetrics().density;
                    panel.setAlpha(0f);
                    panel.setPivotY(0f);
                    panel.setScaleY(reducedMotion ? 1f : 0.92f);
                    panel.setTranslationY(reducedMotion ? 0f : -8f * density);
                    panel.animate()
                            .alpha(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .setDuration(reducedMotion ? Motion.REDUCED_MS : 200L)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                            .start();
                }
            }
            LauncherMotion.staggerRows(rows, animations, reducedMotion, getResources().getDisplayMetrics().density);
        }
        if (rows.getChildCount() > 0) {
            rows.post(() -> {
                if (rows.getChildCount() > 0) {
                    rows.getChildAt(0).requestFocus();
                }
            });
        }
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    @Override
    public void finish() {
        super.finish();
        LauncherMotion.clearWindowTransition(this);
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
    }

    private void addRow(LinearLayout rows, int icon, CharSequence title, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_quick_setting, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused && focusIndicator != null) {
                focusIndicator.onRowFocused(v);
            }
        });
        rows.addView(row);
    }

    private String getPictureModeTitle() {
        SharedPreferences cache = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE);
        int mode = -1;
        try {
            mode = Settings.Global.getInt(getContentResolver(), "picture_mode");
        } catch (Throwable ignored) {
            try {
                mode = Settings.System.getInt(getContentResolver(), "picture_mode");
            } catch (Throwable ignored2) {}
        }
        if (mode < 0) {
            try {
                Class<?> pmClass = null;
                try {
                    pmClass = Class.forName("mitv.display.PictureSettingsManager");
                } catch (Throwable t) {
                    try {
                        pmClass = Class.forName("mitv.display.PictureSettingsManager", true, getMitvClassLoader());
                    } catch (Throwable ignored) {}
                }
                if (pmClass != null) {
                    Method getInstance = pmClass.getMethod("getInstance");
                    Object pm = getInstance.invoke(null);
                    if (pm != null) {
                        Method getPicMode = null;
                        try {
                            getPicMode = pm.getClass().getMethod("getPictureModeValue");
                        } catch (Throwable t) {
                            try {
                                getPicMode = pm.getClass().getMethod("getSceneMode");
                            } catch (Throwable ignored) {}
                        }
                        if (getPicMode != null) {
                            Object val = getPicMode.invoke(pm);
                            if (val instanceof Number) {
                                mode = ((Number) val).intValue();
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("QuickSettings", "PictureSettingsManager error", t);
            }
        }
        if (mode >= 0) {
            String title = mapPictureMode(mode);
            android.util.Log.d("QuickSettings", "Detected active PictureMode: " + title + " (raw=" + mode + ")");
            cache.edit().putString("last_picture_mode", title).apply();
            return title;
        }
        return cache.getString("last_picture_mode", "Standard");
    }

    private static String mapPictureMode(int mode) {
        switch (mode) {
            case 0: return "Standard";
            case 1: return "Custom";
            case 2: return "Monitor";
            case 3: return "Movie";
            case 4: return "Game";
            case 5: return "Vivid";
            case 6: return "Sport";
            case 7: return "HDR Standard";
            case 10: return "HDR10+";
            case 11: return "DV Bright";
            case 12: return "DV Dark";
            case 13: return "DV Custom";
            case 14: return "HLG";
            case 15: return "HDR Game";
            case 16: return "HDR Vivid";
            case 17: return "HDR Movie";
            case 18: return "DV IQ";
            case 19: return "DV Game";
            case 20: return "IMAX";
            case 21: return "Filmmaker";
            case 22: return "HDR Monitor";
            case 23: return "HDR Filmmaker";
            case 24: return "DV Filmmaker";
            case 25: return "Energy Saving";
            default: return "Standard";
        }
    }

    private String getSoundModeOrDtsTitle() {
        SharedPreferences cache = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE);
        try {
            Class<?> smClass = null;
            try {
                smClass = Class.forName("mitv.sound.SoundManager");
            } catch (Throwable t) {
                try {
                    smClass = Class.forName("mitv.sound.SoundManager", true, getMitvClassLoader());
                } catch (Throwable ignored) {}
            }
            Object sm = null;
            if (smClass != null) {
                try {
                    Method getInstance = smClass.getMethod("getInstance");
                    sm = getInstance.invoke(null);
                } catch (Throwable t) {
                    android.util.Log.w("QuickSettings", "SoundManager.getInstance error", t);
                }
            }
            if (sm == null) {
                try {
                    Class<?> implClass = Class.forName("mitv.impl.sound.SoundManagerImpl", true, getMitvClassLoader());
                    Method getInstance = implClass.getMethod("getInstance");
                    sm = getInstance.invoke(null);
                } catch (Throwable ignored) {}
            }

            if (sm != null) {
                android.util.Log.d("QuickSettings", "sm loaded successfully: " + sm.getClass().getName());
                boolean dtsVxOn = false;
                try {
                    Method getVx = sm.getClass().getMethod("getDtsVirtualX");
                    Object vxVal = getVx.invoke(sm);
                    android.util.Log.d("QuickSettings", "raw getDtsVirtualX: " + vxVal);
                    if (vxVal instanceof Number) {
                        int v = ((Number) vxVal).intValue();
                        dtsVxOn = (v == 4 || v == 1);
                    } else if (vxVal instanceof Boolean) {
                        dtsVxOn = (Boolean) vxVal;
                    }
                } catch (Throwable t) {
                    android.util.Log.w("QuickSettings", "err getDtsVirtualX", t);
                }

                boolean bass = false;
                boolean clarity = false;
                boolean virtualizer = false;

                try {
                    Method getBass = sm.getClass().getMethod("getDtsBassEnhancement");
                    Object bVal = getBass.invoke(sm);
                    android.util.Log.d("QuickSettings", "raw getDtsBassEnhancement: " + bVal);
                    if (bVal instanceof Number) bass = ((Number) bVal).intValue() > 0;
                    else if (bVal instanceof Boolean) bass = (Boolean) bVal;
                } catch (Throwable t) {
                    android.util.Log.w("QuickSettings", "err getDtsBassEnhancement", t);
                }

                try {
                    Method getClarity = sm.getClass().getMethod("getDtsDialogClarity");
                    Object cVal = getClarity.invoke(sm);
                    android.util.Log.d("QuickSettings", "raw getDtsDialogClarity: " + cVal);
                    if (cVal instanceof Number) clarity = ((Number) cVal).intValue() > 0;
                    else if (cVal instanceof Boolean) clarity = (Boolean) cVal;
                } catch (Throwable t) {
                    android.util.Log.w("QuickSettings", "err getDtsDialogClarity", t);
                }

                try {
                    Method getVirt = sm.getClass().getMethod("getDtsVirtualSurround");
                    Object vVal = getVirt.invoke(sm);
                    android.util.Log.d("QuickSettings", "raw getDtsVirtualSurround: " + vVal);
                    if (vVal instanceof Number) virtualizer = ((Number) vVal).intValue() > 0;
                    else if (vVal instanceof Boolean) virtualizer = (Boolean) vVal;
                } catch (Throwable t) {
                    android.util.Log.w("QuickSettings", "err getDtsVirtualSurround", t);
                }

                if (bass || clarity || virtualizer) {
                    String dtsTitle;
                    if (bass && clarity && virtualizer) {
                        dtsTitle = "DTS: Virtual:X";
                    } else if (bass && clarity) {
                        dtsTitle = "DTS: Bass + Clarity";
                    } else if (bass && virtualizer) {
                        dtsTitle = "DTS: Bass + Virtualizer";
                    } else if (clarity && virtualizer) {
                        dtsTitle = "DTS: Clarity + Virtualizer";
                    } else if (bass) {
                        dtsTitle = "DTS: Bass";
                    } else if (clarity) {
                        dtsTitle = "DTS: Clarity";
                    } else {
                        dtsTitle = "DTS: Virtualizer";
                    }
                    android.util.Log.d("QuickSettings", "Detected active DTS: " + dtsTitle);
                    cache.edit().putString("last_sound_mode", dtsTitle).apply();
                    return dtsTitle;
                } else if (dtsVxOn) {
                    String dtsTitle = "DTS: Virtual:X";
                    android.util.Log.d("QuickSettings", "Detected active DTS VirtualX: " + dtsTitle);
                    cache.edit().putString("last_sound_mode", dtsTitle).apply();
                    return dtsTitle;
                }

                // DTS is off: get active sound mode
                int soundMode = 0;
                try {
                    Method getMode = sm.getClass().getMethod("getSoundMode");
                    Object modeVal = getMode.invoke(sm);
                    android.util.Log.d("QuickSettings", "raw getSoundMode: " + modeVal);
                    if (modeVal instanceof Number) soundMode = ((Number) modeVal).intValue();
                } catch (Throwable t) {
                    android.util.Log.w("QuickSettings", "err getSoundMode", t);
                    try {
                        Method getMode = sm.getClass().getMethod("getSoundModeHarman");
                        Object modeVal = getMode.invoke(sm);
                        android.util.Log.d("QuickSettings", "raw getSoundModeHarman: " + modeVal);
                        if (modeVal instanceof Number) soundMode = ((Number) modeVal).intValue();
                    } catch (Throwable t2) {
                        android.util.Log.w("QuickSettings", "err getSoundModeHarman", t2);
                    }
                }
                String modeTitle = mapSoundMode(soundMode);
                android.util.Log.d("QuickSettings", "Detected active SoundMode: " + modeTitle + " (raw=" + soundMode + ")");
                cache.edit().putString("last_sound_mode", modeTitle).apply();
                return modeTitle;
            }
        } catch (Throwable t) {
            android.util.Log.w("QuickSettings", "Error detecting sound mode", t);
        }

        return cache.getString("last_sound_mode", "Standard");
    }

    private static String mapSoundMode(int mode) {
        switch (mode) {
            case 0: return "Standard";
            case 1: case 62604: return "Movie";
            case 2: return "News";
            case 3: return "Game";
            case 4: case 62603: return "Custom";
            case 5: return "Music";
            case 6: return "Sport";
            default: return "Standard";
        }
    }

    @SuppressLint("MissingPermission")
    private String getConnectedBluetoothDeviceTitle() {
        SharedPreferences cache = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE);
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null) {
                BluetoothAdapter adapter = bm.getAdapter();
                if (adapter != null && adapter.isEnabled()) {
                    Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                    if (bonded != null) {
                        for (BluetoothDevice device : bonded) {
                            try {
                                Method isConnectedMethod = BluetoothDevice.class.getMethod("isConnected");
                                boolean connected = (boolean) isConnectedMethod.invoke(device);
                                if (connected) {
                                    String name = device.getName();
                                    if (name != null && !isRemoteControl(name)) {
                                        cache.edit().putString("last_bluetooth_device", name).apply();
                                        return name;
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        return getString(R.string.quick_bluetooth);
    }

    private static boolean isRemoteControl(String name) {
        if (name == null || name.trim().isEmpty()) return true;
        String lower = name.toLowerCase();
        return lower.contains("remote") || lower.contains(" rc") || lower.startsWith("rc")
                || lower.contains("mirc") || lower.contains("xiaomi rc") || lower.contains("mi remote");
    }
}
