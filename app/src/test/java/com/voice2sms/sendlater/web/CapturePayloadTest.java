package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.voice2sms.sendlater.domain.Watermark;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Acceptance (webview-capture, R10/I3): the JSON inject.js hands to V2SBridge.requestSendLater.
 * Schema {e164, displayName, draftText, newestMessageId?, newestMessageTimeMs?, watermarkQuality}.
 * The watermark quality is derived from what is actually present, never trusted from the page:
 * EXACT needs id + time, TIMESTAMP needs time, anything else is DEVICE_ONLY.
 *
 * Contract used here: {@code CapturePayload.parse(String json)} returns the payload, or rejects
 * by throwing (any exception) or returning null. Getters: getE164, getDisplayName, getDraftText,
 * getNewestMessageId (String, null when absent), getNewestMessageTimeMs (Long, null when absent),
 * getWatermarkQuality (Watermark.Quality). Numbers are fictional 555-01xx.
 */
public class CapturePayloadTest {

    private static final String E164 = "+15555550123";

    private static JSONObject base() throws Exception {
        JSONObject o = new JSONObject();
        o.put("e164", E164);
        o.put("displayName", "Test Contact");
        o.put("draftText", "running late, there in 10");
        return o;
    }

    private static CapturePayload parseOk(JSONObject o) throws Exception {
        CapturePayload p = CapturePayload.parse(o.toString());
        assertNotNull("valid payload was rejected: " + o, p);
        return p;
    }

    private static void assertRejected(String json) {
        CapturePayload p;
        try {
            p = CapturePayload.parse(json);
        } catch (Exception expected) {
            return;
        }
        if (p != null) fail("payload should have been rejected: " + json);
    }

    // ---------------------------------------------------------------- valid

    @Test
    public void validPayload_withIdAndTime_parsesAsExact() throws Exception {
        JSONObject o = base();
        o.put("newestMessageId", "msg-abc-123");
        o.put("newestMessageTimeMs", 1762300000000L);
        o.put("watermarkQuality", "EXACT");
        CapturePayload p = parseOk(o);
        assertEquals(E164, p.getE164());
        assertEquals("Test Contact", p.getDisplayName());
        assertEquals("running late, there in 10", p.getDraftText());
        assertEquals("msg-abc-123", p.getNewestMessageId());
        assertEquals(Long.valueOf(1762300000000L), p.getNewestMessageTimeMs());
        assertEquals(Watermark.Quality.EXACT, p.getWatermarkQuality());
    }

    @Test
    public void draftText_isKeptExactly_unicodeNewlinesAndOuterSpaces() throws Exception {
        String draft = "  café 😀\nsecond line\t ";
        JSONObject o = base();
        o.put("draftText", draft);
        o.put("watermarkQuality", "TIMESTAMP");
        o.put("newestMessageTimeMs", 1762300000000L);
        assertEquals(draft, parseOk(o).getDraftText());
    }

    // ---------------------------------------------------------------- watermark quality

    @Test
    public void timeOnly_isTimestamp_evenWhenThePageClaimsExact() throws Exception {
        JSONObject o = base();
        o.put("newestMessageTimeMs", 1762300000000L);
        o.put("watermarkQuality", "EXACT");
        CapturePayload p = parseOk(o);
        assertEquals(Watermark.Quality.TIMESTAMP, p.getWatermarkQuality());
        assertNull(p.getNewestMessageId());
        assertEquals(Long.valueOf(1762300000000L), p.getNewestMessageTimeMs());
    }

    @Test
    public void idWithoutTime_isDeviceOnly() throws Exception {
        JSONObject o = base();
        o.put("newestMessageId", "msg-abc-123");
        o.put("watermarkQuality", "EXACT");
        CapturePayload p = parseOk(o);
        assertEquals(Watermark.Quality.DEVICE_ONLY, p.getWatermarkQuality());
        assertNull(p.getNewestMessageTimeMs());
    }

    @Test
    public void neitherIdNorTime_isDeviceOnly_evenWhenThePageClaimsTimestamp() throws Exception {
        JSONObject o = base();
        o.put("watermarkQuality", "TIMESTAMP");
        CapturePayload p = parseOk(o);
        assertEquals(Watermark.Quality.DEVICE_ONLY, p.getWatermarkQuality());
        assertNull(p.getNewestMessageId());
        assertNull(p.getNewestMessageTimeMs());
    }

    @Test
    public void jsonNullIdAndTime_countAsAbsent() throws Exception {
        JSONObject o = base();
        o.put("newestMessageId", JSONObject.NULL);
        o.put("newestMessageTimeMs", JSONObject.NULL);
        o.put("watermarkQuality", "EXACT");
        CapturePayload p = parseOk(o);
        assertEquals(Watermark.Quality.DEVICE_ONLY, p.getWatermarkQuality());
        assertNull(p.getNewestMessageId());
        assertNull(p.getNewestMessageTimeMs());
    }

    // ---------------------------------------------------------------- rejected

    @Test
    public void missingE164_rejected() throws Exception {
        JSONObject o = base();
        o.remove("e164");
        o.put("watermarkQuality", "TIMESTAMP");
        assertRejected(o.toString());
    }

    @Test
    public void nullOrBlankE164_rejected() throws Exception {
        JSONObject o = base();
        o.put("watermarkQuality", "TIMESTAMP");
        o.put("e164", JSONObject.NULL);
        assertRejected(o.toString());
        o.put("e164", "");
        assertRejected(o.toString());
    }

    @Test
    public void displayNameInPlaceOfNumber_rejected() throws Exception {
        // I3: a display name alone is never a recipient.
        JSONObject o = base();
        o.put("e164", "Test Contact");
        o.put("watermarkQuality", "TIMESTAMP");
        assertRejected(o.toString());
    }

    @Test
    public void missingDraft_rejected() throws Exception {
        JSONObject o = base();
        o.remove("draftText");
        o.put("watermarkQuality", "TIMESTAMP");
        assertRejected(o.toString());
    }

    @Test
    public void emptyDraft_rejected() throws Exception {
        JSONObject o = base();
        o.put("draftText", "");
        o.put("watermarkQuality", "TIMESTAMP");
        assertRejected(o.toString());
    }

    @Test
    public void malformedOrNullJson_rejected() {
        assertRejected(null);
        assertRejected("");
        assertRejected("{not json");
        assertRejected("[]");
    }
}
