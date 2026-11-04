package com.voice2sms.sendlater.voice.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import org.junit.Test;

/** R3: bubble content descriptions -> ThreadEvent; list -> TIMESTAMP watermark or DEVICE_ONLY. */
public class VoiceBubbleParserTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    /** 2026-11-04 20:00 local. */
    private static final long NOW = at(2026, Calendar.NOVEMBER, 4, 20, 0);

    private static long at(int y, int month, int d, int h, int m) {
        Calendar c = Calendar.getInstance(ZONE);
        c.clear();
        c.set(y, month, d, h, m, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void fromMeIsOutboundTodayWithTextHash() {
        ThreadEvent e = VoiceBubbleParser.parse("Running late, be there soon from me, 6:46 PM", NOW, ZONE);
        assertNotNull(e);
        assertEquals(ThreadEvent.Direction.OUT, e.getDirection());
        assertEquals(at(2026, Calendar.NOVEMBER, 4, 18, 46), e.getTimeMs());
        assertEquals(ThreadEvent.hashOf("Running late, be there soon"), e.getTextHash());
    }

    @Test
    public void fromNameIsInboundTodayWithTextHash() {
        ThreadEvent e = VoiceBubbleParser.parse("Sounds good from Test Contact, 6:45 PM", NOW, ZONE);
        assertNotNull(e);
        assertEquals(ThreadEvent.Direction.IN, e.getDirection());
        assertEquals(at(2026, Calendar.NOVEMBER, 4, 18, 45), e.getTimeMs());
        assertEquals(ThreadEvent.hashOf("Sounds good"), e.getTextHash());
    }

    @Test
    public void morningTimeIsParsedAsAm() {
        ThreadEvent e = VoiceBubbleParser.parse("Morning from Test Contact, 9:05 AM", NOW, ZONE);
        assertNotNull(e);
        assertEquals(at(2026, Calendar.NOVEMBER, 4, 9, 5), e.getTimeMs());
    }

    @Test
    public void datedFormFromMeUsesTheDate() {
        ThreadEvent e = VoiceBubbleParser.parse("See you Thursday from me, Oct 28, 2:43 PM", NOW, ZONE);
        assertNotNull(e);
        assertEquals(ThreadEvent.Direction.OUT, e.getDirection());
        assertEquals(at(2026, Calendar.OCTOBER, 28, 14, 43), e.getTimeMs());
        assertEquals(ThreadEvent.hashOf("See you Thursday"), e.getTextHash());
    }

    @Test
    public void datedFormFromNameUsesTheDate() {
        ThreadEvent e = VoiceBubbleParser.parse("Thanks from Test Contact, Oct 28, 2:40 PM", NOW, ZONE);
        assertNotNull(e);
        assertEquals(ThreadEvent.Direction.IN, e.getDirection());
        assertEquals(at(2026, Calendar.OCTOBER, 28, 14, 40), e.getTimeMs());
        assertEquals(ThreadEvent.hashOf("Thanks"), e.getTextHash());
    }

    @Test
    public void watermarkIsNewestBubbleWithTimestampQuality() {
        List<String> descs = Arrays.asList(
                "Thanks from Test Contact, Oct 28, 2:40 PM",
                "See you Thursday from me, Oct 28, 2:43 PM",
                "Sounds good from Test Contact, 6:45 PM",
                "Running late, be there soon from me, 6:46 PM");
        Watermark w = VoiceBubbleParser.bubbleWatermark(descs, NOW, ZONE);
        assertNotNull(w);
        assertEquals(Watermark.Quality.TIMESTAMP, w.getQuality());
        assertEquals(Long.valueOf(at(2026, Calendar.NOVEMBER, 4, 18, 46)), w.getNewestTs());
        assertNotNull(w.getBubbleHash());
    }

    @Test
    public void watermarkBubbleHashDependsOnNewestBubbleText() {
        Watermark a = VoiceBubbleParser.bubbleWatermark(
                Arrays.asList("Sounds good from Test Contact, 6:45 PM", "On my way from me, 6:46 PM"), NOW, ZONE);
        Watermark b = VoiceBubbleParser.bubbleWatermark(
                Arrays.asList("Sounds good from Test Contact, 6:45 PM", "Be right there from me, 6:46 PM"), NOW, ZONE);
        assertNotNull(a.getBubbleHash());
        assertNotNull(b.getBubbleHash());
        assertNotEquals(a.getBubbleHash(), b.getBubbleHash());
    }

    @Test
    public void emptyListIsDeviceOnly() {
        Watermark w = VoiceBubbleParser.bubbleWatermark(new ArrayList<String>(), NOW, ZONE);
        assertNotNull(w);
        assertEquals(Watermark.Quality.DEVICE_ONLY, w.getQuality());
        assertNull(w.getNewestTs());
    }
}
