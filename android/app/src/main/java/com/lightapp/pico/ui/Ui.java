package com.lightapp.pico.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.lightapp.pico.R;

/** Building blocks shared by several screens. */
public final class Ui {
    private Ui() {
    }

    /** Pin modes: what is sent to the Pico, and what the user sees. Keep the two in step. */
    public static final String[] PIN_MODES = {"out", "pwm", "in", "in_up", "in_down"};
    public static final String[] PIN_MODE_LABELS = {"Output (on/off)", "Output (dimmable PWM)",
            "Input", "Input, pull-up", "Input, pull-down"};

    public interface OnText {
        void onText(String text);
    }

    public interface OnPick {
        void onPick(String value);
    }

    /**
     * Turn a button into a drop-down: it shows the label of {@code selected},
     * and tapping it offers every label. {@code onPick} gets the matching value.
     */
    public static void picker(Button button, String[] values, String[] labels, String selected, OnPick onPick) {
        String shown = selected == null || selected.isEmpty() ? "Choose…" : selected;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(selected)) shown = labels[i];
        }
        button.setText(shown);
        button.setOnClickListener(view -> {
            PopupMenu menu = new PopupMenu(button.getContext(), button);
            for (int i = 0; i < values.length; i++) menu.getMenu().add(0, i, i, labels[i]);
            menu.setOnMenuItemClickListener(item -> {
                // Show the choice straight away; the caller may also redraw.
                picker(button, values, labels, values[item.getItemId()], onPick);
                onPick.onPick(values[item.getItemId()]);
                return true;
            });
            menu.show();
        });
    }

    /** A pop-up asking for one line of text. */
    public static AlertDialog prompt(Context context, String title, String hint, String initial, OnText onOk) {
        return prompt(context, title, hint, initial, "OK", onOk, null);
    }

    /** As above, with a custom OK button and something to do on cancel (may be null). */
    public static AlertDialog prompt(Context context, String title, String hint, String initial,
                                     String okText, OnText onOk, Runnable onCancel) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_text, null);
        ((TextInputLayout) view.findViewById(R.id.box)).setHint(hint);
        EditText field = view.findViewById(R.id.field);
        field.setText(initial);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(view)
                .setCancelable(onCancel == null)
                .setPositiveButton(okText, (d, which) -> onOk.onText(field.getText().toString().trim()))
                .setNegativeButton(onCancel == null ? "Cancel" : "Disconnect", (d, which) -> {
                    if (onCancel != null) onCancel.run();
                })
                .create();
        dialog.show();
        return dialog;
    }

    /** A yes/no question. */
    public static void confirm(Context context, String title, String message, String yesText, Runnable onYes) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(yesText, (d, which) -> onYes.run())
                .setNegativeButton("Cancel", null)
                .show();
    }
}
