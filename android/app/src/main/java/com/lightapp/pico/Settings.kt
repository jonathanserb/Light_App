package com.lightapp.pico

/**
 * Values you might want to tune, all in one place. Change one and rebuild.
 * (The matching file on the Pico side is pico/settings.py.)
 */
object Settings {
    /** Set the Pico's clock from the phone every time the app connects. */
    const val SET_CLOCK_ON_CONNECT = true

    /** How long to wait for the Pico to answer a command before giving up. */
    const val REPLY_TIMEOUT_MS = 5_000L

    /** How long to wait for Android to confirm one Bluetooth packet was sent. */
    const val WRITE_TIMEOUT_MS = 5_000L

    /** Scanning stops by itself after this long. */
    const val SCAN_DURATION_MS = 15_000L

    /** How often the Status tab re-reads the clock, uptime, etc. while it is open. */
    const val STATUS_REFRESH_MS = 5_000L

    /** How often the Pins tab re-reads pin values while it is open. */
    const val PINS_REFRESH_MS = 2_000L

    /** Packet size asked of the Pico. Bigger means fewer packets per message. */
    const val REQUESTED_MTU = 247

    /** How many lines the Console tab keeps. */
    const val CONSOLE_LOG_LINES = 300
}
