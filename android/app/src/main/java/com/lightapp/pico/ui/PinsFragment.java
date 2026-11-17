package com.lightapp.pico.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.lightapp.pico.PicoViewModel;
import com.lightapp.pico.R;
import com.lightapp.pico.Settings;
import com.lightapp.pico.data.Json;
import com.lightapp.pico.data.PinState;

import java.util.List;
import java.util.Locale;

/**
 * The Pins tab: one card per configured pin, plus the analog inputs.
 * Layouts: res/layout/fragment_pins.xml, item_pin.xml, dialog_add_pin.xml
 */
public class PinsFragment extends Fragment {
    private PicoViewModel vm;
    private View empty;
    private LinearLayout list;
    private TextView adc;
    private boolean dragging;      // a brightness slider is being moved; don't rebuild under the finger
    private boolean renderSkipped; // ...so remember to rebuild once it is let go
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Keeps inputs (and pins changed by schedules or programs) fresh while this tab is open. */
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            vm.pollPins();
            handler.postDelayed(this, Settings.PINS_REFRESH_MS);
        }
    };

    public PinsFragment() {
        super(R.layout.fragment_pins);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        vm = new ViewModelProvider(requireActivity()).get(PicoViewModel.class);
        empty = view.findViewById(R.id.empty);
        list = view.findViewById(R.id.pins);
        adc = view.findViewById(R.id.adc);

        view.findViewById(R.id.add).setOnClickListener(v -> showAddDialog());
        view.findViewById(R.id.read_adc).setOnClickListener(v -> vm.readAdc());

        vm.pins.observe(getViewLifecycleOwner(), pins -> render());
        vm.adc.observe(getViewLifecycleOwner(), this::renderAdc);
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.postDelayed(refresh, Settings.PINS_REFRESH_MS);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void render() {
        if (dragging) {
            renderSkipped = true;
            return;
        }
        renderSkipped = false;
        List<PinState> pins = vm.pins.getValue();
        empty.setVisibility(pins.isEmpty() ? View.VISIBLE : View.GONE);
        list.removeAllViews();
        for (PinState pin : pins) list.addView(pinCard(pin));
    }

    private View pinCard(PinState pin) {
        View card = getLayoutInflater().inflate(R.layout.item_pin, list, false);
        TextView subtitle = card.findViewById(R.id.subtitle);
        MaterialSwitch toggle = card.findViewById(R.id.toggle);
        TextView value = card.findViewById(R.id.value);
        Slider slider = card.findViewById(R.id.slider);
        Button mode = card.findViewById(R.id.mode);

        ((TextView) card.findViewById(R.id.title)).setText(pin.title());
        subtitle.setText(Json.pinName(pin.pin));
        subtitle.setVisibility(pin.label.trim().isEmpty() ? View.GONE : View.VISIBLE);

        boolean isOutput = pin.mode.equals("out");
        boolean isDimmable = pin.mode.equals("pwm");
        toggle.setVisibility(isOutput ? View.VISIBLE : View.GONE);
        value.setVisibility(isOutput ? View.GONE : View.VISIBLE);
        slider.setVisibility(isDimmable ? View.VISIBLE : View.GONE);

        if (isOutput) {
            toggle.setChecked(pin.value == 1);
            toggle.setOnCheckedChangeListener((button, on) -> vm.setPin(pin.pin, on));
        } else if (isDimmable) {
            value.setText(pin.duty + "%");
            slider.setValue(Math.max(0, Math.min(100, pin.duty)));
            slider.addOnChangeListener((s, level, fromUser) -> value.setText(Math.round(level) + "%"));
            slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider s) {
                    dragging = true;
                }

                @Override
                public void onStopTrackingTouch(@NonNull Slider s) {
                    // Only the final position is sent to the Pico.
                    dragging = false;
                    vm.setDuty(pin.pin, Math.round(s.getValue()));
                    if (renderSkipped) handler.post(PinsFragment.this::render);
                }
            });
        } else {
            value.setText(pin.value == 1 ? "HIGH" : "LOW");
        }

        Ui.picker(mode, Ui.PIN_MODES, Ui.PIN_MODE_LABELS, pin.mode,
                newMode -> vm.configurePin(pin.pin, newMode, null));
        card.findViewById(R.id.remove).setOnClickListener(v -> vm.removePin(pin.pin));
        return card;
    }

    private void renderAdc(double[] volts) {
        if (volts == null) return;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < volts.length; i++) {
            text.append(String.format(Locale.US, "%sADC%d (GP%d): %.2f V", i == 0 ? "" : "\n", i, 26 + i, volts[i]));
        }
        adc.setText(text);
        adc.setVisibility(View.VISIBLE);
    }

    private void showAddDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_add_pin, null);
        EditText pin = view.findViewById(R.id.pin);
        EditText label = view.findViewById(R.id.label);
        String[] mode = {"out"};
        Ui.picker(view.findViewById(R.id.mode), Ui.PIN_MODES, Ui.PIN_MODE_LABELS, mode[0],
                picked -> mode[0] = picked);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Add pin")
                .setView(view)
                .setPositiveButton("Add", (dialog, which) -> {
                    String name = pin.getText().toString().trim();
                    if (!name.isEmpty()) vm.configurePin(name, mode[0], label.getText().toString().trim());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
