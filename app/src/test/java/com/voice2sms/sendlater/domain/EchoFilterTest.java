package com.voice2sms.sendlater.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** LIVE-F9 echo rule. Texts are fictional. */
public class EchoFilterTest {

    private static final long T = 1_800_000_000_000L;

    private static ThreadEvent ev(ThreadEvent.Direction d, long t, String text) {
        return new ThreadEvent(d, ThreadEvent.Kind.SMS, t, ThreadEvent.hashOf(text), text);
    }

    private static ThreadEvent out(long t, String text) { return ev(ThreadEvent.Direction.OUT, t, text); }
    private static ThreadEvent in(long t, String text) { return ev(ThreadEvent.Direction.IN, t, text); }

    @Test public void sameTextSameMinute_isEcho() {
        List<ThreadEvent> l = Arrays.asList(out(T, "Leaving now"), in(T, "leaving now!"));
        assertTrue(EchoFilter.isEcho(l, 1));
        assertFalse("an outbound is never an echo", EchoFilter.isEcho(l, 0));
        assertEquals(1, EchoFilter.withoutEchoes(l).size());
    }

    @Test public void withinTwoMinutes_otherOutboundBetween_isEcho() {
        List<ThreadEvent> l = Arrays.asList(out(T, "a"), out(T + 30_000, "b"), in(T + EchoFilter.WINDOW_MS, "a"));
        assertTrue(EchoFilter.isEcho(l, 2));
    }

    @Test public void olderThanTwoMinutes_isNotEcho() {
        List<ThreadEvent> l = Arrays.asList(out(T, "a"), in(T + EchoFilter.WINDOW_MS + 1, "a"));
        assertFalse(EchoFilter.isEcho(l, 1));
    }

    @Test public void inboundBetween_isNotEcho() {
        List<ThreadEvent> l = Arrays.asList(out(T, "a"), in(T, "b"), in(T + 1_000, "a"));
        assertFalse(EchoFilter.isEcho(l, 2));
    }

    @Test public void secondIdenticalInbound_isNotEcho() {
        List<ThreadEvent> l = Arrays.asList(out(T, "a"), in(T, "a"), in(T, "a"));
        boolean[] f = EchoFilter.echoFlags(l);
        assertTrue(f[1]);
        assertFalse(f[2]);
    }

    @Test public void differentText_orNoHash_orBeforeOutbound_isNotEcho() {
        assertFalse(EchoFilter.isEcho(Arrays.asList(out(T, "a"), in(T, "c")), 1));
        ThreadEvent noHash = new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.CALL, T, null, null);
        assertFalse(EchoFilter.isEcho(Arrays.asList(out(T, "a"), noHash), 1));
        assertFalse("inbound earlier than the outbound", EchoFilter.isEcho(Arrays.asList(out(T, "a"), in(T - 60_000, "a")), 1));
        assertFalse(EchoFilter.isEcho(Arrays.asList(in(T, "a")), 0));
        assertFalse(EchoFilter.isEcho(null, 0));
        assertEquals(0, EchoFilter.withoutEchoes(null).size());
    }
}
