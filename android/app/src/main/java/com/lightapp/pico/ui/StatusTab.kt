package com.lightapp.pico.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightapp.pico.Settings
import com.lightapp.pico.data.DeviceInfo
import com.lightapp.pico.data.Schedule
import kotlinx.coroutines.delay

@Composable
fun StatusTab(vm: MainViewModel, info: DeviceInfo) {
    var dialog by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) { delay(Settings.STATUS_REFRESH_MS); vm.pollInfo() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Section("Clock") {
            val t = info.time
            if (info.timeValid && t.size == 7) {
                Text("%s %04d-%02d-%02d  %02d:%02d".format(Schedule.DAY_NAMES[t[6]], t[0], t[1], t[2], t[3], t[4]),
                    style = MaterialTheme.typography.headlineSmall)
            } else {
                Text("Not set – schedules will not run until the clock is set.",
                    color = MaterialTheme.colorScheme.error)
            }
            Text("Set automatically from this phone each time you connect. The Pico forgets the " +
                "time if it loses power.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = vm::syncTime, Modifier.padding(top = 8.dp)) { Text("Set clock now") }
        }
        Section("Device") {
            Fact("Running program", info.program ?: "none")
            Fact("Program at power-up", info.bootProgram ?: "none")
            Fact("Schedules", info.scheduleCount.toString())
            Fact("Up for", uptime(info.uptimeS))
            Fact("Chip temperature", "%.1f °C".format(info.tempC))
            Fact("Free memory", "${info.memFree / 1024} kB")
            Fact("Firmware", info.fw)
        }
        Section("Settings") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialog = "name" }) { Text("Rename") }
                OutlinedButton(onClick = { dialog = "pass" }) { Text(if (info.hasPass) "Passcode ✓" else "Passcode") }
                OutlinedButton(onClick = { dialog = "reboot" }) { Text("Reboot") }
            }
        }
    }

    when (dialog) {
        "name" -> TextPrompt("Rename device", "Name (max 20 characters)", info.name, { dialog = "" }, vm::rename)
        "pass" -> TextPrompt("Passcode", "Leave empty for no passcode", "", { dialog = "" }, vm::setPasscode)
        "reboot" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("Reboot the Pico?") },
            text = { Text("It will disconnect and its clock will need setting again, which happens when you reconnect.") },
            confirmButton = { TextButton(onClick = { dialog = ""; vm.reboot() }) { Text("Reboot") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

private fun uptime(s: Long): String = when {
    s >= 86400 -> "${s / 86400} d ${s % 86400 / 3600} h"
    s >= 3600 -> "${s / 3600} h ${s % 3600 / 60} min"
    else -> "${s / 60} min ${s % 60} s"
}
