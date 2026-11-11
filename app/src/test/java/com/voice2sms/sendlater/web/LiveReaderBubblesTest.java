package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import org.json.JSONObject;
import org.junit.Test;

import java.util.TimeZone;

/** LIVE-F9: ThreadBubble reports and capture payloads carrying the GV web label. Fictional data. */
public class LiveReaderBubblesTest {

    private static final String E164 = "+15555550100";
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final String OUT_LABEL = "Message from you, on my way, Thursday, November 5 2026, 3:04 PM.";
    private static final String IN_LABEL = "Message from Contact A, on my way, Thursday, November 5 2026, 3:04 PM.";
    private static final long T_1504 = GvBubbleLabel.parseTimeMs(OUT_LABEL, UTC);

    private static String bubble(String dir, String text, String label, Boolean failed) throws Exception {
        JSONObject o = new JSONObject();
        o.put("direction", dir);
        o.put("timeMs", JSONObject.NULL);
        o.put("text", text);
        o.put("label", label == null ? JSONObject.NULL : label);
        o.put("id", JSONObject.NULL);
        if (failed != null) o.put("failed", failed.booleanValue());
        return o.toString();
    }

    @Test public void report_labelGivesMinuteTime_marked() throws Exception {
        ThreadBubble.Report r = ThreadBubble.parseReport("{\"e164\":\"" + E164 + "\",\"bubbles\":["
                + bubble("OUT", "on my way", OUT_LABEL, false) + ","
                + bubble("IN", "on my way", IN_LABEL, null) + ","
                + bubble("IN", "later", "garbled", null) + "]}", UTC);
        assertNotNull(r);
        assertEquals(3, r.bubbles.size());
        ThreadBubble out = r.bubbles.get(0);
        assertEquals(Long.valueOf(T_1504), out.getTimeMs());
        assertTrue(out.isTimeFromLabel());
        assertFalse(out.isFailed());
        assertNull(out.getId());
        ThreadEvent ev = out.toThreadEvent();
        assertNotNull(ev);
        assertEquals(ThreadEvent.hashOf("on my way"), ev.getTextHash());
        assertEquals(Long.valueOf(T_1504), r.bubbles.get(1).getTimeMs());
        assertNull("unparseable label -> no time", r.bubbles.get(2).getTimeMs());
        assertFalse(r.bubbles.get(2).isTimeFromLabel());
    }

    @Test public void report_pageTimeWinsOverLabel() {
        ThreadBubble.Report r = ThreadBubble.parseReport("{\"e164\":\"" + E164 + "\",\"bubbles\":["
                + "{\"direction\":\"OUT\",\"timeMs\":1000,\"text\":\"x\",\"label\":\"" + OUT_LABEL + "\"}]}", UTC);
        assertEquals(Long.valueOf(1000), r.bubbles.get(0).getTimeMs());
        assertFalse(r.bubbles.get(0).isTimeFromLabel());
    }

    @Test public void report_failedFlag() throws Exception {
        ThreadBubble.Report r = ThreadBubble.parseReport("{\"e164\":\"" + E164 + "\",\"bubbles\":["
                + bubble("OUT", "x", OUT_LABEL, true) + "]}", UTC);
        assertTrue(r.bubbles.get(0).isFailed());
        assertTrue(r.bubbles.get(0).toString().contains("failed"));
        assertFalse("toString never shows text", r.bubbles.get(0).toString().contains("on my way"));
    }

    @Test public void report_emptyArrayIsEmptyList_notNull() {
        ThreadBubble.Report r = ThreadBubble.parseReport("{\"e164\":\"" + E164 + "\",\"bubbles\":[]}", UTC);
        assertNotNull(r.bubbles);
        assertTrue(r.bubbles.isEmpty());
    }

    @Test public void capture_labelMakesTimestampQuality_neverExact() throws Exception {
        JSONObject o = new JSONObject();
        o.put("e164", E164);
        o.put("draftText", "see you soon");
        o.put("newestMessageId", JSONObject.NULL);
        o.put("newestMessageTimeMs", JSONObject.NULL);
        o.put("newestMessageLabel", IN_LABEL);
        o.put("watermarkQuality", "EXACT");
        CapturePayload p = CapturePayload.parse(o.toString(), UTC);
        assertNotNull(p);
        assertEquals(Long.valueOf(T_1504), p.getNewestMessageTimeMs());
        assertEquals(Watermark.Quality.TIMESTAMP, p.getWatermarkQuality());
        assertFalse(p.toString().contains("on my way"));
    }

    @Test public void capture_badOrMissingLabel_deviceOnly() throws Exception {
        JSONObject o = new JSONObject();
        o.put("e164", E164);
        o.put("draftText", "see you soon");
        assertEquals(Watermark.Quality.DEVICE_ONLY, CapturePayload.parse(o.toString(), UTC).getWatermarkQuality());
        o.put("newestMessageLabel", "nope");
        assertEquals(Watermark.Quality.DEVICE_ONLY, CapturePayload.parse(o.toString(), UTC).getWatermarkQuality());
        o.put("newestMessageLabel", 42);
        assertEquals(Watermark.Quality.DEVICE_ONLY, CapturePayload.parse(o.toString(), UTC).getWatermarkQuality());
    }
}
