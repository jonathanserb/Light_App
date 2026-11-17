package com.lightapp.pico

import com.lightapp.pico.ble.LineFramer
import com.lightapp.pico.ble.chunk
import com.lightapp.pico.data.Action
import com.lightapp.pico.data.DeviceInfo
import com.lightapp.pico.data.PinState
import com.lightapp.pico.data.ProgramInfo
import com.lightapp.pico.data.Schedule
import com.lightapp.pico.data.parseValue
import com.lightapp.pico.data.pinToJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    @Test
    fun framerReassemblesLinesSplitAnywhere() {
        val framer = LineFramer()
        val message = "{\"a\":\"é☀\"}\n{\"b\":2}\n{\"c\"".toByteArray()
        val lines = chunk(message, 3).flatMap { framer.feed(it) }
        assertEquals(listOf("{\"a\":\"é☀\"}", "{\"b\":2}"), lines)
        assertEquals(listOf("{\"c\":3}"), framer.feed(":3}\n".toByteArray()))
    }

    @Test
    fun framerDropsRunawayLine() {
        val framer = LineFramer(maxLine = 8)
        assertEquals(listOf("ok"), framer.feed("0123456789abcdef\nok\n".toByteArray()).takeLast(1))
    }

    @Test
    fun chunkCoversAllBytes() {
        val parts = chunk(ByteArray(50) { it.toByte() }, 20)
        assertEquals(listOf(20, 20, 10), parts.map { it.size })
        assertEquals(49.toByte(), parts.last().last())
    }

    @Test
    fun valuesAndPins() {
        assertEquals(500L, parseValue(" 500 "))
        assertEquals(2.5, parseValue("2.5"))
        assertEquals(true, parseValue("True"))
        assertEquals("LED", parseValue("LED"))
        assertEquals(15, pinToJson("15"))
        assertEquals("LED", pinToJson("led"))
    }

    // These strings are what pico/controller.py actually sends.
    @Test
    fun parsesFirmwareReplies() {
        val info = DeviceInfo.from(JSONObject(
            """{"fw":"1.0.0","name":"Porch","time":[2026,11,16,18,30,0,0],"time_valid":true,
               "uptime":12,"temp_c":25.0,"mem_free":100000,"prog":null,"boot_prog":"blink",
               "n_sched":1,"locked":false,"has_pass":false,"ok":true,"id":1}"""))
        assertNull(info.program)
        assertEquals("blink", info.bootProgram)
        assertEquals(listOf(2026, 11, 16, 18, 30, 0, 0), info.time)

        val pin = PinState.from(JSONObject(
            """{"pin":"16","mode":"pwm","value":0,"duty":37.5,"freq":1000,"label":""}"""))
        assertEquals(37, pin.duty)
        assertEquals("GP16", pin.title)

        val prog = ProgramInfo.from(JSONObject(
            """{"name":"blink","desc":"x","params":{"pin":"LED","on_ms":500}}"""))
        assertEquals(mapOf("on_ms" to "500", "pin" to "LED"), prog.params)
    }

    @Test
    fun scheduleRoundTrip() {
        val s = Schedule(id = 3, name = "Evening", hour = 18, minute = 30, days = 0b0011111,
            durationS = 90, action = Action(type = "pwm", pin = "16", duty = 80))
        val json = s.toJson()
        assertEquals(16, json.getJSONObject("act").get("pin"))
        assertEquals(s, Schedule.from(JSONObject(json.toString())))
        assertEquals("Weekdays", s.daysText())
        assertEquals("Mon Sun", s.copy(days = 0b1000001).daysText())
    }
}
