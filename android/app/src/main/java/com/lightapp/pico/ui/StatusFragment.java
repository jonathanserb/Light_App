package com.lightapp.pico.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.lightapp.pico.PicoViewModel;
import com.lightapp.pico.R;
import com.lightapp.pico.Settings;
import com.lightapp.pico.data.DeviceInfo;
import com.lightapp.pico.data.Schedule;

import java.util.Locale;

/** The Status tab: clock, device facts, device settings. Layout: res/layout/fragment_status.xml */
public class StatusFragment extends Fragment {
    private PicoViewModel vm;
    private TextView clock;
    private View clockWarning;
    private LinearLayout facts;
    private Button passcode;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Re-reads the Pico's status every few seconds while this tab is on screen. */
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            vm.pollInfo();
            handler.postDelayed(this, Settings.STATUS_REFRESH_MS);
        }
    };

    public StatusFragment() {
        super(R.layout.fragment_status);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        vm = new ViewModelProvider(requireActivity()).get(PicoViewModel.class);
        clock = view.findViewById(R.id.clock);
        clockWarning = view.findViewById(R.id.clock_warning);
        facts = view.findViewById(R.id.facts);
        passcode = view.findViewById(R.id.passcode);

        view.findViewById(R.id.sync).setOnClickListener(v -> vm.syncTime());
        view.findViewById(R.id.rename).setOnClickListener(v -> {
            DeviceInfo info = vm.info.getValue();
            Ui.prompt(requireContext(), "Rename device", "Name (max 20 characters)",
                    info == null ? "" : info.name, vm::rename);
        });
        passcode.setOnClickListener(v -> Ui.prompt(requireContext(), "Passcode",
                "Leave empty for no passcode", "", vm::setPasscode));
        view.findViewById(R.id.reboot).setOnClickListener(v -> Ui.confirm(requireContext(),
                "Reboot the Pico?",
                "It will disconnect and its clock will need setting again, which happens when you reconnect.",
                "Reboot", vm::reboot));

        vm.info.observe(getViewLifecycleOwner(), this::render);
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.postDelayed(refresh, Settings.STATUS_REFRESH_MS);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void render(DeviceInfo info) {
        if (info == null) return; // disconnecting; this screen is about to be replaced

        int[] t = info.time;
        boolean clockSet = info.timeValid && t.length == 7;
        clock.setVisibility(clockSet ? View.VISIBLE : View.GONE);
        clockWarning.setVisibility(clockSet ? View.GONE : View.VISIBLE);
        if (clockSet) {
            clock.setText(String.format(Locale.US, "%s %04d-%02d-%02d  %02d:%02d",
                    Schedule.DAY_NAMES[t[6]], t[0], t[1], t[2], t[3], t[4]));
        }

        // The Device card. Add or reorder lines here.
        facts.removeAllViews();
        addFact("Running program", info.program == null ? "none" : info.program);
        addFact("Program at power-up", info.bootProgram == null ? "none" : info.bootProgram);
        addFact("Schedules", String.valueOf(info.scheduleCount));
        addFact("Up for", uptime(info.uptimeSeconds));
        addFact("Chip temperature", String.format(Locale.US, "%.1f °C", info.temperatureC));
        addFact("Free memory", info.freeMemory / 1024 + " kB");
        addFact("Firmware", info.firmware);

        passcode.setText(info.hasPasscode ? "Passcode ✓" : "Passcode");
    }

    private void addFact(String label, String value) {
        View row = getLayoutInflater().inflate(R.layout.row_fact, facts, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        ((TextView) row.findViewById(R.id.value)).setText(value);
        facts.addView(row);
    }

    private static String uptime(long s) {
        if (s >= 86400) return s / 86400 + " d " + s % 86400 / 3600 + " h";
        if (s >= 3600) return s / 3600 + " h " + s % 3600 / 60 + " min";
        return s / 60 + " min " + s % 60 + " s";
    }
}
