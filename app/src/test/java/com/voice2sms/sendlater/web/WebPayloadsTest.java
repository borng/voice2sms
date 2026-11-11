package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * Implementer tests (webview-capture): ChipState JSON (R17/R18), ThreadBubble reports
 * (v2sReadThread -> onThreadRead) and DirectoryReport (R4). Numbers are fictional 555-01xx.
 */
public class WebPayloadsTest {

    private static final String E164 = "+15555550123";
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static ScheduledMessage msg(long id, long at, ScheduledMessage.Status status, HoldReason reason) {
        return new ScheduledMessage.Builder().id(id).createdAt(1L).scheduledFor(at).text("x")
                .recipientE164(E164).recipientDisplayName("Test Contact").status(status)
                .holdReason(reason).build();
    }

    // ---------------------------------------------------------------- ChipState

    @Test
    public void chip_disabled_hasNoLabel() throws Exception {
        JSONObject o = new JSONObject(ChipState.disabled());
        assertFalse(o.getBoolean("enabled"));
        assertTrue(o.isNull("label"));
        assertEquals(0, o.getInt("pendingCount"));
    }

    @Test
    public void chip_disabled_hidesEvenWithMessages() throws Exception {
        List<ScheduledMessage> p = Collections.singletonList(msg(1, 1000, ScheduledMessage.Status.SCHEDULED, null));
        JSONObject o = new JSONObject(ChipState.toJson(false, p, null, UTC));
        assertTrue(o.isNull("label"));
        assertEquals(0, o.getInt("pendingCount"));
    }

    @Test
    public void chip_nothingPending_noLabel() throws Exception {
        JSONObject o = new JSONObject(ChipState.toJson(true, new ArrayList<ScheduledMessage>(), null, UTC));
        assertTrue(o.getBoolean("enabled"));
        assertTrue(o.isNull("label"));
        assertTrue(o.isNull("nextTimeMs"));
    }

    @Test
    public void chip_severalPending_countLabelAndEarliestTime() throws Exception {
        List<ScheduledMessage> p = Arrays.asList(
                msg(1, 5000, ScheduledMessage.Status.SCHEDULED, null),
                msg(2, 3000, ScheduledMessage.Status.SCHEDULED, null));
        JSONObject o = new JSONObject(ChipState.toJson(true, p, null, UTC));
        assertEquals("2 scheduled", o.getString("label"));
        assertEquals(3000L, o.getLong("nextTimeMs"));
        assertEquals(-1, ChipState.singleMessageId(p, null));
    }

    @Test
    public void chip_held_winsAndNamesTheContact() throws Exception {
        List<ScheduledMessage> p = Collections.singletonList(msg(1, 5000, ScheduledMessage.Status.SCHEDULED, null));
        List<ScheduledMessage> h = Collections.singletonList(msg(2, 4000, ScheduledMessage.Status.HELD, HoldReason.REPLIED));
        JSONObject o = new JSONObject(ChipState.toJson(true, p, h, UTC));
        assertEquals("Held · Test Contact replied", o.getString("label"));
        assertEquals("Test Contact", o.getString("heldName"));
        assertEquals(1, o.getInt("heldCount"));
    }

    @Test
    public void chip_singleMessage_tapTargetIsItsId() {
        List<ScheduledMessage> h = Collections.singletonList(msg(7, 4000, ScheduledMessage.Status.HELD, HoldReason.REPLIED));
        assertEquals(7, ChipState.singleMessageId(null, h));
        assertEquals(-1, ChipState.singleMessageId(null, null));
    }

    // ---------------------------------------------------------------- ThreadBubble

    @Test
    public void bubbles_parseDirectionTimeTextAndId() {
        ThreadBubble.Report r = ThreadBubble.parseReport("{\"e164\":\"" + E164 + "\",\"bubbles\":["
                + "{\"direction\":\"IN\",\"timeMs\":1000,\"text\":\"hi there\",\"id\":\"a1\"},"
                + "{\"direction\":\"OUT\",\"timeMs\":null,\"text\":\"ok\",\"id\":null},"
                + "{\"direction\":\"SIDEWAYS\",\"timeMs\":5,\"text\":\"dropped\"}]}");
        assertNotNull(r);
        assertEquals(E164, r.e164);
        assertEquals(2, r.bubbles.size());
        ThreadBubble in = r.bubbles.get(0);
        assertEquals(ThreadEvent.Direction.IN, in.getDirection());
        assertEquals(Long.valueOf(1000), in.getTimeMs());
        assertEquals("a1", in.getId());
        ThreadEvent ev = in.toThreadEvent();
        assertEquals(ThreadEvent.hashOf("hi there"), ev.getTextHash());
        assertNull(r.bubbles.get(1).getTimeMs());
        assertNull(r.bubbles.get(1).getId());
        assertNull("no time -> no ThreadEvent", r.bubbles.get(1).toThreadEvent());
    }

    @Test
    public void bubbles_nullMeansNotShowingThread() {
        ThreadBubble.Report r = ThreadBubble.parseReport("{\"e164\":\"" + E164 + "\",\"bubbles\":null}");
        assertNotNull(r);
        assertNull(r.bubbles);
        assertNull(ThreadBubble.parseReport("{\"bubbles\":[]}"));
        assertNull(ThreadBubble.parseReport("not json"));
        assertNull(ThreadBubble.parseReport(null));
    }

    @Test
    public void bubble_toString_redactsText() {
        ThreadBubble b = new ThreadBubble(ThreadEvent.Direction.IN, 1L, "secret words", "id");
        assertFalse(b.toString().contains("secret"));
    }

    // ---------------------------------------------------------------- DirectoryReport

    @Test
    public void directory_keepsValidPairsOnly() {
        List<DirectoryReport.Entry> l = DirectoryReport.parse("["
                + "{\"displayName\":\"Test Contact\",\"e164\":\"" + E164 + "\"},"
                + "{\"displayName\":\"  \",\"e164\":\"+15555550124\"},"
                + "{\"displayName\":\"Name Only\",\"e164\":\"Name Only\"},"
                + "{\"displayName\":\"Other\"},"
                + "\"junk\"]");
        assertEquals(1, l.size());
        assertEquals("Test Contact", l.get(0).displayName);
        assertEquals(E164, l.get(0).e164);
        assertTrue(DirectoryReport.parse("{}").isEmpty());
        assertTrue(DirectoryReport.parse(null).isEmpty());
    }

    // ---------------------------------------------------------------- CapturePayload extras

    @Test
    public void capture_toString_redactsDraftAndName() {
        CapturePayload p = CapturePayload.parse("{\"e164\":\"" + E164
                + "\",\"displayName\":\"Test Contact\",\"draftText\":\"secret draft\"}");
        assertNotNull(p);
        assertFalse(p.toString().contains("secret"));
        assertFalse(p.toString().contains("Test Contact"));
        assertEquals("t." + E164, p.threadItemId());
    }

    @Test
    public void threadUrl_isThreadOf() {
        String url = "https://voice.google.com/u/0/messages?itemId=t.%2B15555550123";
        assertTrue(ThreadUrl.isThreadOf(url, E164));
        assertFalse(ThreadUrl.isThreadOf(url, "+15555550124"));
        assertFalse(ThreadUrl.isThreadOf(url, null));
        assertEquals(E164, ThreadUrl.oneToOneE164(url + "#frag"));
    }
}
