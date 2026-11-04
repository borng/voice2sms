package com.voice2sms.sendlater.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * OD22(1): after a snooze, replies are judged from the fresh snooze watermark while
 * self-preemption keeps the ORIGINAL watermark. Fictional texts only.
 */
public class SnoozeBaselineTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long T0 = 1_781_092_800_000L;
    /** Original due time; the message was HELD here and snoozed 20 min later. */
    private static final long DUE = T0 + 2 * HOUR;
    private static final long SNOOZE = DUE + 20 * MIN;
    private static final long NEW_DUE = SNOOZE + HOUR;
    private static final String TEXT = "Running late, be there at 9";

    private static Watermark original() {
        return new Watermark(Watermark.Quality.TIMESTAMP, null, T0 - 10 * MIN, null, T0, null);
    }

    private static Watermark fresh() {
        return new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, SNOOZE, null);
    }

    /** The row as the store returns it after the snooze. */
    private static ScheduledMessage snoozed() {
        return new ScheduledMessage.Builder()
                .id(3).createdAt(T0).scheduledFor(NEW_DUE).text(TEXT)
                .recipientE164("+15555550100").recipientDisplayName("Test Contact")
                .holdIfReplied(true).status(ScheduledMessage.Status.SCHEDULED)
                .watermark(fresh()).originalWatermark(original())
                .build();
    }

    private static ThreadEvent outSame(long t) {
        return new ThreadEvent(ThreadEvent.Direction.OUT, ThreadEvent.Kind.SMS, t, ThreadEvent.hashOf(TEXT), TEXT);
    }

    private static ThreadEvent outOther(long t) {
        String s = "Unrelated note about groceries";
        return new ThreadEvent(ThreadEvent.Direction.OUT, ThreadEvent.Kind.SMS, t, ThreadEvent.hashOf(s), s);
    }

    private static ThreadEvent inReply(long t) {
        String s = "ok see you";
        return new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.SMS, t, ThreadEvent.hashOf(s), s);
    }

    private static Decision eval(ScheduledMessage m, OutboundPolicy p, ThreadEvent... events) {
        return LadderEvaluator.evaluate(m, Rung.R1, new ArrayList<>(Arrays.asList(events)), NEW_DUE,
                SessionState.READY, NetworkState.CONNECTED, true, p);
    }

    @Test public void baselinesDefaultToTheWatermarkWhenNeverReplaced() {
        ScheduledMessage m = snoozed().toBuilder().originalWatermark(null).build();
        assertSame(m.getWatermark(), m.getSelfPreemptionBaseline());
        assertNull(m.toBuilder().watermark(null).build().getSelfPreemptionBaseline());
    }

    @Test public void builderCarriesOriginalWatermarkAndRungReason() {
        ScheduledMessage m = snoozed().toBuilder().rungReason(RemindReason.NO_SESSION).build().toBuilder().build();
        assertEquals(original(), m.getOriginalWatermark());
        assertEquals(original(), m.getSelfPreemptionBaseline());
        assertEquals(fresh(), m.getWatermark());
        assertEquals(RemindReason.NO_SESSION, m.getRungReason());
    }

    @Test public void similarOutboundBetweenHoldAndSnoozeStillCancels() {
        Decision d = eval(snoozed(), OutboundPolicy.STILL_SEND, outSame(DUE + 5 * MIN));
        assertEquals(Decision.Kind.CANCEL, d.getKind());
    }

    @Test public void similarOutboundBetweenHoldAndSnoozeCancelsSendAnyway() {
        Decision d = LadderEvaluator.evaluateSendAnyway(snoozed(),
                new ArrayList<>(Arrays.asList(outSame(DUE + 5 * MIN))), NEW_DUE);
        assertEquals(Decision.Kind.CANCEL, d.getKind());
    }

    @Test public void replyBeforeTheSnoozeNoLongerHolds() {
        Decision d = eval(snoozed(), OutboundPolicy.STILL_SEND, inReply(DUE + 1 * MIN));
        assertEquals(Decision.Kind.SEND, d.getKind());
    }

    @Test public void replyAfterTheSnoozeHolds() {
        Decision d = eval(snoozed(), OutboundPolicy.STILL_SEND, inReply(SNOOZE + 10 * MIN));
        assertEquals(Decision.Kind.HOLD, d.getKind());
        assertEquals(HoldReason.REPLIED, d.getHoldReason());
    }

    @Test public void unrelatedOutboundBeforeTheSnoozeIsJudgedFromTheSnooze() {
        Decision before = eval(snoozed(), OutboundPolicy.HOLD_AND_ASK, outOther(DUE + 5 * MIN));
        assertEquals(Decision.Kind.SEND, before.getKind());
        Decision after = eval(snoozed(), OutboundPolicy.HOLD_AND_ASK, outOther(SNOOZE + 10 * MIN));
        assertEquals(Decision.Kind.HOLD, after.getKind());
        assertEquals(HoldReason.OUTBOUND_ONLY, after.getHoldReason());
    }

    @Test public void similarOutboundBeforeTheOriginalWatermarkDoesNotCancel() {
        Decision d = eval(snoozed(), OutboundPolicy.STILL_SEND, outSame(T0 - 20 * MIN));
        assertEquals(Decision.Kind.SEND, d.getKind());
    }

    @Test public void explicitBaselinesOverloadIsWhatTheDefaultUses() {
        ScheduledMessage m = snoozed();
        List<ThreadEvent> ev = new ArrayList<>(Arrays.asList(outSame(DUE + 5 * MIN)));
        // Using the fresh watermark for both baselines (the pre-OD22 behaviour) would send.
        assertEquals(Decision.Kind.SEND, LadderEvaluator.evaluate(m, Rung.R1, ev, NEW_DUE, SessionState.READY,
                NetworkState.CONNECTED, true, OutboundPolicy.STILL_SEND, fresh(), fresh()).getKind());
        assertEquals(Decision.Kind.CANCEL, LadderEvaluator.evaluate(m, Rung.R1, ev, NEW_DUE, SessionState.READY,
                NetworkState.CONNECTED, true, OutboundPolicy.STILL_SEND, fresh(), original()).getKind());
        assertEquals(Classification.SELF_PREEMPTED,
                ThreadEventClassifier.classify(m, ev, true, fresh(), original()));
        assertEquals(Classification.UNCHANGED, ThreadEventClassifier.classify(m, ev, true, fresh(), fresh()));
    }

    @Test public void heldSnoozedMessageStillCancelsOnAnOlderSimilarText() {
        ScheduledMessage held = snoozed().toBuilder().status(ScheduledMessage.Status.HELD)
                .holdReason(HoldReason.REPLIED).build();
        assertEquals(Decision.Kind.CANCEL, eval(held, OutboundPolicy.STILL_SEND, outSame(DUE + 5 * MIN)).getKind());
    }
}
