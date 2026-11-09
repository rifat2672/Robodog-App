package com.rifat.robodog;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/**
 * Bluetooth LE সংযোগ: "RoboDog" নামের ESP32 খুঁজে Nordic UART Service দিয়ে কথা বলে।
 * Pairing লাগে না। সব state শুধু main thread এ বদলায়, তাই lock লাগে না।
 * Permission (BLUETOOTH_SCAN/CONNECT বা Location) MainActivity আগেই নিয়ে নেয়।
 */
@SuppressLint("MissingPermission")
public class BleLink implements DogLink {

    static final UUID SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");   // ফোন -> ESP32
    static final UUID TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");   // ESP32 -> ফোন
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final String NAME_PREFIX = "RoboDog";

    private static final long SCAN_TIMEOUT_MS = 10000;
    private static final long CONNECT_TIMEOUT_MS = 12000;
    private static final long WRITE_TIMEOUT_MS = 1500;
    private static final int MTU = 185;

    private final Context ctx;
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final StringBuilder rxBuf = new StringBuilder();

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxChar;
    private String deviceName;
    private boolean scanning, connected, done, writing, servicesRequested;

    public BleLink(Context ctx, BluetoothAdapter adapter, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.adapter = adapter;
        this.listener = listener;
    }

    // ===================== খোঁজা (scan) =====================

    @Override
    public void connect() {
        if (adapter == null || !adapter.isEnabled()) {
            finish(ctx.getString(R.string.err_bt_off));
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            finish(ctx.getString(R.string.err_bt_off));
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanning = true;
        scanner.startScan(null, settings, scanCallback);
        main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
    }

    private final Runnable scanTimeout = this::onScanTimeout;

    private void onScanTimeout() {
        if (scanning) {
            stopScan();
            finish(ctx.getString(R.string.err_ble_not_found));
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            main.post(() -> onDeviceFound(result));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) main.post(() -> onDeviceFound(r));
        }

        @Override
        public void onScanFailed(int errorCode) {
            main.post(() -> {
                scanning = false;
                main.removeCallbacks(scanTimeout);
                finish(ctx.getString(R.string.err_ble_scan, errorCode));
            });
        }
    };

    private void onDeviceFound(ScanResult r) {
        if (!scanning || done) return;
        ScanRecord rec = r.getScanRecord();
        String name = rec != null ? rec.getDeviceName() : null;
        if (name == null) name = r.getDevice().getName();

        boolean match = name != null && name.startsWith(NAME_PREFIX);
        if (!match && name == null && rec != null) {
            // নাম না পেলে service UUID দেখে চিনি
            List<ParcelUuid> uuids = rec.getServiceUuids();
            match = uuids != null && uuids.contains(new ParcelUuid(SERVICE));
        }
        if (!match) return;

        stopScan();
        deviceName = name != null ? name : r.getDevice().getAddress();
        gatt = r.getDevice().connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private void stopScan() {
        main.removeCallbacks(scanTimeout);
        if (!scanning) return;
        scanning = false;
        try {
            scanner.stopScan(scanCallback);
        } catch (Exception ignored) {
            // Bluetooth বন্ধ হয়ে গেলে exception আসতে পারে
        }
    }

    private final Runnable connectTimeout = this::onConnectTimeout;

    private void onConnectTimeout() {
        if (!connected) finish(ctx.getString(R.string.err_timeout));
    }

    // ===================== GATT =====================

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (done) return;
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    // বড় MTU চাই যাতে ESP32 এর পুরো status এক প্যাকেটে আসে
                    if (g.requestMtu(MTU)) {
                        main.postDelayed(discoverFallback, 1500);
                    } else {
                        discoverServices();
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    finish(connected
                            ? ctx.getString(R.string.err_link_lost)
                            : ctx.getString(R.string.err_ble_connect, status));
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            main.post(BleLink.this::discoverServices);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> setupService(status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(BleLink.this::onReady);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> {
                main.removeCallbacks(writeTimeout);
                writing = false;
                pump();
            });
        }

        // Android 13 (API 33) ও নতুন
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            onNotify(value);
        }

        // Android 12 ও পুরনো
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            onNotify(c.getValue());
        }
    };

    private final Runnable discoverFallback = this::discoverServices;

    private void discoverServices() {
        main.removeCallbacks(discoverFallback);
        if (done || servicesRequested || gatt == null) return;
        servicesRequested = true;
        gatt.discoverServices();
    }

    @SuppressWarnings("deprecation")
    private void setupService(int status) {
        if (done || gatt == null) return;
        BluetoothGattService s = gatt.getService(SERVICE);
        if (status != BluetoothGatt.GATT_SUCCESS || s == null) {
            finish(ctx.getString(R.string.err_ble_service));
            return;
        }
        rxChar = s.getCharacteristic(RX);
        BluetoothGattCharacteristic tx = s.getCharacteristic(TX);
        if (rxChar == null || tx == null) {
            finish(ctx.getString(R.string.err_ble_service));
            return;
        }

        // ESP32 এর উত্তর (notify) চালু
        gatt.setCharacteristicNotification(tx, true);
        BluetoothGattDescriptor d = tx.getDescriptor(CCCD);
        if (d == null) {
            onReady();
            return;
        }
        boolean ok;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ok = gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    == BluetoothStatusCodes.SUCCESS;
        } else {
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            ok = gatt.writeDescriptor(d);
        }
        if (!ok) onReady();   // notify ছাড়াও কমান্ড পাঠানো যাবে
    }

    private void onReady() {
        if (done || connected) return;
        connected = true;
        main.removeCallbacks(connectTimeout);
        listener.onConnected(this, deviceName);
        pump();
    }

    /** binder thread থেকে আসে — লাইন জোড়া লাগিয়ে main thread এ পাঠাই */
    private void onNotify(byte[] value) {
        if (value == null) return;
        String chunk = new String(value, StandardCharsets.UTF_8);
        main.post(() -> {
            if (done) return;
            rxBuf.append(chunk);
            int nl;
            while ((nl = rxBuf.indexOf("\n")) >= 0) {
                String line = rxBuf.substring(0, nl).trim();
                rxBuf.delete(0, nl + 1);
                if (!line.isEmpty()) listener.onLine(this, line);
            }
            if (rxBuf.length() > 256) rxBuf.setLength(0);
        });
    }

    // ===================== পাঠানো =====================
    // BLE এ একসাথে একটাই write চলতে পারে, তাই queue

    private static boolean isMove(String c) {
        return c.equals("F") || c.equals("B") || c.equals("L") || c.equals("R");
    }

    @Override
    public void send(String command) {
        if (!connected || done) return;
        // চলার কমান্ড বারবার আসে — পুরনোটা জমে থাকলে বাদ দিই
        if (isMove(command)) queue.removeIf(BleLink::isMove);
        queue.add(command);
        pump();
    }

    private final Runnable writeTimeout = () -> {
        writing = false;
        pump();
    };

    private final Runnable retryPump = this::pump;

    @SuppressWarnings("deprecation")
    private void pump() {
        if (!connected || done || writing || queue.isEmpty() || gatt == null || rxChar == null) return;
        String cmd = queue.poll();
        byte[] data = (cmd + "\n").getBytes(StandardCharsets.UTF_8);
        int type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        boolean ok;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ok = gatt.writeCharacteristic(rxChar, data, type) == BluetoothStatusCodes.SUCCESS;
        } else {
            rxChar.setWriteType(type);
            rxChar.setValue(data);
            ok = gatt.writeCharacteristic(rxChar);
        }
        if (ok) {
            writing = true;
            main.postDelayed(writeTimeout, WRITE_TIMEOUT_MS);
        } else {
            queue.addFirst(cmd);              // Bluetooth ব্যস্ত, একটু পরে আবার
            main.removeCallbacks(retryPump);
            main.postDelayed(retryPump, 50);
        }
    }

    // ===================== বন্ধ =====================

    @Override
    public void close() {
        if (done) return;
        done = true;
        cleanup();
    }

    private void finish(String reason) {
        if (done) return;
        done = true;
        cleanup();
        listener.onDisconnected(this, reason);
    }

    private void cleanup() {
        stopScan();
        connected = false;
        queue.clear();
        main.removeCallbacks(connectTimeout);
        main.removeCallbacks(discoverFallback);
        main.removeCallbacks(writeTimeout);
        main.removeCallbacks(retryPump);
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }

    @Override
    public boolean isConnected() {
        return connected && !done;
    }
}
