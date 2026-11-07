package com.voice2sms.sendlater.web;

import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.voice.logic.PhoneNumbers;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.TimeZone;

/**
 * The JSON inject.js hands to {@code V2SBridge.requestSendLater} (R10):
 * {@code {e164, displayName, draftText, newestMessageId?, newestMessageTimeMs?, watermarkQuality}}.
 *
 * <p>The watermark quality is derived from the fields actually present and never trusted from
 * the page: EXACT needs id + time, TIMESTAMP needs time, anything else is DEVICE_ONLY. A display
 * name is never accepted as the recipient (I3). The draft is kept exactly as typed.
 * {@link #toString()} redacts the draft and name.
 *
 * <p>LIVE-F9: GV web shows no bubble time or id, so the page may send
 * {@code newestMessageLabel} (the newest bubble's screen-reader label) instead of a time. Java
 * parses its minute ({@link GvBubbleLabel}); a capture with a parsed label is TIMESTAMP quality,
 * never EXACT (there is no id). The label holds message text and is not kept.
 */
public final class CapturePayload {

    private final String e164;
    private final String displayName;
    private final String draftText;
    private final String newestMessageId;
    private final Long newestMessageTimeMs;
    private final Watermark.Quality watermarkQuality;

    private CapturePayload(String e164, String displayName, String draftText,
                           String newestMessageId, Long newestMessageTimeMs) {
        this.e164 = e164;
        this.displayName = displayName;
        this.draftText = draftText;
        this.newestMessageId = newestMessageId;
        this.newestMessageTimeMs = newestMessageTimeMs;
        this.watermarkQuality = qualityOf(newestMessageId, newestMessageTimeMs);
    }

    /** Parsed payload, or null when the JSON is malformed or fails validation. */
    public static CapturePayload parse(String json) {
        return parse(json, TimeZone.getDefault());
    }

    /** As {@link #parse(String)}, reading a label time in {@code tz}. */
    public static CapturePayload parse(String json, TimeZone tz) {
        if (json == null || json.trim().isEmpty()) return null;
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            return null;
        }
        String e164 = string(o, "e164");
        if (!PhoneNumbers.isE164(e164)) return null;
        String draft = string(o, "draftText");
        if (draft == null || draft.isEmpty()) return null;
        String name = string(o, "displayName");
        if (name != null && name.trim().isEmpty()) name = null;
        String id = string(o, "newestMessageId");
        if (id != null && id.isEmpty()) id = null;
        Long time = null;
        Object t = o.opt("newestMessageTimeMs");
        if (t instanceof Number) {
            double d = ((Number) t).doubleValue();
            long l = ((Number) t).longValue();
            if (d > 0 && !Double.isNaN(d) && !Double.isInfinite(d)) time = l;
        }
        if (time == null) time = GvBubbleLabel.parseTimeMs(string(o, "newestMessageLabel"), tz);
        return new CapturePayload(e164, name, draft, id, time);
    }

    /** EXACT only with id + time, TIMESTAMP with time only, DEVICE_ONLY otherwise. */
    public static Watermark.Quality qualityOf(String newestId, Long newestTimeMs) {
        if (newestTimeMs == null) return Watermark.Quality.DEVICE_ONLY;
        return newestId != null && !newestId.isEmpty()
                ? Watermark.Quality.EXACT : Watermark.Quality.TIMESTAMP;
    }

    private static String string(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : null;
    }

    public String getE164() { return e164; }
    public String getDisplayName() { return displayName; }
    public String getDraftText() { return draftText; }
    public String getNewestMessageId() { return newestMessageId; }
    public Long getNewestMessageTimeMs() { return newestMessageTimeMs; }
    public Watermark.Quality getWatermarkQuality() { return watermarkQuality; }

    /** GV thread item id for this capture ("t." + E.164). */
    public String threadItemId() { return "t." + e164; }

    @Override
    public String toString() {
        return "CapturePayload{quality=" + watermarkQuality + ", draftLen=" + draftText.length()
                + ", hasName=" + (displayName != null) + "}";
    }
}
