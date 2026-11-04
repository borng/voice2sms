package com.voice2sms.sendlater.voice.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Which visible bubbles count as new inbound for the R33 early hold. */
public class EarlyHoldFilterTest {

    private static final long MIN = 60_000L;
    private static final long T = 1_793_000_040_000L - (1_793_000_040_000L % MIN);

    private static ThreadEvent in(long t, String text) {
        return new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.SMS, t, ThreadEvent.hashOf(text), text);
    }

    private static ThreadEvent out(long t, String text) {
        return new ThreadEvent(ThreadEvent.Direction.OUT, ThreadEvent.Kind.SMS, t, ThreadEvent.hashOf(text), text);
    }

    private static Watermark ts(ThreadEvent newest) {
        return new Watermark(Watermark.Quality.TIMESTAMP, null, newest.getTimeMs(), newest.getTextHash(), T + 30_000L, null);
    }

    @Test public void watermarkBubbleItselfIsNotAReply() {
        ThreadEvent last = in(T, "see you");
        List<ThreadEvent> got = EarlyHoldFilter.newInbound(Arrays.asList(in(T - MIN, "hi"), last), ts(last), 0L);
        assertTrue(got.isEmpty());
    }

    @Test public void sameMinuteReplyBelowTheWatermarkBubbleCounts() {
        ThreadEvent last = in(T, "see you");
        ThreadEvent reply = in(T, "actually wait");
        List<ThreadEvent> got = EarlyHoldFilter.newInbound(Arrays.asList(last, null, reply), ts(last), 0L);
        assertEquals(Collections.singletonList(reply), got);
    }

    @Test public void outboundNeverCounts() {
        ThreadEvent last = in(T, "see you");
        assertTrue(EarlyHoldFilter.newInbound(Arrays.asList(last, out(T + MIN, "ok")), ts(last), 0L).isEmpty());
    }

    @Test public void watermarkBubbleScrolledAwayUsesStrictlyNewerTime() {
        ThreadEvent last = in(T, "see you");
        ThreadEvent sameMinute = in(T, "other");
        ThreadEvent later = in(T + MIN, "later");
        List<ThreadEvent> got = EarlyHoldFilter.newInbound(Arrays.asList(sameMinute, later), ts(last), 0L);
        assertEquals(Collections.singletonList(later), got);
    }

    @Test public void deviceOnlyCountsFromTheCaptureMinute() {
        Watermark wm = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, T + 30_000L, null);
        ThreadEvent before = in(T - MIN, "old");
        ThreadEvent same = in(T, "new");
        assertEquals(Collections.singletonList(same),
                EarlyHoldFilter.newInbound(Arrays.asList(before, same), wm, 0L));
        assertEquals("null watermark falls back to createdAt", Collections.singletonList(same),
                EarlyHoldFilter.newInbound(Arrays.asList(before, same), null, T + 10_000L));
    }

    @Test public void nothingVisibleNothingNew() {
        assertTrue(EarlyHoldFilter.newInbound(null, null, 0L).isEmpty());
        assertTrue(EarlyHoldFilter.newInbound(Collections.<ThreadEvent>emptyList(), null, 0L).isEmpty());
    }
}
