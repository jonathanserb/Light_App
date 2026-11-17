package com.lightapp.pico.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lightapp.pico.Settings
import com.lightapp.pico.ble.PicoBle
import com.lightapp.pico.data.DeviceInfo
import com.lightapp.pico.data.PicoClient
import com.lightapp.pico.data.PinState
import com.lightapp.pico.data.ProgramInfo
import com.lightapp.pico.data.Schedule
import com.lightapp.pico.data.mapObjects
import com.lightapp.pico.data.parseValue
import com.lightapp.pico.data.pinToJson
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val ble = PicoBle(app)
    val client = PicoClient(ble, viewModelScope)
    private val prefs = app.getSharedPreferences("pico", 0)

    val info = MutableStateFlow<DeviceInfo?>(null)
    val pins = MutableStateFlow<List<PinState>>(emptyList())
    val schedules = MutableStateFlow<List<Schedule>>(emptyList())
    val programs = MutableStateFlow<List<ProgramInfo>>(emptyList())
    val adc = MutableStateFlow<List<Double>>(emptyList())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8) // each is shown once as a pop-up
    val lastDevice = MutableStateFlow(
        prefs.getString("addr", null)?.let { it to (prefs.getString("name", null) ?: "Pico") }
    )

    private var address: String? = null
    private var userDisconnected = false
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            var previous = PicoBle.State.DISCONNECTED
            ble.state.collect { state ->
                when (state) {
                    PicoBle.State.CONNECTED -> handshake()
                    PicoBle.State.DISCONNECTED -> {
                        info.value = null
                        if (!userDisconnected && previous != PicoBle.State.DISCONNECTED) {
                            say(if (previous == PicoBle.State.CONNECTING)
                                "Couldn't connect. Is the Pico powered and in range?"
                            else "Connection lost")
                        }
                    }
                    else -> {}
                }
                previous = state
            }
        }
        viewModelScope.launch {
            client.events.collect { ev ->
                when (ev.optString("ev")) {
                    "fired" -> { say("Schedule ran: ${ev.optString("name").ifBlank { "#${ev.optInt("sched")}" }}"); refreshAll() }
                    "prog_end" -> { say("Program “${ev.optString("name")}” finished"); refreshAll() }
                    "prog_err" -> { say("Program “${ev.optString("name")}” failed: ${ev.optString("err")}"); refreshAll() }
                    "sched_err" -> say("Schedule failed: ${ev.optString("err")}")
                }
            }
        }
    }

    /** Show a short pop-up message at the bottom of the screen. */
    private fun say(text: String) {
        messages.tryEmit(text)
    }

    /**
     * Every action below is wrapped in this: it runs [block] and turns any
     * Bluetooth or Pico error into a pop-up, unless [quiet] (background polling).
     */
    private fun run(quiet: Boolean = false, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: IOException) {
            if (!quiet) say(e.message ?: "Bluetooth error")
        }
    }

    // ---- connecting ------------------------------------------------------

    fun startScan() {
        scanJob?.cancel()
        ble.startScan()
        scanJob = viewModelScope.launch { delay(Settings.SCAN_DURATION_MS); ble.stopScan() }
    }

    fun connect(address: String, name: String) {
        this.address = address
        userDisconnected = false
        prefs.edit().putString("addr", address).putString("name", name).apply()
        lastDevice.value = address to name
        ble.connect(address)
    }

    fun disconnect() {
        userDisconnected = true
        ble.disconnect()
    }

    private fun handshake() = viewModelScope.launch {
        try {
            val first = DeviceInfo.from(client.request("info"))
            if (first.locked) {
                val saved = prefs.getString("pass_$address", null)
                val ok = saved != null && runCatching { client.request("auth", "pass" to saved) }.isSuccess
                if (!ok) {
                    info.value = first // UI asks for the passcode
                    return@launch
                }
            }
            unlocked()
        } catch (e: IOException) {
            // Connected to something that doesn't speak the protocol: don't sit there half-connected.
            if (ble.state.value == PicoBle.State.CONNECTED) {
                userDisconnected = true
                ble.disconnect()
                say("This device didn't respond as expected (${e.message})")
            }
        }
    }

    fun submitPasscode(pass: String) = run {
        client.request("auth", "pass" to pass)
        prefs.edit().putString("pass_$address", pass).apply()
        unlocked()
    }

    private suspend fun unlocked() {
        // The Pico has no battery-backed clock, so set it on every connection.
        if (Settings.SET_CLOCK_ON_CONNECT) client.request("time.set", "t" to phoneTime())
        loadAll()
    }

    private fun phoneTime(): JSONArray {
        val now = LocalDateTime.now()
        return JSONArray(listOf(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute,
            now.second, now.dayOfWeek.value - 1))
    }

    // ---- loading ---------------------------------------------------------

    private suspend fun loadInfo() {
        val i = DeviceInfo.from(client.request("info"))
        info.value = i
        if (i.name != lastDevice.value?.second) {
            prefs.edit().putString("name", i.name).apply()
            lastDevice.value = (address ?: return) to i.name
        }
    }

    private suspend fun loadPins() {
        pins.value = client.request("pin.list").optJSONArray("pins").mapObjects(PinState::from)
            .sortedBy { it.pin.toIntOrNull() ?: -1 }
    }

    private suspend fun loadSchedules() {
        schedules.value = client.request("sched.list").optJSONArray("items").mapObjects(Schedule::from)
            .sortedBy { it.hour * 60 + it.minute }
    }

    private suspend fun loadPrograms() {
        programs.value = client.request("prog.list").optJSONArray("items").mapObjects(ProgramInfo::from)
    }

    private suspend fun loadAll() {
        loadInfo(); loadPins(); loadSchedules(); loadPrograms()
    }

    fun refreshAll() = run { loadAll() }
    fun pollInfo() = run(quiet = true) { if (info.value?.locked == false) loadInfo() }
    fun pollPins() = run(quiet = true) { if (info.value?.locked == false) loadPins() }

    // ---- device ----------------------------------------------------------

    fun syncTime() = run {
        client.request("time.set", "t" to phoneTime())
        loadInfo()
        say("Clock set from phone")
    }

    fun rename(name: String) = run {
        client.request("name.set", "name" to name)
        loadInfo()
        say("Renamed. The new name is advertised after the next reboot.")
    }

    fun setPasscode(pass: String) = run {
        client.request("pass.set", "pass" to pass)
        prefs.edit().putString("pass_$address", pass).apply()
        loadInfo()
        say(if (pass.isEmpty()) "Passcode removed" else "Passcode set")
    }

    fun reboot() = run {
        client.request("reboot")
        say("Rebooting…")
    }

    // ---- pins ------------------------------------------------------------

    fun configurePin(pin: String, mode: String, label: String? = null) = run {
        val args = mutableListOf<Pair<String, Any?>>("pin" to pinToJson(pin), "mode" to mode)
        if (label != null) args += "label" to label
        client.request("pin.cfg", *args.toTypedArray())
        loadPins()
    }

    fun setPin(pin: String, on: Boolean) = run {
        client.request("pin.set", "pin" to pinToJson(pin), "value" to if (on) 1 else 0)
        loadPins()
    }

    fun setDuty(pin: String, duty: Int) = run {
        client.request("pin.set", "pin" to pinToJson(pin), "duty" to duty)
        loadPins()
    }

    fun readAdc() = run {
        adc.value = (0..2).map { client.request("adc.get", "ch" to it).optDouble("volts") }
    }

    // ---- schedules -------------------------------------------------------

    fun saveSchedule(s: Schedule) = run {
        client.request("sched.put", "item" to s.toJson())
        loadSchedules()
    }

    fun deleteSchedule(id: Int) = run {
        client.request("sched.del", "sid" to id)
        loadSchedules()
    }

    // ---- programs --------------------------------------------------------

    /** Store edited parameters; a running program picks them up immediately. */
    private suspend fun storeParams(program: ProgramInfo, edits: Map<String, String>) {
        for ((key, text) in edits) {
            if (text != program.params[key]) {
                // An empty field resets the parameter to the program's default.
                val value = if (text.isBlank()) null else parseValue(text)
                client.request("param.set", "key" to "${program.name}.$key", "value" to value)
            }
        }
    }

    fun saveParams(program: ProgramInfo, edits: Map<String, String>) = run {
        storeParams(program, edits)
        loadPrograms()
        say("Parameters saved")
    }

    fun runProgram(program: ProgramInfo, edits: Map<String, String>) = run {
        storeParams(program, edits)
        client.request("prog.run", "name" to program.name)
        loadPrograms(); loadInfo()
    }

    fun stopProgram() = run {
        client.request("prog.stop")
        loadInfo(); loadPins()
    }

    fun setBootProgram(name: String?) = run {
        client.request("prog.boot", "name" to name)
        loadInfo()
    }

    // ---- console ---------------------------------------------------------

    fun sendRaw(text: String) = run {
        // Accept either a full JSON object or just a command name such as "info".
        val line = if (text.trim().startsWith("{")) text.trim()
        else JSONObject().put("id", 0).put("cmd", text.trim()).toString()
        client.sendRaw(line)
    }

    override fun onCleared() {
        ble.stopScan()
        ble.disconnect()
    }
}
