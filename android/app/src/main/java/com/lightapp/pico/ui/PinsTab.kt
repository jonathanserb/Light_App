package com.lightapp.pico.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lightapp.pico.data.PinState
import com.lightapp.pico.data.pinName
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun PinsTab(vm: MainViewModel) {
    val pins by vm.pins.collectAsStateWithLifecycle()
    val adc by vm.adc.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    // Keep inputs (and pins changed by schedules/programs) fresh while this tab is open.
    LaunchedEffect(Unit) {
        while (true) { delay(2000); vm.pollPins() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        if (pins.isEmpty()) {
            Section { Text("No pins set up yet. Add the pins your lights or sensors are wired to.") }
        }
        pins.forEach { pin -> PinCard(vm, pin) }
        Button(onClick = { adding = true }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Text("Add pin") }

        Section("Analog inputs") {
            if (adc.size == 3) {
                Text(adc.mapIndexed { i, v -> "ADC$i (GP${26 + i}): %.2f V".format(v) }.joinToString("\n"))
            }
            OutlinedButton(onClick = vm::readAdc, Modifier.padding(top = 8.dp)) { Text("Read") }
        }
    }

    if (adding) AddPinDialog(onDismiss = { adding = false }) { pin, mode, label ->
        vm.configurePin(pin, mode, label)
    }
}

@Composable
private fun PinCard(vm: MainViewModel, pin: PinState) {
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pin.title, style = MaterialTheme.typography.titleMedium)
                if (pin.label.isNotBlank()) Text(pinName(pin.pin), style = MaterialTheme.typography.bodySmall)
            }
            when (pin.mode) {
                "out" -> Switch(pin.value == 1, onCheckedChange = { vm.setPin(pin.pin, it) })
                "pwm" -> Text("${pin.duty}%")
                else -> Text(if (pin.value == 1) "HIGH" else "LOW", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (pin.mode == "pwm") {
            // Local value while dragging; only the final position is sent to the Pico.
            var level by remember(pin.duty) { mutableFloatStateOf(pin.duty.toFloat()) }
            Slider(
                value = level,
                onValueChange = { level = it },
                onValueChangeFinished = { vm.setDuty(pin.pin, level.roundToInt()) },
                valueRange = 0f..100f,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Picker(PIN_MODES, pin.mode, { vm.configurePin(pin.pin, it) }, Modifier.weight(1f))
            IconButton(onClick = { vm.configurePin(pin.pin, "off") }) { Icon(Icons.Default.Delete, "Remove pin") }
        }
    }
}

@Composable
private fun AddPinDialog(onDismiss: () -> Unit, onAdd: (pin: String, mode: String, label: String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("out") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add pin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pin, { pin = it }, label = { Text("GP number, or LED") }, singleLine = true)
                OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true)
                Picker(PIN_MODES, mode, { mode = it })
            }
        },
        confirmButton = {
            TextButton(enabled = pin.isNotBlank(), onClick = { onAdd(pin, mode, label.trim()); onDismiss() }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
