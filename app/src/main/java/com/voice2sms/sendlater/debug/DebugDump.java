package com.voice2sms.sendlater.debug;

import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import java.util.List;

/**
 * Debug-only JSON rendering of the Send Later state for the smoke script (DEBUG_DUMP). Pure JVM.
 *
 * <p>Field order is fixed ({@code "id"} then {@code "status"} first in each record) so a shell
 * script can grep {@code "id":N,"status":"HELD"} without a JSON parser. Message text is never
 * written: only its SHA-256 token hash and length. Phone numbers are masked to the last 4 digits.
 */
public final class DebugDump {

    private DebugDump() {}

    /** A stored thread event with the conversation key it belongs to. */
    public static final class KeyedEvent {
        final String conversationKey;
        final ThreadEvent event;

        public KeyedEvent(String conversationKey, ThreadEvent event) {
            this.conversationKey = conversationKey;
            this.event = event;
        }
    }

    public static String toJson(long nowMs, List<ScheduledMessage> records, List<KeyedEvent> events) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"now\":").append(nowMs).append(",\"records\":[");
        boolean first = true;
        if (records != null) {
            for (ScheduledMessage m : records) {
                if (m == null) continue;
                if (!first) sb.append(',');
                first = false;
                record(sb, m);
            }
        }
        sb.append("],\"events\":[");
        first = true;
        if (events != null) {
            for (KeyedEvent k : events) {
                if (k == null || k.event == null) continue;
                if (!first) sb.append(',');
                first = false;
                event(sb, k);
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void record(StringBuilder sb, ScheduledMessage m) {
        sb.append("{\"id\":").append(m.getId());
        str(sb, "status", name(m.getStatus()));
        str(sb, "holdReason", name(m.getHoldReason()));
        num(sb, "scheduledFor", m.getScheduledFor());
        num(sb, "createdAt", m.getCreatedAt());
        str(sb, "e164", mask(m.getRecipientE164()));
        str(sb, "textHash", ThreadEvent.hashOf(m.getText()));
        num(sb, "textLen", m.getText() == null ? 0 : m.getText().length());
        str(sb, "rung", name(m.getRung()));
        str(sb, "source", name(m.getSource()));
        sb.append(",\"holdIfReplied\":").append(m.isHoldIfReplied());
        str(sb, "lateness", name(m.getLatenessPolicy()));
        num(sb, "attemptCount", m.getAttemptCount());
        str(sb, "lastError", m.getLastError());
        sb.append(",\"watermark\":");
        Watermark w = m.getWatermark();
        if (w == null) {
            sb.append("null");
        } else {
            sb.append("{\"quality\":").append(quote(name(w.getQuality())));
            str(sb, "newestServerId", w.getNewestServerId());
            sb.append(",\"newestTs\":").append(w.getNewestTs() == null ? "null" : w.getNewestTs().toString());
            str(sb, "bubbleHash", w.getBubbleHash());
            num(sb, "capturedAtDevice", w.getCapturedAtDevice());
            sb.append(",\"skewEstimateMs\":")
                    .append(w.getSkewEstimateMs() == null ? "null" : w.getSkewEstimateMs().toString());
            sb.append('}');
        }
        sb.append('}');
    }

    private static void event(StringBuilder sb, KeyedEvent k) {
        ThreadEvent e = k.event;
        sb.append("{\"key\":").append(quote(mask(k.conversationKey)));
        str(sb, "direction", name(e.getDirection()));
        str(sb, "kind", name(e.getKind()));
        num(sb, "timeMs", e.getTimeMs());
        str(sb, "textHash", e.getTextHash());
        sb.append('}');
    }

    /**
     * Masks a phone number or conversation key: E.164 numbers keep only their last 4 digits
     * ({@code +…1234}); name keys keep only the {@code name:} prefix.
     */
    public static String mask(String s) {
        if (s == null) return null;
        if (s.startsWith("name:")) return "name:…";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        if (digits.length() <= 4) return "…";
        return "+…" + digits.substring(digits.length() - 4);
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    private static void str(StringBuilder sb, String key, String value) {
        sb.append(",\"").append(key).append("\":").append(quote(value));
    }

    private static void num(StringBuilder sb, String key, long value) {
        sb.append(",\"").append(key).append("\":").append(value);
    }

    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
