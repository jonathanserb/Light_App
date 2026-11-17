package com.lightapp.pico.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import com.lightapp.pico.Settings;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The real Bluetooth LE link, using the Nordic UART Service: text lines out
 * through {@link #sendLine}, text lines in through the listener.
 *
 * Android calls back on its own threads; everything is passed to the main
 * thread first, so the rest of the app never has to think about threads.
 *
 * The Bluetooth permissions must already be granted before calling anything
 * here (the connect screen takes care of that).
 */
@SuppressLint("MissingPermission")
public class PicoBle implements PicoLink {
    // These must match pico/ble_uart.py.
    public static final UUID SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID RX = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"); // phone -> Pico
    public static final UUID TX = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"); // Pico -> phone
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LineFramer framer = new LineFramer();
    private final Map<String, Found> found = new LinkedHashMap<>();
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();

    private Listener listener;
    private State state = State.DISCONNECTED;
    private boolean scanning;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private int mtu = 23;
    private boolean writing;

    public PicoBle(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public State getState() {
        return state;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    @Override
    public boolean isBluetoothOn() {
        BluetoothAdapter adapter = adapter();
        return adapter != null && adapter.isEnabled();
    }

    private void setState(State newState) {
        state = newState;
        if (listener != null) listener.onState(newState);
    }

    // ---- scanning --------------------------------------------------------

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null) name = result.getDevice().getName();
            if (name == null) name = "Pico";
            Found item = new Found(result.getDevice().getAddress(), name, result.getRssi());
            main.post(() -> {
                found.put(item.address, item);
                reportScan();
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            main.post(() -> {
                scanning = false;
                reportScan();
            });
        }
    };

    private void reportScan() {
        if (listener != null) listener.onScan(new ArrayList<>(found.values()), scanning);
    }

    @Override
    public void startScan() {
        BluetoothAdapter adapter = adapter();
        BluetoothLeScanner scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        stopScan();
        found.clear();
        // Only show devices advertising our service, i.e. Picos running the firmware.
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        scanning = true;
        reportScan();
    }

    @Override
    public void stopScan() {
        if (!scanning) return;
        try {
            adapter().getBluetoothLeScanner().stopScan(scanCallback);
        } catch (RuntimeException e) {
            // Bluetooth was switched off mid-scan; nothing left to stop.
        }
        scanning = false;
        reportScan();
    }

    // ---- connection ------------------------------------------------------

    @Override
    public void connect(String address) {
        stopScan();
        disconnect();
        BluetoothDevice device;
        try {
            device = adapter().getRemoteDevice(address);
        } catch (RuntimeException e) {
            return; // Bluetooth unavailable or a malformed address
        }
        setState(State.CONNECTING);
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    @Override
    public void disconnect() {
        if (gatt != null) close(gatt);
    }

    /** Tear down connection {@code g}. Ignored if it is an old one we already replaced. */
    private void close(BluetoothGatt g) {
        try {
            g.disconnect();
            g.close();
        } catch (RuntimeException e) {
            // already gone
        }
        if (g != gatt) return;
        gatt = null;
        rx = null;
        writing = false;
        writeQueue.clear();
        main.removeCallbacks(writeTimedOut);
        setState(State.DISCONNECTED);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    mtu = 23;
                    framer.reset();
                    // A bigger MTU means far fewer packets per message. Falls back to 23 if refused.
                    if (!g.requestMtu(Settings.REQUESTED_MTU)) g.discoverServices();
                } else {
                    close(g);
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
            main.post(() -> {
                if (g != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu;
                g.discoverServices();
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g != gatt) return;
                if (!enableNotifications(g)) close(g);
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            main.post(() -> {
                if (g != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) setState(State.CONNECTED);
                else close(g);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> {
                if (g != gatt) return;
                main.removeCallbacks(writeTimedOut);
                writing = false;
                if (status == BluetoothGatt.GATT_SUCCESS) writeNext();
                else close(g);
            });
        }

        // Android 13+
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            received(g, value);
        }

        // Android 12 and older
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] value = c.getValue();
            if (value != null) received(g, value.clone());
        }
    };

    /** Ask the Pico to send us its lines. Returns false if this isn't one of our Picos. */
    @SuppressWarnings("deprecation")
    private boolean enableNotifications(BluetoothGatt g) {
        BluetoothGattService service = g.getService(SERVICE);
        if (service == null) return false;
        BluetoothGattCharacteristic tx = service.getCharacteristic(TX);
        rx = service.getCharacteristic(RX);
        if (tx == null || rx == null) return false;
        BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD);
        if (cccd == null) return false;
        g.setCharacteristicNotification(tx, true);
        byte[] enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        if (Build.VERSION.SDK_INT >= 33) {
            return g.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS;
        }
        cccd.setValue(enable);
        return g.writeDescriptor(cccd);
    }

    private void received(BluetoothGatt g, byte[] bytes) {
        main.post(() -> {
            if (g != gatt) return;
            for (String line : framer.feed(bytes)) {
                if (listener != null) listener.onLine(line);
            }
        });
    }

    // ---- sending ---------------------------------------------------------
    // Android allows one Bluetooth operation at a time, so packets wait in a
    // queue and the next one goes out when the previous one is confirmed.

    @Override
    public void sendLine(String text) {
        if (state != State.CONNECTED) return;
        byte[] data = (text + "\n").getBytes(StandardCharsets.UTF_8);
        writeQueue.addAll(LineFramer.chunk(data, mtu - 3));
        writeNext();
    }

    private final Runnable writeTimedOut = () -> {
        if (gatt != null) close(gatt);
    };

    @SuppressWarnings("deprecation")
    private void writeNext() {
        if (writing || gatt == null || rx == null) return;
        byte[] part = writeQueue.poll();
        if (part == null) return;
        int type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) {
            started = gatt.writeCharacteristic(rx, part, type) == BluetoothStatusCodes.SUCCESS;
        } else {
            rx.setWriteType(type);
            rx.setValue(part);
            started = gatt.writeCharacteristic(rx);
        }
        if (!started) {
            close(gatt);
            return;
        }
        writing = true;
        main.postDelayed(writeTimedOut, Settings.WRITE_TIMEOUT_MS);
    }
}
