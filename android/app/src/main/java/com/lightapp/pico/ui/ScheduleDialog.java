package com.lightapp.pico.ui;

import android.app.TimePickerDialog;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputLayout;
import com.lightapp.pico.PicoViewModel;
import com.lightapp.pico.R;
import com.lightapp.pico.data.ProgramInfo;
import com.lightapp.pico.data.Schedule;
import com.lightapp.pico.data.ScheduleAction;

import java.util.List;

/**
 * The pop-up for adding or editing one schedule. It edits a private copy and
 * only sends it to the Pico when Save is pressed.
 * Layout: res/layout/dialog_schedule.xml
 */
public final class ScheduleDialog {
    private ScheduleDialog() {
    }

    /** Action types: what is sent to the Pico, and what the user sees. Keep the two in step. */
    private static final String[] ACTIONS = {"pin", "pwm", "prog", "stop"};
    private static final String[] ACTION_LABELS = {"Switch a pin", "Dim a pin (PWM)",
            "Start a program", "Stop the program"};

    public static void show(Fragment host, PicoViewModel vm, Schedule original) {
        Schedule s = original.copy();
        ScheduleAction act = s.action;
        boolean isNew = s.id == null;

        View view = host.getLayoutInflater().inflate(R.layout.dialog_schedule, null);
        Button time = view.findViewById(R.id.time);
        ChipGroup days = view.findViewById(R.id.days);
        EditText name = view.findViewById(R.id.name);
        Button action = view.findViewById(R.id.action);
        View pinBox = view.findViewById(R.id.pin_box);
        EditText pin = view.findViewById(R.id.pin);
        View onRow = view.findViewById(R.id.on_row);
        MaterialSwitch on = view.findViewById(R.id.on);
        TextView dutyLabel = view.findViewById(R.id.duty_label);
        Slider duty = view.findViewById(R.id.duty);
        Button program = view.findViewById(R.id.program);
        TextInputLayout durationBox = view.findViewById(R.id.duration_box);
        EditText duration = view.findViewById(R.id.duration);
        View delete = view.findViewById(R.id.delete);
        TextView problemText = view.findViewById(R.id.problem);

        // Time: the button shows it, tapping opens a 24-hour clock.
        time.setText(s.timeText());
        time.setOnClickListener(v -> new TimePickerDialog(host.requireContext(), (picker, hour, minute) -> {
            s.hour = hour;
            s.minute = minute;
            time.setText(s.timeText());
        }, s.hour, s.minute, true).show());

        // Days: one chip per weekday.
        for (int day = 0; day < 7; day++) {
            int bit = 1 << day;
            Chip chip = new Chip(host.requireContext());
            chip.setText(Schedule.DAY_NAMES[day]);
            chip.setCheckable(true);
            chip.setChecked(s.onDay(day));
            chip.setOnCheckedChangeListener((button, checked) -> s.days = checked ? s.days | bit : s.days & ~bit);
            days.addView(chip);
        }

        name.setText(s.name);
        pin.setText(act.pin);
        on.setChecked(act.value == 1);
        duty.setValue(Math.max(0, Math.min(100, act.duty)));
        dutyLabel.setText("Brightness " + act.duty + "%");
        duty.addOnChangeListener((slider, level, fromUser) -> dutyLabel.setText("Brightness " + Math.round(level) + "%"));
        duration.setText(s.durationSeconds > 0 ? String.valueOf(s.durationSeconds) : "");

        List<ProgramInfo> programs = vm.programs.getValue();
        String[] programNames = new String[programs.size()];
        for (int i = 0; i < programNames.length; i++) programNames[i] = programs.get(i).name;
        Ui.picker(program, programNames, programNames, act.program, picked -> act.program = picked);

        // Show only the rows that apply to the chosen action.
        Runnable showRows = () -> {
            boolean usesPin = act.type.equals("pin") || act.type.equals("pwm");
            pinBox.setVisibility(usesPin ? View.VISIBLE : View.GONE);
            onRow.setVisibility(act.type.equals("pin") ? View.VISIBLE : View.GONE);
            dutyLabel.setVisibility(act.type.equals("pwm") ? View.VISIBLE : View.GONE);
            duty.setVisibility(act.type.equals("pwm") ? View.VISIBLE : View.GONE);
            program.setVisibility(act.type.equals("prog") ? View.VISIBLE : View.GONE);
            durationBox.setVisibility(act.type.equals("stop") ? View.GONE : View.VISIBLE);
            durationBox.setHelperText(act.type.equals("pin") ? "Switches the pin back afterwards"
                    : act.type.equals("pwm") ? "Turns the pin off afterwards"
                    : "Stops the program afterwards");
        };
        Ui.picker(action, ACTIONS, ACTION_LABELS, act.type, picked -> {
            act.type = picked;
            showRows.run();
        });
        showRows.run();

        AlertDialog dialog = new MaterialAlertDialogBuilder(host.requireContext())
                .setTitle(isNew ? "New schedule" : "Edit schedule")
                .setView(view)
                .setPositiveButton("Save", null) // handled below so a mistake doesn't close the pop-up
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();

        delete.setVisibility(isNew ? View.GONE : View.VISIBLE);
        delete.setOnClickListener(v -> {
            vm.deleteSchedule(s.id);
            dialog.dismiss();
        });

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            // Collect what the fields hold now.
            s.name = name.getText().toString().trim();
            act.pin = pin.getText().toString().trim();
            act.value = on.isChecked() ? 1 : 0;
            act.duty = Math.round(duty.getValue());
            String seconds = duration.getText().toString().trim();
            s.durationSeconds = seconds.isEmpty() ? 0 : Math.min(86400, Integer.parseInt(seconds));

            String problem = problemWith(s);
            if (problem != null) {
                problemText.setText(problem);
                problemText.setVisibility(View.VISIBLE);
                return;
            }
            vm.saveSchedule(s);
            dialog.dismiss();
        });
    }

    /** What stops this schedule being saved, or null if it is fine. */
    static String problemWith(Schedule s) {
        ScheduleAction act = s.action;
        if (s.days == 0) return "Pick at least one day";
        boolean usesPin = act.type.equals("pin") || act.type.equals("pwm");
        if (usesPin && act.pin.isEmpty()) return "Enter a pin";
        if (act.type.equals("prog") && act.program.isEmpty()) return "Choose a program";
        return null;
    }
}
