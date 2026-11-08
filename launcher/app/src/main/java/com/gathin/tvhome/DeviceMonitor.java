package com.gathin.tvhome;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Monitors TV accessory hardware states:
 * 1. Paired Bluetooth TV Remote battery level.
 * 2. Connected Bluetooth audio devices (headphones/speakers) and their battery levels.
 */
public final class DeviceMonitor {

    private static final String TAG = "TvHome.DeviceMonitor";

    public interface Listener {
        void onDeviceStateChanged();
    }

    public static final class AudioStatus {
        public final boolean isConnected;
        public final String name;
        public final int batteryPercent; // -1 if unknown

        public AudioStatus(boolean isConnected, String name, int batteryPercent) {
            this.isConnected = isConnected;
            this.name = name != null ? name : "";
            this.batteryPercent = batteryPercent;
        }
    }

    public static final class RemoteStatus {
        public final boolean isConnected;
        public final String name;
        public final int batteryPercent; // -1 if unknown

        public RemoteStatus(boolean isConnected, String name, int batteryPercent) {
            this.isConnected = isConnected;
            this.name = name != null ? name : "";
            this.batteryPercent = batteryPercent;
        }
    }

    private final Context context;
    private final Handler mainHandler;
    private Listener listener;
    private boolean registered = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            notifyChanged();
        }
    };

    private final AudioDeviceCallback audioCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            notifyChanged();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            notifyChanged();
        }
    };

    public DeviceMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void start(Listener listener) {
        this.listener = listener;
        if (!registered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
            filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }

            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.registerAudioDeviceCallback(audioCallback, mainHandler);
            }
            registered = true;
        }
        notifyChanged();
    }

    public void stop() {
        if (registered) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {}
            try {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.unregisterAudioDeviceCallback(audioCallback);
                }
            } catch (Exception ignored) {}
            registered = false;
        }
        listener = null;
    }

    private void notifyChanged() {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onDeviceStateChanged();
            }
        });
    }

    /**
     * Resolves current remote status.
     */
    public RemoteStatus getRemoteStatus() {
        // 1. Try InputManager (API 31+)
        try {
            InputManager im = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            if (im != null) {
                for (int id : im.getInputDeviceIds()) {
                    InputDevice dev = im.getInputDevice(id);
                    if (dev == null || !dev.isExternal()) continue;
                    int sources = dev.getSources();
                    if ((sources & InputDevice.SOURCE_KEYBOARD) != 0 || (sources & InputDevice.SOURCE_DPAD) != 0) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            android.hardware.BatteryState bs = dev.getBatteryState();
                            if (bs != null && bs.isPresent()) {
                                float cap = bs.getCapacity();
                                if (cap >= 0f) {
                                    int pct = Math.round(cap * 100f);
                                    return new RemoteStatus(true, dev.getName(), pct);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "InputDevice query error: " + t.getMessage());
        }

        // 2. Try bonded Bluetooth devices
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()) {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice dev : bonded) {
                        String name = dev.getName();
                        if (name == null) continue;
                        String lower = name.toLowerCase();
                        if (lower.contains("rc") || lower.contains("remote") || lower.contains("mirc")) {
                            int battery = readBatteryLevel(dev);
                            return new RemoteStatus(true, name, battery);
                        }
                    }
                }
            }
        } catch (SecurityException se) {
            Log.w(TAG, "Bluetooth permission not granted: " + se.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "Bluetooth remote query error: " + t.getMessage());
        }

        return new RemoteStatus(false, "", -1);
    }

    /**
     * Resolves current connected Bluetooth audio device and battery.
     */
    public AudioStatus getConnectedAudioStatus() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo info : outputs) {
                    int type = info.getType();
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                            || type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                        String productName = info.getProductName() != null ? info.getProductName().toString() : "Audio";
                        int battery = -1;

                        // Try to find matching BluetoothDevice for battery
                        try {
                            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                            if (adapter != null && adapter.isEnabled()) {
                                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                                if (bonded != null) {
                                    for (BluetoothDevice bd : bonded) {
                                        String bdName = bd.getName();
                                        if (bdName != null && (bdName.equalsIgnoreCase(productName) || productName.contains(bdName))) {
                                            battery = readBatteryLevel(bd);
                                            break;
                                        }
                                    }
                                }
                            }
                        } catch (SecurityException ignored) {}

                        return new AudioStatus(true, productName, battery);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Audio query error: " + t.getMessage());
        }

        return new AudioStatus(false, "", -1);
    }

    private static int readBatteryLevel(BluetoothDevice dev) {
        if (dev == null) return -1;
        try {
            Method m = dev.getClass().getMethod("getBatteryLevel");
            Object res = m.invoke(dev);
            if (res instanceof Integer) {
                int val = (Integer) res;
                if (val >= 0) return val;
            }
        } catch (Throwable ignored) {}
        return -1;
    }
}
