package com.voice2sms.sendlater.web;

import com.voice2sms.sendlater.domain.ThreadEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One message bubble read from the open GV web thread by {@code window.v2sReadThread}
 * ({@code {direction: "IN"|"OUT", timeMs: number|null, text: string, id: string|null}}).
 * Carries raw text: never log or persist it beyond what the store allows.
 */
public final class ThreadBubble {

    /** Upper bound on bubbles accepted from one report. */
    public static final int MAX_BUBBLES = 200;

    private final ThreadEvent.Direction direction;
    private final Long timeMs;
    private final String text;
    private final String id;

    public ThreadBubble(ThreadEvent.Direction direction, Long timeMs, String text, String id) {
        this.direction = direction;
        this.timeMs = timeMs;
        this.text = text;
        this.id = id;
    }

    public ThreadEvent.Direction getDirection() { return direction; }
    public Long getTimeMs() { return timeMs; }
    public String getText() { return text; }
    public String getId() { return id; }

    /** SMS ThreadEvent with textHash set via ThreadEvent.hashOf, or null when the time is unknown. */
    public ThreadEvent toThreadEvent() {
        if (timeMs == null) return null;
        return new ThreadEvent(direction, ThreadEvent.Kind.SMS, timeMs, ThreadEvent.hashOf(text), text);
    }

    @Override
    public String toString() {
        return "ThreadBubble{" + direction + ", timeKnown=" + (timeMs != null)
                + ", idKnown=" + (id != null) + "}";
    }

    /** What the page answered for one v2sReadThread request. */
    public static final class Report {
        public final String e164;
        /** Null when the page was not showing that thread or could not read it. */
        public final List<ThreadBubble> bubbles;

        Report(String e164, List<ThreadBubble> bubbles) {
            this.e164 = e164;
            this.bubbles = bubbles;
        }
    }

    /**
     * Parses {@code {"e164": "...", "bubbles": [...] | null}}. Returns null for malformed JSON or a
     * missing e164. Entries without a valid direction or string text are dropped.
     */
    public static Report parseReport(String json) {
        if (json == null) return null;
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            return null;
        }
        Object e = o.opt("e164");
        if (!(e instanceof String) || ((String) e).isEmpty()) return null;
        Object b = o.opt("bubbles");
        if (!(b instanceof JSONArray)) return new Report((String) e, null);
        return new Report((String) e, parseList((JSONArray) b));
    }

    static List<ThreadBubble> parseList(JSONArray arr) {
        List<ThreadBubble> out = new ArrayList<>();
        int start = Math.max(0, arr.length() - MAX_BUBBLES);
        for (int i = start; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x == null) continue;
            ThreadEvent.Direction dir;
            Object d = x.opt("direction");
            if ("IN".equals(d)) dir = ThreadEvent.Direction.IN;
            else if ("OUT".equals(d)) dir = ThreadEvent.Direction.OUT;
            else continue;
            Object t = x.opt("text");
            if (!(t instanceof String)) continue;
            Long time = null;
            Object tm = x.opt("timeMs");
            if (tm instanceof Number && ((Number) tm).longValue() > 0) time = ((Number) tm).longValue();
            String id = null;
            Object idv = x.opt("id");
            if (idv instanceof String && !((String) idv).isEmpty()) id = (String) idv;
            out.add(new ThreadBubble(dir, time, (String) t, id));
        }
        return Collections.unmodifiableList(out);
    }
}
