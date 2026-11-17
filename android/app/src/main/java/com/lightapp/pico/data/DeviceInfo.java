package com.lightapp.pico.data;

import org.json.JSONArray;
import org.json.JSONObject;

/** The reply to the "info" command. */
public class DeviceInfo {
    public String firmware;
    public String name;
    public int[] time = new int[0]; // year, month, day, hour, minute, second, weekday (0 = Monday)
    public boolean timeValid;
    public long uptimeSeconds;
    public double temperatureC;
    public long freeMemory;
    public String program;     // running program, or null
    public String bootProgram; // program started at power-up, or null
    public int scheduleCount;
    public boolean locked;     // a passcode is needed before anything else works
    public boolean hasPasscode;

    public static DeviceInfo from(JSONObject o) {
        DeviceInfo info = new DeviceInfo();
        info.firmware = o.optString("fw", "?");
        info.name = o.optString("name", "Pico");
        JSONArray t = o.optJSONArray("time");
        if (t != null) {
            info.time = new int[t.length()];
            for (int i = 0; i < t.length(); i++) info.time[i] = t.optInt(i);
        }
        info.timeValid = o.optBoolean("time_valid");
        info.uptimeSeconds = o.optLong("uptime");
        info.temperatureC = o.optDouble("temp_c", 0);
        info.freeMemory = o.optLong("mem_free");
        info.program = Json.stringOrNull(o, "prog");
        info.bootProgram = Json.stringOrNull(o, "boot_prog");
        info.scheduleCount = o.optInt("n_sched");
        info.locked = o.optBoolean("locked");
        info.hasPasscode = o.optBoolean("has_pass");
        return info;
    }
}
