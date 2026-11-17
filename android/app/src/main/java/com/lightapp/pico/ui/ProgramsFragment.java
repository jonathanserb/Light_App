package com.lightapp.pico.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.textfield.TextInputLayout;
import com.lightapp.pico.PicoViewModel;
import com.lightapp.pico.R;
import com.lightapp.pico.data.DeviceInfo;
import com.lightapp.pico.data.ProgramInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Programs tab: one card per program in programs.py on the Pico, with a
 * text box per parameter. Layouts: res/layout/fragment_programs.xml,
 * item_program.xml, item_param.xml
 */
public class ProgramsFragment extends Fragment {
    private PicoViewModel vm;
    private LinearLayout list;
    private final Map<String, View> cards = new LinkedHashMap<>(); // program name -> its card

    public ProgramsFragment() {
        super(R.layout.fragment_programs);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        vm = new ViewModelProvider(requireActivity()).get(PicoViewModel.class);
        list = view.findViewById(R.id.programs);
        // The cards are only rebuilt when the program list itself changes, so text
        // being typed into a parameter survives the regular status refresh.
        vm.programs.observe(getViewLifecycleOwner(), this::rebuild);
        vm.info.observe(getViewLifecycleOwner(), info -> markRunning());
    }

    private void rebuild(List<ProgramInfo> programs) {
        list.removeAllViews();
        cards.clear();
        for (ProgramInfo program : programs) {
            View card = getLayoutInflater().inflate(R.layout.item_program, list, false);
            ((TextView) card.findViewById(R.id.name)).setText(program.name);
            ((TextView) card.findViewById(R.id.description)).setText(program.description);

            // One text box per parameter, remembered so the buttons can read them back.
            LinearLayout params = card.findViewById(R.id.params);
            Map<String, EditText> fields = new LinkedHashMap<>();
            for (Map.Entry<String, String> param : program.params.entrySet()) {
                View row = getLayoutInflater().inflate(R.layout.item_param, params, false);
                ((TextInputLayout) row.findViewById(R.id.box)).setHint(param.getKey());
                EditText field = row.findViewById(R.id.field);
                field.setText(param.getValue());
                fields.put(param.getKey(), field);
                params.addView(row);
            }

            card.findViewById(R.id.run).setOnClickListener(v -> vm.runProgram(program, textIn(fields)));
            card.findViewById(R.id.stop).setOnClickListener(v -> vm.stopProgram());
            View save = card.findViewById(R.id.save);
            save.setVisibility(program.params.isEmpty() ? View.GONE : View.VISIBLE);
            save.setOnClickListener(v -> vm.saveParams(program, textIn(fields)));

            cards.put(program.name, card);
            list.addView(card);
        }
        markRunning();
    }

    private static Map<String, String> textIn(Map<String, EditText> fields) {
        Map<String, String> text = new LinkedHashMap<>();
        for (Map.Entry<String, EditText> field : fields.entrySet()) {
            text.put(field.getKey(), field.getValue().getText().toString());
        }
        return text;
    }

    /** Update the parts of each card that depend on which program is running or set for power-up. */
    private void markRunning() {
        DeviceInfo info = vm.info.getValue();
        if (info == null) return;
        for (Map.Entry<String, View> entry : cards.entrySet()) {
            String name = entry.getKey();
            View card = entry.getValue();
            boolean running = name.equals(info.program);
            card.findViewById(R.id.running).setVisibility(running ? View.VISIBLE : View.GONE);
            card.findViewById(R.id.run).setVisibility(running ? View.GONE : View.VISIBLE);
            card.findViewById(R.id.stop).setVisibility(running ? View.VISIBLE : View.GONE);

            CheckBox boot = card.findViewById(R.id.boot);
            boot.setOnCheckedChangeListener(null); // so setting it here doesn't count as a tap
            boot.setChecked(name.equals(info.bootProgram));
            boot.setOnCheckedChangeListener((button, checked) -> vm.setBootProgram(checked ? name : null));
        }
    }
}
