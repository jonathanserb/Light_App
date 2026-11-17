package com.lightapp.pico.ui;

import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.color.MaterialColors;
import com.lightapp.pico.PicoViewModel;
import com.lightapp.pico.R;

import java.util.List;

/**
 * The Console tab: a raw view of the traffic, for debugging the firmware or
 * trying new commands. Layout: res/layout/fragment_console.xml
 */
public class ConsoleFragment extends Fragment {
    private TextView log;
    private ScrollView scroll;

    public ConsoleFragment() {
        super(R.layout.fragment_console);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        PicoViewModel vm = new ViewModelProvider(requireActivity()).get(PicoViewModel.class);
        log = view.findViewById(R.id.log);
        scroll = view.findViewById(R.id.scroll);
        EditText input = view.findViewById(R.id.input);

        view.findViewById(R.id.send).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            vm.sendRaw(text);
            input.setText("");
        });

        vm.log.observe(getViewLifecycleOwner(), this::render);
    }

    private void render(List<String> lines) {
        // Lines sent by the phone (starting with an arrow pointing right) are coloured.
        int sentColour = MaterialColors.getColor(log, androidx.appcompat.R.attr.colorPrimary);
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (String line : lines) {
            int start = text.length();
            text.append(line).append('\n');
            if (line.startsWith("→")) {
                text.setSpan(new ForegroundColorSpan(sentColour), start, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        log.setText(text);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); // keep the newest line in view
    }
}
