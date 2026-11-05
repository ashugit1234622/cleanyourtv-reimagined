package com.gathin.tvhome;

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

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    @SuppressWarnings("deprecation")
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
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
        rows.addView(row);
    }

    private String getPictureModeTitle() {
        SharedPreferences cache = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE);
        int mode = -1;
        try {
            Class<?> pmClass = Class.forName("mitv.display.PictureSettingsManager");
            Method getInstance = pmClass.getMethod("getInstance");
            Object pm = getInstance.invoke(null);
            if (pm != null) {
                Method getPicMode = pm.getClass().getMethod("getPictureModeValue");
                Object val = getPicMode.invoke(pm);
                if (val instanceof Number) {
                    mode = ((Number) val).intValue();
                }
            }
        } catch (Throwable ignored) {
        }
        if (mode < 0) {
            try {
                mode = Settings.Global.getInt(getContentResolver(), "picture_mode", 0);
            } catch (Throwable ignored) {
            }
        }
        if (mode >= 0) {
            String title = mapPictureMode(mode);
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
            Class<?> smClass = Class.forName("mitv.sound.SoundManager");
            Method getInstance = smClass.getMethod("getInstance");
            Object sm = getInstance.invoke(null);
            if (sm != null) {
                boolean dtsVxOn = false;
                try {
                    Method getVx = sm.getClass().getMethod("getDtsVirtualX");
                    Object vxVal = getVx.invoke(sm);
                    if (vxVal instanceof Number) {
                        dtsVxOn = ((Number) vxVal).intValue() == 4; // SOUND_DTS_STATUS_ON = 4
                    } else if (vxVal instanceof Boolean) {
                        dtsVxOn = (Boolean) vxVal;
                    }
                } catch (Throwable ignored) {}

                boolean bass = false;
                boolean clarity = false;
                boolean virtualizer = false;

                try {
                    Method getBass = sm.getClass().getMethod("getDtsBassEnhancement");
                    Object bVal = getBass.invoke(sm);
                    if (bVal instanceof Number) bass = ((Number) bVal).intValue() > 0;
                    else if (bVal instanceof Boolean) bass = (Boolean) bVal;
                } catch (Throwable ignored) {}

                try {
                    Method getClarity = sm.getClass().getMethod("getDtsDialogClarity");
                    Object cVal = getClarity.invoke(sm);
                    if (cVal instanceof Number) clarity = ((Number) cVal).intValue() > 0;
                    else if (cVal instanceof Boolean) clarity = (Boolean) cVal;
                } catch (Throwable ignored) {}

                try {
                    Method getVirt = sm.getClass().getMethod("getDtsVirtualSurround");
                    Object vVal = getVirt.invoke(sm);
                    if (vVal instanceof Number) virtualizer = ((Number) vVal).intValue() > 0;
                    else if (vVal instanceof Boolean) virtualizer = (Boolean) vVal;
                } catch (Throwable ignored) {}

                if (dtsVxOn || bass || clarity || virtualizer) {
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
                    } else if (virtualizer) {
                        dtsTitle = "DTS: Virtualizer";
                    } else {
                        dtsTitle = "DTS: Virtual:X";
                    }
                    cache.edit().putString("last_sound_mode", dtsTitle).apply();
                    return dtsTitle;
                }

                int soundMode = 0;
                try {
                    Method getMode = sm.getClass().getMethod("getSoundEffectType");
                    Object modeVal = getMode.invoke(sm);
                    if (modeVal instanceof Number) soundMode = ((Number) modeVal).intValue();
                } catch (Throwable ignored) {
                    try {
                        Method getMode = sm.getClass().getMethod("getSoundMode");
                        Object modeVal = getMode.invoke(sm);
                        if (modeVal instanceof Number) soundMode = ((Number) modeVal).intValue();
                    } catch (Throwable ignored2) {}
                }
                String modeTitle = mapSoundMode(soundMode);
                cache.edit().putString("last_sound_mode", modeTitle).apply();
                return modeTitle;
            }
        } catch (Throwable ignored) {}

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
            default: return "Standard";
        }
    }

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
