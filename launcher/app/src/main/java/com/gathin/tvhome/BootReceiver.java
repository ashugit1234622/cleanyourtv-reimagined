package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Ensures HomeKeyService is registered in enabled_accessibility_services immediately upon boot,
 * surviving device restarts and reboots.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Config cfg = Config.load(context);
        if (cfg.restoreKeyServices) {
            ServiceRepair.restore(context);
        }
    }
}

