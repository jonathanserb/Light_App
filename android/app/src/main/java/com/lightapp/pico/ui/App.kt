package com.lightapp.pico.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightapp.pico.ble.PicoBle

private val TABS = listOf(
    "Status" to Icons.Default.Info,
    "Pins" to Icons.Default.Settings,
    "Schedule" to Icons.Default.DateRange,
    "Programs" to Icons.Default.PlayArrow,
    "Console" to Icons.Default.Menu,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    val state by vm.ble.state.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(message) {
        message?.let {
            vm.message.value = null
            snackbar.showSnackbar(it)
        }
    }

    val device = info
    val connected = state == PicoBle.State.CONNECTED && device != null
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (connected) device!!.name else "Pico Light") },
                actions = {
                    if (connected) {
                        IconButton(onClick = vm::refreshAll) { Icon(Icons.Default.Refresh, "Refresh") }
                        TextButton(onClick = vm::disconnect) { Text("Disconnect") }
                    }
                },
            )
        },
        bottomBar = {
            if (connected && !device!!.locked) NavigationBar {
                TABS.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !connected -> ConnectScreen(vm, state)
                device!!.locked -> PasscodeDialog(onSubmit = vm::submitPasscode, onCancel = vm::disconnect)
                tab == 0 -> StatusTab(vm, device)
                tab == 1 -> PinsTab(vm)
                tab == 2 -> SchedulesTab(vm)
                tab == 3 -> ProgramsTab(vm, device)
                else -> ConsoleTab(vm)
            }
        }
    }
}

@Composable
private fun PasscodeDialog(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Passcode") },
        text = {
            OutlinedTextField(pass, { pass = it }, label = { Text("This Pico is locked") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onSubmit(pass) }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Disconnect") } },
    )
}
