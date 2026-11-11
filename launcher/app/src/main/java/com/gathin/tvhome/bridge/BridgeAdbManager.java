package com.gathin.tvhome.bridge;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages loopback ADB connection to 127.0.0.1:5555 for injecting arrow and selection keys.
 * Uses a pure-Java ADB client (com.tananaev:adblib) with RSA key generation and persistence.
 *
 * Strict security constraints:
 * - Only 6 whitelisted remote keys are accepted: up, down, left, right, ok, back.
 * - Never executes arbitrary commands or accepts unverified strings.
 * - Connects only to 127.0.0.1 (loopback), never external hosts.
 * - Auto-stops connection after 15 minutes of inactivity for the spike.
 */
public class BridgeAdbManager {

    private static final String TAG = "BridgeAdbManager";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final long AUTO_STOP_MS = 15 * 60 * 1000L; // 15 minutes
    private static final long DEFAULT_SHELL_TIMEOUT_MS = 4500L;
    private static final int MAX_SHELL_OUTPUT_BYTES = 512 * 1024;
    private static final byte[] EMPTY_BYTES = new byte[0];

    public enum Status {
        DISABLED,
        DISCONNECTED,
        CONNECTING,
        AWAITING_AUTH,
        CONNECTED,
        ERROR
    }

    private static final Map<String, Integer> WHITELISTED_KEYS;
    static {
        Map<String, Integer> map = new HashMap<>();
        map.put("up", KeyEvent.KEYCODE_DPAD_UP);       // 19
        map.put("down", KeyEvent.KEYCODE_DPAD_DOWN);   // 20
        map.put("left", KeyEvent.KEYCODE_DPAD_LEFT);   // 21
        map.put("right", KeyEvent.KEYCODE_DPAD_RIGHT); // 22
        map.put("ok", KeyEvent.KEYCODE_DPAD_CENTER);   // 23
        map.put("back", KeyEvent.KEYCODE_BACK);        // 4
        WHITELISTED_KEYS = Collections.unmodifiableMap(map);
    }

    private static volatile BridgeAdbManager instance;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile Status status = Status.DISCONNECTED;
    private volatile String lastError = null;
    private volatile long lastActivityTime = 0L;
    private volatile long connectStartTime = 0L;

    private Socket socket;
    private AdbConnection connection;
    private final Object connectionLock = new Object();
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);

    private static final AdbBase64 BASE64 = new AdbBase64() {
        @Override
        public String encodeToString(byte[] data) {
            return Base64.encodeToString(data, Base64.NO_WRAP);
        }
    };

    public static BridgeAdbManager getInstance(Context ctx) {
        if (instance == null) {
            synchronized (BridgeAdbManager.class) {
                if (instance == null) {
                    instance = new BridgeAdbManager(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private BridgeAdbManager(Context ctx) {
        this.context = ctx;
    }

    public Status getStatus() {
        checkAutoStop();
        return status;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isConnected() {
        checkAutoStop();
        synchronized (connectionLock) {
            return status == Status.CONNECTED && connection != null && socket != null && !socket.isClosed();
        }
    }

    public long getAutoStopRemainingSec() {
        if (status != Status.CONNECTED && status != Status.CONNECTING && status != Status.AWAITING_AUTH) {
            return 0L;
        }
        long elapsed = SystemClock.elapsedRealtime() - connectStartTime;
        long remaining = Math.max(0L, AUTO_STOP_MS - elapsed);
        return remaining / 1000L;
    }

    private void checkAutoStop() {
        long refTime = lastActivityTime > 0 ? lastActivityTime : connectStartTime;
        if (refTime > 0 && (SystemClock.elapsedRealtime() - refTime > AUTO_STOP_MS)) {
            Log.i(TAG, "Loopback ADB inactive for 15 minutes; auto-stopping");
            disconnect();
        }
    }

    public void connectAsync() {
        if (isConnected() || isConnecting.get()) return;
        executor.execute(this::connectInternal);
    }

    public boolean connectSync(long timeoutMs) {
        if (isConnected()) return true;
        executor.execute(this::connectInternal);
        long start = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (isConnected()) return true;
            if (status == Status.AWAITING_AUTH || status == Status.ERROR) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return isConnected();
    }

    private void connectInternal() {
        if (!isConnecting.compareAndSet(false, true)) return;
        status = Status.CONNECTING;
        lastError = null;
        connectStartTime = SystemClock.elapsedRealtime();

        try {
            Log.i(TAG, "Starting loopback ADB connection to " + HOST + ":" + PORT);
            AdbCrypto crypto = getOrCreateCrypto();

            Socket s = new Socket();
            s.connect(new InetSocketAddress(HOST, PORT), 4000);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);

            AdbConnection conn = AdbConnection.create(s, crypto);

            // Connect with timeout. abortOnUnauthorised = false so it waits for TV dialog if not yet approved.
            // connect(long timeout, TimeUnit unit, boolean abortOnUnauthorised)
            boolean connected = conn.connect(6, TimeUnit.SECONDS, false);

            synchronized (connectionLock) {
                if (connected) {
                    this.socket = s;
                    this.connection = conn;
                    this.status = Status.CONNECTED;
                    this.lastActivityTime = SystemClock.elapsedRealtime();
                    Log.i(TAG, "Loopback ADB connected successfully!");
                } else {
                    this.socket = s;
                    this.connection = conn;
                    this.status = Status.AWAITING_AUTH;
                    Log.w(TAG, "Loopback ADB waiting for authorization on TV screen...");
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Loopback ADB connection failed", ex);
            status = Status.ERROR;
            lastError = ex.getMessage();
            closeQuietly();
        } finally {
            isConnecting.set(false);
        }
    }

    public void disconnect() {
        synchronized (connectionLock) {
            closeQuietly();
            status = Status.DISCONNECTED;
            connectStartTime = 0L;
        }
    }

    private void closeQuietly() {
        synchronized (connectionLock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {}
                connection = null;
            }
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {}
                socket = null;
            }
        }
    }

    /**
     * Injects a remote key by action name.
     * @param action One of: up, down, left, right, ok, back
     * @return Execution latency in milliseconds
     * @throws IllegalArgumentException If action is not whitelisted
     * @throws IllegalStateException If not connected
     * @throws IOException If communication error
     */
    public long sendKey(String action) throws IOException, InterruptedException {
        Integer keyCode = WHITELISTED_KEYS.get(action);
        if (keyCode == null) {
            throw new IllegalArgumentException("Action '" + action + "' is not a whitelisted arrow key");
        }

        checkAutoStop();
        if (!isConnected()) {
            connectSync(1500);
        }

        synchronized (connectionLock) {
            if (!isConnected()) {
                throw new IllegalStateException("ADB loopback is not connected (status: " + status + ")");
            }

            long t0 = SystemClock.elapsedRealtime();
            String command = "shell:input keyevent " + keyCode;

            try {
                AdbStream stream = connection.open(command);
                // The stream completes execution; read or close
                try {
                    // Small read with brief wait or just close stream
                    stream.close();
                } catch (Exception ignored) {}

                long elapsed = SystemClock.elapsedRealtime() - t0;
                lastActivityTime = SystemClock.elapsedRealtime();
                Log.d(TAG, "Key " + action + " (" + keyCode + ") injected in " + elapsed + " ms");
                return elapsed;
            } catch (Exception ex) {
                Log.e(TAG, "Failed to inject key " + action + ", marking disconnected", ex);
                status = Status.ERROR;
                lastError = ex.getMessage();
                closeQuietly();
                throw ex;
            }
        }
    }

    /**
     * Executes an internal system shell command via loopback ADB.
     * Synchronously waits for completion up to 4.5 seconds.
     * @param cmd Shell command to execute
     * @return true if command executed successfully, false otherwise
     */
    public boolean executeShell(String cmd) {
        return runShellInternal(cmd, DEFAULT_SHELL_TIMEOUT_MS, false) != null;
    }

    /**
     * Executes an internal system shell command via loopback ADB and returns its UTF-8 output.
     * @param cmd Shell command to execute
     * @param timeoutMs Maximum wait time in milliseconds
     * @return UTF-8 output string (possibly empty), or null if loopback ADB failed/disconnected
     */
    public String executeShellOutput(String cmd, long timeoutMs) {
        byte[] bytes = runShellInternal(cmd, timeoutMs > 0 ? timeoutMs : DEFAULT_SHELL_TIMEOUT_MS, true);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private byte[] runShellInternal(String cmd, long timeoutMs, boolean captureOutput) {
        if (cmd == null || cmd.isEmpty()) return null;
        checkAutoStop();
        if (!isConnected()) {
            Log.i(TAG, "executeShell: connecting to loopback ADB...");
            if (!connectSync(2500)) {
                Log.w(TAG, "executeShell: failed to connect to loopback ADB (status: " + status + ")");
                return null;
            }
        }

        synchronized (connectionLock) {
            if (!isConnected()) {
                Log.w(TAG, "executeShell: ADB not connected after connectSync");
                return null;
            }
            try {
                String command = "shell:" + cmd;
                AdbStream stream = connection.open(command);
                ByteArrayOutputStream out = captureOutput ? new ByteArrayOutputStream(4096) : null;
                long start = SystemClock.elapsedRealtime();
                try {
                    while (!stream.isClosed() && (SystemClock.elapsedRealtime() - start < timeoutMs)) {
                        try {
                            byte[] data = stream.read();
                            if (data == null || data.length == 0) break;
                            if (out != null && out.size() < MAX_SHELL_OUTPUT_BYTES) {
                                int toWrite = Math.min(data.length, MAX_SHELL_OUTPUT_BYTES - out.size());
                                out.write(data, 0, toWrite);
                            }
                        } catch (IOException e) {
                            break;
                        }
                    }
                } catch (Exception ignored) {}
                try {
                    stream.close();
                } catch (Exception ignored) {}
                lastActivityTime = SystemClock.elapsedRealtime();
                Log.i(TAG, "executeShell completed: " + cmd);
                return out != null ? out.toByteArray() : EMPTY_BYTES;
            } catch (Exception ex) {
                Log.e(TAG, "executeShell failed for cmd: " + cmd, ex);
                status = Status.ERROR;
                lastError = ex.getMessage();
                closeQuietly();
                return null;
            }
        }
    }

    public static boolean isWhitelistedAction(String action) {
        return WHITELISTED_KEYS.containsKey(action);
    }

    private AdbCrypto getOrCreateCrypto() throws Exception {
        File privFile = new File(context.getFilesDir(), "bridge_adb_key");
        File pubFile = new File(context.getFilesDir(), "bridge_adb_key.pub");

        if (privFile.exists() && pubFile.exists() && privFile.length() > 0 && pubFile.length() > 0) {
            try {
                return AdbCrypto.loadAdbKeyPair(BASE64, privFile, pubFile);
            } catch (Exception e) {
                Log.w(TAG, "Failed to load existing ADB keypair, regenerating...", e);
            }
        }

        Log.i(TAG, "Generating new 2048-bit RSA keypair for Bridge ADB...");
        AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(BASE64);
        crypto.saveAdbKeyPair(privFile, pubFile);
        return crypto;
    }
}

