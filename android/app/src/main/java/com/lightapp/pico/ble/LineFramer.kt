package com.lightapp.pico.ble

import java.io.ByteArrayOutputStream

/**
 * BLE delivers arbitrary fragments; the protocol is newline-terminated lines.
 * Splitting is done on bytes so a multi-byte UTF-8 character that straddles
 * two fragments is never decoded half-way.
 */
class LineFramer(private val maxLine: Int = 16 * 1024) {
    private val buf = ByteArrayOutputStream()

    fun reset() = buf.reset()

    fun feed(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        for (b in bytes) {
            if (b == '\n'.code.toByte()) {
                if (buf.size() > 0) out.add(buf.toString("UTF-8"))
                buf.reset()
            } else {
                if (buf.size() >= maxLine) buf.reset() // runaway line: drop it
                buf.write(b.toInt())
            }
        }
        return out
    }
}

/** Split an outgoing message into pieces that fit in one BLE write. */
fun chunk(data: ByteArray, size: Int): List<ByteArray> =
    (data.indices step size).map { data.copyOfRange(it, minOf(it + size, data.size)) }
