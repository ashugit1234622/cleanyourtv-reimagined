package com.gathin.tvhome.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.gathin.tvhome.R;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BridgeService extends Service {
    private static final String TAG = "BridgeService";
    private static final String CHANNEL_ID = "phone_bridge_channel";
    private static final int NOTIFICATION_ID = 8765;
    public static final String EXTRA_FROM_BOOT = "extra_from_boot";

    private BridgeHttpServer server;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public static void start(Context context) {
        Intent intent = new Intent(context, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void startFromBoot(Context context) {
        Intent intent = new Intent(context, BridgeService.class);
        intent.putExtra(EXTRA_FROM_BOOT, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, BridgeService.class);
        context.stopService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "BridgeService onCreate");
        createNotificationChannel();

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean fromBoot = intent != null && intent.getBooleanExtra(EXTRA_FROM_BOOT, false);
        if (server == null) {
            if (fromBoot) {
                Log.i(TAG, "Starting from boot - scheduling server startup with 25s delay to protect boot intro");
                executor.schedule(this::startServerInternal, 25L, TimeUnit.SECONDS);
            } else {
                Log.i(TAG, "Starting from UI/toggle - starting server immediately");
                executor.execute(this::startServerInternal);
            }
        }
        return START_STICKY;
    }

    private synchronized void startServerInternal() {
        if (server != null) return;
        try {
            server = new BridgeHttpServer(this, BridgeConfig.SERVER_PORT);
            server.start();
            Log.i(TAG, "BridgeHttpServer started successfully on port " + BridgeConfig.SERVER_PORT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start BridgeHttpServer on port " + BridgeConfig.SERVER_PORT, e);
            server = null;
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "BridgeService onDestroy");
        executor.shutdownNow();
        if (server != null) {
            server.stop();
            server = null;
            Log.i(TAG, "BridgeHttpServer stopped");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Phone Bridge",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains local network bridge to control the TV from your phone");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent settingsIntent = new Intent(this, BridgeSettingsActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("Phone Bridge is active")
                .setContentText("Local web app running on port " + BridgeConfig.SERVER_PORT)
                .setSmallIcon(R.drawable.ic_home_settings)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
