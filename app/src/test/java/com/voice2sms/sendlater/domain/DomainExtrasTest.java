package com.voice2sms.sendlater.domain;

import static com.voice2sms.sendlater.domain.Fixtures.DAY;
import static com.voice2sms.sendlater.domain.Fixtures.HOUR;
import static com.voice2sms.sendlater.domain.Fixtures.MIN;
import static com.voice2sms.sendlater.domain.Fixtures.T0;
import static com.voice2sms.sendlater.domain.Fixtures.TEXT;
import static com.voice2sms.sendlater.domain.Fixtures.events;
import static com.voice2sms.sendlater.domain.Fixtures.inbound;
import static com.voice2sms.sendlater.domain.Fixtures.message;
import static com.voice2sms.sendlater.domain.Fixtures.none;
import static com.voice2sms.sendlater.domain.Fixtures.outboundSms;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.TimeZone;

import org.junit.Test;

/** Implementer's own tests for the pinned contracts and the edges the acceptance tests leave open. */
public class DomainExtrasTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    private static final long DUE = T0 + 3 * HOUR;

    private static Decision eval(ScheduledMessage m, long now) {
        return LadderEvaluator.evaluate(m, Rung.R1, none(), now, SessionState.ready(), NetworkState.CONNECTED, true);
    }

    @Test
    public void pinnedEnumValues() {
        assertEquals(set("DEFAULT", "WITHIN_30_MIN", "NEVER_LATE", "UP_TO_24H"), names(LatenessPolicy.values()));
        assertEquals(set("REPLIED", "REACHED_OUT", "OUTBOUND_ONLY", "UNVERIFIED", "LOCKED"), names(HoldReason.values()));
        assertEquals(set("LATE", "NO_SESSION", "NO_NETWORK", "LOCKED", "NO_SENDER"), names(RemindReason.values()));
        assertEquals(set("SENT_VERIFIED", "SENT_UNVERIFIED", "FAILED", "CANNOT_SHOW"), names(SendOutcome.values()));
        assertTrue(LatenessPolicy.AUTO == LatenessPolicy.DEFAULT);
        assertTrue(LatenessPolicy.NEVER == LatenessPolicy.NEVER_LATE);
        assertTrue(LatenessPolicy.SEND_ANYWAY == LatenessPolicy.UP_TO_24H);
    }

    @Test
    public void upTo24h_isHardCappedAt24h() {
        assertEquals(DAY, LatenessBudget.budgetMs(7 * DAY, LatenessPolicy.UP_TO_24H));
        ScheduledMessage m = message(3 * HOUR).latenessPolicy(LatenessPolicy.UP_TO_24H).build();
        assertEquals(Decision.Kind.SEND, eval(m, DUE + 23 * HOUR).getKind());
        assertEquals(Decision.Kind.REMIND, eval(m, DUE + DAY + MIN).getKind());
    }

    @Test
    public void exactlyTwentyFourHoursAhead_is30Minutes() {
        assertEquals(30 * MIN, LatenessBudget.budgetMs(DAY, LatenessPolicy.DEFAULT));
    }

    @Test
    public void beforeDue_waitsUntilDue() {
        Decision d = eval(message(3 * HOUR).build(), DUE - MIN);
        assertEquals(Decision.Kind.WAIT, d.getKind());
        assertEquals(DUE, d.getUntilMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void terminalStatus_isRejected() {
        eval(message(3 * HOUR).status(ScheduledMessage.Status.SENT_VERIFIED).build(), DUE);
    }

    @Test
    public void held_similarOutboundSince_cancels() {
        ScheduledMessage held = message(3 * HOUR).status(ScheduledMessage.Status.HELD)
                .holdReason(HoldReason.REPLIED).build();
        Decision d = LadderEvaluator.evaluate(held, Rung.R1, events(outboundSms(DUE + HOUR, TEXT)), DUE + 2 * HOUR,
                SessionState.ready(), NetworkState.CONNECTED, true);
        assertEquals(Decision.Kind.CANCEL, d.getKind());
    }

    @Test
    public void held_keepsItsReason() {
        ScheduledMessage held = message(3 * HOUR).status(ScheduledMessage.Status.HELD)
                .holdReason(HoldReason.REACHED_OUT).build();
        assertEquals(HoldReason.REACHED_OUT, eval(held, DUE + HOUR).getHoldReason());
    }

    @Test
    public void noNetwork_waitsUntilTheBudgetDeadline() {
        Decision d = LadderEvaluator.evaluate(message(3 * HOUR).build(), Rung.R1, none(), DUE + MIN,
                SessionState.ready(), NetworkState.NONE, true);
        assertEquals(Decision.Kind.WAIT, d.getKind());
        assertEquals(DUE + 30 * MIN, d.getUntilMs());
        assertEquals(RemindReason.NO_NETWORK, d.getRemindReason());
    }

    @Test
    public void reminderRung_neverAutoSends() {
        Decision d = LadderEvaluator.evaluate(message(3 * HOUR).build(), Rung.R3, none(), DUE + MIN,
                SessionState.ready(), NetworkState.CONNECTED, true);
        assertEquals(Decision.Kind.REMIND, d.getKind());
    }

    @Test
    public void rpcStub_isUnavailable() {
        assertFalse(new RpcThreadEventSource().isAvailable());
    }

    @Test
    public void conversationRef_nameKeyIsNormalized() {
        assertEquals("name:jose maria", new ConversationRef(null, "  José   MARÍA ").key());
        assertEquals(new ConversationRef(null, "jose maria").key(), new ConversationRef("", "José María").key());
    }

    @Test
    public void similarity_ignoresDiacriticsAndNulls() {
        assertTrue(ThreadEventClassifier.similar("Café at noon", "cafe at noon"));
        assertFalse(ThreadEventClassifier.similar(null, "x"));
        assertFalse(ThreadEventClassifier.similar("!!", "??"));
    }

    @Test
    public void classifier_inboundBeatsUnrelatedOutbound_selfPreemptBeatsAll() {
        ScheduledMessage m = message(3 * HOUR).build();
        assertEquals(Classification.REPLIED, ThreadEventClassifier.classify(m,
                events(outboundSms(T0 + HOUR, "unrelated words here"), inbound(ThreadEvent.Kind.SMS, T0 + 2 * HOUR, "hi")), true));
        assertEquals(Classification.SELF_PREEMPTED, ThreadEventClassifier.classify(m,
                events(inbound(ThreadEvent.Kind.SMS, T0 + HOUR, "hi"), outboundSms(T0 + 2 * HOUR, TEXT)), true));
    }

    @Test
    public void classifier_timestampWatermark_ignoresEventsAtOrBeforeNewestTs() {
        ScheduledMessage m = message(3 * HOUR).build(); // newestTs = T0 - 10 min
        assertEquals(Classification.UNCHANGED, ThreadEventClassifier.classify(m,
                events(inbound(ThreadEvent.Kind.SMS, T0 - 10 * MIN, "old")), true));
    }

    @Test
    public void chip_reachedOutReadsCalled() {
        assertEquals("Held · Test Contact called",
                ChipLabel.format(1, 1, DUE, "Test Contact", null, HoldReason.REACHED_OUT, ZONE));
    }

    @Test
    public void chip_heldWithoutName_usesFormattedNumber() {
        assertEquals("Held · (555) 555-0100 replied",
                ChipLabel.format(0, 1, DUE, null, "+15555550100", HoldReason.REPLIED, ZONE));
    }

    @Test
    public void chip_longNameTruncatedTo20Chars() {
        String s = ChipLabel.heldDisplayName("Abcdefghijklmnopqrstuvwxyz", null);
        assertEquals(20, s.length());
        assertTrue(s.endsWith("…"));
    }

    @Test
    public void chip_nothingPending_isNull() {
        assertEquals(null, ChipLabel.format(0, 0, DUE, null, ZONE));
    }

    private static HashSet<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }

    private static HashSet<String> names(Enum<?>[] values) {
        HashSet<String> s = new HashSet<>();
        for (Enum<?> v : values) s.add(v.name());
        return s;
    }
}
