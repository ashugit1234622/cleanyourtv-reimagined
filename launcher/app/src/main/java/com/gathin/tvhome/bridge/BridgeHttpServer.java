package com.gathin.tvhome.bridge;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

public class BridgeHttpServer extends NanoHTTPD {
    private static final String TAG = "BridgeHttpServer";

    private final Context context;

    public BridgeHttpServer(Context context, int port) {
        super(port);
        this.context = context.getApplicationContext();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String remoteIp = session.getRemoteIpAddress();
        Log.d(TAG, "Incoming " + session.getMethod() + " " + session.getUri() + " from " + remoteIp);

        // 1. LAN-Only Verification
        if (!BridgeConfig.isAllowedPrivateIp(remoteIp)) {
            Log.w(TAG, "Rejected non-LAN request from " + remoteIp);
            return errorResponse(Response.Status.FORBIDDEN, "Forbidden: LAN access only");
        }

        String uri = session.getUri();
        Method method = session.getMethod();

        // 2. Static Serving (GET / and GET /index.html and favicon)
        if (Method.GET.equals(method) && ("/".equals(uri) || "/index.html".equals(uri))) {
            return serveStaticIndex();
        }
        if (Method.GET.equals(method) && "/favicon.ico".equals(uri)) {
            Response resp = newFixedLengthResponse(Response.Status.NO_CONTENT, "image/x-icon", "");
            resp.addHeader("Cache-Control", "public, max-age=86400");
            resp.addHeader("X-Content-Type-Options", "nosniff");
            return resp;
        }

        // 3. Public Endpoints
        if (Method.GET.equals(method) && "/health".equals(uri)) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("status", "ok");
            } catch (JSONException ignored) {}
            return jsonResponse(Response.Status.OK, obj.toString());
        }

        if (Method.POST.equals(method) && "/pair".equals(uri)) {
            return handlePair(session);
        }

        // 4. Authentication Check for All Protected Endpoints
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader == null) authHeader = session.getHeaders().get("Authorization");

        String token = null;
        if (authHeader != null && authHeader.trim().toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            token = authHeader.trim().substring(7).trim();
        }

        if (token == null || !BridgeConfig.isTokenValid(context, token)) {
            Log.w(TAG, "Unauthorized request to " + uri + " - Header: " + authHeader);
            return errorResponse(Response.Status.UNAUTHORIZED, "Unauthorized: Valid Bearer token required");
        }

        // 5. Protected Endpoint Routing
        try {
            if (Method.GET.equals(method) && "/apps".equals(uri)) {
                return handleGetApps();
            } else if (Method.GET.equals(method) && "/icon".equals(uri)) {
                return handleGetIcon(session);
            } else if (Method.POST.equals(method) && "/launch".equals(uri)) {
                return handleLaunch(session);
            } else if (Method.GET.equals(method) && "/resolve".equals(uri)) {
                return handleResolve(session);
            } else if (Method.GET.equals(method) && "/stats".equals(uri)) {
                return handleStats();
            } else if (Method.POST.equals(method) && "/key".equals(uri)) {
                return handleKey(session);
            } else if (Method.POST.equals(method) && "/wallpaper".equals(uri)) {
                return handleUploadWallpaper(session);
            } else if (Method.GET.equals(method) && "/wallpapers".equals(uri)) {
                return handleGetWallpapers();
            } else if (Method.GET.equals(method) && "/spike/adb/status".equals(uri)) {
                return handleSpikeAdbStatus();
            } else if (Method.POST.equals(method) && "/spike/adb/connect".equals(uri)) {
                return handleSpikeAdbConnect();
            } else if (Method.POST.equals(method) && "/spike/adb/key".equals(uri)) {
                return handleKey(session);
            } else {
                return errorResponse(Response.Status.NOT_FOUND, "Endpoint not found: " + uri);
            }
        } catch (Exception e) {
            Log.e(TAG, "Internal error handling " + uri, e);
            return errorResponse(Response.Status.INTERNAL_ERROR, "Internal error: " + e.getMessage());
        }
    }

    private Response serveStaticIndex() {
        try {
            InputStream is = context.getAssets().open("bridge/index.html");
            Response resp = newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", is);
            resp.addHeader("Cache-Control", "no-cache");
            resp.addHeader("X-Content-Type-Options", "nosniff");
            return resp;
        } catch (IOException e) {
            Log.e(TAG, "Could not open assets/bridge/index.html", e);
            return errorResponse(Response.Status.NOT_FOUND, "index.html asset not found");
        }
    }

    private Response handlePair(IHTTPSession session) {
        String body = readBody(session);
        if (body == null || body.trim().isEmpty()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Missing JSON body");
        }

        String pin = null;
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("pin")) pin = json.getString("pin").trim();
        } catch (JSONException e) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON: " + e.getMessage());
        }

        if (pin == null || pin.isEmpty()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Missing PIN");
        }

        BridgeConfig.PairResult result = BridgeConfig.verifyAndPair(context, pin);
        if (!result.success) {
            return errorResponse(Response.Status.UNAUTHORIZED, result.errorMessage);
        }

        JSONObject resp = new JSONObject();
        try {
            resp.put("status", "paired");
            resp.put("token", result.token);
            resp.put("tvName", result.tvName);
        } catch (JSONException ignored) {}

        return jsonResponse(Response.Status.OK, resp.toString());
    }

    private Response handleGetApps() {
        List<BridgeAppHelper.AppEntry> entries = BridgeAppHelper.getLauncherAppList(context);
        JSONArray appsArr = new JSONArray();

        for (BridgeAppHelper.AppEntry entry : entries) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("name", entry.label);
                obj.put("package", entry.packageName);
                obj.put("iconUrl", "/icon?pkg=" + Uri.encode(entry.packageName));
                obj.put("isDock", entry.isDock);
                if (entry.isDock && entry.dockPosition >= 0) {
                    obj.put("dockPos", entry.dockPosition);
                }
                obj.put("launchOnly", entry.launchOnly);
            } catch (JSONException ignored) {}
            appsArr.put(obj);
        }

        JSONObject resp = new JSONObject();
        try {
            resp.put("apps", appsArr);
        } catch (JSONException ignored) {}

        return jsonResponse(Response.Status.OK, resp.toString());
    }

    private Response handleGetIcon(IHTTPSession session) {
        Map<String, List<String>> params = session.getParameters();
        List<String> pkgList = params.get("pkg");
        if (pkgList == null || pkgList.isEmpty()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Missing 'pkg' query parameter");
        }

        String pkg = pkgList.get(0);
        if (!BridgeAppHelper.isPackageInAppList(context, pkg)) {
            return errorResponse(Response.Status.NOT_FOUND, "Package not found in app list");
        }

        byte[] pngBytes = BridgeAppHelper.getAppIconPng(context, pkg);
        if (pngBytes == null || pngBytes.length == 0) {
            return errorResponse(Response.Status.NOT_FOUND, "Could not load icon for " + pkg);
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(pngBytes);
        Response resp = newFixedLengthResponse(Response.Status.OK, "image/png", bais, pngBytes.length);
        resp.addHeader("Cache-Control", "private, max-age=86400");
        resp.addHeader("X-Content-Type-Options", "nosniff");
        return resp;
    }

    private Response handleLaunch(IHTTPSession session) {
        String body = readBody(session);
        if (body == null || body.trim().isEmpty()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Missing JSON body");
        }

        String pkg = null;
        String url = null;
        boolean remember = false;

        try {
            JSONObject json = new JSONObject(body);
            if (json.has("package") && !json.isNull("package")) {
                pkg = json.getString("package").trim();
            }
            if (json.has("url") && !json.isNull("url")) {
                url = json.getString("url").trim();
            }
            if (json.has("remember")) {
                remember = json.getBoolean("remember");
            }
        } catch (JSONException e) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON: " + e.getMessage());
        }

        PackageManager pm = context.getPackageManager();

        // 1. If package given: must be in launcher's app list. If not: 403 Forbidden
        if (pkg != null && !pkg.isEmpty()) {
            if (!BridgeAppHelper.isPackageInAppList(context, pkg)) {
                Log.w(TAG, "Package " + pkg + " rejected: not in launcher's app list");
                return errorResponse(Response.Status.FORBIDDEN, "Package not in app list");
            }
        }

        // 2. If URL given: validate scheme
        if (url != null && !url.isEmpty()) {
            Uri parsedUri;
            try {
                parsedUri = Uri.parse(url);
            } catch (Exception e) {
                return errorResponse(Response.Status.BAD_REQUEST, "Malformed URL");
            }

            String scheme = parsedUri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return errorResponse(Response.Status.BAD_REQUEST, "Invalid URL scheme: must be http or https");
            }

            String host = parsedUri.getHost();

            if (pkg == null || pkg.isEmpty()) {
                // Package omitted: check if host is remembered
                String rememberedPkg = (host != null) ? BridgeConfig.getRememberedPackage(context, host) : null;
                if (rememberedPkg != null && BridgeAppHelper.isPackageInAppList(context, rememberedPkg)) {
                    pkg = rememberedPkg;
                } else {
                    // Resolve candidates among launcher's app list
                    Intent resolveIntent = new Intent(Intent.ACTION_VIEW, parsedUri);
                    List<ResolveInfo> resolvers = pm.queryIntentActivities(resolveIntent, 0);
                    if (resolvers == null) resolvers = new ArrayList<>();

                    List<ResolveInfo> candidates = new ArrayList<>();
                    for (ResolveInfo ri : resolvers) {
                        if (ri.activityInfo != null && BridgeAppHelper.isPackageInAppList(context, ri.activityInfo.packageName)) {
                            candidates.add(ri);
                        }
                    }

                    if (candidates.isEmpty()) {
                        return errorResponse(new CustomStatus(422, "Unprocessable Entity"), "No installed app can open this link");
                    } else if (candidates.size() > 1) {
                        JSONArray candArray = new JSONArray();
                        for (ResolveInfo ri : candidates) {
                            JSONObject cObj = new JSONObject();
                            try {
                                cObj.put("package", ri.activityInfo.packageName);
                                cObj.put("label", String.valueOf(ri.loadLabel(pm)));
                            } catch (JSONException ignored) {}
                            candArray.put(cObj);
                        }
                        JSONObject conflict = new JSONObject();
                        try {
                            conflict.put("candidates", candArray);
                        } catch (JSONException ignored) {}
                        return jsonResponse(Response.Status.CONFLICT, conflict.toString());
                    } else {
                        pkg = candidates.get(0).activityInfo.packageName;
                    }
                }
            }

            // If remember flag set and we have a host + pkg, save it
            if (remember && host != null && pkg != null) {
                BridgeConfig.setRememberedPackage(context, host, pkg);
            }
        } else if (pkg == null || pkg.isEmpty()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Must provide either 'package' or 'url'");
        }

        // Construct launch intent
        Intent launchIntent;
        if (url != null && !url.isEmpty()) {
            launchIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            launchIntent.setPackage(pkg);
        } else {
            launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launchIntent == null) launchIntent = pm.getLaunchIntentForPackage(pkg);
        }

        if (launchIntent == null) {
            return errorResponse(new CustomStatus(422, "Unprocessable Entity"), "Could not construct launch intent for '" + pkg + "'");
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        try {
            Log.i(TAG, "Launching " + pkg + (url != null ? " with URL: " + url : ""));
            context.startActivity(launchIntent);
            // Record KEY_APP_LAUNCHED only after intent dispatches without exception
            recordAppLaunched();
        } catch (Exception ex) {
            Log.e(TAG, "Launch failed for " + pkg, ex);
            return errorResponse(Response.Status.INTERNAL_ERROR, "Launch failed: " + ex.getMessage());
        }

        JSONObject resp = new JSONObject();
        try {
            resp.put("status", "launched");
        } catch (JSONException ignored) {}

        return jsonResponse(Response.Status.OK, resp.toString());
    }

    private Response handleResolve(IHTTPSession session) {
        Map<String, List<String>> params = session.getParameters();
        List<String> urlList = params.get("url");
        if (urlList == null || urlList.isEmpty()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Missing 'url' parameter");
        }

        String url = urlList.get(0);
        Uri parsedUri;
        try {
            parsedUri = Uri.parse(url);
        } catch (Exception e) {
            return errorResponse(Response.Status.BAD_REQUEST, "Malformed URL");
        }

        Intent resolveIntent = new Intent(Intent.ACTION_VIEW, parsedUri);
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> resolvers = pm.queryIntentActivities(resolveIntent, 0);
        if (resolvers == null) resolvers = new ArrayList<>();

        JSONArray candArray = new JSONArray();
        for (ResolveInfo ri : resolvers) {
            if (ri.activityInfo != null && BridgeAppHelper.isPackageInAppList(context, ri.activityInfo.packageName)) {
                JSONObject cObj = new JSONObject();
                try {
                    cObj.put("package", ri.activityInfo.packageName);
                    cObj.put("label", String.valueOf(ri.loadLabel(pm)));
                } catch (JSONException ignored) {}
                candArray.put(cObj);
            }
        }

        if (candArray.length() == 0) {
            return errorResponse(new CustomStatus(422, "Unprocessable Entity"), "No installed app can open this link");
        }

        JSONObject resp = new JSONObject();
        try {
            resp.put("candidates", candArray);
        } catch (JSONException ignored) {}

        return jsonResponse(Response.Status.OK, resp.toString());
    }

    private Response handleStats() {
        JSONObject root = new JSONObject();
        try {
            // RAM
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);
            long totalMb = mi.totalMem / (1024 * 1024);
            long availMb = mi.availMem / (1024 * 1024);
            long usedMb = Math.max(0L, totalMb - availMb);
            double percentUsedRam = totalMb > 0 ? Math.round(((double) usedMb * 100.0 / totalMb) * 10.0) / 10.0 : 0.0;

            JSONObject ramObj = new JSONObject();
            ramObj.put("percentUsed", percentUsedRam);
            ramObj.put("usedMb", usedMb);
            ramObj.put("totalMb", totalMb);
            root.put("ram", ramObj);

            // Storage
            File dataDir = Environment.getDataDirectory();
            StatFs stat = new StatFs(dataDir.getPath());
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availBlocks = stat.getAvailableBlocksLong();
            double totalGb = Math.round(((double)(totalBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)) * 10.0) / 10.0;
            double availGb = Math.round(((double)(availBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)) * 10.0) / 10.0;
            double usedGb = Math.max(0.0, Math.round((totalGb - availGb) * 10.0) / 10.0);
            double percentUsedStorage = totalGb > 0 ? Math.round(((usedGb / totalGb) * 100.0) * 10.0) / 10.0 : 0.0;

            JSONObject storageObj = new JSONObject();
            storageObj.put("percentUsed", percentUsedStorage);
            storageObj.put("usedGb", usedGb);
            storageObj.put("totalGb", totalGb);
            root.put("storage", storageObj);

            // System
            long uptimeMs = SystemClock.elapsedRealtime();
            long hours = TimeUnit.MILLISECONDS.toHours(uptimeMs);
            long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMs) % 60;
            String uptimeFormatted = hours + "h " + minutes + "m";

            JSONObject sysObj = new JSONObject();
            sysObj.put("uptimeFormatted", uptimeFormatted);
            sysObj.put("androidVersion", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            sysObj.put("deviceModel", Build.MODEL);
            root.put("system", sysObj);

            // Wi-Fi (RSSI and IP, no SSID)
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = wm != null ? wm.getConnectionInfo() : null;
            int rssi = wifiInfo != null ? wifiInfo.getRssi() : -50;
            String ip = BridgeConfig.getDeviceIp(context);

            JSONObject wifiObj = new JSONObject();
            wifiObj.put("rssi", rssi);
            wifiObj.put("ip", ip);
            root.put("wifi", wifiObj);

        } catch (Exception e) {
            Log.e(TAG, "Error compiling stats", e);
        }

        return jsonResponse(Response.Status.OK, root.toString());
    }

    private Response handleKey(IHTTPSession session) {
        String body = readBody(session);
        if (body == null || body.trim().isEmpty()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Missing JSON body");
        }

        String action = null;
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("action")) action = json.getString("action").trim().toLowerCase(Locale.ROOT);
        } catch (JSONException e) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON: " + e.getMessage());
        }

        if (action == null || action.isEmpty()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Missing 'action' parameter");
        }

        if (BridgeAdbManager.isWhitelistedAction(action)) {
            BridgeAdbManager adb = BridgeAdbManager.getInstance(context);
            if (!adb.isConnected()) {
                boolean ok = adb.connectSync(1500);
                if (!ok) {
                    if (adb.getStatus() == BridgeAdbManager.Status.AWAITING_AUTH) {
                        return errorResponse(new CustomStatus(501, "Not Implemented"), "Arrow keys need setup on the TV first. Check Phone Bridge settings.");
                    }
                    return errorResponse(new CustomStatus(503, "Service Unavailable"), "Arrow key connection temporarily unavailable (" + adb.getStatus() + ")");
                }
            }
            try {
                long latencyMs = adb.sendKey(action);
                JSONObject resp = new JSONObject();
                resp.put("status", "ok");
                resp.put("action", action);
                resp.put("latencyMs", latencyMs);
                return jsonResponse(Response.Status.OK, resp.toString());
            } catch (Exception ex) {
                return errorResponse(new CustomStatus(503, "Service Unavailable"), "Key injection failed: " + ex.getMessage());
            }
        }

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        switch (action) {
            case "play_pause":
                sendMediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                break;
            case "play":
                sendMediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY);
                break;
            case "pause":
                sendMediaKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE);
                break;
            case "next":
                sendMediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
            case "prev":
                sendMediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                break;
            case "volume_up":
                if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                break;
            case "volume_down":
                if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                break;
            case "volume_mute":
                if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                break;
            case "home":
                Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(homeIntent);
                break;
            default:
                return errorResponse(Response.Status.BAD_REQUEST, "Unknown key action: " + action);
        }

        JSONObject resp = new JSONObject();
        try {
            resp.put("status", "ok");
        } catch (JSONException ignored) {}

        return jsonResponse(Response.Status.OK, resp.toString());
    }

    private void sendMediaKey(AudioManager am, int keyCode) {
        if (am == null) return;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private Response handleSpikeAdbStatus() {
        BridgeAdbManager adb = BridgeAdbManager.getInstance(context);
        JSONObject obj = new JSONObject();
        try {
            obj.put("status", adb.getStatus().name());
            obj.put("connected", adb.isConnected());
            obj.put("autoStopRemainingSec", adb.getAutoStopRemainingSec());
            obj.put("lastError", adb.getLastError());
        } catch (JSONException ignored) {}
        return jsonResponse(Response.Status.OK, obj.toString());
    }

    private Response handleSpikeAdbConnect() {
        BridgeAdbManager adb = BridgeAdbManager.getInstance(context);
        adb.connectAsync();
        JSONObject obj = new JSONObject();
        try {
            obj.put("status", adb.getStatus().name());
            obj.put("connected", adb.isConnected());
        } catch (JSONException ignored) {}
        return jsonResponse(Response.Status.OK, obj.toString());
    }

    private void recordAppLaunched() {
        SharedPreferences prefs = context.getSharedPreferences("boot_intro_prefs", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("app_launched_persisted", true).commit();
        Log.d(TAG, "Recorded app_launched_persisted = true in boot_intro_prefs");
    }

    private Response handleUploadWallpaper(IHTTPSession session) {
        String body = readBody(session);
        if (body == null || body.trim().isEmpty()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Missing request body");
        }

        String fileName = null;
        String base64Data = null;
        try {
            JSONObject json = new JSONObject(body);
            if (json.has("name")) fileName = json.getString("name");
            if (json.has("data")) base64Data = json.getString("data");
        } catch (JSONException e) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid JSON: " + e.getMessage());
        }

        if (base64Data == null || base64Data.trim().isEmpty()) {
            return errorResponse(Response.Status.BAD_REQUEST, "Missing 'data' parameter");
        }

        // 1. Decode Base64 bytes
        byte[] imageBytes;
        try {
            imageBytes = Base64.decode(base64Data, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return errorResponse(Response.Status.BAD_REQUEST, "Invalid base64 encoding");
        }

        // 2. Check size (max 10 MB CleanYourTV limit)
        if (imageBytes.length == 0 || imageBytes.length > 10 * 1024 * 1024) {
            return errorResponse(Response.Status.BAD_REQUEST, "Wallpaper size must be between 1 byte and 10 MB (got " + imageBytes.length + " bytes)");
        }

        // 3. Format validation (Magic bytes: JPEG 0xFFD8FF, PNG 0x89504E47)
        boolean isJpeg = imageBytes.length >= 3 && (imageBytes[0] & 0xFF) == 0xFF && (imageBytes[1] & 0xFF) == 0xD8 && (imageBytes[2] & 0xFF) == 0xFF;
        boolean isPng = imageBytes.length >= 8 && (imageBytes[0] & 0xFF) == 0x89 && (imageBytes[1] & 0xFF) == 0x50 && (imageBytes[2] & 0xFF) == 0x4E && (imageBytes[3] & 0xFF) == 0x47;

        if (!isJpeg && !isPng) {
            return errorResponse(Response.Status.BAD_REQUEST, "Unsupported image format: Only JPG/JPEG and PNG are allowed (WebP and others rejected)");
        }

        // 4. Resolution check using BitmapFactory.Options
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, opts);
        int width = opts.outWidth;
        int height = opts.outHeight;

        if (width < 3840 || height < 2160) {
            return errorResponse(Response.Status.BAD_REQUEST, "Image resolution (" + width + "x" + height + ") is below 4K (minimum 3840x2160 required)");
        }

        // 5. Ensure wallpapers folder exists
        File wallpapersDir = new File(context.getExternalFilesDir(null), "wallpapers");
        if (!wallpapersDir.exists()) {
            wallpapersDir.mkdirs();
        }

        // 6. Duplicate detection via MD5 checksum
        String uploadMd5 = md5Hex(imageBytes);
        File[] existing = wallpapersDir.listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (f.isFile() && f.length() == imageBytes.length) {
                    byte[] existingBytes = readFileBytes(f);
                    if (existingBytes != null && uploadMd5.equals(md5Hex(existingBytes))) {
                        return errorResponse(new CustomStatus(409, "Conflict"), "Wallpaper already exists on TV (" + f.getName() + ")");
                    }
                }
            }
        }

        // 7. Sanitize filename
        String cleanName = sanitizeWallpaperName(fileName, isJpeg ? ".jpg" : ".png");
        File targetFile = new File(wallpapersDir, cleanName);
        if (targetFile.exists()) {
            cleanName = System.currentTimeMillis() + "_" + cleanName;
            targetFile = new File(wallpapersDir, cleanName);
        }

        // 8. Write file to wallpapers directory
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(imageBytes);
            fos.flush();
        } catch (IOException e) {
            Log.e(TAG, "Failed to write wallpaper file", e);
            return errorResponse(Response.Status.INTERNAL_ERROR, "Failed to write wallpaper to disk: " + e.getMessage());
        }

        targetFile.setReadable(true, false);
        Log.i(TAG, "New 4K wallpaper saved: " + cleanName + " (" + width + "x" + height + ", " + imageBytes.length + " bytes)");

        JSONObject resp = new JSONObject();
        try {
            resp.put("status", "ok");
            resp.put("filename", cleanName);
            resp.put("width", width);
            resp.put("height", height);
            resp.put("size", imageBytes.length);
        } catch (JSONException ignored) {}
        return jsonResponse(Response.Status.OK, resp.toString());
    }

    private Response handleGetWallpapers() {
        File wallpapersDir = new File(context.getExternalFilesDir(null), "wallpapers");
        File[] files = wallpapersDir.exists() ? wallpapersDir.listFiles() : null;
        JSONArray arr = new JSONArray();
        int count = 0;
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && f.length() > 0 && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) {
                    count++;
                    JSONObject item = new JSONObject();
                    try {
                        item.put("name", f.getName());
                        item.put("size", f.length());
                    } catch (JSONException ignored) {}
                    arr.put(item);
                }
            }
        }
        JSONObject res = new JSONObject();
        try {
            res.put("count", count);
            res.put("wallpapers", arr);
        } catch (JSONException ignored) {}
        return jsonResponse(Response.Status.OK, res.toString());
    }

    private static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readFileBytes(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int read = fis.read(buf);
            return read == buf.length ? buf : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitizeWallpaperName(String name, String fallbackExt) {
        if (name == null || name.trim().isEmpty()) {
            return "wallpaper_" + System.currentTimeMillis() + fallbackExt;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : fallbackExt;
        if (!ext.equals(".jpg") && !ext.equals(".jpeg") && !ext.equals(".png")) {
            ext = fallbackExt;
        }
        String cleanBase = base.replaceAll("[^A-Za-z0-9_.-]", "_").replaceAll("_+", "_");
        if (cleanBase.isEmpty() || "_".equals(cleanBase)) {
            cleanBase = "wallpaper_" + System.currentTimeMillis();
        }
        return cleanBase + ext;
    }

    private String readBody(IHTTPSession session) {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
            return files.get("postData");
        } catch (IOException | ResponseException e) {
            Log.e(TAG, "Error reading request body", e);
            return null;
        }
    }

    private static Response jsonResponse(Response.IStatus status, String json) {
        Response resp = newFixedLengthResponse(status, "application/json; charset=UTF-8", json);
        resp.addHeader("Cache-Control", "no-store");
        resp.addHeader("X-Content-Type-Options", "nosniff");
        return resp;
    }

    private static Response errorResponse(Response.IStatus status, String msg) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("message", msg);
        } catch (JSONException ignored) {}
        return jsonResponse(status, obj.toString());
    }

    private static class CustomStatus implements Response.IStatus {
        private final int code;
        private final String desc;

        CustomStatus(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        @Override
        public String getDescription() {
            return "" + code + " " + desc;
        }

        @Override
        public int getRequestStatus() {
            return code;
        }
    }
}
