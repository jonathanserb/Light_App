package com.lightapp.pico.data

import com.lightapp.pico.ble.PicoBle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The Pico answered, but with an error (or not at all). */
class PicoException(message: String) : IOException(message)

/**
 * Request/response on top of the line link. Every request carries an "id" that
 * the Pico echoes back; lines without a matching id are unsolicited events.
 */
class PicoClient(private val ble: PicoBle, scope: CoroutineScope) {
    val events = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val log = MutableStateFlow<List<String>>(emptyList())

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    init {
        scope.launch { ble.lines.collect(::onLine) }
        scope.launch {
            ble.state.collect { state ->
                if (state == PicoBle.State.DISCONNECTED) {
                    pending.values.forEach { it.completeExceptionally(IOException("Disconnected")) }
                    pending.clear()
                }
            }
        }
    }

    private fun addLog(line: String) = log.update { (it + line).takeLast(300) }

    private fun onLine(line: String) {
        addLog("← $line")
        val msg = try { JSONObject(line) } catch (e: JSONException) { return }
        val waiter = if (msg.has("id") && !msg.isNull("id")) pending.remove(msg.optInt("id")) else null
        if (waiter != null) waiter.complete(msg) else if (msg.has("ev")) events.tryEmit(msg)
    }

    /** Send a command and wait for its reply. Throws [IOException] on any failure. */
    suspend fun request(cmd: String, vararg args: Pair<String, Any?>): JSONObject {
        val id = nextId.getAndIncrement()
        val req = JSONObject().put("id", id).put("cmd", cmd)
        for ((k, v) in args) req.put(k, v ?: JSONObject.NULL)
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        try {
            sendRaw(req.toString())
            val res = withTimeoutOrNull(5000) { reply.await() }
                ?: throw PicoException("No reply from the Pico")
            if (!res.optBoolean("ok")) throw PicoException(res.optString("err", "Command failed"))
            return res
        } finally {
            pending.remove(id)
        }
    }

    /** Send a line as-is (used by the console). The reply shows up in [log]. */
    suspend fun sendRaw(line: String) {
        addLog("→ $line")
        ble.sendLine(line)
    }
}
