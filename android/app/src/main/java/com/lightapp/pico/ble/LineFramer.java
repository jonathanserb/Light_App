package com.lightapp.pico.ble;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bluetooth delivers arbitrary fragments; the protocol is newline-terminated
 * lines. Splitting is done on bytes so a multi-byte character that straddles
 * two fragments is never decoded half-way.
 */
public class LineFramer {
    private final int maxLine;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    public LineFramer() {
        this(16 * 1024);
    }

    public LineFramer(int maxLine) {
        this.maxLine = maxLine;
    }

    public void reset() {
        buffer.reset();
    }

    /** Add received bytes; returns every line they completed. */
    public List<String> feed(byte[] bytes) {
        List<String> lines = new ArrayList<>();
        for (byte b : bytes) {
            if (b == '\n') {
                if (buffer.size() > 0) {
                    lines.add(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                }
                buffer.reset();
            } else {
                if (buffer.size() >= maxLine) buffer.reset(); // runaway line: drop it
                buffer.write(b);
            }
        }
        return lines;
    }

    /** Split an outgoing message into pieces that fit in one Bluetooth packet. */
    public static List<byte[]> chunk(byte[] data, int size) {
        List<byte[]> parts = new ArrayList<>();
        for (int start = 0; start < data.length; start += size) {
            parts.add(Arrays.copyOfRange(data, start, Math.min(start + size, data.length)));
        }
        return parts;
    }
}
