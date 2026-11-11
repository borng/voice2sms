package com.voice2sms.sendlater.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class DebugDumpTest {

    /** Fictional 555-01xx number, assembled so no 10-digit literal sits in this public repo. */
    private static final String NUMBER = "+1202555" + "0142";

    @Test public void rendersRecordsWatermarksAndEvents_withoutTextOrFullNumbers() throws Exception {
        ScheduledMessage m = new ScheduledMessage.Builder()
                .id(7).createdAt(10).scheduledFor(20).text("quote \" and\nnewline")
                .recipientE164(NUMBER).status(ScheduledMessage.Status.HELD)
                .holdReason(HoldReason.REPLIED)
                .watermark(new Watermark(Watermark.Quality.TIMESTAMP, null, 15L, "abc", 16L, null))
                .build();
        ThreadEvent ev = new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.SMS, 30,
                ThreadEvent.hashOf("hi there"), "hi there");
        String json = DebugDump.toJson(99, Arrays.asList(m, null),
                Collections.singletonList(new DebugDump.KeyedEvent(NUMBER, ev)));

        assertFalse(json.contains("\n"));
        assertFalse(json.contains("quote"));
        assertFalse(json.contains("hi there"));
        assertFalse(json.contains("555" + "0142"));
        assertTrue(json, json.contains("{\"id\":7,\"status\":\"HELD\""));

        JSONObject o = new JSONObject(json);
        assertEquals(99, o.getLong("now"));
        JSONArray recs = o.getJSONArray("records");
        assertEquals(1, recs.length());
        JSONObject r = recs.getJSONObject(0);
        assertEquals("REPLIED", r.getString("holdReason"));
        assertEquals("+…0142", r.getString("e164"));
        assertEquals(ThreadEvent.hashOf("quote \" and\nnewline"), r.getString("textHash"));
        assertEquals("TIMESTAMP", r.getJSONObject("watermark").getString("quality"));
        assertEquals(15L, r.getJSONObject("watermark").getLong("newestTs"));
        JSONObject e = o.getJSONArray("events").getJSONObject(0);
        assertEquals("IN", e.getString("direction"));
        assertEquals(ev.getTextHash(), e.getString("textHash"));
    }

    @Test public void maskKeepsOnlyLastFourDigits() {
        assertEquals("+…0142", DebugDump.mask(NUMBER));
        assertEquals("name:…", DebugDump.mask("name:somebody"));
        assertEquals("…", DebugDump.mask("12"));
        assertEquals(null, DebugDump.mask(null));
    }

    @Test public void quoteEscapesControlCharacters() {
        assertEquals("\"a\\\"b\\\\c\\u0001\"", DebugDump.quote("a\"b\\c\u0001"));
    }
}
