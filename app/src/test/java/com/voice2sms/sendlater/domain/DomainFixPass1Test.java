package com.voice2sms.sendlater.domain;

import static com.voice2sms.sendlater.domain.Fixtures.DAY;
import static com.voice2sms.sendlater.domain.Fixtures.HOUR;
import static com.voice2sms.sendlater.domain.Fixtures.MIN;
import static com.voice2sms.sendlater.domain.Fixtures.T0;
import static com.voice2sms.sendlater.domain.Fixtures.TEXT;
import static com.voice2sms.sendlater.domain.Fixtures.events;
import static com.voice2sms.sendlater.domain.Fixtures.inboundSms;
import static com.voice2sms.sendlater.domain.Fixtures.message;
import static com.voice2sms.sendlater.domain.Fixtures.none;
import static com.voice2sms.sendlater.domain.Fixtures.outboundSms;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/** Domain review fix pass 1: F1, F2, F3/OD20(5), OD20(1), F4, F5/OD20(6). */
public class DomainFixPass1Test {

    private static final long AHEAD = 3 * HOUR;
    private static final long DUE = T0 + AHEAD;
    private static final long ON_TIME = DUE + MIN;

    private static Decision eval(ScheduledMessage m, List<ThreadEvent> events, long now) {
        return LadderEvaluator.evaluate(m, Rung.R1, events, now,
                SessionState.ready(), NetworkState.CONNECTED, true);
    }

    private static ThreadEvent outboundHashOnly(long t, String text) {
        return new ThreadEvent(ThreadEvent.Direction.OUT, ThreadEvent.Kind.SMS, t, ThreadEvent.hashOf(text), null);
    }

    // ---- F1: send anyway refuses terminal and IN_FLIGHT statuses ------------------------

    @Test
    public void sendAnyway_refusesTerminalAndInFlightStatuses() {
        ScheduledMessage.Status[] refused = {
                ScheduledMessage.Status.CANCELED, ScheduledMessage.Status.SENT_VERIFIED,
                ScheduledMessage.Status.SELF_PREEMPTED, ScheduledMessage.Status.EXPIRED,
                ScheduledMessage.Status.FAILED, ScheduledMessage.Status.IN_FLIGHT };
        for (ScheduledMessage.Status s : refused) {
            try {
                Decision d = LadderEvaluator.evaluateSendAnyway(message(AHEAD).status(s).build(), none(), ON_TIME);
                fail("send anyway on " + s + " must be refused, got " + d.getKind());
            } catch (IllegalArgumentException expected) {
                // refused, like evaluate() on a terminal status
            }
        }
    }

    @Test
    public void sendAnyway_refusesTerminalEvenWithNullEvents() {
        try {
            LadderEvaluator.evaluateSendAnyway(
                    message(AHEAD).status(ScheduledMessage.Status.CANCELED).build(), null, ON_TIME);
            fail("a stale Send anyway on a cancelled message must never send");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void sendAnyway_scheduledAndHeld_stillSend() {
        assertEquals(Decision.Kind.SEND, LadderEvaluator.evaluateSendAnyway(
                message(AHEAD).status(ScheduledMessage.Status.SCHEDULED).build(), none(), ON_TIME).getKind());
        assertEquals(Decision.Kind.SEND, LadderEvaluator.evaluateSendAnyway(
                message(AHEAD).status(ScheduledMessage.Status.HELD).build(), null, ON_TIME).getKind());
    }

    // ---- F2: reminded (SCHEDULED) messages expire after 24 h ---------------------------

    @Test
    public void reminded_lateScheduled_expiresAfter24h() {
        assertEquals(Decision.Kind.EXPIRED, eval(message(AHEAD).build(), none(), DUE + DAY + MIN).getKind());
        assertEquals(Decision.Kind.EXPIRED, eval(message(AHEAD).build(), none(), DUE + DAY).getKind());
    }

    @Test
    public void reminded_lateScheduled_under24h_stillReminds() {
        Decision d = eval(message(AHEAD).build(), none(), DUE + DAY - MIN);
        assertEquals(Decision.Kind.REMIND, d.getKind());
        assertEquals(RemindReason.LATE, d.getReason());
    }

    @Test
    public void reminded_noSender_expiresAfter24h() {
        Decision d = LadderEvaluator.evaluate(message(AHEAD).rung(Rung.R2).build(), Rung.R2, none(),
                DUE + DAY + MIN, SessionState.ready(), NetworkState.CONNECTED, true);
        assertEquals(Decision.Kind.EXPIRED, d.getKind());
    }

    @Test
    public void unverifiedLate_expiresAfter24h() {
        assertEquals(Decision.Kind.EXPIRED, eval(message(AHEAD).build(), null, DUE + DAY + MIN).getKind());
    }

    @Test
    public void inFlight_isNotExpiredByTheReminderRule() {
        // IN_FLIGHT is the engine's verify state; R29's "reminded" expiry covers SCHEDULED only.
        Decision d = eval(message(AHEAD).status(ScheduledMessage.Status.IN_FLIGHT).build(), none(), DUE + DAY + MIN);
        assertEquals(Decision.Kind.REMIND, d.getKind());
    }

    // ---- F3 / OD20(5): hashOf and the hash fallback ------------------------------------

    @Test
    public void hashOf_isLowercaseHexSha256OfNormalizedText() {
        // SHA-256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ThreadEvent.hashOf("ABC!"));
        // SHA-256("") for a token-less text
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ThreadEvent.hashOf("?!"));
        assertNull(ThreadEvent.hashOf(null));
    }

    @Test
    public void hashOf_ignoresCaseDiacriticsPunctuationAndWhitespace() {
        assertEquals(ThreadEvent.hashOf("cafe at noon"), ThreadEvent.hashOf("  Café,   at\nNOON! "));
        assertFalse(ThreadEvent.hashOf("cafe at noon").equals(ThreadEvent.hashOf("cafe at one")));
    }

    @Test
    public void hashOnlyOutbound_sameText_isSelfPreempted() {
        Classification c = ThreadEventClassifier.classify(message(AHEAD).build(),
                events(outboundHashOnly(T0 + HOUR, "running late, BE there at 9!")), true);
        assertEquals(Classification.SELF_PREEMPTED, c);
        assertEquals(Decision.Kind.CANCEL,
                eval(message(AHEAD).build(), events(outboundHashOnly(T0 + HOUR, TEXT)), ON_TIME).getKind());
    }

    @Test
    public void hashOnlyOutbound_differentText_isOutboundOnly() {
        Classification c = ThreadEventClassifier.classify(message(AHEAD).build(),
                events(outboundHashOnly(T0 + HOUR, "What time is dinner")), true);
        assertEquals(Classification.OUTBOUND_ONLY, c);
    }

    @Test
    public void outboundWithoutTextOrHash_isOutboundOnly() {
        ThreadEvent bare = new ThreadEvent(ThreadEvent.Direction.OUT, ThreadEvent.Kind.SMS, T0 + HOUR, null, null);
        assertEquals(Classification.OUTBOUND_ONLY,
                ThreadEventClassifier.classify(message(AHEAD).build(), events(bare), true));
    }

    @Test
    public void bothTextsPresent_hashIsNotConsulted() {
        // Event text differs, its hash (deliberately wrong) equals the message's: text decides.
        ThreadEvent e = new ThreadEvent(ThreadEvent.Direction.OUT, ThreadEvent.Kind.SMS, T0 + HOUR,
                ThreadEvent.hashOf(TEXT), "What time is dinner");
        assertEquals(Classification.OUTBOUND_ONLY,
                ThreadEventClassifier.classify(message(AHEAD).build(), events(e), true));
    }

    @Test
    public void tokenLessMessage_neverMatchesByHash() {
        ScheduledMessage m = message(AHEAD).text("!!").build();
        assertEquals(Classification.OUTBOUND_ONLY,
                ThreadEventClassifier.classify(m, events(outboundHashOnly(T0 + HOUR, "??")), true));
    }

    @Test
    public void sendAnyway_hashOnlyOutboundSinceWatermark_cancels() {
        long now = DUE + 2 * HOUR;
        Decision d = LadderEvaluator.evaluateSendAnyway(message(AHEAD).status(ScheduledMessage.Status.HELD).build(),
                events(outboundHashOnly(now - 30_000L, TEXT)), now);
        assertEquals(Decision.Kind.CANCEL, d.getKind());
    }

    // ---- OD20(1): containment needs >= 3 tokens in the shorter text --------------------

    @Test
    public void containment_oneTokenOk_isNotSimilar() {
        assertFalse(ThreadEventClassifier.similar("ok, I'll call you when I land", "ok"));
        assertFalse(ThreadEventClassifier.similar("Happy birthday! Dinner at 7 tonight?", "tonight"));
    }

    @Test
    public void containment_twoTokens_isNotSimilar() {
        assertFalse(ThreadEventClassifier.similar("ok, I'll call you when I land", "call you"));
    }

    @Test
    public void containment_threeTokens_isSimilar() {
        assertTrue(ThreadEventClassifier.similar("ok, I'll call you when I land", "call you when"));
    }

    @Test
    public void shortTexts_jaccardStillApplies() {
        assertTrue(ThreadEventClassifier.similar("ok", "OK!"));
        assertTrue(ThreadEventClassifier.similar("on my way", "On my way"));
    }

    @Test
    public void shortOutboundOk_afterScheduling_isOutboundOnlyNotCancel() {
        Classification c = ThreadEventClassifier.classify(message(AHEAD).text("ok, I'll call you when I land").build(),
                events(outboundSms(T0 + HOUR, "ok")), true);
        assertEquals(Classification.OUTBOUND_ONLY, c);
    }

    // ---- F4: IN_FLIGHT own bubble -> CANCEL(SELF_PREEMPTED) (engine maps to SENT_VERIFIED) ----

    @Test
    public void inFlight_ownBubbleSeen_isCancelSelfPreempted() {
        Decision d = eval(message(AHEAD).status(ScheduledMessage.Status.IN_FLIGHT).build(),
                events(outboundSms(ON_TIME, TEXT)), ON_TIME + MIN);
        assertEquals(Decision.Kind.CANCEL, d.getKind());
        assertEquals(Classification.SELF_PREEMPTED, d.getReason());
    }

    // ---- F5 / OD20(6): TIMESTAMP/EXACT inbound margin ----------------------------------

    private static Watermark tsWatermark(Watermark.Quality q, long newestTs, Long skew) {
        return new Watermark(q, q == Watermark.Quality.EXACT ? "srv-1" : null, newestTs, null, T0, skew);
    }

    private static Classification withWm(Watermark wm, ThreadEvent e) {
        return ThreadEventClassifier.classify(message(AHEAD).watermark(wm).build(), events(e), true);
    }

    @Test
    public void timestamp_inboundWithin90sBeforeWatermark_counts() {
        Watermark wm = tsWatermark(Watermark.Quality.TIMESTAMP, T0, null);
        assertEquals(Classification.REPLIED, withWm(wm, inboundSms(T0 - 60_000L, "you there?")));
        assertEquals(Classification.REPLIED, withWm(wm, inboundSms(T0 - 90_000L, "you there?")));
    }

    @Test
    public void timestamp_inboundBeyondMargin_isIgnored() {
        Watermark wm = tsWatermark(Watermark.Quality.TIMESTAMP, T0, null);
        assertEquals(Classification.UNCHANGED, withWm(wm, inboundSms(T0 - 91_000L, "you there?")));
    }

    @Test
    public void exact_inboundWithinMargin_counts() {
        Watermark wm = tsWatermark(Watermark.Quality.EXACT, T0, null);
        assertEquals(Classification.REPLIED, withWm(wm, inboundSms(T0 - 30_000L, "you there?")));
    }

    @Test
    public void timestamp_marginShiftedBySkew() {
        // skew +30 s: inbound counts from T0 - 60 s.
        Watermark plus = tsWatermark(Watermark.Quality.TIMESTAMP, T0, 30_000L);
        assertEquals(Classification.REPLIED, withWm(plus, inboundSms(T0 - 60_000L, "you there?")));
        assertEquals(Classification.UNCHANGED, withWm(plus, inboundSms(T0 - 61_000L, "you there?")));
        // skew -30 s: inbound counts from T0 - 120 s.
        Watermark minus = tsWatermark(Watermark.Quality.TIMESTAMP, T0, -30_000L);
        assertEquals(Classification.REPLIED, withWm(minus, inboundSms(T0 - 120_000L, "you there?")));
        assertEquals(Classification.UNCHANGED, withWm(minus, inboundSms(T0 - 121_000L, "you there?")));
    }

    @Test
    public void timestamp_outboundInsideMargin_isIgnored() {
        Watermark wm = tsWatermark(Watermark.Quality.TIMESTAMP, T0, null);
        assertEquals(Classification.UNCHANGED, withWm(wm, outboundSms(T0 - 60_000L, "What time is dinner")));
        assertEquals(Classification.UNCHANGED, withWm(wm, outboundSms(T0 - 30_000L, TEXT)));
        assertEquals(Classification.OUTBOUND_ONLY, withWm(wm, outboundSms(T0 + 1_000L, "What time is dinner")));
    }
}
