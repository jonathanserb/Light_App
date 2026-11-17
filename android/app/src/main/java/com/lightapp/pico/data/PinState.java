package com.lightapp.pico.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One pin as reported by "pin.list". */
public class PinState {
    public String pin;   // "15" or "LED"
    public String mode;  // in, in_up, in_down, out, pwm
    public int value;    // 0 or 1
    public int duty;     // 0-100 %, for pwm
    public String label;

    /** What to call the pin on screen: its label if it has one, else "GP15". */
    public String title() {
        return label.trim().isEmpty() ? Json.pinName(pin) : label;
    }

    public static PinState from(JSONObject o) {
        PinState p = new PinState();
        p.pin = String.valueOf(o.opt("pin"));
        p.mode = o.optString("mode", "in");
        p.value = o.optInt("value");
        p.duty = (int) o.optDouble("duty", 0);
        p.label = o.optString("label");
        return p;
    }

    /** Sorted with the LED first, then by GP number. */
    public static List<PinState> listFrom(JSONArray array) {
        List<PinState> list = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o != null) list.add(from(o));
            }
        }
        list.sort((a, b) -> Integer.compare(order(a), order(b)));
        return list;
    }

    private static int order(PinState p) {
        return p.pin.matches("\\d+") ? Integer.parseInt(p.pin) : -1;
    }
}
