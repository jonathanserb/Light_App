package com.lightapp.pico.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.lightapp.pico.PicoViewModel;
import com.lightapp.pico.R;
import com.lightapp.pico.data.Schedule;

import java.util.List;

/**
 * The Schedule tab: one card per schedule. Tapping a card edits it.
 * Layouts: res/layout/fragment_schedules.xml, item_schedule.xml
 */
public class SchedulesFragment extends Fragment {
    private PicoViewModel vm;
    private View empty;
    private LinearLayout list;

    public SchedulesFragment() {
        super(R.layout.fragment_schedules);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        vm = new ViewModelProvider(requireActivity()).get(PicoViewModel.class);
        empty = view.findViewById(R.id.empty);
        list = view.findViewById(R.id.schedules);
        view.findViewById(R.id.add).setOnClickListener(v -> ScheduleDialog.show(this, vm, new Schedule()));
        vm.schedules.observe(getViewLifecycleOwner(), this::render);
    }

    private void render(List<Schedule> schedules) {
        empty.setVisibility(schedules.isEmpty() ? View.VISIBLE : View.GONE);
        list.removeAllViews();
        for (Schedule schedule : schedules) {
            View card = getLayoutInflater().inflate(R.layout.item_schedule, list, false);
            ((TextView) card.findViewById(R.id.time)).setText(schedule.timeText());
            ((TextView) card.findViewById(R.id.summary)).setText(schedule.summary());
            ((TextView) card.findViewById(R.id.days)).setText(schedule.daysText());

            MaterialSwitch enabled = card.findViewById(R.id.enabled);
            enabled.setChecked(schedule.enabled);
            enabled.setOnCheckedChangeListener((button, on) -> {
                Schedule changed = schedule.copy();
                changed.enabled = on;
                vm.saveSchedule(changed);
            });

            card.setOnClickListener(v -> ScheduleDialog.show(this, vm, schedule));
            list.addView(card);
        }
    }
}
