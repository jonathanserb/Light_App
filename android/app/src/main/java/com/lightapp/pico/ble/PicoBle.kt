package com.lightapp.pico.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.lightapp.pico.Settings
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bluetooth LE link to the Pico using the Nordic UART Service: text lines out
 * through [sendLine], text lines in through [lines].
 *
 * Callers must hold the Bluetooth runtime permissions before calling anything
 * here (the connect screen takes care of that).
 */
@SuppressLint("MissingPermission")
class PicoBle(private val context: Context) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    data class Found(val address: String, val name: String, val rssi: Int)

    val state = MutableStateFlow(State.DISCONNECTED)
    val scanning = MutableStateFlow(false)
    val found = MutableStateFlow<List<Found>>(emptyList())
    val lines = MutableSharedFlow<String>(extraBufferCapacity = 256)

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rx: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    @Volatile private var writeDone: CompletableDeferred<Int>? = null
    private val framer = LineFramer()
    private val writeLock = Mutex()

    // ---- scanning --------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val item = Found(
                result.device.address,
                result.scanRecord?.deviceName ?: result.device.name ?: "Pico",
                result.rssi,
            )
            found.update { list ->
                if (list.any { it.address == item.address }) {
                    list.map { if (it.address == item.address) item else it }
                } else list + item
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning.value = false
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        stopScan()
        found.value = emptyList()
        // Only show devices advertising our service, i.e. Picos running the firmware.
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning.value = true
    }

    fun stopScan() {
        if (!scanning.value) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning.value = false
    }

    // ---- connection ------------------------------------------------------

    fun connect(address: String) {
        stopScan()
        disconnect()
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        state.value = State.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.let { closed(it) }
    }

    private fun closed(g: BluetoothGatt) {
        runCatching { g.disconnect() }
        runCatching { g.close() }
        if (gatt === g) {
            gatt = null
            rx = null
            writeDone?.completeExceptionally(IOException("Disconnected"))
            state.value = State.DISCONNECTED
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                mtu = 23
                framer.reset()
                // A bigger MTU means far fewer packets per message. Falls back to 23 if refused.
                if (!g.requestMtu(Settings.REQUESTED_MTU)) g.discoverServices()
            } else {
                closed(g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            val tx = service?.getCharacteristic(TX)
            val cccd = tx?.getDescriptor(CCCD)
            rx = service?.getCharacteristic(RX)
            if (tx == null || cccd == null || rx == null) return closed(g)
            g.setCharacteristicNotification(tx, true)
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!started) closed(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) state.value = State.CONNECTED else closed(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(status)
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            received(value)
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            received(c.value ?: return)
        }
    }

    private fun received(bytes: ByteArray) {
        val complete = synchronized(framer) { framer.feed(bytes) }
        complete.forEach { lines.tryEmit(it) }
    }

    /** Send one line. Android allows one GATT operation at a time, so writes are queued. */
    suspend fun sendLine(text: String) = writeLock.withLock {
        for (part in chunk((text + "\n").toByteArray(), mtu - 3)) {
            val g = gatt ?: throw IOException("Not connected")
            val c = rx ?: throw IOException("Not connected")
            val done = CompletableDeferred<Int>()
            writeDone = done
            val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, part, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = part
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (!started) throw IOException("Bluetooth write failed")
            val status = withTimeoutOrNull(Settings.WRITE_TIMEOUT_MS) { done.await() }
                ?: throw IOException("Bluetooth write timed out")
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Bluetooth write failed ($status)")
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // phone -> Pico
        val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Pico -> phone
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
