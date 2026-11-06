package com.gathin.tvhome.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BridgeBootReceiver extends BroadcastReceiver {
    private static final String TAG = "BridgeBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.i(TAG, "BOOT_COMPLETED received");
        if (BridgeConfig.isEnabled(context)) {
            Log.i(TAG, "Phone Bridge enabled - starting BridgeService (with 25s boot delay)");
            BridgeService.startFromBoot(context);
        } else {
            Log.i(TAG, "Phone Bridge disabled - not starting service");
        }
    }
}
