package com.avr.homeautomation;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Main Activity providing the Smart-Home Dashboard interface for the
 * ATmega32 microcontroller via HC-05 Bluetooth (SPP) module.
 */
public class MainActivity extends AppCompatActivity implements BluetoothManager.BluetoothCallback {

    // Microcontroller UART Protocol Commands
    public static final char CMD_LIGHT_ON = 'L';
    public static final char CMD_LIGHT_OFF = 'l';
    public static final char CMD_AUX_ON = 'R';
    public static final char CMD_AUX_OFF = 'r';
    public static final char CMD_FAN_ON = 'F';
    public static final char CMD_FAN_OFF = 'f';
    public static final char CMD_SPEED_0 = '0';
    public static final char CMD_SPEED_40 = '1';
    public static final char CMD_SPEED_70 = '2';
    public static final char CMD_SPEED_100 = '3';
    public static final char CMD_GET_STATUS = 'S';

    private BluetoothManager bluetoothManager;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // UI Widgets - Header & Connection
    private TextView tvConnectionStatus;
    private TextView tvConnectedDevice;
    private MaterialButton btnConnect;

    // UI Widgets - Room Light
    private TextView tvLightStateBadge;
    private MaterialButton btnLightOn;
    private MaterialButton btnLightOff;

    // UI Widgets - Auxiliary Appliance
    private TextView tvAuxStateBadge;
    private MaterialButton btnAuxOn;
    private MaterialButton btnAuxOff;

    // UI Widgets - Fan Control
    private TextView tvFanStateBadge;
    private MaterialButton btnFanOn;
    private MaterialButton btnFanOff;
    private MaterialButton btnSpeed0;
    private MaterialButton btnSpeed1;
    private MaterialButton btnSpeed2;
    private MaterialButton btnSpeed3;
    private MaterialButton[] speedButtons;
    private int currentSpeedIndex = -1;

    // UI Widgets - Terminal Console
    private MaterialButton btnGetStatus;
    private MaterialButton btnClearConsole;
    private NestedScrollView terminalScrollView;
    private TextView tvTerminalOutput;
    private final SpannableStringBuilder terminalBuffer = new SpannableStringBuilder();

    // Appliance Logical States
    private boolean isLightOn = false;
    private boolean isAuxOn = false;
    private boolean isFanOn = false;

    // Permission Request Launcher
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (granted == null || !granted) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    showDeviceSelectionDialog();
                } else {
                    Toast.makeText(this, R.string.permission_denied_prompt, Toast.LENGTH_LONG).show();
                    appendTerminalLog("System", "Bluetooth permissions were denied by user.", ContextCompat.getColor(this, R.color.terminal_text_error));
                }
            });

    // Bluetooth Enable Request Launcher
    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (bluetoothManager.isBluetoothEnabled()) {
                    checkPermissionsAndConnect();
                } else {
                    Toast.makeText(this, R.string.bluetooth_disabled_prompt, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bluetoothManager = new BluetoothManager(this);
        bluetoothManager.setCallback(this);

        initViews();
        setupClickListeners();
        updateConnectionStateUi(ConnectionState.DISCONNECTED, null);
        setControlsEnabled(false);
    }

    private void initViews() {
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvConnectedDevice = findViewById(R.id.tvConnectedDevice);
        btnConnect = findViewById(R.id.btnConnect);

        tvLightStateBadge = findViewById(R.id.tvLightStateBadge);
        btnLightOn = findViewById(R.id.btnLightOn);
        btnLightOff = findViewById(R.id.btnLightOff);

        tvAuxStateBadge = findViewById(R.id.tvAuxStateBadge);
        btnAuxOn = findViewById(R.id.btnAuxOn);
        btnAuxOff = findViewById(R.id.btnAuxOff);

        tvFanStateBadge = findViewById(R.id.tvFanStateBadge);
        btnFanOn = findViewById(R.id.btnFanOn);
        btnFanOff = findViewById(R.id.btnFanOff);
        btnSpeed0 = findViewById(R.id.btnSpeed0);
        btnSpeed1 = findViewById(R.id.btnSpeed1);
        btnSpeed2 = findViewById(R.id.btnSpeed2);
        btnSpeed3 = findViewById(R.id.btnSpeed3);
        speedButtons = new MaterialButton[]{btnSpeed0, btnSpeed1, btnSpeed2, btnSpeed3};

        btnGetStatus = findViewById(R.id.btnGetStatus);
        btnClearConsole = findViewById(R.id.btnClearConsole);
        terminalScrollView = findViewById(R.id.terminalScrollView);
        tvTerminalOutput = findViewById(R.id.tvTerminalOutput);
    }

    private void setupClickListeners() {
        // Connect / Disconnect button
        btnConnect.setOnClickListener(v -> {
            if (bluetoothManager.isConnected()) {
                bluetoothManager.disconnect();
            } else if (bluetoothManager.isConnecting()) {
                bluetoothManager.disconnect();
                updateConnectionStateUi(ConnectionState.DISCONNECTED, null);
            } else {
                checkPermissionsAndConnect();
            }
        });

        // Room Light (Relay 1 + LED)
        btnLightOn.setOnClickListener(v -> executeCommand(CMD_LIGHT_ON, "Light ON", () -> updateLightUi(true)));
        btnLightOff.setOnClickListener(v -> executeCommand(CMD_LIGHT_OFF, "Light OFF", () -> updateLightUi(false)));

        // Auxiliary Appliance (Relay 2)
        btnAuxOn.setOnClickListener(v -> executeCommand(CMD_AUX_ON, "Aux ON", () -> updateAuxUi(true)));
        btnAuxOff.setOnClickListener(v -> executeCommand(CMD_AUX_OFF, "Aux OFF", () -> updateAuxUi(false)));

        // Fan Control (DC Motor + PWM)
        btnFanOn.setOnClickListener(v -> executeCommand(CMD_FAN_ON, "Fan ON", () -> updateFanUi(true)));
        btnFanOff.setOnClickListener(v -> executeCommand(CMD_FAN_OFF, "Fan OFF", () -> {
            updateFanUi(false);
            highlightSpeedButton(-1);
        }));

        // Fan Speed Presets
        btnSpeed0.setOnClickListener(v -> executeCommand(CMD_SPEED_0, "Fan Speed 0%", () -> {
            highlightSpeedButton(0);
            updateFanUi(false);
        }));
        btnSpeed1.setOnClickListener(v -> executeCommand(CMD_SPEED_40, "Fan Speed 40%", () -> {
            highlightSpeedButton(1);
            updateFanUi(true);
        }));
        btnSpeed2.setOnClickListener(v -> executeCommand(CMD_SPEED_70, "Fan Speed 70%", () -> {
            highlightSpeedButton(2);
            updateFanUi(true);
        }));
        btnSpeed3.setOnClickListener(v -> executeCommand(CMD_SPEED_100, "Fan Speed 100%", () -> {
            highlightSpeedButton(3);
            updateFanUi(true);
        }));

        // Microcontroller Status & EEPROM Logs
        btnGetStatus.setOnClickListener(v -> executeCommand(CMD_GET_STATUS, "Request Status & EEPROM", null));

        // Clear Console
        btnClearConsole.setOnClickListener(v -> {
            terminalBuffer.clear();
            tvTerminalOutput.setText(R.string.terminal_placeholder);
        });
    }

    /**
     * Executes command if connected; otherwise alerts user.
     */
    private void executeCommand(char cmd, String label, Runnable uiAction) {
        if (!bluetoothManager.isConnected()) {
            Toast.makeText(this, R.string.warn_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean success = bluetoothManager.sendCommand(cmd);
        if (success) {
            appendTerminalTx(cmd, label);
            if (uiAction != null) {
                uiAction.run();
            }
        } else {
            Toast.makeText(this, "Failed to send command '" + cmd + "'", Toast.LENGTH_SHORT).show();
        }
    }

    // --- Permission & Bluetooth Enablement Checks ---

    private void checkPermissionsAndConnect() {
        if (!bluetoothManager.isBluetoothSupported()) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG).show();
            return;
        }

        if (!bluetoothManager.isBluetoothEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableBtIntent);
            return;
        }

        List<String> requiredPermissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (requiredPermissions.isEmpty()) {
            showDeviceSelectionDialog();
        } else {
            permissionLauncher.launch(requiredPermissions.toArray(new String[0]));
        }
    }

    /**
     * Shows paired Bluetooth devices dialog to select the HC-05 module.
     */
    @SuppressLint("MissingPermission")
    private void showDeviceSelectionDialog() {
        Set<BluetoothDevice> pairedDevices = bluetoothManager.getPairedDevices();

        if (pairedDevices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.select_bluetooth_device)
                    .setMessage(R.string.no_paired_devices)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        List<BluetoothDevice> deviceList = new ArrayList<>(pairedDevices);
        CharSequence[] items = new CharSequence[deviceList.size()];
        int defaultHc05Index = -1;

        for (int i = 0; i < deviceList.size(); i++) {
            BluetoothDevice dev = deviceList.get(i);
            String name = (dev.getName() != null && !dev.getName().isEmpty()) ? dev.getName() : "Unknown Device";
            String addr = dev.getAddress();
            items[i] = name + "\n" + addr;
            if (name.toUpperCase().contains("HC-05") || name.toUpperCase().contains("HC05")) {
                defaultHc05Index = i;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.select_bluetooth_device)
                .setSingleChoiceItems(items, defaultHc05Index, (dialog, which) -> {
                    dialog.dismiss();
                    BluetoothDevice selectedDevice = deviceList.get(which);
                    bluetoothManager.connect(selectedDevice);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- BluetoothManager Callback Implementations ---

    @Override
    public void onConnecting(BluetoothDevice device) {
        updateConnectionStateUi(ConnectionState.CONNECTING, device);
        appendTerminalLog("SYS", "Connecting to " + getDeviceDisplayName(device) + "...",
                ContextCompat.getColor(this, R.color.terminal_text_warning));
    }

    @Override
    public void onConnected(BluetoothDevice device) {
        updateConnectionStateUi(ConnectionState.CONNECTED, device);
        setControlsEnabled(true);
        appendTerminalLog("SYS", "Connected to " + getDeviceDisplayName(device) + " (SPP)",
                ContextCompat.getColor(this, R.color.terminal_text_green));
        Toast.makeText(this, "Connected to " + getDeviceDisplayName(device), Toast.LENGTH_SHORT).show();

        // Automatically query initial status upon connection
        executeCommand(CMD_GET_STATUS, "Initial Status Request", null);
    }

    @Override
    public void onDisconnected() {
        updateConnectionStateUi(ConnectionState.DISCONNECTED, null);
        setControlsEnabled(false);
        appendTerminalLog("SYS", "Bluetooth connection closed.",
                ContextCompat.getColor(this, R.color.terminal_text_dim));
    }

    @Override
    public void onConnectionFailed(String errorMessage) {
        updateConnectionStateUi(ConnectionState.DISCONNECTED, null);
        setControlsEnabled(false);
        appendTerminalLog("ERROR", errorMessage,
                ContextCompat.getColor(this, R.color.terminal_text_error));
        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onDataReceived(String line) {
        appendTerminalRx(line);
        parseIncomingTelemetry(line);
    }

    // --- Telemetry Parsing & Automatic State Synchronization ---

    private void parseIncomingTelemetry(String line) {
        String upper = line.toUpperCase().trim();

        // Hardware Manual Lock Warning Detection
        if (upper.contains("ERR:MANUAL_LOCK")) {
            Toast.makeText(this, R.string.warn_manual_lock, Toast.LENGTH_LONG).show();
        }

        // Room Light State Detection
        if (upper.contains("LIGHT: ON") || upper.contains("LIGHT ON") || upper.equals("L:1")) {
            updateLightUi(true);
        } else if (upper.contains("LIGHT: OFF") || upper.contains("LIGHT OFF") || upper.equals("L:0")) {
            updateLightUi(false);
        }

        // Auxiliary Appliance State Detection
        if (upper.contains("AUX: ON") || upper.contains("RELAY: ON") || upper.contains("RELAY 2: ON") || upper.equals("R:1")) {
            updateAuxUi(true);
        } else if (upper.contains("AUX: OFF") || upper.contains("RELAY: OFF") || upper.contains("RELAY 2: OFF") || upper.equals("R:0")) {
            updateAuxUi(false);
        }

        // Fan State & PWM Speed Detection
        if (upper.contains("FAN: ON") || upper.contains("FAN ON") || upper.equals("F:1")) {
            updateFanUi(true);
        } else if (upper.contains("FAN: OFF") || upper.contains("FAN OFF") || upper.equals("F:0")) {
            updateFanUi(false);
            highlightSpeedButton(-1);
        }

        if (upper.contains("SPEED: 0") || upper.contains("PWM: 0%") || upper.contains("SPEED 0")) {
            highlightSpeedButton(0);
            updateFanUi(false);
        } else if (upper.contains("SPEED: 40") || upper.contains("PWM: 40%") || upper.contains("SPEED 1")) {
            highlightSpeedButton(1);
            updateFanUi(true);
        } else if (upper.contains("SPEED: 70") || upper.contains("PWM: 70%") || upper.contains("SPEED 2")) {
            highlightSpeedButton(2);
            updateFanUi(true);
        } else if (upper.contains("SPEED: 100") || upper.contains("PWM: 100%") || upper.contains("SPEED 3")) {
            highlightSpeedButton(3);
            updateFanUi(true);
        }
    }

    // --- UI State Management ---

    private enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    @SuppressLint("MissingPermission")
    private void updateConnectionStateUi(ConnectionState state, BluetoothDevice device) {
        switch (state) {
            case DISCONNECTED:
                tvConnectionStatus.setText(R.string.status_disconnected);
                tvConnectionStatus.setBackgroundResource(R.drawable.bg_status_badge);
                tvConnectionStatus.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_disconnected_bg)));
                tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected_text));

                btnConnect.setText(R.string.btn_connect);
                btnConnect.setIconResource(R.drawable.ic_bluetooth);
                btnConnect.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary)));

                tvConnectedDevice.setVisibility(View.GONE);
                break;

            case CONNECTING:
                tvConnectionStatus.setText(R.string.status_connecting);
                tvConnectionStatus.setBackgroundResource(R.drawable.bg_status_badge);
                tvConnectionStatus.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connecting_bg)));
                tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connecting_text));

                btnConnect.setText(android.R.string.cancel);
                btnConnect.setIconResource(R.drawable.ic_clear);
                btnConnect.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary)));

                if (device != null) {
                    tvConnectedDevice.setVisibility(View.VISIBLE);
                    tvConnectedDevice.setText("Connecting to: " + getDeviceDisplayName(device));
                }
                break;

            case CONNECTED:
                tvConnectionStatus.setText(R.string.status_connected);
                tvConnectionStatus.setBackgroundResource(R.drawable.bg_status_badge);
                tvConnectionStatus.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connected_bg)));
                tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected_text));

                btnConnect.setText(R.string.btn_disconnect);
                btnConnect.setIconResource(R.drawable.ic_clear);
                btnConnect.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_disconnected_text)));

                if (device != null) {
                    tvConnectedDevice.setVisibility(View.VISIBLE);
                    tvConnectedDevice.setText("Connected: " + getDeviceDisplayName(device) + " (" + device.getAddress() + ")");
                }
                break;
        }
    }

    private void setControlsEnabled(boolean enabled) {
        float alpha = enabled ? 1.0f : 0.45f;

        btnLightOn.setEnabled(enabled);
        btnLightOff.setEnabled(enabled);
        btnLightOn.setAlpha(alpha);
        btnLightOff.setAlpha(alpha);

        btnAuxOn.setEnabled(enabled);
        btnAuxOff.setEnabled(enabled);
        btnAuxOn.setAlpha(alpha);
        btnAuxOff.setAlpha(alpha);

        btnFanOn.setEnabled(enabled);
        btnFanOff.setEnabled(enabled);
        btnFanOn.setAlpha(alpha);
        btnFanOff.setAlpha(alpha);

        for (MaterialButton btn : speedButtons) {
            btn.setEnabled(enabled);
            btn.setAlpha(alpha);
        }

        btnGetStatus.setEnabled(enabled);
        btnGetStatus.setAlpha(alpha);
    }

    private void updateLightUi(boolean on) {
        isLightOn = on;
        tvLightStateBadge.setText(on ? R.string.state_on : R.string.state_off);
        tvLightStateBadge.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, on ? R.color.status_connected_bg : R.color.surface_card_stroke)));
        tvLightStateBadge.setTextColor(ContextCompat.getColor(this, on ? R.color.status_connected_text : R.color.text_secondary));
    }

    private void updateAuxUi(boolean on) {
        isAuxOn = on;
        tvAuxStateBadge.setText(on ? R.string.state_on : R.string.state_off);
        tvAuxStateBadge.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, on ? R.color.status_connected_bg : R.color.surface_card_stroke)));
        tvAuxStateBadge.setTextColor(ContextCompat.getColor(this, on ? R.color.status_connected_text : R.color.text_secondary));
    }

    private void updateFanUi(boolean on) {
        isFanOn = on;
        String badgeText = on ? getString(R.string.state_on) : getString(R.string.state_off);
        if (on && currentSpeedIndex >= 0 && currentSpeedIndex < speedButtons.length) {
            badgeText += " (" + speedButtons[currentSpeedIndex].getText() + ")";
        }
        tvFanStateBadge.setText(badgeText);
        tvFanStateBadge.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, on ? R.color.status_connecting_bg : R.color.surface_card_stroke)));
        tvFanStateBadge.setTextColor(ContextCompat.getColor(this, on ? R.color.status_connecting_text : R.color.text_secondary));
    }

    private void highlightSpeedButton(int selectedIndex) {
        currentSpeedIndex = selectedIndex;
        int activeBg = ContextCompat.getColor(this, R.color.primary);
        int activeText = ContextCompat.getColor(this, R.color.on_primary);
        int defaultText = ContextCompat.getColor(this, R.color.text_primary);
        int defaultStroke = ContextCompat.getColor(this, R.color.surface_card_stroke);

        for (int i = 0; i < speedButtons.length; i++) {
            MaterialButton btn = speedButtons[i];
            if (i == selectedIndex) {
                btn.setBackgroundTintList(ColorStateList.valueOf(activeBg));
                btn.setTextColor(activeText);
                btn.setStrokeWidth(0);
            } else {
                btn.setBackgroundTintList(null);
                btn.setTextColor(defaultText);
                btn.setStrokeColor(ColorStateList.valueOf(defaultStroke));
                btn.setStrokeWidth(1);
            }
        }
    }

    // --- Monospace Terminal Output Formatting ---

    private void appendTerminalTx(char cmd, String label) {
        String time = timeFormat.format(new Date());
        String text = String.format("[%s TX] > %s ('%c')\n", time, label, cmd);
        appendColoredSpan(text, ContextCompat.getColor(this, R.color.terminal_text_tx));
    }

    private void appendTerminalRx(String line) {
        String time = timeFormat.format(new Date());
        String text = String.format("[%s RX] %s\n", time, line);

        int color;
        if (line.toUpperCase().contains("ERR:MANUAL_LOCK") || line.toUpperCase().contains("ERROR") || line.toUpperCase().contains("FAIL")) {
            color = ContextCompat.getColor(this, R.color.terminal_text_error);
        } else if (line.toUpperCase().contains("WARN") || line.toUpperCase().contains("LOCK")) {
            color = ContextCompat.getColor(this, R.color.terminal_text_warning);
        } else {
            color = ContextCompat.getColor(this, R.color.terminal_text_green);
        }

        appendColoredSpan(text, color);
    }

    private void appendTerminalLog(String tag, String message, int color) {
        String time = timeFormat.format(new Date());
        String text = String.format("[%s %s] %s\n", time, tag, message);
        appendColoredSpan(text, color);
    }

    private void appendColoredSpan(String text, int color) {
        int start = terminalBuffer.length();
        terminalBuffer.append(text);
        terminalBuffer.setSpan(new ForegroundColorSpan(color), start, terminalBuffer.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        tvTerminalOutput.setText(terminalBuffer);
        terminalScrollView.post(() -> terminalScrollView.fullScroll(View.FOCUS_DOWN));
    }

    @SuppressLint("MissingPermission")
    private String getDeviceDisplayName(BluetoothDevice device) {
        if (device == null) return "Unknown";
        try {
            return (device.getName() != null && !device.getName().isEmpty()) ? device.getName() : device.getAddress();
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothManager != null) {
            bluetoothManager.destroy();
        }
    }
}
