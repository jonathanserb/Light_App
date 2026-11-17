package com.lightapp.pico;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/**
 * Opens the real screens against {@link FakePico} and uses them the way a
 * person would. Runs on your computer; no phone or Pico needed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppTest {
    private FakePico pico;
    private MainActivity activity;

    @Before
    public void setUp() {
        pico = new FakePico();
        PicoViewModel.linkFactory = context -> pico;
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT);
        activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        settle();
    }

    @After
    public void tearDown() {
        PicoViewModel.linkFactory = com.lightapp.pico.ble.PicoBle::new;
    }

    // ---- helpers ---------------------------------------------------------

    /** Let everything that is waiting to happen, happen. */
    private void settle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private <T extends View> T find(int id) {
        T view = activity.findViewById(id);
        assertNotNull("view not on screen", view);
        return view;
    }

    private String text(View parent, int id) {
        return ((TextView) parent.findViewById(id)).getText().toString();
    }

    private void click(View view) {
        view.performClick();
        settle();
    }

    private void openTab(int id) {
        ((BottomNavigationView) find(R.id.tabs)).setSelectedItemId(id);
        settle();
    }

    private void connect() {
        click(find(R.id.scan));
        ViewGroup devices = find(R.id.devices);
        assertEquals(1, devices.getChildCount());
        click(devices.getChildAt(0));
    }

    private AlertDialog dialog() {
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private PicoViewModel vm() {
        return new ViewModelProvider(activity).get(PicoViewModel.class);
    }

    // ---- tests -----------------------------------------------------------

    @Test
    public void startsOnTheConnectScreen() {
        assertEquals("Pico Light", ((MaterialToolbar) find(R.id.toolbar)).getTitle().toString());
        assertEquals(View.GONE, find(R.id.tabs).getVisibility());
        assertEquals(View.VISIBLE, find(R.id.idle).getVisibility());
    }

    @Test
    public void connectingSetsTheClockAndShowsStatus() {
        connect();
        assertNotNull("clock was not set on connect", pico.last("time.set"));
        assertEquals("Porch", ((MaterialToolbar) find(R.id.toolbar)).getTitle().toString());
        assertEquals(View.VISIBLE, find(R.id.tabs).getVisibility());
        assertEquals("Mon 2026-11-16  18:30", text(find(R.id.root), R.id.clock));
        ViewGroup facts = find(R.id.facts);
        assertEquals(7, facts.getChildCount());
        assertEquals("1 min 15 s", text(facts.getChildAt(3), R.id.value));
    }

    @Test
    public void pinsTabShowsAndSwitchesPins() {
        connect();
        openTab(R.id.tab_pins);
        ViewGroup pins = find(R.id.pins);
        assertEquals(3, pins.getChildCount());
        assertEquals(View.GONE, find(R.id.empty).getVisibility());

        // Sorted: LED, then GP2, then GP15.
        View led = pins.getChildAt(0);
        View door = pins.getChildAt(1);
        View strip = pins.getChildAt(2);
        assertEquals("LED", text(led, R.id.title));
        assertEquals("Door", text(door, R.id.title));
        assertEquals("HIGH", text(door, R.id.value));
        assertEquals("40%", text(strip, R.id.value));
        assertEquals(View.VISIBLE, strip.findViewById(R.id.slider).getVisibility());
        assertEquals(View.GONE, led.findViewById(R.id.slider).getVisibility());

        MaterialSwitch toggle = led.findViewById(R.id.toggle);
        assertTrue(toggle.isChecked());
        click(toggle);
        assertEquals(0, pico.last("pin.set").optInt("value"));
        assertEquals("LED", pico.last("pin.set").optString("pin"));
        // The list was rebuilt from what the Pico now reports.
        assertFalse(((MaterialSwitch) pins.getChildAt(0).findViewById(R.id.toggle)).isChecked());

        click(find(R.id.read_adc));
        assertTrue(text(find(R.id.root), R.id.adc).contains("ADC2 (GP28): 1.65 V"));
    }

    @Test
    public void aChangeThePicoRefusesIsReported() {
        connect();
        openTab(R.id.tab_pins);
        String[] message = {null};
        vm().setMessageListener(text -> message[0] = text);
        vm().configurePin("LED", "pwm", null);
        settle();
        assertEquals("LED can't do mode 'pwm'", message[0]);
        ViewGroup pins = find(R.id.pins);
        assertEquals(View.VISIBLE, pins.getChildAt(0).findViewById(R.id.toggle).getVisibility());
    }

    @Test
    public void scheduleTabListsAndEdits() {
        connect();
        openTab(R.id.tab_schedule);
        ViewGroup schedules = find(R.id.schedules);
        assertEquals(1, schedules.getChildCount());
        View card = schedules.getChildAt(0);
        assertEquals("18:30", text(card, R.id.time));
        assertEquals("Evening · GP15 ON", text(card, R.id.summary));
        assertEquals("Weekdays", text(card, R.id.days));

        click(card.findViewById(R.id.enabled));
        assertFalse(pico.last("sched.put").optJSONObject("item").optBoolean("en"));

        // Edit it in the pop-up and save.
        click(card);
        AlertDialog dialog = dialog();
        assertTrue(dialog.isShowing());
        assertEquals("18:30", text(dialog.findViewById(R.id.time).getRootView(), R.id.time));
        ((EditText) dialog.findViewById(R.id.duration)).setText("90");
        ((EditText) dialog.findViewById(R.id.pin)).setText("");
        click(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        assertTrue("a schedule with no pin must not be saved", dialog.isShowing());
        assertEquals("Enter a pin", text(dialog.findViewById(R.id.problem).getRootView(), R.id.problem));

        ((EditText) dialog.findViewById(R.id.pin)).setText("16");
        click(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        assertFalse(dialog.isShowing());
        assertEquals(90, pico.last("sched.put").optJSONObject("item").optInt("dur"));
        assertEquals(16, pico.last("sched.put").optJSONObject("item").optJSONObject("act").optInt("pin"));
        assertEquals(1, pico.last("sched.put").optJSONObject("item").optInt("id"));

        // A new one starts from sensible defaults.
        click(find(R.id.add));
        click(dialog().getButton(AlertDialog.BUTTON_POSITIVE));
        assertTrue(pico.last("sched.put").optJSONObject("item").isNull("id"));
    }

    @Test
    public void programsTabRunsWithEditedParameters() {
        connect();
        openTab(R.id.tab_programs);
        ViewGroup programs = find(R.id.programs);
        assertEquals(2, programs.getChildCount());
        View blink = programs.getChildAt(0);
        ViewGroup params = blink.findViewById(R.id.params);
        assertEquals(2, params.getChildCount());
        assertEquals(View.GONE, blink.findViewById(R.id.running).getVisibility());
        assertEquals(View.GONE, programs.getChildAt(1).findViewById(R.id.save).getVisibility());

        // Parameters are sorted by name: on_ms, then pin.
        ((EditText) params.getChildAt(0).findViewById(R.id.field)).setText("100");
        click(blink.findViewById(R.id.run));
        assertEquals("blink.on_ms", pico.last("param.set").optString("key"));
        assertEquals(100, pico.last("param.set").optInt("value"));
        assertEquals("blink", pico.last("prog.run").optString("name"));
        assertEquals(View.VISIBLE, blink.findViewById(R.id.running).getVisibility());
        assertEquals(View.VISIBLE, blink.findViewById(R.id.stop).getVisibility());
        assertEquals(View.GONE, blink.findViewById(R.id.run).getVisibility());

        click(blink.findViewById(R.id.boot));
        assertEquals("blink", pico.last("prog.boot").optString("name"));

        click(blink.findViewById(R.id.stop));
        assertNull(pico.running);
        assertEquals(View.VISIBLE, blink.findViewById(R.id.run).getVisibility());
    }

    @Test
    public void consoleShowsTrafficAndSends() {
        connect();
        openTab(R.id.tab_console);
        assertTrue(text(find(R.id.root), R.id.log).contains("\"cmd\":\"info\""));
        ((EditText) find(R.id.input)).setText("adc.get");
        click(find(R.id.send));
        assertNotNull(pico.last("adc.get"));
        assertTrue(text(find(R.id.root), R.id.log).contains("\"volts\":1.65"));
    }

    @Test
    public void lockedPicoAsksForPasscodeUntilItIsRight() {
        pico.passcode = "1234";
        connect();
        assertEquals(View.GONE, find(R.id.tabs).getVisibility());

        AlertDialog first = dialog();
        assertTrue(first.isShowing());
        ((EditText) first.findViewById(R.id.field)).setText("0000");
        click(first.getButton(AlertDialog.BUTTON_POSITIVE));
        assertEquals(View.GONE, find(R.id.tabs).getVisibility());

        AlertDialog second = dialog();
        assertTrue("should ask again after a wrong passcode", second != first && second.isShowing());
        ((EditText) second.findViewById(R.id.field)).setText("1234");
        click(second.getButton(AlertDialog.BUTTON_POSITIVE));
        assertEquals(View.VISIBLE, find(R.id.tabs).getVisibility());
        assertNotNull(pico.last("time.set"));
    }

    @Test
    public void losingTheConnectionReturnsToTheConnectScreen() {
        connect();
        openTab(R.id.tab_pins);
        pico.drop();
        settle();
        assertEquals(View.GONE, find(R.id.tabs).getVisibility());
        assertEquals(View.VISIBLE, find(R.id.idle).getVisibility());
        assertEquals("Reconnect to Porch", text(find(R.id.root), R.id.reconnect));
    }
}
