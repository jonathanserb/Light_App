package com.lightapp.pico.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Small helpers for building and reading protocol messages. */
public final class Json {
    private Json() {
    }

    /** Build an object from alternating keys and values: obj("pin", 15, "value", 1). */
    public static JSONObject obj(Object... keysAndValues) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < keysAndValues.length; i += 2) {
                Object value = keysAndValues[i + 1];
                o.put((String) keysAndValues[i], value == null ? JSONObject.NULL : value);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return o;
    }

    /** A text field, or null if it is missing or JSON null. */
    public static String stringOrNull(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key);
    }

    /** Pins travel as a GP number, or the string "LED" for the on-board LED. */
    public static Object pin(String pin) {
        String text = pin.trim();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return text.toUpperCase(Locale.ROOT);
        }
    }

    /** How a pin is shown to the user: "GP15" or "LED". */
    public static String pinName(String pin) {
        return pin.matches("\\d+") ? "GP" + pin : pin;
    }

    /** Turn text typed into a parameter field into the value the program expects. */
    public static Object parseValue(String text) {
        String t = text.trim();
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            // not a whole number
        }
        try {
            double d = Double.parseDouble(t);
            if (!Double.isNaN(d) && !Double.isInfinite(d)) return d;
        } catch (NumberFormatException e) {
            // not a number at all
        }
        if (t.equalsIgnoreCase("true")) return true;
        if (t.equalsIgnoreCase("false")) return false;
        return t;
    }
}
