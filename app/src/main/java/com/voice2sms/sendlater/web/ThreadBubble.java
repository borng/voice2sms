package com.voice2sms.sendlater.web;

import com.voice2sms.sendlater.domain.ThreadEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * One message bubble read from the open GV web thread by {@code window.v2sReadThread}
 * ({@code {direction: "IN"|"OUT", timeMs: number|null, text: string, id: string|null,
 * label?: string|null, failed?: boolean, sending?: boolean}}).
 * Carries raw text: never log or persist it beyond what the store allows.
 *
 * <p>LIVE-F9: the real GV web page gives no time or id; when {@code timeMs} is absent the
 * screen-reader {@code label} supplies a minute-precision time ({@link GvBubbleLabel}), marked
 * {@link #isTimeFromLabel()}: the page draws that label on an optimistic bubble too, so it dates
 * the bubble but does not prove the server took it. A {@code failed} bubble ("Not sent") never
 * verifies a send (OD28 L1). The label is not kept.
 *
 * <p>Deliver M4: a {@code sending} bubble (GV still shows it as pending / sending) has not been
 * taken by the server yet, so it never settles a verification. A bubble that is both failed and
 * sending counts as failed only.
 */
public final class ThreadBubble {

    /** Upper bound on bubbles accepted from one report. */
    public static final int MAX_BUBBLES = 200;

    private final ThreadEvent.Direction direction;
    private final Long timeMs;
    private final String text;
    private final String id;
    private final boolean timeFromLabel;
    private final boolean failed;
    private final boolean sending;

    public ThreadBubble(ThreadEvent.Direction direction, Long timeMs, String text, String id) {
        this(direction, timeMs, text, id, false, false);
    }

    public ThreadBubble(ThreadEvent.Direction direction, Long timeMs, String text, String id,
                        boolean timeFromLabel, boolean failed) {
        this(direction, timeMs, text, id, timeFromLabel, failed, false);
    }

    /** Deliver M4: {@code sending} = the page still shows this bubble as pending / sending. */
    public ThreadBubble(ThreadEvent.Direction direction, Long timeMs, String text, String id,
                        boolean timeFromLabel, boolean failed, boolean sending) {
        this.direction = direction;
        this.timeMs = timeMs;
        this.text = text;
        this.id = id;
        this.timeFromLabel = timeMs != null && timeFromLabel;
        this.failed = failed;
        this.sending = sending && !failed;
    }

    public ThreadEvent.Direction getDirection() { return direction; }
    public Long getTimeMs() { return timeMs; }
    public String getText() { return text; }
    public String getId() { return id; }
    /** The time came from the bubble's label (minute precision), not from a page time. */
    public boolean isTimeFromLabel() { return timeFromLabel; }
    /** The page marks this bubble as failed / not sent: it never verifies a send. */
    public boolean isFailed() { return failed; }
    /** Deliver M4: the page still shows this bubble as pending / sending (never also failed). */
    public boolean isSending() { return sending; }

    /** SMS ThreadEvent with textHash set via ThreadEvent.hashOf, or null when the time is unknown. */
    public ThreadEvent toThreadEvent() {
        if (timeMs == null) return null;
        return new ThreadEvent(direction, ThreadEvent.Kind.SMS, timeMs, ThreadEvent.hashOf(text), text);
    }

    @Override
    public String toString() {
        return "ThreadBubble{" + direction + ", timeKnown=" + (timeMs != null)
                + ", idKnown=" + (id != null) + (timeFromLabel ? ", labelTime" : "")
                + (failed ? ", failed" : "") + (sending ? ", sending" : "") + "}";
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
     * missing e164. Entries without a valid direction or string text are dropped. Label times are
     * read in the device time zone.
     */
    public static Report parseReport(String json) {
        return parseReport(json, TimeZone.getDefault());
    }

    /** As {@link #parseReport(String)}, reading label times in {@code tz}. */
    public static Report parseReport(String json, TimeZone tz) {
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
        return new Report((String) e, parseList((JSONArray) b, tz));
    }

    static List<ThreadBubble> parseList(JSONArray arr) {
        return parseList(arr, TimeZone.getDefault());
    }

    static List<ThreadBubble> parseList(JSONArray arr, TimeZone tz) {
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
            boolean fromLabel = false;
            if (time == null) {
                Object lv = x.opt("label");
                if (lv instanceof String) {
                    time = GvBubbleLabel.parseTimeMs((String) lv, tz);
                    fromLabel = time != null;
                }
            }
            boolean failed = Boolean.TRUE.equals(x.opt("failed"));
            boolean sending = Boolean.TRUE.equals(x.opt("sending"));
            out.add(new ThreadBubble(dir, time, (String) t, id, fromLabel, failed, sending));
        }
        return Collections.unmodifiableList(out);
    }
}
