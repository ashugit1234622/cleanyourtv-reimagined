package com.gathin.tvhome.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class BridgeConfig {
    private static final String TAG = "BridgeConfig";

    public static final String PREFS_NAME = "bridge_prefs";
    public static final String KEY_BRIDGE_ENABLED = "bridge_enabled";
    public static final String KEY_PAIRED_TOKENS = "paired_tokens";
    public static final String KEY_REMEMBERED_HOST_PREFIX = "remembered_host_";

    public static final int SERVER_PORT = 8765;
    public static final long PIN_EXPIRY_MS = 5 * 60 * 1000L; // 5 minutes

    private static final SecureRandom RANDOM = new SecureRandom();

    // In-memory PIN state (active ONLY while pairing screen is visible)
    private static volatile boolean isPairingScreenVisible = false;
    private static volatile String activePin = null;
    private static volatile long pinExpiryTime = 0L;
    private static volatile int failedAttempts = 0;
    private static final List<Long> attemptTimestamps = new ArrayList<>();

    private BridgeConfig() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BRIDGE_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BRIDGE_ENABLED, enabled).apply();
    }

    // --- Pairing Screen & PIN Management ---

    public static synchronized void setPairingScreenVisible(boolean visible) {
        isPairingScreenVisible = visible;
        if (visible) {
            generateNewPin();
        } else {
            activePin = null;
            pinExpiryTime = 0L;
            failedAttempts = 0;
            attemptTimestamps.clear();
        }
    }

    public static synchronized boolean isPairingScreenVisible() {
        return isPairingScreenVisible;
    }

    public static synchronized String generateNewPin() {
        int pinNum = RANDOM.nextInt(1000000);
        activePin = String.format(Locale.ROOT, "%06d", pinNum);
        pinExpiryTime = System.currentTimeMillis() + PIN_EXPIRY_MS;
        failedAttempts = 0;
        attemptTimestamps.clear();
        Log.i(TAG, "Generated new active PIN: " + activePin + " (expires in 5m)");
        return activePin;
    }

    public static synchronized String getActivePin() {
        if (!isPairingScreenVisible || activePin == null) return null;
        if (System.currentTimeMillis() > pinExpiryTime) {
            activePin = null;
            return null;
        }
        return activePin;
    }

    public static synchronized long getPinRemainingSeconds() {
        if (!isPairingScreenVisible || activePin == null) return 0L;
        long rem = pinExpiryTime - System.currentTimeMillis();
        return Math.max(0L, rem / 1000L);
    }

    public static class PairResult {
        public final boolean success;
        public final String token;
        public final String tvName;
        public final String errorMessage;
        public final int remainingAttempts;

        public PairResult(boolean success, String token, String tvName, String errorMessage, int remainingAttempts) {
            this.success = success;
            this.token = token;
            this.tvName = tvName;
            this.errorMessage = errorMessage;
            this.remainingAttempts = remainingAttempts;
        }
    }

    public static synchronized PairResult verifyAndPair(Context context, String submittedPin) {
        long now = System.currentTimeMillis();

        // 1. Rate limiting: max 3 attempts per minute
        attemptTimestamps.removeIf(ts -> now - ts > 60000L);
        if (attemptTimestamps.size() >= 3) {
            return new PairResult(false, null, null, "Too many pairing attempts. Please wait a minute.", 0);
        }
        attemptTimestamps.add(now);

        // 2. Screen visibility check
        if (!isPairingScreenVisible || activePin == null) {
            return new PairResult(false, null, null, "Pairing screen is not open on the TV.", 0);
        }

        // 3. Expiry check
        if (now > pinExpiryTime) {
            activePin = null;
            return new PairResult(false, null, null, "Pairing PIN has expired. Check your TV for a new PIN.", 0);
        }

        // 4. Match check
        String cleanPin = submittedPin != null ? submittedPin.trim().replaceAll("\\D", "") : "";
        if (!activePin.equals(cleanPin)) {
            failedAttempts++;
            int remaining = Math.max(0, 5 - failedAttempts);
            if (remaining == 0) {
                activePin = null; // PIN invalidated after 5 failed attempts
                return new PairResult(false, null, null, "Too many failed attempts. PIN invalidated.", 0);
            }
            return new PairResult(false, null, null, "Wrong PIN. " + remaining + " attempts remaining.", remaining);
        }

        // 5. Success - single use: invalidate PIN immediately
        activePin = null;
        pinExpiryTime = 0L;
        failedAttempts = 0;
        attemptTimestamps.clear();

        // Generate 256-bit cryptographically secure token (64 hex chars)
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = bytesToHex(tokenBytes);

        // Persist token in paired set
        Set<String> tokens = new HashSet<>(prefs(context).getStringSet(KEY_PAIRED_TOKENS, Collections.emptySet()));
        tokens.add(token);
        prefs(context).edit().putStringSet(KEY_PAIRED_TOKENS, tokens).apply();
        Log.i(TAG, "Device paired successfully with token: " + token.substring(0, 8) + "...");

        return new PairResult(true, token, "CleanYourTV", null, 5);
    }

    public static synchronized boolean isTokenValid(Context context, String token) {
        if (token == null || token.trim().isEmpty()) return false;
        Set<String> tokens = prefs(context).getStringSet(KEY_PAIRED_TOKENS, Collections.emptySet());
        return tokens.contains(token.trim());
    }

    public static synchronized void unpairAll(Context context) {
        prefs(context).edit().remove(KEY_PAIRED_TOKENS).apply();
        Log.i(TAG, "Unpaired all devices");
    }

    // --- Remembered Apps ---

    public static String getRememberedPackage(Context context, String host) {
        if (host == null || host.isEmpty()) return null;
        return prefs(context).getString(KEY_REMEMBERED_HOST_PREFIX + host.toLowerCase(Locale.ROOT), null);
    }

    public static void setRememberedPackage(Context context, String host, String pkg) {
        if (host == null || host.isEmpty() || pkg == null || pkg.isEmpty()) return;
        prefs(context).edit()
                .putString(KEY_REMEMBERED_HOST_PREFIX + host.toLowerCase(Locale.ROOT), pkg)
                .apply();
        Log.i(TAG, "Remembered " + host + " -> " + pkg);
    }

    public static void clearRememberedApps(Context context) {
        SharedPreferences sp = prefs(context);
        SharedPreferences.Editor editor = sp.edit();
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith(KEY_REMEMBERED_HOST_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
        Log.i(TAG, "Cleared all remembered app mappings");
    }

    // --- Helpers ---

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    public static String getDeviceIp(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network net = cm.getActiveNetwork();
                if (net != null) {
                    LinkProperties lp = cm.getLinkProperties(net);
                    if (lp != null) {
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress addr = la.getAddress();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                return addr.getHostAddress();
                            }
                        }
                    }
                }
            }

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    public static boolean isAllowedPrivateIp(String ip) {
        if (ip == null || ip.trim().isEmpty()) return false;
        String clean = ip.trim();

        // IPv4-mapped IPv6 (::ffff:192.168.x.x)
        if (clean.startsWith("::ffff:")) {
            clean = clean.substring(7);
        }

        if ("127.0.0.1".equals(clean) || "::1".equals(clean) || "localhost".equalsIgnoreCase(clean)) {
            return true;
        }

        // IPv4 private ranges
        if (clean.startsWith("10.") || clean.startsWith("192.168.")) {
            return true;
        }
        if (clean.startsWith("172.")) {
            String[] parts = clean.split("\\.");
            if (parts.length >= 2) {
                try {
                    int sec = Integer.parseInt(parts[1]);
                    if (sec >= 16 && sec <= 31) return true;
                } catch (NumberFormatException ignored) {}
            }
        }

        // IPv6 Unique Local (fc00::/7) or Link-Local (fe80::/10)
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80")) {
            return true;
        }

        try {
            InetAddress addr = InetAddress.getByName(clean);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
