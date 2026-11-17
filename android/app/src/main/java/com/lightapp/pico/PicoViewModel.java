package com.lightapp.pico;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import com.lightapp.pico.ble.PicoBle;
import com.lightapp.pico.ble.PicoLink;
import com.lightapp.pico.data.DeviceInfo;
import com.lightapp.pico.data.Json;
import com.lightapp.pico.data.PicoClient;
import com.lightapp.pico.data.PinState;
import com.lightapp.pico.data.ProgramInfo;
import com.lightapp.pico.data.Schedule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The hub of the app. It holds everything the screens show (the LiveData
 * fields) and has one method per thing the user can do. Screens never talk to
 * Bluetooth themselves: they call a method here and redraw when a field changes.
 *
 * It outlives screen rotations, so the connection is kept while the app is open.
 */
public class PicoViewModel extends AndroidViewModel implements PicoLink.Listener, PicoClient.Listener {

    /** Receives short pop-up messages ("Clock set from phone", errors, ...). */
    public interface MessageListener {
        void onMessage(String text);
    }

    /** How the link is made. The tests swap this for a pretend Pico. */
    public static Function<Context, PicoLink> linkFactory = PicoBle::new;

    // ---- what the screens show -------------------------------------------
    public final MutableLiveData<PicoLink.State> state = new MutableLiveData<>(PicoLink.State.DISCONNECTED);
    public final MutableLiveData<DeviceInfo> info = new MutableLiveData<>(); // null until connected
    public final MutableLiveData<List<PinState>> pins = new MutableLiveData<>(new ArrayList<>());
    public final MutableLiveData<List<Schedule>> schedules = new MutableLiveData<>(new ArrayList<>());
    public final MutableLiveData<List<ProgramInfo>> programs = new MutableLiveData<>(new ArrayList<>());
    public final MutableLiveData<double[]> adc = new MutableLiveData<>(); // volts on ADC0-2, null until read
    public final MutableLiveData<List<PicoLink.Found>> found = new MutableLiveData<>(new ArrayList<>());
    public final MutableLiveData<Boolean> scanning = new MutableLiveData<>(false);
    public final MutableLiveData<List<String>> log = new MutableLiveData<>(new ArrayList<>());
    public final MutableLiveData<String[]> lastDevice = new MutableLiveData<>(); // {address, name} or null

    private final PicoLink link;
    private final PicoClient client;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> lastSeen = new HashMap<>();
    private MessageListener messageListener;
    private String address;
    private boolean userDisconnected;

    public PicoViewModel(@NonNull Application app) {
        super(app);
        link = linkFactory.apply(app);
        link.setListener(this);
        client = new PicoClient(link, this);
        prefs = app.getSharedPreferences("pico", Context.MODE_PRIVATE);
        String savedAddress = prefs.getString("addr", null);
        if (savedAddress != null) {
            lastDevice.setValue(new String[]{savedAddress, prefs.getString("name", "Pico")});
        }
    }

    // ---- messages --------------------------------------------------------

    public void setMessageListener(MessageListener listener) {
        messageListener = listener;
    }

    /** Show a short pop-up message at the bottom of the screen. */
    private void say(String text) {
        if (messageListener != null) messageListener.onMessage(text);
    }

    /**
     * Send a command; {@code reply} runs when the Pico says ok. Any failure
     * (Bluetooth trouble, or the Pico refusing) becomes a pop-up message.
     */
    private void send(String cmd, JSONObject args, PicoClient.Reply reply) {
        client.request(cmd, args, reply, message -> {
            if (!userDisconnected) say(message);
        });
    }

    /**
     * Like {@link #send}, and if it fails also runs {@code onFailure}. Used to
     * put a switch or drop-down back the way it was when the Pico says no.
     */
    private void send(String cmd, JSONObject args, PicoClient.Reply reply, Runnable onFailure) {
        client.request(cmd, args, reply, message -> {
            if (!userDisconnected) say(message);
            onFailure.run();
        });
    }

    /** Publish the list again unchanged, so the screen redraws from what the Pico really has. */
    private <T> void redraw(MutableLiveData<T> data) {
        data.setValue(data.getValue());
    }

    /** Like {@link #send}, but failures are ignored. For background refreshing. */
    private void sendQuietly(String cmd, PicoClient.Reply reply) {
        client.request(cmd, null, reply, message -> {
        });
    }

    private static final PicoClient.Reply NOTHING = reply -> {
    };

    // ---- connecting ------------------------------------------------------

    public boolean isBluetoothOn() {
        return link.isBluetoothOn();
    }

    private final Runnable stopScan = this::stopScanning;

    private void stopScanning() {
        link.stopScan();
    }

    public void startScan() {
        main.removeCallbacks(stopScan);
        link.startScan();
        main.postDelayed(stopScan, Settings.SCAN_DURATION_MS);
    }

    public void connect(String address, String name) {
        this.address = address;
        userDisconnected = false;
        rememberDevice(name);
        link.connect(address);
    }

    public void disconnect() {
        userDisconnected = true;
        link.disconnect();
    }

    private void rememberDevice(String name) {
        prefs.edit().putString("addr", address).putString("name", name).apply();
        lastDevice.setValue(new String[]{address, name});
    }

    @Override
    public void onScan(List<PicoLink.Found> devices, boolean isScanning) {
        found.setValue(devices);
        scanning.setValue(isScanning);
    }

    @Override
    public void onState(PicoLink.State newState) {
        PicoLink.State previous = state.getValue();
        state.setValue(newState);
        if (newState == PicoLink.State.CONNECTED) {
            handshake();
        } else if (newState == PicoLink.State.DISCONNECTED) {
            client.failAll("Disconnected");
            lastSeen.clear();
            info.setValue(null);
            if (!userDisconnected && previous != PicoLink.State.DISCONNECTED) {
                say(previous == PicoLink.State.CONNECTING
                        ? "Couldn't connect. Is the Pico powered and in range?"
                        : "Connection lost");
            }
        }
    }

    /** First words after connecting: who are you, and do you need a passcode? */
    private void handshake() {
        client.request("info", null, reply -> {
            DeviceInfo first = DeviceInfo.from(reply);
            if (!first.locked) {
                unlocked();
                return;
            }
            String saved = prefs.getString("pass_" + address, null);
            if (saved == null) {
                info.setValue(first); // the screen sees "locked" and asks for the passcode
            } else {
                client.request("auth", Json.obj("pass", saved), ok -> unlocked(), wrong -> info.setValue(first));
            }
        }, message -> {
            // Connected to something that doesn't speak the protocol: don't sit there half-connected.
            if (link.getState() == PicoLink.State.CONNECTED) {
                userDisconnected = true;
                link.disconnect();
                say("This device didn't respond as expected (" + message + ")");
            }
        });
    }

    public void submitPasscode(String pass) {
        client.request("auth", Json.obj("pass", pass), reply -> {
            prefs.edit().putString("pass_" + address, pass).apply();
            unlocked();
        }, message -> {
            say(message);
            info.setValue(info.getValue()); // makes the screen ask again
        });
    }

    private void unlocked() {
        if (Settings.SET_CLOCK_ON_CONNECT) {
            // The Pico has no battery-backed clock, so set it on every connection.
            send("time.set", Json.obj("t", phoneTime()), reply -> loadAll());
        } else {
            loadAll();
        }
    }

    private JSONArray phoneTime() {
        LocalDateTime now = LocalDateTime.now();
        JSONArray t = new JSONArray();
        t.put(now.getYear()).put(now.getMonthValue()).put(now.getDayOfMonth());
        t.put(now.getHour()).put(now.getMinute()).put(now.getSecond());
        t.put(now.getDayOfWeek().getValue() - 1); // the Pico counts Monday as 0
        return t;
    }

    // ---- loading ---------------------------------------------------------
    // Each load asks the Pico for its current state and publishes it. Lists
    // are only published when they really changed, so screens aren't rebuilt
    // (and half-typed text lost) by every background refresh.

    private boolean changed(String what, Object json) {
        String text = String.valueOf(json);
        return !text.equals(lastSeen.put(what, text));
    }

    private void gotInfo(JSONObject reply) {
        DeviceInfo i = DeviceInfo.from(reply);
        info.setValue(i);
        String[] last = lastDevice.getValue();
        if (address != null && (last == null || !i.name.equals(last[1]))) rememberDevice(i.name);
    }

    private void gotPins(JSONObject reply) {
        JSONArray array = reply.optJSONArray("pins");
        if (changed("pins", array)) pins.setValue(PinState.listFrom(array));
    }

    private void gotSchedules(JSONObject reply) {
        JSONArray array = reply.optJSONArray("items");
        if (changed("schedules", array)) schedules.setValue(Schedule.listFrom(array));
    }

    private void gotPrograms(JSONObject reply) {
        JSONArray array = reply.optJSONArray("items");
        if (changed("programs", array)) programs.setValue(ProgramInfo.listFrom(array));
    }

    private void loadInfo() {
        send("info", null, this::gotInfo);
    }

    private void loadPins() {
        send("pin.list", null, this::gotPins);
    }

    private void loadSchedules() {
        send("sched.list", null, this::gotSchedules);
    }

    private void loadPrograms() {
        send("prog.list", null, this::gotPrograms);
    }

    private void loadAll() {
        loadInfo();
        loadPins();
        loadSchedules();
        loadPrograms();
    }

    private boolean ready() {
        DeviceInfo i = info.getValue();
        return i != null && !i.locked;
    }

    public void refreshAll() {
        loadAll();
    }

    public void pollInfo() {
        if (ready()) sendQuietly("info", this::gotInfo);
    }

    public void pollPins() {
        if (ready()) sendQuietly("pin.list", this::gotPins);
    }

    // ---- device ----------------------------------------------------------

    public void syncTime() {
        send("time.set", Json.obj("t", phoneTime()), reply -> {
            loadInfo();
            say("Clock set from phone");
        });
    }

    public void rename(String name) {
        send("name.set", Json.obj("name", name), reply -> {
            loadInfo();
            say("Renamed. The new name is advertised after the next reboot.");
        });
    }

    public void setPasscode(String pass) {
        send("pass.set", Json.obj("pass", pass), reply -> {
            prefs.edit().putString("pass_" + address, pass).apply();
            loadInfo();
            say(pass.isEmpty() ? "Passcode removed" : "Passcode set");
        });
    }

    public void reboot() {
        send("reboot", null, reply -> say("Rebooting…"));
    }

    // ---- pins ------------------------------------------------------------

    /** Set a pin's mode. {@code label} may be null to leave the label alone. */
    public void configurePin(String pin, String mode, String label) {
        JSONObject args = label == null
                ? Json.obj("pin", Json.pin(pin), "mode", mode)
                : Json.obj("pin", Json.pin(pin), "mode", mode, "label", label);
        send("pin.cfg", args, reply -> loadPins(), () -> redraw(pins));
    }

    public void removePin(String pin) {
        configurePin(pin, "off", null);
    }

    public void setPin(String pin, boolean on) {
        send("pin.set", Json.obj("pin", Json.pin(pin), "value", on ? 1 : 0), reply -> loadPins(),
                () -> redraw(pins));
    }

    public void setDuty(String pin, int duty) {
        send("pin.set", Json.obj("pin", Json.pin(pin), "duty", duty), reply -> loadPins(),
                () -> redraw(pins));
    }

    public void readAdc() {
        double[] volts = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            int ch = channel;
            send("adc.get", Json.obj("ch", ch), reply -> {
                volts[ch] = reply.optDouble("volts");
                if (ch == 2) adc.setValue(volts);
            });
        }
    }

    // ---- schedules -------------------------------------------------------

    public void saveSchedule(Schedule schedule) {
        send("sched.put", Json.obj("item", schedule.toJson()), reply -> loadSchedules(),
                () -> redraw(schedules));
    }

    public void deleteSchedule(int id) {
        send("sched.del", Json.obj("sid", id), reply -> loadSchedules());
    }

    // ---- programs --------------------------------------------------------

    /** Store edited parameters; a running program picks them up immediately. */
    private void storeParams(ProgramInfo program, Map<String, String> edits) {
        for (Map.Entry<String, String> edit : edits.entrySet()) {
            String text = edit.getValue();
            if (text.equals(program.params.get(edit.getKey()))) continue;
            // An empty field resets the parameter to the program's default.
            Object value = text.trim().isEmpty() ? null : Json.parseValue(text);
            send("param.set", Json.obj("key", program.name + "." + edit.getKey(), "value", value), NOTHING);
        }
    }

    public void saveParams(ProgramInfo program, Map<String, String> edits) {
        storeParams(program, edits);
        send("prog.list", null, reply -> {
            gotPrograms(reply);
            say("Parameters saved");
        });
    }

    public void runProgram(ProgramInfo program, Map<String, String> edits) {
        storeParams(program, edits);
        send("prog.run", Json.obj("name", program.name), reply -> {
            loadPrograms();
            loadInfo();
        });
    }

    public void stopProgram() {
        send("prog.stop", null, reply -> {
            loadInfo();
            loadPins();
        });
    }

    /** {@code name} null means no program starts at power-up. */
    public void setBootProgram(String name) {
        send("prog.boot", Json.obj("name", name), reply -> loadInfo(), () -> redraw(info));
    }

    // ---- console and events ----------------------------------------------

    /** Accepts either a full JSON object or just a command name such as "info". */
    public void sendRaw(String text) {
        String line = text.trim();
        if (!line.startsWith("{")) line = Json.obj("id", 0, "cmd", line).toString();
        if (link.getState() == PicoLink.State.CONNECTED) client.sendRaw(line);
    }

    @Override
    public void onLine(String line) {
        client.onLine(line);
    }

    @Override
    public void onLog(List<String> lines) {
        log.setValue(lines);
    }

    @Override
    public void onEvent(JSONObject event) {
        String name = event.optString("name");
        switch (event.optString("ev")) {
            case "fired":
                say("Schedule ran: " + (name.isEmpty() ? "#" + event.optInt("sched") : name));
                loadAll();
                break;
            case "prog_end":
                say("Program “" + name + "” finished");
                loadAll();
                break;
            case "prog_err":
                say("Program “" + name + "” failed: " + event.optString("err"));
                loadAll();
                break;
            case "sched_err":
                say("Schedule failed: " + event.optString("err"));
                break;
            default:
                break;
        }
    }

    @Override
    protected void onCleared() {
        userDisconnected = true;
        main.removeCallbacks(stopScan);
        link.stopScan();
        link.disconnect();
    }
}
