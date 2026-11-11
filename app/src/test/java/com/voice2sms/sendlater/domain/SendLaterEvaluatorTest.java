package com.voice2sms.sendlater.domain;

import static com.voice2sms.sendlater.domain.Fixtures.DAY;
import static com.voice2sms.sendlater.domain.Fixtures.HOUR;
import static com.voice2sms.sendlater.domain.Fixtures.MIN;
import static com.voice2sms.sendlater.domain.Fixtures.T0;
import static com.voice2sms.sendlater.domain.Fixtures.TEXT;
import static com.voice2sms.sendlater.domain.Fixtures.events;
import static com.voice2sms.sendlater.domain.Fixtures.inbound;
import static com.voice2sms.sendlater.domain.Fixtures.inboundSms;
import static com.voice2sms.sendlater.domain.Fixtures.message;
import static com.voice2sms.sendlater.domain.Fixtures.none;
import static com.voice2sms.sendlater.domain.Fixtures.outboundSms;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.Test;

/**
 * R25-R29 / I2 / I6: every row of the classification -> effect table (R26) and
 * of the "facts only move down" table (R27), "Send anyway" (R28) and the 24 h
 * expiry (R29).
 *
 * Fixture: created at T0, due 3 h later (AUTO budget 30 min per LatenessBudget),
 * TIMESTAMP watermark captured at T0. "On time" = due + 1 min; "late" = due + 45 min.
 */
public class SendLaterEvaluatorTest {

    private static final long AHEAD = 3 * HOUR;
    private static final long DUE = T0 + AHEAD;
    private static final long ON_TIME = DUE + MIN;
    private static final long LATE = DUE + 45 * MIN;
    private static final long BETWEEN = T0 + HOUR; // an event between scheduling and due

    private static final String OTHER_TEXT = "What time is dinner tonight";

    private static Decision eval(ScheduledMessage m, List<ThreadEvent> events, long now) {
        return LadderEvaluator.evaluate(m, Rung.R1, events, now,
                SessionState.ready(), NetworkState.CONNECTED, true);
    }

    private static void assertKind(Decision.Kind expected, Decision d) {
        assertNotNull("evaluate must never return null", d);
        assertEquals(expected, d.getKind());
    }

    /** Reasons are enums whose exact names belong to the domain node; we pin only the gist. */
    private static void assertReasonMentions(Decision d, String... anyOf) {
        assertNotNull("decision " + d.getKind() + " must carry a reason", d.getReason());
        String r = String.valueOf(d.getReason()).toUpperCase(Locale.ROOT);
        for (String a : anyOf) {
            if (r.contains(a.toUpperCase(Locale.ROOT))) return;
        }
        throw new AssertionError("reason " + r + " should mention one of " + java.util.Arrays.toString(anyOf));
    }

    // ---- R26: classification -> effect --------------------------------------------------

    @Test
    public void replied_holdIfRepliedOn_holds() {
        Decision d = eval(message(AHEAD).holdIfReplied(true).build(),
                events(inboundSms(BETWEEN, "ok see you then")), ON_TIME);
        assertKind(Decision.Kind.HOLD, d);
        assertReasonMentions(d, "REPL");
    }

    @Test
    public void replied_holdIfRepliedOff_sends() {
        Decision d = eval(message(AHEAD).holdIfReplied(false).build(),
                events(inboundSms(BETWEEN, "ok see you then")), ON_TIME);
        assertKind(Decision.Kind.SEND, d);
    }

    @Test
    public void reachedOut_call_holdIfRepliedOn_holds() {
        Decision d = eval(message(AHEAD).holdIfReplied(true).build(),
                events(inbound(ThreadEvent.Kind.CALL, BETWEEN, null)), ON_TIME);
        assertKind(Decision.Kind.HOLD, d);
    }

    @Test
    public void reachedOut_voicemail_holdIfRepliedOn_holds() {
        Decision d = eval(message(AHEAD).holdIfReplied(true).build(),
                events(inbound(ThreadEvent.Kind.VOICEMAIL, BETWEEN, null)), ON_TIME);
        assertKind(Decision.Kind.HOLD, d);
    }

    @Test
    public void reachedOut_holdIfRepliedOff_sends() {
        Decision d = eval(message(AHEAD).holdIfReplied(false).build(),
                events(inbound(ThreadEvent.Kind.CALL, BETWEEN, null)), ON_TIME);
        assertKind(Decision.Kind.SEND, d);
    }

    @Test
    public void selfPreempted_cancels() {
        Decision d = eval(message(AHEAD).build(),
                events(outboundSms(BETWEEN, "running late, be there at 9!")), ON_TIME);
        assertKind(Decision.Kind.CANCEL, d);
    }

    @Test
    public void selfPreempted_cancelsEvenWithHoldIfRepliedOff() {
        Decision d = eval(message(AHEAD).holdIfReplied(false).build(),
                events(outboundSms(BETWEEN, TEXT)), ON_TIME);
        assertKind(Decision.Kind.CANCEL, d);
    }

    @Test
    public void outboundOnly_defaultPolicyIsStillSend_sends() {
        Decision d = eval(message(AHEAD).build(), events(outboundSms(BETWEEN, OTHER_TEXT)), ON_TIME);
        assertKind(Decision.Kind.SEND, d);
    }

    @Test
    public void outboundOnly_stillSend_sends() {
        Decision d = LadderEvaluator.evaluate(message(AHEAD).build(), Rung.R1,
                events(outboundSms(BETWEEN, OTHER_TEXT)), ON_TIME,
                SessionState.ready(), NetworkState.CONNECTED, true, OutboundPolicy.STILL_SEND);
        assertKind(Decision.Kind.SEND, d);
    }

    @Test
    public void outboundOnly_holdAndAsk_holds() {
        Decision d = LadderEvaluator.evaluate(message(AHEAD).build(), Rung.R1,
                events(outboundSms(BETWEEN, OTHER_TEXT)), ON_TIME,
                SessionState.ready(), NetworkState.CONNECTED, true, OutboundPolicy.HOLD_AND_ASK);
        assertKind(Decision.Kind.HOLD, d);
    }

    @Test
    public void unchanged_sends() {
        assertKind(Decision.Kind.SEND, eval(message(AHEAD).build(), none(), ON_TIME));
    }

    @Test
    public void unverified_withinBudget_holds() {
        Decision d = LadderEvaluator.evaluate(message(AHEAD).build(), Rung.R1, null, ON_TIME,
                SessionState.ready(), NetworkState.CONNECTED, false);
        assertKind(Decision.Kind.HOLD, d);
        assertReasonMentions(d, "UNVERIFIED");
    }

    @Test
    public void unverified_listenerOffEmptyEvents_holds() {
        Decision d = LadderEvaluator.evaluate(message(AHEAD).build(), Rung.R1, none(), ON_TIME,
                SessionState.ready(), NetworkState.CONNECTED, false);
        assertKind(Decision.Kind.HOLD, d);
        assertReasonMentions(d, "UNVERIFIED");
    }

    @Test
    public void unverified_beyondBudget_reminds() {
        Decision d = LadderEvaluator.evaluate(message(AHEAD).build(), Rung.R1, null, LATE,
                SessionState.ready(), NetworkState.CONNECTED, false);
        assertKind(Decision.Kind.REMIND, d);
    }

    // ---- R27: facts only move down ------------------------------------------------------

    @Test
    public void lateBeyondBudget_remindsEvenWhenUnchanged() {
        Decision d = eval(message(AHEAD).build(), none(), LATE);
        assertKind(Decision.Kind.REMIND, d);
        assertReasonMentions(d, "LATE");
    }

    @Test
    public void lateJustInsideBudget_stillSends() {
        // AUTO budget for 3 h ahead is 30 min: 29 min late is still allowed.
        assertKind(Decision.Kind.SEND, eval(message(AHEAD).build(), none(), DUE + 29 * MIN));
    }

    @Test
    public void neverPolicy_anyLateness_reminds() {
        Decision d = eval(message(AHEAD).latenessPolicy(LatenessPolicy.NEVER).build(), none(), DUE + 2 * MIN);
        assertKind(Decision.Kind.REMIND, d);
    }

    @Test
    public void sessionMissing_reminds() {
        Decision d = LadderEvaluator.evaluate(message(AHEAD).build(), Rung.R1, none(), ON_TIME,
                SessionState.missing(), NetworkState.CONNECTED, true);
        assertKind(Decision.Kind.REMIND, d);
        assertReasonMentions(d, "SESSION", "SIGN");
    }

    @Test
    public void sessionExpired_reminds() {
        Decision d = LadderEvaluator.evaluate(message(AHEAD).build(), Rung.R1, none(), ON_TIME,
                SessionState.expired(), NetworkState.CONNECTED, true);
        assertKind(Decision.Kind.REMIND, d);
        assertReasonMentions(d, "SESSION", "SIGN");
    }

    @Test
    public void noNetwork_withinBudget_waits() {
        Decision d = LadderEvaluator.evaluate(message(AHEAD).build(), Rung.R1, none(), ON_TIME,
                SessionState.ready(), NetworkState.NONE, true);
        assertKind(Decision.Kind.WAIT, d);
        assertTrue("WAIT must point to a later retry time", d.getUntilMs() > ON_TIME);
    }

    @Test
    public void noNetwork_beyondBudget_reminds() {
        Decision d = LadderEvaluator.evaluate(message(AHEAD).build(), Rung.R1, none(), LATE,
                SessionState.ready(), NetworkState.NONE, true);
        assertKind(Decision.Kind.REMIND, d);
    }

    @Test
    public void lockedUnshowable_reminds() {
        Decision d = LadderEvaluator.evaluate(message(AHEAD).build(), Rung.R1, none(), ON_TIME,
                SessionState.lockedUnshowable(), NetworkState.CONNECTED, true);
        assertKind(Decision.Kind.REMIND, d);
        assertReasonMentions(d, "LOCK");
    }

    // ---- R28: Send anyway ---------------------------------------------------------------

    @Test
    public void sendAnyway_ignoresReplies() {
        Decision d = LadderEvaluator.evaluateSendAnyway(message(AHEAD).status(ScheduledMessage.Status.HELD).build(),
                events(inboundSms(BETWEEN, "ok see you then")), ON_TIME);
        assertKind(Decision.Kind.SEND, d);
    }

    @Test
    public void sendAnyway_ignoresLatenessBudget() {
        Decision d = LadderEvaluator.evaluateSendAnyway(message(AHEAD).status(ScheduledMessage.Status.HELD).build(),
                none(), DUE + 3 * HOUR);
        assertKind(Decision.Kind.SEND, d);
    }

    @Test
    public void sendAnyway_similarOutboundInsideLast60s_cancels() {
        long now = DUE + 2 * HOUR;
        Decision d = LadderEvaluator.evaluateSendAnyway(message(AHEAD).status(ScheduledMessage.Status.HELD).build(),
                events(outboundSms(now - 30_000L, TEXT)), now);
        assertKind(Decision.Kind.CANCEL, d);
    }

    @Test
    public void sendAnyway_similarOutboundOlderThan60sButSinceWatermark_cancels() {
        // Orchestrator ruling 2026-11-04 (OD20(2), R28 amended): the send-anyway self-preemption
        // check covers every outbound event since the watermark, not only the last 60 s.
        long now = DUE + 2 * HOUR;
        Decision d = LadderEvaluator.evaluateSendAnyway(message(AHEAD).status(ScheduledMessage.Status.HELD).build(),
                events(outboundSms(now - 5 * MIN, TEXT)), now);
        assertKind(Decision.Kind.CANCEL, d);
    }

    // ---- R29: 24 h expiry ---------------------------------------------------------------

    @Test
    public void heldIgnoredFor24h_expires() {
        ScheduledMessage held = message(AHEAD).status(ScheduledMessage.Status.HELD).build();
        assertKind(Decision.Kind.EXPIRED, eval(held, none(), DUE + DAY + MIN));
    }

    @Test
    public void heldForLessThan24h_doesNotExpire() {
        ScheduledMessage held = message(AHEAD).status(ScheduledMessage.Status.HELD).build();
        Decision d = eval(held, none(), DUE + 2 * HOUR);
        assertNotNull(d);
        assertNotEquals(Decision.Kind.EXPIRED, d.getKind());
        assertNotEquals("a held message never auto-sends 2 h late (I2)", Decision.Kind.SEND, d.getKind());
    }

    // ---- I6: lateness beats a would-be SEND, never the other way ------------------------

    @Test
    public void lateAndRepliedWithHoldOff_doesNotSend() {
        Decision d = eval(message(AHEAD).holdIfReplied(false).build(),
                events(inboundSms(BETWEEN, "ok see you then")), LATE);
        assertNotEquals(Decision.Kind.SEND, d.getKind());
    }
}
