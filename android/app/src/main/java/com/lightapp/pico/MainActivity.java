package com.lightapp.pico;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.lightapp.pico.ble.PicoLink;
import com.lightapp.pico.data.DeviceInfo;
import com.lightapp.pico.ui.ConnectFragment;
import com.lightapp.pico.ui.ConsoleFragment;
import com.lightapp.pico.ui.PinsFragment;
import com.lightapp.pico.ui.ProgramsFragment;
import com.lightapp.pico.ui.SchedulesFragment;
import com.lightapp.pico.ui.StatusFragment;
import com.lightapp.pico.ui.Ui;

/**
 * The app's only window. It owns the frame (top bar, bottom tabs) and decides
 * which screen sits in the middle: the connect screen, or the selected tab.
 * Layout: res/layout/activity_main.xml
 */
public class MainActivity extends AppCompatActivity {
    private static final int CONNECT_SCREEN = 0;

    private PicoViewModel vm;
    private MaterialToolbar toolbar;
    private BottomNavigationView tabs;
    private AlertDialog passcodeDialog;
    private String shownScreen; // tag of the fragment currently in the container

    /** Which screen belongs to which tab. Add new tabs here (and in res/menu/tabs.xml). */
    private Fragment fragmentFor(int screen) {
        if (screen == R.id.tab_status) return new StatusFragment();
        if (screen == R.id.tab_pins) return new PinsFragment();
        if (screen == R.id.tab_schedule) return new SchedulesFragment();
        if (screen == R.id.tab_programs) return new ProgramsFragment();
        if (screen == R.id.tab_console) return new ConsoleFragment();
        return new ConnectFragment();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toolbar = findViewById(R.id.toolbar);
        tabs = findViewById(R.id.tabs);
        keepClearOfSystemBars(findViewById(R.id.root));

        // After a screen rotation Android restores the fragment itself; just note which it was.
        Fragment restored = getSupportFragmentManager().findFragmentById(R.id.container);
        shownScreen = restored == null ? null : restored.getTag();

        vm = new ViewModelProvider(this).get(PicoViewModel.class);
        vm.setMessageListener(text ->
                Snackbar.make(findViewById(R.id.container), text, Snackbar.LENGTH_LONG).show());

        toolbar.inflateMenu(R.menu.top);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh) vm.refreshAll();
            if (item.getItemId() == R.id.action_disconnect) vm.disconnect();
            return true;
        });
        tabs.setOnItemSelectedListener(item -> {
            show(item.getItemId());
            return true;
        });

        vm.state.observe(this, state -> render());
        vm.info.observe(this, info -> render());
    }

    @Override
    protected void onDestroy() {
        vm.setMessageListener(null);
        if (passcodeDialog != null) passcodeDialog.dismiss();
        super.onDestroy();
    }

    /** Bring the frame in line with the connection: title, buttons, tabs, screen. */
    private void render() {
        DeviceInfo info = vm.info.getValue();
        boolean connected = vm.state.getValue() == PicoLink.State.CONNECTED && info != null;
        boolean locked = connected && info.locked;
        boolean ready = connected && !locked;

        toolbar.setTitle(connected ? info.name : getString(R.string.app_name));
        toolbar.getMenu().findItem(R.id.action_refresh).setVisible(ready);
        toolbar.getMenu().findItem(R.id.action_disconnect).setVisible(connected);
        tabs.setVisibility(ready ? View.VISIBLE : View.GONE);
        show(ready ? tabs.getSelectedItemId() : CONNECT_SCREEN);

        if (locked && passcodeDialog == null) {
            passcodeDialog = Ui.prompt(this, "Passcode", "This Pico is locked", "", "Unlock", pass -> {
                passcodeDialog = null;
                vm.submitPasscode(pass);
            }, () -> {
                passcodeDialog = null;
                vm.disconnect();
            });
        } else if (!locked && passcodeDialog != null) {
            passcodeDialog.dismiss();
            passcodeDialog = null;
        }
    }

    private void show(int screen) {
        String tag = String.valueOf(screen);
        if (tag.equals(shownScreen)) return;
        shownScreen = tag;
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, fragmentFor(screen), tag)
                .commit();
    }

    /** Pad the frame so nothing hides under the status bar, navigation bar or keyboard. */
    private void keepClearOfSystemBars(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }
}
