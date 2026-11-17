package com.lightapp.pico;

/**
 * Values you might want to tune, all in one place. Change one and rebuild.
 * (The matching file on the Pico side is pico/settings.py.)
 */
public final class Settings {
    /** Set the Pico's clock from the phone every time the app connects. */
    public static final boolean SET_CLOCK_ON_CONNECT = true;

    /** How long to wait for the Pico to answer a command before giving up. */
    public static final long REPLY_TIMEOUT_MS = 5_000;

    /** How long to wait for Android to confirm one Bluetooth packet was sent. */
    public static final long WRITE_TIMEOUT_MS = 5_000;

    /** Scanning stops by itself after this long. */
    public static final long SCAN_DURATION_MS = 15_000;

    /** How often the Status tab re-reads the clock, uptime, etc. while it is open. */
    public static final long STATUS_REFRESH_MS = 5_000;

    /** How often the Pins tab re-reads pin values while it is open. */
    public static final long PINS_REFRESH_MS = 2_000;

    /** Packet size asked of the Pico. Bigger means fewer packets per message. */
    public static final int REQUESTED_MTU = 247;

    /** How many lines the Console tab keeps. */
    public static final int CONSOLE_LOG_LINES = 300;

    private Settings() {
    }
}
