package com.lightapp.pico.data;

import android.os.Handler;
import android.os.Looper;

import com.lightapp.pico.Settings;
import com.lightapp.pico.ble.PicoLink;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Request/reply on top of the line link. Every request carries an "id" that
 * the Pico echoes back; lines without a matching id are unsolicited events.
 *
 * Nothing here blocks: you hand {@link #request} two callbacks and exactly one
 * of them is called later, on the main thread.
 */
public class PicoClient {
    public interface Reply {
        void onReply(JSONObject reply);
    }

    public interface Failure {
        void onFailure(String message);
    }

    public interface Listener {
        /** The Pico said something nobody asked for, e.g. a schedule just ran. */
        void onEvent(JSONObject event);

        /** The console log gained a line. */
        void onLog(List<String> log);
    }

    private static class Pending {
        Reply reply;
        Failure failure;
        Runnable timeout;
    }

    private final PicoLink link;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final List<String> log = new ArrayList<>();
    private int nextId = 1;

    public PicoClient(PicoLink link, Listener listener) {
        this.link = link;
        this.listener = listener;
    }

    /**
     * Send a command. {@code args} may be null. {@code reply} gets the Pico's
     * answer if it was "ok"; otherwise {@code failure} gets the reason.
     */
    public void request(String cmd, JSONObject args, Reply reply, Failure failure) {
        if (link.getState() != PicoLink.State.CONNECTED) {
            failure.onFailure("Not connected");
            return;
        }
        int id = nextId++;
        JSONObject request = args == null ? new JSONObject() : args;
        try {
            request.put("id", id).put("cmd", cmd);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        Pending p = new Pending();
        p.reply = reply;
        p.failure = failure;
        p.timeout = () -> {
            if (pending.remove(id) != null) failure.onFailure("No reply from the Pico");
        };
        pending.put(id, p);
        main.postDelayed(p.timeout, Settings.REPLY_TIMEOUT_MS);
        sendRaw(request.toString());
    }

    /** Send a line as-is (used by the console). Any answer shows up in the log. */
    public void sendRaw(String line) {
        addLog("→ " + line);
        link.sendLine(line);
    }

    /** Feed in every line received from the Pico. */
    public void onLine(String line) {
        addLog("← " + line);
        JSONObject message;
        try {
            message = new JSONObject(line);
        } catch (JSONException e) {
            return; // not ours; it is still visible in the console
        }
        Pending p = message.isNull("id") ? null : pending.remove(message.optInt("id"));
        if (p != null) {
            main.removeCallbacks(p.timeout);
            if (message.optBoolean("ok")) p.reply.onReply(message);
            else p.failure.onFailure(message.optString("err", "Command failed"));
        } else if (message.has("ev")) {
            listener.onEvent(message);
        }
    }

    /** The connection dropped: nobody still waiting will get an answer. */
    public void failAll(String message) {
        List<Pending> waiting = new ArrayList<>(pending.values());
        pending.clear();
        for (Pending p : waiting) {
            main.removeCallbacks(p.timeout);
            p.failure.onFailure(message);
        }
    }

    private void addLog(String line) {
        log.add(line);
        while (log.size() > Settings.CONSOLE_LOG_LINES) log.remove(0);
        listener.onLog(new ArrayList<>(log));
    }
}
