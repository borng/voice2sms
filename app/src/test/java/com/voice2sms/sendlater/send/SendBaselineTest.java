package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEvent.Direction;
import com.voice2sms.sendlater.web.ThreadBubble;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fix pass 1: only a NEW own bubble beyond a pre-send baseline verifies (R31), and the engine's
 * post-send read dates old untimed bubbles as old.
 */
public class SendBaselineTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long CHECK = NOW - 20_000L;
    private static final String BODY = "Running ten minutes late, save me a seat please";
    private static final String HASH = ThreadEvent.hashOf(BODY);
    private static final String E164 = "+15550100000";

    private static ThreadBubble out(Long t, String text) { return new ThreadBubble(Direction.OUT, t, text, null); }
    private static ThreadBubble in(Long t, String text) { return new ThreadBubble(Direction.IN, t, text, null); }

    private static List<ThreadBubble> list(ThreadBubble... b) { return new ArrayList<>(Arrays.asList(b)); }

    private static SendVerifier.Result verdict(SendWatch w, List<ThreadBubble> bubbles, boolean pending) {
        List<ThreadEvent> ev = w.onPoll(bubbles, pending, NOW);
        return ev == null ? null : SendVerifier.verify(HASH, ev, CHECK, NOW);
    }

    // ------------------------------------------------------------------ false verification (HIGH)

    @Test public void anUntimedIdenticalOldBubbleNeverVerifies() {
        SendWatch w = new SendWatch(HASH);
        List<ThreadBubble> thread = list(in(NOW - 3_600_000L, "see you"), out(null, BODY));
        assertNull("baseline read", verdict(w, thread, true));
        assertEquals("the same old bubble, before inject.js tapped Send", SendVerifier.Result.UNVERIFIED,
                verdict(w, thread, true));
        assertEquals("still only the old bubble after the box cleared", SendVerifier.Result.UNVERIFIED,
                verdict(w, thread, false));
    }

    @Test public void aTimedIdenticalBubbleInsideTheClockSlackNeverVerifies() {
        SendWatch w = new SendWatch(HASH);
        List<ThreadBubble> thread = list(out(CHECK - 60_000L, BODY));
        assertNull(verdict(w, thread, true));
        assertEquals("a minute-old identical bubble is not this send", SendVerifier.Result.UNVERIFIED,
                verdict(w, thread, false));
    }

    @Test public void aNewOwnBubbleBeyondTheBaselineVerifies() {
        SendWatch w = new SendWatch(HASH);
        assertNull(verdict(w, list(in(NOW - 3_600_000L, "see you"), out(null, BODY)), true));
        assertEquals(SendVerifier.Result.VERIFIED,
                verdict(w, list(in(NOW - 3_600_000L, "see you"), out(null, BODY), out(null, BODY)), false));
    }

    @Test public void aReadBeforeTheTextIsTypedIsNotABaseline() {
        SendWatch w = new SendWatch(HASH);
        assertNull("thread open, box still empty (inject.js not filled yet)",
                verdict(w, list(out(null, BODY)), false));
        assertNull(w.baseline());
        assertNull(verdict(w, list(out(null, BODY)), true));
        assertNotNull(w.baseline());
    }

    @Test public void aBaselineThatCannotBeAlignedNeverVerifies() {
        SendWatch w = new SendWatch(HASH);
        assertNull(verdict(w, list(in(NOW - 7_200_000L, "a"), in(NOW - 3_600_000L, "b")), true));
        assertEquals("a different window of the thread: nothing counts as new", SendVerifier.Result.UNVERIFIED,
                verdict(w, list(out(null, BODY)), false));
    }

    @Test public void aRepeatedTextUsesTheLatestAlignment() {
        ThreadBaseline single = new ThreadBaseline(list(out(null, BODY)));
        assertEquals("one identical bubble: aligned at the latest copy, nothing new", 1,
                single.newStart(list(out(null, BODY))));
        ThreadBaseline two = new ThreadBaseline(list(in(1L, "a"), out(null, BODY)));
        assertEquals(2, two.newStart(list(in(1L, "a"), out(null, BODY), out(null, BODY))));
        assertEquals(-1, two.newStart(null));
    }

    @Test public void aSendBeforeTheThreadWasReadableVerifiesOnTheNewestOwnBubble() {
        SendWatch w = new SendWatch(HASH);
        assertNull("new conversation: no thread page while the text is pending", verdict(w, null, true));
        assertEquals(SendVerifier.Result.VERIFIED, verdict(w, list(out(null, BODY)), false));

        SendWatch other = new SendWatch(HASH);
        assertNull(verdict(other, null, true));
        assertEquals("newest outbound is something else", SendVerifier.Result.UNVERIFIED,
                verdict(other, list(out(null, BODY), out(null, "something else")), false));

        SendWatch never = new SendWatch(HASH);
        assertNull("never saw our text pending: cannot date the read", verdict(never, list(out(null, BODY)), false));
    }

    // ------------------------------------------------------------------ late replies (MEDIUM)

    @Test public void anOldUntimedInboundIsNotALateReply() {
        SendWatch w = new SendWatch(HASH);
        List<ThreadBubble> base = list(in(NOW - 3_600_000L, "earlier"), in(null, "untimed old reply"));
        assertNull(verdict(w, base, true));
        List<ThreadBubble> after = new ArrayList<>(base);
        after.add(out(null, BODY));
        assertEquals(SendVerifier.Result.VERIFIED, verdict(w, after, false));
    }

    @Test public void aNewInboundBeforeOurBubbleIsALateReply() {
        SendWatch w = new SendWatch(HASH);
        assertNull(verdict(w, list(in(NOW - 3_600_000L, "earlier")), true));
        assertEquals(SendVerifier.Result.VERIFIED_WITH_LATE_REPLY,
                verdict(w, list(in(NOW - 3_600_000L, "earlier"), in(null, "wait"), out(null, BODY)), false));
    }

    @Test public void aNewInboundAfterOurBubbleIsNotALateReply() {
        SendWatch w = new SendWatch(HASH);
        assertNull(verdict(w, list(in(NOW - 3_600_000L, "earlier")), true));
        assertEquals(SendVerifier.Result.VERIFIED,
                verdict(w, list(in(NOW - 3_600_000L, "earlier"), out(null, BODY), in(null, "ok")), false));
    }

    @Test public void stampDatesOldUntimedBubblesAtTheirEarliestAndNewOnesAtTheirLatest() {
        List<ThreadBubble> later = list(in(NOW - 3_600_000L, "a"), in(null, "b"), out(null, BODY));
        List<ThreadEvent> e = ThreadBaseline.stamp(later, 2, NOW);
        assertEquals(NOW - 3_600_000L, e.get(1).getTimeMs());
        assertEquals(NOW, e.get(2).getTimeMs());
        List<ThreadEvent> allOld = ThreadBaseline.stamp(later, -1, NOW);
        assertEquals("unaligned: everything is old", NOW - 3_600_000L, allOld.get(2).getTimeMs());
        assertEquals(0L, ThreadBaseline.stamp(list(in(null, "x")), -1, NOW).get(0).getTimeMs());
    }

    // ------------------------------------------------------------------ timing constants (MEDIUM)

    @Test public void theActivityOutlivesTheEnginesPostSendRead() {
        // OD27: the engine no longer reads the thread; the post-outcome grace keeps its 12 s.
        assertEquals(12_000L, ScheduledSendController.FINISH_GRACE_MS);
        assertTrue(ScheduledSendController.sendDelayMs(0L) >= ScheduledSendController.MIN_SEND_DELAY_MS);
        assertEquals(10_000L, ScheduledSendController.sendDelayMs(10_000L));
    }
}
