package com.avr.homeautomation;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Encapsulates Bluetooth Classic (SPP) connectivity for ATmega32 + HC-05 communication.
 * Handles background socket connection, asynchronous stream reading, and UI-thread callbacks.
 */
public class BluetoothManager {

    private static final String TAG = "AVR_BluetoothManager";

    // Standard Classic Bluetooth Serial Port Profile (SPP) UUID
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface BluetoothCallback {
        void onConnecting(BluetoothDevice device);
        void onConnected(BluetoothDevice device);
        void onDisconnected();
        void onConnectionFailed(String errorMessage);
        void onDataReceived(String line);
    }

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final Handler mainHandler;
    private final ExecutorService connectionExecutor;
    private final ExecutorService readerExecutor;

    private BluetoothSocket bluetoothSocket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private BluetoothDevice connectedDevice;
    private BluetoothCallback callback;

    private volatile boolean isConnected = false;
    private volatile boolean isConnecting = false;

    public BluetoothManager(Context context) {
        this.context = context.getApplicationContext();
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.connectionExecutor = Executors.newSingleThreadExecutor();
        this.readerExecutor = Executors.newSingleThreadExecutor();
    }

    public void setCallback(BluetoothCallback callback) {
        this.callback = callback;
    }

    public boolean isBluetoothSupported() {
        return bluetoothAdapter != null;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean isConnected() {
        return isConnected && bluetoothSocket != null && bluetoothSocket.isConnected();
    }

    public boolean isConnecting() {
        return isConnecting;
    }

    public BluetoothDevice getConnectedDevice() {
        return connectedDevice;
    }

    /**
     * Retrieve paired devices with permission check guard.
     */
    @SuppressLint("MissingPermission")
    public Set<BluetoothDevice> getPairedDevices() {
        if (!isBluetoothSupported() || !bluetoothAdapter.isEnabled()) {
            return Collections.emptySet();
        }
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            return bonded != null ? bonded : Collections.emptySet();
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while accessing paired devices: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Initiate asynchronous connection to HC-05 device.
     */
    @SuppressLint("MissingPermission")
    public void connect(final BluetoothDevice device) {
        if (device == null) {
            notifyConnectionFailed("Target Bluetooth device is null");
            return;
        }

        if (isConnected()) {
            disconnect();
        }

        isConnecting = true;
        notifyConnecting(device);

        connectionExecutor.execute(() -> {
            try {
                // Cancel discovery before connecting as recommended by Android documentation
                if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }

                Log.d(TAG, "Attempting RFCOMM socket connection to: " + device.getAddress());
                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();

                synchronized (BluetoothManager.this) {
                    bluetoothSocket = socket;
                    inputStream = socket.getInputStream();
                    outputStream = socket.getOutputStream();
                    connectedDevice = device;
                    isConnected = true;
                    isConnecting = false;
                }

                Log.d(TAG, "Successfully connected to HC-05 (" + device.getAddress() + ")");
                notifyConnected(device);

                // Start dedicated reader thread
                startReaderLoop();

            } catch (SecurityException e) {
                isConnecting = false;
                Log.e(TAG, "Permission denied during connection: " + e.getMessage());
                notifyConnectionFailed("Permission denied: " + e.getMessage());
                closeSocketSilently();
            } catch (IOException e) {
                isConnecting = false;
                Log.e(TAG, "Connection failed: " + e.getMessage());
                notifyConnectionFailed("Failed to connect to " + device.getName() + " (" + e.getMessage() + ")");
                closeSocketSilently();
            }
        });
    }

    /**
     * Dedicated background loop reading newline-delimited ASCII lines from the ATmega32.
     */
    private void startReaderLoop() {
        readerExecutor.execute(() -> {
            BufferedReader reader = null;
            try {
                InputStream currentStream;
                synchronized (BluetoothManager.this) {
                    currentStream = inputStream;
                }
                if (currentStream == null) {
                    return;
                }

                reader = new BufferedReader(new InputStreamReader(currentStream, StandardCharsets.US_ASCII));
                String line;

                while (isConnected && (line = reader.readLine()) != null) {
                    final String receivedData = line.trim();
                    if (!receivedData.isEmpty()) {
                        notifyDataReceived(receivedData);
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "Reader stream closed or interrupted: " + e.getMessage());
            } finally {
                if (isConnected) {
                    disconnect();
                }
            }
        });
    }

    /**
     * Send a single-character ASCII command flushed immediately over OutputStream.
     */
    public boolean sendCommand(char cmd) {
        return sendData(String.valueOf(cmd));
    }

    /**
     * Send arbitrary ASCII string data flushed immediately.
     */
    public synchronized boolean sendData(String data) {
        if (!isConnected() || outputStream == null) {
            Log.w(TAG, "Cannot send command - Bluetooth is not connected");
            return false;
        }

        try {
            outputStream.write(data.getBytes(StandardCharsets.US_ASCII));
            outputStream.flush();
            Log.d(TAG, "TX Command Sent: " + data);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error writing to Bluetooth stream: " + e.getMessage());
            disconnect();
            return false;
        }
    }

    /**
     * Disconnect and release all socket and stream resources.
     */
    public synchronized void disconnect() {
        boolean wasConnected = isConnected || isConnecting;
        isConnected = false;
        isConnecting = false;

        closeSocketSilently();
        connectedDevice = null;

        if (wasConnected) {
            notifyDisconnected();
        }
    }

    private synchronized void closeSocketSilently() {
        try {
            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
            }
        } catch (IOException ignored) {}

        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
        } catch (IOException ignored) {}

        try {
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
                bluetoothSocket = null;
            }
        } catch (IOException ignored) {}
    }

    /**
     * Clean shutdown when Activity is destroyed.
     */
    public void destroy() {
        disconnect();
        try {
            connectionExecutor.shutdownNow();
            readerExecutor.shutdownNow();
        } catch (Exception e) {
            Log.w(TAG, "Error shutting down executors: " + e.getMessage());
        }
    }

    // --- Main Thread Dispatchers for UI Callbacks ---

    private void notifyConnecting(final BluetoothDevice device) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onConnecting(device);
            }
        });
    }

    private void notifyConnected(final BluetoothDevice device) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onConnected(device);
            }
        });
    }

    private void notifyDisconnected() {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onDisconnected();
            }
        });
    }

    private void notifyConnectionFailed(final String errorMessage) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onConnectionFailed(errorMessage);
            }
        });
    }

    private void notifyDataReceived(final String line) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onDataReceived(line);
            }
        });
    }
}
