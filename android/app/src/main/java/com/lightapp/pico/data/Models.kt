package com.lightapp.pico.data

import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else getString(key)

/** Pins travel as a GP number, or the string "LED" for the on-board LED. */
fun pinToJson(pin: String): Any = pin.trim().toIntOrNull() ?: pin.trim().uppercase()

fun pinName(pin: String): String = if (pin.toIntOrNull() != null) "GP$pin" else pin

/** Turn text typed into a parameter field into the JSON value the program expects. */
fun parseValue(text: String): Any {
    val t = text.trim()
    return t.toLongOrNull() ?: t.toDoubleOrNull()?.takeIf { it.isFinite() }
        ?: when (t.lowercase()) { "true" -> true; "false" -> false; else -> t }
}

data class DeviceInfo(
    val fw: String,
    val name: String,
    val time: List<Int>, // year, month, day, hour, minute, second, weekday (0 = Monday)
    val timeValid: Boolean,
    val uptimeS: Long,
    val tempC: Double,
    val memFree: Long,
    val program: String?,
    val bootProgram: String?,
    val scheduleCount: Int,
    val locked: Boolean,
    val hasPass: Boolean,
) {
    companion object {
        fun from(o: JSONObject): DeviceInfo {
            val t = o.optJSONArray("time") ?: JSONArray()
            return DeviceInfo(
                fw = o.optString("fw", "?"),
                name = o.optString("name", "Pico"),
                time = List(t.length()) { t.optInt(it) },
                timeValid = o.optBoolean("time_valid"),
                uptimeS = o.optLong("uptime"),
                tempC = o.optDouble("temp_c", 0.0),
                memFree = o.optLong("mem_free"),
                program = o.strOrNull("prog"),
                bootProgram = o.strOrNull("boot_prog"),
                scheduleCount = o.optInt("n_sched"),
                locked = o.optBoolean("locked"),
                hasPass = o.optBoolean("has_pass"),
            )
        }
    }
}

data class PinState(
    val pin: String,
    val mode: String, // in, in_up, in_down, out, pwm
    val value: Int,
    val duty: Int,
    val label: String,
) {
    val title: String get() = label.ifBlank { pinName(pin) }

    companion object {
        fun from(o: JSONObject) = PinState(
            pin = o.get("pin").toString(),
            mode = o.optString("mode", "in"),
            value = o.optInt("value"),
            duty = o.optDouble("duty", 0.0).toInt(),
            label = o.optString("label"),
        )
    }
}

data class Action(
    val type: String = "pin", // pin, pwm, prog, stop
    val pin: String = "LED",
    val value: Int = 1,
    val duty: Int = 100,
    val program: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("type", type).also {
        when (type) {
            "pin" -> it.put("pin", pinToJson(pin)).put("value", value)
            "pwm" -> it.put("pin", pinToJson(pin)).put("duty", duty)
            "prog" -> it.put("name", program)
        }
    }

    fun describe(): String = when (type) {
        "pin" -> "${pinName(pin)} ${if (value == 1) "ON" else "OFF"}"
        "pwm" -> "${pinName(pin)} at $duty%"
        "prog" -> "Run “$program”"
        else -> "Stop program"
    }

    companion object {
        fun from(o: JSONObject) = Action(
            type = o.optString("type", "pin"),
            pin = if (o.has("pin")) o.get("pin").toString() else "LED",
            value = o.optInt("value", 1),
            duty = o.optInt("duty", 100),
            program = o.optString("name"),
        )
    }
}

data class Schedule(
    val id: Int? = null, // null = not yet stored on the Pico
    val name: String = "",
    val enabled: Boolean = true,
    val hour: Int = 18,
    val minute: Int = 0,
    val days: Int = 127, // bit 0 = Monday ... bit 6 = Sunday
    val durationS: Int = 0, // 0 = leave it; otherwise undo the action after this long
    val action: Action = Action(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("en", enabled).put("h", hour).put("m", minute)
        .put("days", days).put("dur", durationS).put("act", action.toJson())
        .also { if (id != null) it.put("id", id) }

    fun daysText(): String = when (days) {
        127 -> "Every day"
        0b0011111 -> "Weekdays"
        0b1100000 -> "Weekends"
        else -> DAY_NAMES.filterIndexed { i, _ -> days shr i and 1 == 1 }.joinToString(" ")
    }

    companion object {
        val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        fun from(o: JSONObject) = Schedule(
            id = o.getInt("id"),
            name = o.optString("name"),
            enabled = o.optBoolean("en", true),
            hour = o.optInt("h"),
            minute = o.optInt("m"),
            days = o.optInt("days", 127),
            durationS = o.optInt("dur"),
            action = Action.from(o.getJSONObject("act")),
        )
    }
}

data class ProgramInfo(val name: String, val desc: String, val params: Map<String, String>) {
    companion object {
        fun from(o: JSONObject): ProgramInfo {
            val p = o.optJSONObject("params") ?: JSONObject()
            return ProgramInfo(
                name = o.getString("name"),
                desc = o.optString("desc"),
                params = p.keys().asSequence().sorted().associateWith { p.get(it).toString() },
            )
        }
    }
}

fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else List(length()) { f(getJSONObject(it)) }
