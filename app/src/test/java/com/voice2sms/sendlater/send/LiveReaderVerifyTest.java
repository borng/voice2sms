package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.web.ThreadBubble;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LIVE-F9: verification on the real GV web page (minute label times, no ids, an echoing test
 * number, failed bubbles). Texts are fictional.
 */
public class LiveReaderVerifyTest {

    private static final long MIN = 60_000L;
    private static final long CHECK = 1_800_000_000_000L - (1_800_000_000_000L % MIN) + 10_000L;   // :10 past a minute
    private static final long SEND = CHECK + 20_000L;
    private static final long MINUTE = CHECK - 10_000L;   // the label's minute for CHECK and SEND
    private static final String BODY = "Running ten minutes late, save me a seat";
    private static final String HASH = ThreadEvent.hashOf(BODY);

    private static ThreadEvent ev(ThreadEvent.Direction d, long t, String text) {
        return new ThreadEvent(d, ThreadEvent.Kind.SMS, t, ThreadEvent.hashOf(text), text);
    }

    private static ThreadBubble lb(ThreadEvent.Direction d, long t, String text) {
        return new ThreadBubble(d, t, text, null, true, false);
    }

    private static ThreadBubble failed(long t, String text) {
        return new ThreadBubble(ThreadEvent.Direction.OUT, t, text, null, true, true);
    }

    // ---------------------------------------------------------------- SendVerifier

    @Test public void echoAfterOurSend_isVerified_notLateReply() {
        List<ThreadEvent> l = Arrays.asList(
                ev(ThreadEvent.Direction.OUT, MINUTE + MIN, BODY),
                ev(ThreadEvent.Direction.IN, MINUTE + MIN, BODY));
        assertEquals(SendVerifier.Result.VERIFIED, SendVerifier.verify(HASH, l, CHECK, SEND + MIN));
    }

    @Test public void genuineDifferentReplyBeforeOurs_isLateReply() {
        List<ThreadEvent> l = Arrays.asList(
                ev(ThreadEvent.Direction.IN, MINUTE + MIN, "Where are you?"),
                ev(ThreadEvent.Direction.OUT, MINUTE + MIN, BODY));
        assertEquals(SendVerifier.Result.VERIFIED_WITH_LATE_REPLY, SendVerifier.verify(HASH, l, CHECK, SEND + MIN));
    }

    @Test public void inboundWithOurTextAlone_neverVerifies() {
        List<ThreadEvent> l = Arrays.asList(ev(ThreadEvent.Direction.IN, MINUTE + MIN, BODY));
        assertEquals(SendVerifier.Result.UNVERIFIED, SendVerifier.verify(HASH, l, CHECK, SEND));
    }

    // ---------------------------------------------------------------- ThreadBaseline / SendWatch

    @Test public void echoOfAnOldOutbound_isNotALateReply() {
        // Old outbound "Earlier note" echoed back after the check; then our own send.
        List<ThreadBubble> later = Arrays.asList(
                lb(ThreadEvent.Direction.OUT, MINUTE - MIN, "Earlier note"),
                lb(ThreadEvent.Direction.IN, MINUTE + MIN, "Earlier note"),
                lb(ThreadEvent.Direction.OUT, MINUTE + MIN, BODY));
        List<ThreadEvent> evs = ThreadBaseline.verificationEvents(HASH, later, 1, SEND + MIN);
        assertEquals(SendVerifier.Result.VERIFIED, SendVerifier.verify(HASH, evs, CHECK, SEND + MIN));
    }

    @Test public void failedBubble_neverVerifies() {
        List<ThreadBubble> before = Arrays.asList(lb(ThreadEvent.Direction.IN, MINUTE - 5 * MIN, "hello"));
        List<ThreadBubble> after = new ArrayList<>(before);
        after.add(failed(MINUTE, BODY));
        SendWatch w = new SendWatch(HASH);
        assertNull(w.onPoll(before, true, CHECK));
        for (long t = SEND; t < SEND + 30_000L; t += 2_000L) {
            List<ThreadEvent> evs = w.onPoll(after, false, t);
            assertNotNull(evs);
            SendVerifier.Result v = SendVerifier.verify(HASH, evs, CHECK, SEND);
            assertEquals(SendVerifier.Result.UNVERIFIED, v);
            assertNull(w.settle(v, false, t));
        }
    }

    @Test public void failedNewestOutbound_withoutBaseline_neverVerifies() {
        List<ThreadBubble> after = Arrays.asList(failed(MINUTE, BODY));
        assertEquals(-1, ThreadBaseline.newestOutboundIfOwn(HASH, after));
    }

    @Test public void labelTime_mustSettle_notConfirmedAtOnce() {
        List<ThreadBubble> before = Arrays.asList(lb(ThreadEvent.Direction.IN, MINUTE - 5 * MIN, "hello"));
        List<ThreadBubble> after = new ArrayList<>(before);
        after.add(lb(ThreadEvent.Direction.OUT, MINUTE, BODY));
        after.add(lb(ThreadEvent.Direction.IN, MINUTE, BODY));   // the echo
        SendWatch w = new SendWatch(HASH);
        assertNull(w.onPoll(before, true, CHECK));
        List<ThreadEvent> evs = w.onPoll(after, false, SEND);
        SendVerifier.Result v = SendVerifier.verify(HASH, evs, CHECK, SEND);
        assertEquals(SendVerifier.Result.VERIFIED, v);
        assertNull("one sighting of a label-timed bubble decides nothing", w.settle(v, false, SEND));
        evs = w.onPoll(after, false, SEND + SendWatch.SETTLE_MS);
        v = SendVerifier.verify(HASH, evs, CHECK, SEND);
        assertEquals(SendVerifier.Result.VERIFIED, w.settle(v, false, SEND + SendWatch.SETTLE_MS));
    }

    @Test public void pageTime_stillConfirmsAtOnce() {
        List<ThreadBubble> before = Arrays.asList(new ThreadBubble(ThreadEvent.Direction.IN, CHECK - 5 * MIN, "hello", null));
        List<ThreadBubble> after = new ArrayList<>(before);
        after.add(new ThreadBubble(ThreadEvent.Direction.OUT, SEND, BODY, null));
        SendWatch w = new SendWatch(HASH);
        assertNull(w.onPoll(before, true, CHECK));
        List<ThreadEvent> evs = w.onPoll(after, false, SEND);
        SendVerifier.Result v = SendVerifier.verify(HASH, evs, CHECK, SEND);
        assertTrue(v == SendVerifier.Result.VERIFIED);
        assertEquals(SendVerifier.Result.VERIFIED, w.settle(v, false, SEND));
    }
}
