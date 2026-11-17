package com.lightapp.pico.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lightapp.pico.ble.PicoBle

private val PERMISSIONS = if (Build.VERSION.SDK_INT >= 31) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

@Composable
fun ConnectScreen(vm: MainViewModel, state: PicoBle.State) {
    val context = LocalContext.current
    val scanning by vm.ble.scanning.collectAsStateWithLifecycle()
    val found by vm.ble.found.collectAsStateWithLifecycle()
    val last by vm.lastDevice.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (vm.ble.bluetoothOn) pending?.invoke() else note = "Bluetooth is off."
    }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        when {
            !result.values.all { it } -> note = "Bluetooth permission is needed to find and talk to the Pico."
            !vm.ble.bluetoothOn -> enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> pending?.invoke()
        }
    }

    // Every Bluetooth action goes through here so permissions and the radio are checked first.
    fun withBluetooth(action: () -> Unit) {
        note = null
        pending = action
        when {
            PERMISSIONS.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } ->
                askPermissions.launch(PERMISSIONS)
            !vm.ble.bluetoothOn -> enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> action()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state != PicoBle.State.DISCONNECTED) { // connecting, or connected and loading
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Connecting…", Modifier.weight(1f))
                OutlinedButton(onClick = vm::disconnect) { Text("Cancel") }
            }
            return@Column
        }

        Text("Connect to a Pico running the light controller firmware. Settings are stored on " +
            "the Pico, so it keeps running its schedules after you disconnect.")

        last?.let { (address, name) ->
            Button(onClick = { withBluetooth { vm.connect(address, name) } }, Modifier.fillMaxWidth()) {
                Text("Reconnect to $name")
            }
        }
        OutlinedButton(onClick = { withBluetooth(vm::startScan) }, Modifier.fillMaxWidth(), enabled = !scanning) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Scanning…")
            } else Text("Scan for devices")
        }
        note?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        found.forEach { device ->
            Card(Modifier.fillMaxWidth().clickable { withBluetooth { vm.connect(device.address, device.name) } }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(device.address, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!scanning && found.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Nothing found yet? Check the Pico is powered, within a few metres, and not " +
                    "already connected to another phone." +
                    if (Build.VERSION.SDK_INT < 31) " On this Android version Location must also be switched on for Bluetooth scanning." else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
