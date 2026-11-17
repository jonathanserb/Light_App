package com.lightapp.pico.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lightapp.pico.data.Schedule
import kotlin.math.roundToInt

private val ACTION_TYPES = listOf(
    "pin" to "Switch a pin",
    "pwm" to "Dim a pin (PWM)",
    "prog" to "Start a program",
    "stop" to "Stop the program",
)

@Composable
fun SchedulesTab(vm: MainViewModel) {
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val programs by vm.programs.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Schedule?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        if (schedules.isEmpty()) {
            Section { Text("No schedules yet. Schedules are stored on the Pico and run without the phone.") }
        }
        schedules.forEach { s ->
            Section {
                Row(Modifier.fillMaxWidth().clickable { editing = s }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("%02d:%02d".format(s.hour, s.minute), style = MaterialTheme.typography.headlineMedium)
                        Text(listOf(s.name, s.action.describe() + if (s.durationS > 0) " for ${s.durationS} s" else "")
                            .filter { it.isNotBlank() }.joinToString(" · "))
                        Text(s.daysText(), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(s.enabled, onCheckedChange = { vm.saveSchedule(s.copy(enabled = it)) })
                }
            }
        }
        Button(onClick = { editing = Schedule() }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Add schedule")
        }
    }

    editing?.let { s ->
        ScheduleDialog(s, programs.map { it.name }, onDismiss = { editing = null },
            onSave = vm::saveSchedule, onDelete = vm::deleteSchedule)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(
    initial: Schedule,
    programs: List<String>,
    onDismiss: () -> Unit,
    onSave: (Schedule) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    var duration by remember { mutableStateOf(if (initial.durationS > 0) initial.durationS.toString() else "") }
    val time = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    val act = s.action
    val valid = s.days != 0 && when (act.type) {
        "pin", "pwm" -> act.pin.isNotBlank()
        "prog" -> act.program.isNotBlank()
        else -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == null) "New schedule" else "Edit schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeInput(time)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Schedule.DAY_NAMES.forEachIndexed { i, name ->
                        val on = s.days shr i and 1 == 1
                        Box(
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { s = s.copy(days = s.days xor (1 shl i)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(name.take(2), style = MaterialTheme.typography.labelMedium,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(s.name, { s = s.copy(name = it.take(24)) }, label = { Text("Name (optional)") }, singleLine = true)
                Picker(ACTION_TYPES, act.type, { s = s.copy(action = act.copy(type = it)) })
                if (act.type == "pin" || act.type == "pwm") {
                    OutlinedTextField(act.pin, { s = s.copy(action = act.copy(pin = it)) },
                        label = { Text("Pin (GP number, or LED)") }, singleLine = true)
                }
                when (act.type) {
                    "pin" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Turn it on", Modifier.weight(1f))
                        Switch(act.value == 1, { s = s.copy(action = act.copy(value = if (it) 1 else 0)) })
                    }
                    "pwm" -> {
                        Text("Brightness ${act.duty}%")
                        Slider(act.duty.toFloat(), { s = s.copy(action = act.copy(duty = it.roundToInt())) }, valueRange = 0f..100f)
                    }
                    "prog" -> Picker(programs.map { it to it }, act.program, { s = s.copy(action = act.copy(program = it)) })
                }
                if (act.type != "stop") {
                    OutlinedTextField(
                        duration, { duration = it.filter(Char::isDigit).take(5) },
                        label = { Text("Undo after (seconds, optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = {
                            Text(when (act.type) {
                                "pin" -> "Switches the pin back afterwards"
                                "pwm" -> "Turns the pin off afterwards"
                                else -> "Stops the program afterwards"
                            })
                        },
                    )
                }
                if (initial.id != null) {
                    TextButton(onClick = { onDelete(initial.id); onDismiss() }) {
                        Text("Delete schedule", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(s.copy(hour = time.hour, minute = time.minute,
                    durationS = (duration.toIntOrNull() ?: 0).coerceAtMost(86400)))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
