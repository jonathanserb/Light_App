package com.lightapp.pico;

import android.os.Handler;
import android.os.Looper;

import com.lightapp.pico.ble.PicoLink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A pretend Pico for the tests: it stands in for Bluetooth and answers the
 * same commands the real firmware does, with a small fixed set-up.
 */
public class FakePico implements PicoLink {
    public final List<JSONObject> received = new ArrayList<>();
    public String passcode; // set to make the Pico locked
    public String running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private State state = State.DISCONNECTED;
    private boolean authed;
    private final JSONArray pins;

    public FakePico() {
        try {
            pins = new JSONArray("["
                    + "{\"pin\":\"LED\",\"mode\":\"out\",\"value\":1,\"duty\":0,\"freq\":1000,\"label\":\"\"},"
                    + "{\"pin\":\"15\",\"mode\":\"pwm\",\"value\":0,\"duty\":40,\"freq\":1000,\"label\":\"Strip\"},"
                    + "{\"pin\":\"2\",\"mode\":\"in_up\",\"value\":1,\"duty\":0,\"freq\":1000,\"label\":\"Door\"}]");
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The most recent request with this command, or null. */
    public JSONObject last(String cmd) {
        for (int i = received.size() - 1; i >= 0; i--) {
            if (cmd.equals(received.get(i).optString("cmd"))) return received.get(i);
        }
        return null;
    }

    /** As if the Pico lost power. */
    public void drop() {
        setState(State.DISCONNECTED);
    }

    private void setState(State newState) {
        state = newState;
        listener.onState(newState);
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public boolean isBluetoothOn() {
        return true;
    }

    @Override
    public void startScan() {
        listener.onScan(Collections.singletonList(new Found("AA:BB:CC:DD:EE:FF", "Porch", -52)), true);
    }

    @Override
    public void stopScan() {
    }

    @Override
    public void connect(String address) {
        authed = false;
        setState(State.CONNECTING);
        main.post(() -> setState(State.CONNECTED));
    }

    @Override
    public void disconnect() {
        if (state != State.DISCONNECTED) setState(State.DISCONNECTED);
    }

    @Override
    public void sendLine(String text) {
        try {
            JSONObject request = new JSONObject(text);
            received.add(request);
            JSONObject reply = answer(request);
            reply.put("id", request.opt("id"));
            if (!reply.has("ok")) reply.put("ok", true);
            main.post(() -> listener.onLine(reply.toString()));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private JSONObject refuse(String why) throws JSONException {
        return new JSONObject().put("ok", false).put("err", why);
    }

    private JSONObject pin(Object id) {
        for (int i = 0; i < pins.length(); i++) {
            if (pins.optJSONObject(i).optString("pin").equals(String.valueOf(id))) return pins.optJSONObject(i);
        }
        return null;
    }

    private JSONObject answer(JSONObject request) throws JSONException {
        String cmd = request.optString("cmd");
        boolean locked = passcode != null && !authed;
        if (locked && !cmd.equals("info") && !cmd.equals("auth")) return refuse("locked");
        switch (cmd) {
            case "info":
                return new JSONObject().put("fw", "1.1.0").put("name", "Porch")
                        .put("time", new JSONArray("[2026,11,16,18,30,0,0]")).put("time_valid", true)
                        .put("uptime", 75).put("temp_c", 25.1).put("mem_free", 120000)
                        .put("prog", running == null ? JSONObject.NULL : running)
                        .put("boot_prog", JSONObject.NULL).put("n_sched", 1)
                        .put("locked", locked).put("has_pass", passcode != null);
            case "auth":
                if (!request.optString("pass").equals(passcode)) return refuse("wrong passcode");
                authed = true;
                return new JSONObject();
            case "pin.list":
                return new JSONObject().put("pins", new JSONArray(pins.toString()));
            case "pin.set":
                pin(request.opt("pin")).put("value", request.optInt("value")).put("duty", request.optInt("duty"));
                return new JSONObject();
            case "pin.cfg":
                if (request.optString("pin").equals("LED") && request.optString("mode").equals("pwm")) {
                    return refuse("LED can't do mode 'pwm'");
                }
                pin(request.opt("pin")).put("mode", request.optString("mode"));
                return new JSONObject();
            case "adc.get":
                return new JSONObject().put("raw", 32768).put("volts", 1.65);
            case "sched.list":
                return new JSONObject().put("items", new JSONArray("[{\"id\":1,\"name\":\"Evening\",\"en\":true,"
                        + "\"h\":18,\"m\":30,\"days\":31,\"dur\":0,\"act\":{\"type\":\"pin\",\"pin\":15,\"value\":1}}]"));
            case "prog.list":
                return new JSONObject().put("items", new JSONArray("["
                        + "{\"name\":\"blink\",\"desc\":\"Toggle a pin\",\"params\":{\"pin\":\"LED\",\"on_ms\":500}},"
                        + "{\"name\":\"timer\",\"desc\":\"On, wait, off\",\"params\":{}}]"));
            case "prog.run":
                running = request.optString("name");
                return new JSONObject();
            case "prog.stop":
                running = null;
                return new JSONObject();
            default: // time.set, param.set, sched.put, ... : accepted, nothing to pretend
                return new JSONObject();
        }
    }
}
