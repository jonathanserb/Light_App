package com.lightapp.pico;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.lightapp.pico.ble.LineFramer;
import com.lightapp.pico.data.DeviceInfo;
import com.lightapp.pico.data.Json;
import com.lightapp.pico.data.PinState;
import com.lightapp.pico.data.ProgramInfo;
import com.lightapp.pico.data.Schedule;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Checks the pieces that turn bytes into lines and lines into data. */
@RunWith(RobolectricTestRunner.class)
public class ProtocolTest {
    @Test
    public void framerReassemblesLinesSplitAnywhere() {
        LineFramer framer = new LineFramer();
        byte[] message = "{\"a\":\"é☀\"}\n{\"b\":2}\n{\"c\"".getBytes(StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        for (byte[] part : LineFramer.chunk(message, 3)) lines.addAll(framer.feed(part));
        assertEquals(Arrays.asList("{\"a\":\"é☀\"}", "{\"b\":2}"), lines);
        assertEquals(Arrays.asList("{\"c\":3}"), framer.feed(":3}\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void framerDropsRunawayLine() {
        LineFramer framer = new LineFramer(8);
        List<String> lines = framer.feed("0123456789abcdef\nok\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("ok", lines.get(lines.size() - 1));
    }

    @Test
    public void chunkCoversAllBytes() {
        List<byte[]> parts = LineFramer.chunk(new byte[50], 20);
        assertEquals(3, parts.size());
        assertEquals(10, parts.get(2).length);
    }

    @Test
    public void valuesAndPins() {
        assertEquals(500L, Json.parseValue(" 500 "));
        assertEquals(2.5, Json.parseValue("2.5"));
        assertEquals(true, Json.parseValue("True"));
        assertEquals("LED", Json.parseValue("LED"));
        assertEquals(15, Json.pin("15"));
        assertEquals("LED", Json.pin("led"));
        assertEquals("GP15", Json.pinName("15"));
    }

    // These strings are what pico/controller.py actually sends.
    @Test
    public void parsesFirmwareReplies() throws Exception {
        DeviceInfo info = DeviceInfo.from(new JSONObject(
                "{\"fw\":\"1.1.0\",\"name\":\"Porch\",\"time\":[2026,11,16,18,30,0,0],\"time_valid\":true,"
                        + "\"uptime\":12,\"temp_c\":25.0,\"mem_free\":100000,\"prog\":null,\"boot_prog\":\"blink\","
                        + "\"n_sched\":1,\"locked\":false,\"has_pass\":false,\"ok\":true,\"id\":1}"));
        assertNull(info.program);
        assertEquals("blink", info.bootProgram);
        assertEquals(18, info.time[3]);

        PinState pin = PinState.from(new JSONObject(
                "{\"pin\":\"16\",\"mode\":\"pwm\",\"value\":0,\"duty\":37.5,\"freq\":1000,\"label\":\"\"}"));
        assertEquals(37, pin.duty);
        assertEquals("GP16", pin.title());

        ProgramInfo program = ProgramInfo.from(new JSONObject(
                "{\"name\":\"blink\",\"desc\":\"x\",\"params\":{\"pin\":\"LED\",\"on_ms\":500}}"));
        assertEquals("500", program.params.get("on_ms"));
        assertEquals("[on_ms, pin]", program.params.keySet().toString());
    }

    @Test
    public void scheduleRoundTrip() {
        Schedule s = new Schedule();
        s.id = 3;
        s.name = "Evening";
        s.hour = 18;
        s.minute = 30;
        s.days = 0b0011111;
        s.durationSeconds = 90;
        s.action.type = "pwm";
        s.action.pin = "16";
        s.action.duty = 80;

        JSONObject json = s.toJson();
        assertEquals(16, json.optJSONObject("act").opt("pin"));
        assertEquals(json.toString(), s.copy().toJson().toString());
        assertEquals("Weekdays", s.daysText());
        assertEquals("Evening · GP16 at 80% for 90 s", s.summary());
        s.days = 0b1000001;
        assertEquals("Mon Sun", s.daysText());
        assertTrue(new Schedule().toJson().isNull("id"));
    }
}
