package com.lightapp.pico.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.lightapp.pico.PicoViewModel;
import com.lightapp.pico.R;
import com.lightapp.pico.ble.PicoLink;

import java.util.List;

/**
 * The screen shown while no Pico is connected: scan, pick a device, or
 * reconnect to the last one. Layout: res/layout/fragment_connect.xml
 */
public class ConnectFragment extends Fragment {
    // Android 12+ has dedicated Bluetooth permissions; older versions need Location to scan.
    private static final String[] PERMISSIONS = Build.VERSION.SDK_INT >= 31
            ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};

    private PicoViewModel vm;
    private View connecting;
    private View idle;
    private Button reconnect;
    private Button scan;
    private TextView note;
    private TextView hint;
    private LinearLayout devices;
    private Runnable pendingAction; // what to do once permission and Bluetooth are sorted out

    private final ActivityResultLauncher<Intent> enableBluetooth = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (vm.isBluetoothOn()) runPending();
                else showNote("Bluetooth is off.");
            });

    private final ActivityResultLauncher<String[]> askPermissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (result.containsValue(false)) {
                    showNote("Bluetooth permission is needed to find and talk to the Pico.");
                } else {
                    withBluetooth(pendingAction);
                }
            });

    public ConnectFragment() {
        super(R.layout.fragment_connect);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        vm = new ViewModelProvider(requireActivity()).get(PicoViewModel.class);
        connecting = view.findViewById(R.id.connecting);
        idle = view.findViewById(R.id.idle);
        reconnect = view.findViewById(R.id.reconnect);
        scan = view.findViewById(R.id.scan);
        note = view.findViewById(R.id.note);
        hint = view.findViewById(R.id.hint);
        devices = view.findViewById(R.id.devices);

        view.findViewById(R.id.cancel).setOnClickListener(v -> vm.disconnect());
        scan.setOnClickListener(v -> withBluetooth(vm::startScan));

        vm.state.observe(getViewLifecycleOwner(), state -> render());
        vm.scanning.observe(getViewLifecycleOwner(), scanning -> render());
        vm.found.observe(getViewLifecycleOwner(), found -> render());
        vm.lastDevice.observe(getViewLifecycleOwner(), last -> render());
    }

    /** Every Bluetooth action goes through here, so permissions and the radio are checked first. */
    private void withBluetooth(Runnable action) {
        showNote(null);
        pendingAction = action;
        for (String permission : PERMISSIONS) {
            if (requireContext().checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                askPermissions.launch(PERMISSIONS);
                return;
            }
        }
        if (!vm.isBluetoothOn()) {
            enableBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        runPending();
    }

    private void runPending() {
        Runnable action = pendingAction;
        pendingAction = null;
        if (action != null) action.run();
    }

    private void showNote(String text) {
        note.setText(text);
        note.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private void render() {
        // "Connecting" also covers the moment after connecting while settings are loaded.
        boolean busy = vm.state.getValue() != PicoLink.State.DISCONNECTED;
        connecting.setVisibility(busy ? View.VISIBLE : View.GONE);
        idle.setVisibility(busy ? View.GONE : View.VISIBLE);

        String[] last = vm.lastDevice.getValue();
        reconnect.setVisibility(last == null ? View.GONE : View.VISIBLE);
        if (last != null) {
            reconnect.setText("Reconnect to " + last[1]);
            reconnect.setOnClickListener(v -> withBluetooth(() -> vm.connect(last[0], last[1])));
        }

        boolean scanning = Boolean.TRUE.equals(vm.scanning.getValue());
        scan.setEnabled(!scanning);
        scan.setText(scanning ? "Scanning…" : "Scan for devices");

        List<PicoLink.Found> found = vm.found.getValue();
        devices.removeAllViews();
        for (PicoLink.Found device : found) {
            View card = getLayoutInflater().inflate(R.layout.item_device, devices, false);
            ((TextView) card.findViewById(R.id.name)).setText(device.name);
            ((TextView) card.findViewById(R.id.address)).setText(device.address);
            ((TextView) card.findViewById(R.id.signal)).setText(device.rssi + " dBm");
            card.setOnClickListener(v -> withBluetooth(() -> vm.connect(device.address, device.name)));
            devices.addView(card);
        }

        hint.setVisibility(!scanning && found.isEmpty() ? View.VISIBLE : View.GONE);
        hint.setText("Nothing found yet? Check the Pico is powered, within a few metres, and not "
                + "already connected to another phone."
                + (Build.VERSION.SDK_INT < 31
                ? " On this Android version Location must also be switched on for Bluetooth scanning." : ""));
    }
}
