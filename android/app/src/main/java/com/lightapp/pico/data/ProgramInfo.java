package com.lightapp.pico.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One program from "prog.list", with the current value of each of its parameters. */
public class ProgramInfo {
    public String name;
    public String description;
    public final Map<String, String> params = new TreeMap<>(); // sorted by parameter name

    public static ProgramInfo from(JSONObject o) {
        ProgramInfo p = new ProgramInfo();
        p.name = o.optString("name");
        p.description = o.optString("desc");
        JSONObject values = o.optJSONObject("params");
        if (values != null) {
            for (Iterator<String> keys = values.keys(); keys.hasNext(); ) {
                String key = keys.next();
                p.params.put(key, String.valueOf(values.opt(key)));
            }
        }
        return p;
    }

    public static List<ProgramInfo> listFrom(JSONArray array) {
        List<ProgramInfo> list = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o != null) list.add(from(o));
            }
        }
        return list;
    }
}
