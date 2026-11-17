package com.lightapp.pico.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One schedule: at a time of day, on some days, do an action. */
public class Schedule {
    public static final String[] DAY_NAMES = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    public static final int EVERY_DAY = 127;

    public Integer id;            // null = not yet stored on the Pico
    public String name = "";
    public boolean enabled = true;
    public int hour = 18;
    public int minute = 0;
    public int days = EVERY_DAY;  // bit 0 = Monday ... bit 6 = Sunday
    public int durationSeconds;   // 0 = leave it; otherwise undo the action after this long
    public ScheduleAction action = new ScheduleAction();

    public boolean onDay(int day) {
        return (days >> day & 1) == 1;
    }

    public String timeText() {
        return String.format(Locale.US, "%02d:%02d", hour, minute);
    }

    public String daysText() {
        if (days == EVERY_DAY) return "Every day";
        if (days == 0b0011111) return "Weekdays";
        if (days == 0b1100000) return "Weekends";
        StringBuilder text = new StringBuilder();
        for (int day = 0; day < 7; day++) {
            if (onDay(day)) text.append(text.length() == 0 ? "" : " ").append(DAY_NAMES[day]);
        }
        return text.toString();
    }

    /** "Evening · GP15 ON for 90 s" */
    public String summary() {
        String what = action.describe() + (durationSeconds > 0 ? " for " + durationSeconds + " s" : "");
        return name.trim().isEmpty() ? what : name + " · " + what;
    }

    public JSONObject toJson() {
        JSONObject o = Json.obj("name", name, "en", enabled, "h", hour, "m", minute,
                "days", days, "dur", durationSeconds, "act", action.toJson());
        if (id != null) {
            try {
                o.put("id", id.intValue());
            } catch (JSONException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return o;
    }

    public Schedule copy() {
        return from(toJson());
    }

    public static Schedule from(JSONObject o) {
        Schedule s = new Schedule();
        s.id = o.has("id") ? (Integer) o.optInt("id") : null;
        s.name = o.optString("name");
        s.enabled = o.optBoolean("en", true);
        s.hour = o.optInt("h");
        s.minute = o.optInt("m");
        s.days = o.optInt("days", EVERY_DAY);
        s.durationSeconds = o.optInt("dur");
        s.action = ScheduleAction.from(o.optJSONObject("act"));
        return s;
    }

    /** Sorted by time of day. */
    public static List<Schedule> listFrom(JSONArray array) {
        List<Schedule> list = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o != null) list.add(from(o));
            }
        }
        list.sort((a, b) -> Integer.compare(a.hour * 60 + a.minute, b.hour * 60 + b.minute));
        return list;
    }
}
