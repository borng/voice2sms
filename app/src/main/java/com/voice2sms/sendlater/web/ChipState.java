package com.voice2sms.sendlater.web;

import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.TimeZone;

/**
 * JSON answered by {@code V2SBridge.sendLaterChipState(e164)} (R17):
 * {@code {enabled, pendingCount, heldCount, nextTimeMs, heldName, label}}. {@code label} is the
 * {@link ChipLabel} text, or null when the chip is hidden. Pure JVM.
 */
public final class ChipState {

    private ChipState() {}

    /** State for a page where Send Later is off: no tab and no chip. */
    public static String disabled() {
        return toJson(false, null, null, TimeZone.getDefault());
    }

    public static String toJson(boolean enabled, List<ScheduledMessage> pending,
                                List<ScheduledMessage> held, TimeZone zone) {
        int p = enabled && pending != null ? pending.size() : 0;
        int h = enabled && held != null ? held.size() : 0;
        Long next = null;
        if (p > 0) {
            for (ScheduledMessage m : pending) {
                if (next == null || m.getScheduledFor() < next) next = m.getScheduledFor();
            }
        }
        ScheduledMessage firstHeld = h > 0 ? held.get(0) : null;
        String heldName = firstHeld != null
                ? ChipLabel.heldDisplayName(firstHeld.getRecipientDisplayName(), firstHeld.getRecipientE164())
                : null;
        String label = (p + h) > 0
                ? ChipLabel.format(p, h, next != null ? next : 0L,
                        firstHeld != null ? firstHeld.getRecipientDisplayName() : null,
                        firstHeld != null ? firstHeld.getRecipientE164() : null,
                        firstHeld != null ? firstHeld.getHoldReason() : null, zone)
                : null;
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", enabled);
            o.put("pendingCount", p);
            o.put("heldCount", h);
            o.put("nextTimeMs", next != null ? next : JSONObject.NULL);
            o.put("heldName", heldName != null ? heldName : JSONObject.NULL);
            o.put("label", label != null ? label : JSONObject.NULL);
        } catch (JSONException e) {
            return "{\"enabled\":false}";
        }
        return o.toString();
    }

    /**
     * R18 tap target: the id of the only pending/held message, or -1 when there are several
     * (the Upcoming screen filtered to the thread) or none.
     */
    public static long singleMessageId(List<ScheduledMessage> pending, List<ScheduledMessage> held) {
        int p = pending != null ? pending.size() : 0;
        int h = held != null ? held.size() : 0;
        if (p + h != 1) return -1;
        return p == 1 ? pending.get(0).getId() : held.get(0).getId();
    }
}
