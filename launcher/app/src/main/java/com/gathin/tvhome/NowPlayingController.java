package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coordinates media playback detection from Android's MediaSessionManager
 * and GuardRevival NotificationListenerService, managing active controllers and UI updates.
 */
public final class NowPlayingController {
    private static final String TAG = "NowPlayingController";
    private static volatile NowPlayingController sInstance;

    public interface Listener {
        void onNowPlayingChanged(NowPlayingModel model);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private Handler mainHandler;

    private GuardRevival notificationListener;
    private MediaController activeController;
    private NowPlayingModel currentModel;
    private boolean isRegisteredSessionCallback = false;

    private final Map<String, Bitmap> artCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 20;
                }
            }
    );
    private final ExecutorService artExecutor = Executors.newSingleThreadExecutor();

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            runOnMain(() -> updateFromActiveController());
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            runOnMain(() -> updateFromActiveController());
        }

        @Override
        public void onSessionDestroyed() {
            runOnMain(() -> {
                Log.d(TAG, "Active media session destroyed");
                clearActiveController();
                refreshSessions();
            });
        }
    };

    private NowPlayingController() {}

    public static NowPlayingController getInstance() {
        if (sInstance == null) {
            synchronized (NowPlayingController.class) {
                if (sInstance == null) {
                    sInstance = new NowPlayingController();
                }
            }
        }
        return sInstance;
    }

    private void runOnMain(Runnable r) {
        if (r == null) return;
        if (mainHandler == null) {
            try {
                Looper looper = Looper.getMainLooper();
                if (looper != null) {
                    mainHandler = new Handler(looper);
                }
            } catch (Throwable ignored) {}
        }
        if (mainHandler != null) {
            mainHandler.post(r);
        } else {
            r.run();
        }
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            listener.onNowPlayingChanged(currentModel);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public NowPlayingModel getCurrentModel() {
        return currentModel;
    }

    public void onNotificationListenerConnected(GuardRevival listener) {
        this.notificationListener = listener;
        Log.i(TAG, "GuardRevival notification listener connected");
        runOnMain(this::refreshSessions);
    }

    public void onNotificationListenerDisconnected() {
        this.notificationListener = null;
        Log.i(TAG, "GuardRevival notification listener disconnected");
    }

    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        runOnMain(this::refreshSessions);
    }

    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        runOnMain(this::refreshSessions);
    }

    public void refreshSessions() {
        if (notificationListener == null) {
            return;
        }

        try {
            MediaSessionManager msm = (MediaSessionManager) notificationListener.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;

            ComponentName cn = new ComponentName(notificationListener, GuardRevival.class);
            List<MediaController> controllers = msm.getActiveSessions(cn);

            MediaController candidate = null;
            if (controllers != null && !controllers.isEmpty()) {
                // Find first playing controller, or fallback to first controller
                for (MediaController c : controllers) {
                    PlaybackState state = c.getPlaybackState();
                    if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                        candidate = c;
                        break;
                    }
                }
                if (candidate == null) {
                    candidate = controllers.get(0);
                }
            }

            attachController(candidate);
        } catch (SecurityException se) {
            Log.w(TAG, "SecurityException querying active sessions: " + se.getMessage());
        } catch (Throwable t) {
            Log.w(TAG, "Error querying active sessions: " + t.getMessage());
        }
    }

    private void attachController(MediaController controller) {
        if (activeController == controller) {
            updateFromActiveController();
            return;
        }

        clearActiveController();

        if (controller != null) {
            activeController = controller;
            try {
                if (mainHandler != null) {
                    activeController.registerCallback(controllerCallback, mainHandler);
                } else {
                    activeController.registerCallback(controllerCallback);
                }
                isRegisteredSessionCallback = true;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to register controller callback: " + t.getMessage());
            }
            updateFromActiveController();
        } else {
            postModelUpdate(null);
        }
    }

    private void clearActiveController() {
        if (activeController != null && isRegisteredSessionCallback) {
            try {
                activeController.unregisterCallback(controllerCallback);
            } catch (Throwable ignored) {}
            isRegisteredSessionCallback = false;
        }
        activeController = null;
    }

    public void updateFromActiveController() {
        if (activeController == null || notificationListener == null) {
            postModelUpdate(null);
            return;
        }

        PlaybackState state = activeController.getPlaybackState();
        boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        boolean isPaused = state != null && (state.getState() == PlaybackState.STATE_PAUSED
                || state.getState() == PlaybackState.STATE_BUFFERING);

        // If stopped, state none, or error, don't show card
        if (!isPlaying && !isPaused) {
            postModelUpdate(null);
            return;
        }

        MediaMetadata metadata = activeController.getMetadata();
        String title = "";
        String artist = "";
        Bitmap art = null;
        String artUri = null;
        long duration = 0;
        long position = state != null ? state.getPosition() : 0;

        if (metadata != null) {
            title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null || title.isEmpty()) {
                title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            }

            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null || artist.isEmpty()) {
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            if (artist == null || artist.isEmpty()) {
                artist = metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR);
            }

            art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) {
                art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            if (art == null) {
                art = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }

            artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (artUri == null || artUri.isEmpty()) {
                artUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
            }
            if (artUri == null || artUri.isEmpty()) {
                artUri = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            }
            if ((artUri == null || artUri.isEmpty()) && metadata.getDescription() != null) {
                Uri descUri = metadata.getDescription().getIconUri();
                if (descUri != null) {
                    artUri = descUri.toString();
                }
                if (art == null) {
                    art = metadata.getDescription().getIconBitmap();
                }
            }

            duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }

        if (art == null && artUri != null && !artUri.isEmpty()) {
            art = artCache.get(artUri);
        }

        String pkg = activeController.getPackageName();
        PackageManager pm = notificationListener.getPackageManager();
        String appName = resolveFriendlyAppName(pm, pkg);
        Drawable appIcon = resolveAppIcon(pm, pkg);

        if ((title == null || title.isEmpty()) && (artist == null || artist.isEmpty())) {
            postModelUpdate(null);
            return;
        }

        NowPlayingModel model = new NowPlayingModel(
                title != null ? title : "",
                artist != null ? artist : "",
                pkg != null ? pkg : "",
                appName,
                appIcon,
                art,
                artUri,
                isPlaying,
                duration,
                position
        );

        postModelUpdate(model);

        if (art == null && artUri != null && !artUri.isEmpty()) {
            loadArtworkAsync(artUri);
        }
    }

    private void postModelUpdate(NowPlayingModel model) {
        this.currentModel = model;
        for (Listener l : listeners) {
            try {
                l.onNowPlayingChanged(model);
            } catch (Throwable t) {
                Log.e(TAG, "Listener error in onNowPlayingChanged", t);
            }
        }
    }

    private void loadArtworkAsync(final String uriStr) {
        if (uriStr == null || uriStr.isEmpty()) return;
        artExecutor.execute(() -> {
            Bitmap decoded = null;
            InputStream is = null;
            try {
                if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                    URL url = new URL(uriStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    conn.setInstanceFollowRedirects(true);
                    conn.connect();
                    if (conn.getResponseCode() == 200) {
                        is = conn.getInputStream();
                        decoded = BitmapFactory.decodeStream(is);
                    }
                    conn.disconnect();
                } else if (notificationListener != null) {
                    Uri uri = Uri.parse(uriStr);
                    is = notificationListener.getContentResolver().openInputStream(uri);
                    decoded = BitmapFactory.decodeStream(is);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to load artwork from " + uriStr + ": " + t.getMessage());
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Throwable ignored) {}
                }
            }

            if (decoded != null) {
                int maxDim = Math.max(decoded.getWidth(), decoded.getHeight());
                if (maxDim > 256) {
                    float ratio = 256f / maxDim;
                    int newW = Math.max(1, Math.round(decoded.getWidth() * ratio));
                    int newH = Math.max(1, Math.round(decoded.getHeight() * ratio));
                    Bitmap scaled = Bitmap.createScaledBitmap(decoded, newW, newH, true);
                    if (scaled != decoded) {
                        decoded.recycle();
                        decoded = scaled;
                    }
                }

                final Bitmap finalBmp = decoded;
                artCache.put(uriStr, finalBmp);
                runOnMain(() -> {
                    if (currentModel != null && uriStr.equals(currentModel.getArtUri())) {
                        NowPlayingModel updated = currentModel.withArtBitmap(finalBmp);
                        postModelUpdate(updated);
                    }
                });
            }
        });
    }

    public static String resolveFriendlyAppName(PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        if (pkg.contains("teamsmart") || pkg.contains("smarttubetv")) return "SmartTube";
        if (pkg.contains("tizentube")) return "TizenTube";
        if (pkg.contains("youtube.tvmusic") || pkg.contains("music")) return "YouTube Music";
        if (pkg.contains("youtube")) return "YouTube";
        if (pkg.contains("spotify")) return "Spotify";
        if (pkg.contains("hotstar")) return "JioHotstar";
        if (pkg.contains("netflix")) return "Netflix";
        if (pkg.contains("jellyfin")) return "Jellyfin";
        if (pkg.contains("plex")) return "Plex";
        if (pkg.contains("vlc")) return "VLC";

        if (pm != null) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                CharSequence label = pm.getApplicationLabel(info);
                if (label != null && label.length() > 0) {
                    return label.toString();
                }
            } catch (Throwable ignored) {}
        }
        return pkg;
    }

    public static Drawable resolveAppIcon(PackageManager pm, String pkg) {
        if (pm == null || pkg == null || pkg.isEmpty()) return null;
        try {
            return pm.getApplicationIcon(pkg);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Toggles play/pause on the active media session.
     */
    public boolean togglePlayback() {
        if (activeController == null) {
            refreshSessions();
        }
        if (activeController == null) return false;
        try {
            PlaybackState state = activeController.getPlaybackState();
            if (state == null) return false;
            if (state.getState() == PlaybackState.STATE_PLAYING) {
                activeController.getTransportControls().pause();
            } else {
                activeController.getTransportControls().play();
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to toggle media playback: " + t.getMessage());
            return false;
        }
    }

    /**
     * Opens the currently playing app.
     */
    public boolean openCurrentApp(Context context) {
        if (context == null || activeController == null) return false;
        String pkg = activeController.getPackageName();
        if (pkg == null || pkg.isEmpty()) return false;

        PackageManager pm = context.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) {
            launch = pm.getLaunchIntentForPackage(pkg);
        }
        if (launch != null) {
            try {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to start active media app: " + t.getMessage());
            }
        }
        return false;
    }
}
