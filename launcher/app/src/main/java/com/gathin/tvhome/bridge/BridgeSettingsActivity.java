package com.gathin.tvhome.bridge;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.gathin.tvhome.R;
import com.gathin.tvhome.bridge.qr.QrBitmapHelper;

import java.util.Locale;

public class BridgeSettingsActivity extends Activity {

    private TextView statusText;
    private TextView toggleButton;
    private LinearLayout pairingContainer;
    private TextView addressText;
    private ImageView qrImage;
    private TextView pinText;
    private TextView expiryText;
    private TextView helpText;
    private TextView unpairButton;
    private TextView clearRememberedButton;

    private String displayedPin = null;

    private final Handler tickerHandler = new Handler(Looper.getMainLooper());
    private final Runnable tickerRunnable = new Runnable() {
        @Override
        public void run() {
            updateTick();
            tickerHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bridge_settings);

        statusText = findViewById(R.id.bridgeStatusText);
        toggleButton = findViewById(R.id.bridgeToggleButton);
        pairingContainer = findViewById(R.id.pairingContainer);
        addressText = findViewById(R.id.bridgeAddressText);
        qrImage = findViewById(R.id.bridgeQrImage);
        pinText = findViewById(R.id.bridgePinText);
        expiryText = findViewById(R.id.bridgeExpiryText);
        helpText = findViewById(R.id.bridgeHelpText);
        unpairButton = findViewById(R.id.bridgeUnpairButton);
        clearRememberedButton = findViewById(R.id.bridgeClearRememberedButton);

        toggleButton.setOnClickListener(v -> toggleBridge());
        unpairButton.setOnClickListener(v -> {
            BridgeConfig.unpairAll(this);
            Toast.makeText(this, "All paired devices removed", Toast.LENGTH_SHORT).show();
        });
        clearRememberedButton.setOnClickListener(v -> {
            BridgeConfig.clearRememberedApps(this);
            Toast.makeText(this, "Remembered apps cleared", Toast.LENGTH_SHORT).show();
        });

        toggleButton.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = BridgeConfig.isEnabled(this);
        if (enabled) {
            BridgeService.start(this);
        }
        BridgeConfig.setPairingScreenVisible(enabled);
        updateDisplay();
        tickerHandler.post(tickerRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        tickerHandler.removeCallbacks(tickerRunnable);
        // Guarantee: PIN and QR exist only while this screen is visible
        BridgeConfig.setPairingScreenVisible(false);
        displayedPin = null;
    }

    private void toggleBridge() {
        boolean enabled = !BridgeConfig.isEnabled(this);
        BridgeConfig.setEnabled(this, enabled);
        if (enabled) {
            BridgeService.start(this);
            BridgeConfig.setPairingScreenVisible(true);
            Toast.makeText(this, "Phone Bridge enabled", Toast.LENGTH_SHORT).show();
        } else {
            BridgeService.stop(this);
            BridgeConfig.setPairingScreenVisible(false);
            displayedPin = null;
            Toast.makeText(this, "Phone Bridge disabled", Toast.LENGTH_SHORT).show();
        }
        updateDisplay();
    }

    private void updateDisplay() {
        boolean enabled = BridgeConfig.isEnabled(this);
        String ip = BridgeConfig.getDeviceIp(this);
        String baseUrl = "http://" + ip + ":" + BridgeConfig.SERVER_PORT;

        if (enabled) {
            statusText.setText("● " + getString(R.string.phone_bridge_status_running));
            statusText.setTextColor(0xFF4CAF50); // Green
            toggleButton.setText(R.string.phone_bridge_toggle_on);
            pairingContainer.setVisibility(View.VISIBLE);

            addressText.setText("Connect on your phone: " + baseUrl);
            helpText.setText("Open " + baseUrl + " in your phone's browser or scan the QR code.");

            refreshPinAndQr(ip);
        } else {
            statusText.setText("○ " + getString(R.string.phone_bridge_status_stopped));
            statusText.setTextColor(0xFFACB8C1); // Neutral gray
            toggleButton.setText(R.string.phone_bridge_toggle_off);
            pairingContainer.setVisibility(View.GONE);
            displayedPin = null;
        }
    }

    private void refreshPinAndQr(String ip) {
        String pin = BridgeConfig.getActivePin();
        if (pin == null) {
            pin = BridgeConfig.generateNewPin();
        }

        if (!pin.equals(displayedPin)) {
            displayedPin = pin;
            // Format PIN as 3 digits space 3 digits (e.g. "123 456")
            String formattedPin = pin.length() == 6 ? pin.substring(0, 3) + " " + pin.substring(3) : pin;
            pinText.setText(formattedPin);

            // Generate QR code encoding http://<ip>:8765/?pin=<PIN>
            String qrUrl = "http://" + ip + ":" + BridgeConfig.SERVER_PORT + "/?pin=" + pin;
            Bitmap qrBitmap = QrBitmapHelper.createQrBitmap(qrUrl, 6, 2);
            if (qrBitmap != null) {
                qrImage.setImageBitmap(qrBitmap);
                qrImage.setVisibility(View.VISIBLE);
            } else {
                qrImage.setVisibility(View.GONE);
            }
        }
    }

    private void updateTick() {
        if (!BridgeConfig.isEnabled(this)) return;

        long remaining = BridgeConfig.getPinRemainingSeconds();
        if (remaining <= 0) {
            // PIN expired while user was looking at the screen - regenerate fresh PIN
            String ip = BridgeConfig.getDeviceIp(this);
            BridgeConfig.generateNewPin();
            refreshPinAndQr(ip);
            remaining = BridgeConfig.getPinRemainingSeconds();
        }

        long min = remaining / 60;
        long sec = remaining % 60;
        expiryText.setText(String.format(Locale.ROOT, "PIN expires in %d:%02d • Valid while this screen is open", min, sec));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
