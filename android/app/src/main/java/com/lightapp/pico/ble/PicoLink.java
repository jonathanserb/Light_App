package com.lightapp.pico.ble;

import java.util.List;

/**
 * A text-line connection to a Pico. The app only ever talks to this interface;
 * {@link PicoBle} is the real Bluetooth version, and the tests use a pretend one.
 *
 * Every method must be called on the main thread, and the listener is always
 * called on the main thread.
 */
public interface PicoLink {
    enum State { DISCONNECTED, CONNECTING, CONNECTED }

    /** A Pico seen while scanning. */
    class Found {
        public final String address;
        public final String name;
        public final int rssi; // signal strength in dBm; closer to 0 is stronger

        public Found(String address, String name, int rssi) {
            this.address = address;
            this.name = name;
            this.rssi = rssi;
        }
    }

    interface Listener {
        void onState(State state);

        void onLine(String line);

        void onScan(List<Found> devices, boolean scanning);
    }

    void setListener(Listener listener);

    State getState();

    boolean isBluetoothOn();

    void startScan();

    void stopScan();

    void connect(String address);

    void disconnect();

    /** Queue one line for sending. Dropped if not connected. */
    void sendLine(String text);
}
