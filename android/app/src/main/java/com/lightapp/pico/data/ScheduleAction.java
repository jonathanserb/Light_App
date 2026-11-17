package com.lightapp.pico.data;

import org.json.JSONObject;

/** What a schedule does when its time comes. */
public class ScheduleAction {
    public String type = "pin"; // pin, pwm, prog, stop
    public String pin = "LED";  // for pin and pwm
    public int value = 1;       // for pin: 1 = on, 0 = off
    public int duty = 100;      // for pwm: 0-100 %
    public String program = ""; // for prog

    public JSONObject toJson() {
        switch (type) {
            case "pin":
                return Json.obj("type", type, "pin", Json.pin(pin), "value", value);
            case "pwm":
                return Json.obj("type", type, "pin", Json.pin(pin), "duty", duty);
            case "prog":
                return Json.obj("type", type, "name", program);
            default:
                return Json.obj("type", type);
        }
    }

    /** Short description for the schedule list. */
    public String describe() {
        switch (type) {
            case "pin":
                return Json.pinName(pin) + (value == 1 ? " ON" : " OFF");
            case "pwm":
                return Json.pinName(pin) + " at " + duty + "%";
            case "prog":
                return "Run “" + program + "”";
            default:
                return "Stop program";
        }
    }

    public static ScheduleAction from(JSONObject o) {
        ScheduleAction a = new ScheduleAction();
        if (o == null) return a;
        a.type = o.optString("type", "pin");
        a.pin = o.has("pin") ? String.valueOf(o.opt("pin")) : "LED";
        a.value = o.optInt("value", 1);
        a.duty = o.optInt("duty", 100);
        a.program = o.optString("name");
        return a;
    }
}
