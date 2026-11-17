package com.lightapp.pico.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lightapp.pico.data.DeviceInfo
import com.lightapp.pico.data.ProgramInfo

@Composable
fun ProgramsTab(vm: MainViewModel, info: DeviceInfo) {
    val programs by vm.programs.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Section {
            Text("Programs live in programs.py on the Pico. One runs at a time. Saved parameters " +
                "are stored on the Pico and apply straight away, even to a running program.",
                style = MaterialTheme.typography.bodySmall)
        }
        programs.forEach { ProgramCard(vm, it, running = info.program == it.name, atBoot = info.bootProgram == it.name) }
    }
}

@Composable
private fun ProgramCard(vm: MainViewModel, program: ProgramInfo, running: Boolean, atBoot: Boolean) {
    // Unsaved edits; reset whenever the stored values change.
    val edits = remember(program) { mutableStateMapOf<String, String>().apply { putAll(program.params) } }
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(program.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (running) Text("● running", color = MaterialTheme.colorScheme.primary)
        }
        Text(program.desc, style = MaterialTheme.typography.bodySmall)
        program.params.keys.forEach { key ->
            OutlinedTextField(
                edits[key] ?: "", { edits[key] = it },
                label = { Text(key) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                Button(onClick = vm::stopProgram) { Text("Stop") }
            } else {
                Button(onClick = { vm.runProgram(program, edits.toMap()) }) { Text("Run") }
            }
            if (program.params.isNotEmpty()) {
                OutlinedButton(onClick = { vm.saveParams(program, edits.toMap()) }) { Text("Save") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(atBoot, onCheckedChange = { vm.setBootProgram(if (it) program.name else null) })
            Text("Start when the Pico powers up")
        }
    }
}
