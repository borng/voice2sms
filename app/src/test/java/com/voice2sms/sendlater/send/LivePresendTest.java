package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.Classification;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.PreSendCheck;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.web.ThreadBubble;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * live-presend (D30, LIVE-F3; R26, R28, R31, OD22(5), OD27): the pre-send read of the open GV web
 * thread, classified against the record's watermark. Bubbles carry label times (minute
 * precision, as the real page gives them), no ids. Fictional numbers and texts only.
 */
public class LivePresendTest {

    private static final long MIN = 60_000L;
    /** A whole minute (label times are minute precision); only differences matter. */
    private static final long T0 = 1_781_092_800_000L;
    private static final String E164 = "+15555550100";
    private static final String TEXT = "Running late, be there at 9";
    private static final int LIMIT = ScheduledSendController.READ_LIMIT;

    // ------------------------------------------------------------------ fixtures

    private static ThreadBubble in(String text, long t) {
        return new ThreadBubble(ThreadEvent.Direction.IN, t, text, null, true, false);
    }

    private static ThreadBubble out(String text, long t) {
        return new ThreadBubble(ThreadEvent.Direction.OUT, t, text, null, true, false);
    }

    private static ThreadBubble failedOut(String text, long t) {
        return new ThreadBubble(ThreadEvent.Direction.OUT, t, text, null, true, true);
    }

    private static ThreadBubble untimedIn(String text) {
        return new ThreadBubble(ThreadEvent.Direction.IN, null, text, null, false, false);
    }

    /** Scheduled at T0 + 1 min from a thread whose newest bubble (a label time) was at T0. */
    private static Watermark timestamp(long newestTs) {
        return new Watermark(Watermark.Quality.TIMESTAMP, null, newestTs, null, T0 + MIN, null);
    }

    /** No newest message known (a number never texted, or a sheet capture without a thread). */
    private static Watermark deviceOnly(long capturedAt) {
        return new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, capturedAt, null);
    }

    private static ScheduledMessage message(Watermark wm, boolean holdIfReplied) {
        return new ScheduledMessage.Builder()
                .id(5L).createdAt(T0 + MIN).scheduledFor(T0 + 60 * MIN).text(TEXT).recipientE164(E164)
                .source(ScheduledMessage.Source.WEBVIEW).rung(Rung.R1).holdIfReplied(holdIfReplied)
                .latenessPolicy(LatenessPolicy.DEFAULT).status(ScheduledMessage.Status.IN_FLIGHT)
                .watermark(wm).build();
    }

    private static Classification classify(ScheduledMessage m, ThreadBubble... read) {
        return PreSendRead.classify(m, new ArrayList<>(Arrays.asList(read)), LIMIT, T0 + 60 * MIN);
    }

    // ------------------------------------------------------------------ classifier over read bubbles

    @Test public void newerIncomingAfterTheWatermarkIsReplied() {
        ScheduledMessage m = message(timestamp(T0), true);
        assertEquals(Classification.REPLIED, classify(m,
                out("see you tonight", T0 - 5 * MIN), in("sure", T0), in("actually wait", T0 + 30 * MIN)));
        assertEquals(PreSendCheck.Verdict.HOLD_REPLIED,
                PreSendCheck.check(OutboundPolicy.STILL_SEND).decide(m, Classification.REPLIED));
        assertEquals(HoldReason.REPLIED, PreSendCheck.Verdict.HOLD_REPLIED.holdReason());
    }

    @Test public void aReplyWithHoldIfRepliedOffStillSends() {
        ScheduledMessage m = message(timestamp(T0), false);
        Classification c = classify(m, in("sure", T0), in("actually wait", T0 + 30 * MIN));
        assertEquals(Classification.REPLIED, c);
        assertEquals(PreSendCheck.Verdict.SEND, PreSendCheck.check(OutboundPolicy.STILL_SEND).decide(m, c));
    }

    @Test public void unchangedThreadSends() {
        ScheduledMessage m = message(timestamp(T0), true);
        Classification c = classify(m, out("see you tonight", T0 - 5 * MIN), in("sure", T0));
        assertEquals(Classification.UNCHANGED, c);
        assertEquals(PreSendCheck.Verdict.SEND, PreSendCheck.required(OutboundPolicy.STILL_SEND).decide(m, c));
    }

    @Test public void anEchoOfOurOwnTextIsNotAReply() {
        // DEVICE_ONLY capture just after an earlier text of ours and its echo (the D17 number).
        ScheduledMessage m = message(deviceOnly(T0 + 30_000L), true);
        assertEquals(Classification.UNCHANGED, classify(m, out("test one", T0), in("test one", T0)));
        // A different inbound text in the same window is a genuine reply.
        assertEquals(Classification.REPLIED, classify(m, out("test one", T0), in("got it", T0)));
    }

    @Test public void anEchoAfterTheWatermarkLeavesOnlyTheOutbound() {
        // The user hand-sent an unrelated text after scheduling; the number echoed it back.
        ScheduledMessage m = message(timestamp(T0), true);
        Classification c = classify(m, in("sure", T0), out("parking now", T0 + 20 * MIN),
                in("parking now", T0 + 20 * MIN));
        assertEquals(Classification.OUTBOUND_ONLY, c);
        assertEquals(PreSendCheck.Verdict.SEND, PreSendCheck.check(OutboundPolicy.STILL_SEND).decide(m, c));
        assertEquals(PreSendCheck.Verdict.HOLD_OUTBOUND_ONLY,
                PreSendCheck.check(OutboundPolicy.HOLD_AND_ASK).decide(m, c));
    }

    @Test public void theWatermarksOwnBubbleInTheSameMinuteIsNotAReply() {
        // Two inbound bubbles in the newest minute at capture: the read aligns at the last one.
        ScheduledMessage m = message(timestamp(T0), true);
        assertEquals(Classification.UNCHANGED, classify(m, in("one", T0 - MIN), in("two", T0), in("three", T0)));
    }

    @Test public void sameMinuteBubblesAfterTheAnchorFollowTheClassifierWindows() {
        // Captured inside the anchor's minute: later bubbles of that minute may be new.
        ScheduledMessage m = message(new Watermark(Watermark.Quality.TIMESTAMP, null, T0, null,
                T0 + 20_000L, null), true);
        // An inbound right after the anchor, same minute: counts (inbound window reaches back 90 s).
        assertEquals(Classification.REPLIED, classify(m,
                in("two", T0), out("unrelated", T0), in("new reply", T0)));
        // An outbound in the anchor's minute: outbound must be strictly newer, so not counted.
        assertEquals(Classification.UNCHANGED, classify(m, in("two", T0), out("unrelated", T0)));
        // Captured after that minute was over: every bubble of the minute was already there.
        ScheduledMessage later = message(timestamp(T0), true);
        assertEquals(Classification.UNCHANGED, classify(later,
                in("two", T0), out("unrelated", T0), in("new reply", T0)));
    }

    @Test public void theUsersSimilarTextSelfPreempts() {
        ScheduledMessage m = message(timestamp(T0), true);
        Classification c = classify(m, in("sure", T0), out("running late, be there at 9!", T0 + 10 * MIN));
        assertEquals(Classification.SELF_PREEMPTED, c);
        assertEquals(PreSendCheck.Verdict.SELF_PREEMPTED,
                PreSendCheck.check(OutboundPolicy.STILL_SEND).decide(m, c));
    }

    @Test public void aFailedBubbleIsIgnored() {
        ScheduledMessage m = message(timestamp(T0), true);
        assertEquals(Classification.UNCHANGED, classify(m, in("sure", T0), failedOut(TEXT, T0 + 10 * MIN)));
    }

    @Test public void emptyThreadAndNoNewestMessageAtSchedulingIsUnchanged() {
        ScheduledMessage m = message(deviceOnly(T0 + MIN), true);
        assertEquals(Classification.UNCHANGED, classify(m));
        assertEquals(PreSendCheck.Verdict.SEND,
                PreSendCheck.required(OutboundPolicy.STILL_SEND).decide(m, Classification.UNCHANGED));
    }

    @Test public void emptyThreadWhenTheWatermarkHadMessagesCannotBeAligned() {
        assertEquals(Classification.UNVERIFIED, classify(message(timestamp(T0), true)));
    }

    @Test public void unreadableReadsAreUnverified() {
        ScheduledMessage m = message(deviceOnly(T0 + MIN), true);
        assertEquals(Classification.UNVERIFIED, PreSendRead.classify(m, null, LIMIT, T0));
        // A bubble without a time cannot be placed against a DEVICE_ONLY watermark.
        assertEquals(Classification.UNVERIFIED, classify(m, untimedIn("hello")));
    }

    @Test public void aMissingAnchorIsJudgedByTimeOnlyWhenTheReadReachesBack() {
        ScheduledMessage m = message(timestamp(T0), true);
        // No bubble at the anchor time, but an older one is there: judged by time.
        assertEquals(Classification.REPLIED, classify(m, in("older", T0 - 10 * MIN), in("new", T0 + 20 * MIN)));
        // A full read entirely newer than the anchor: cannot tell what is new.
        List<ThreadBubble> full = new ArrayList<>();
        for (int i = 0; i < LIMIT; i++) full.add(out("note " + i, T0 + (10 + i) * MIN));
        assertEquals(Classification.UNVERIFIED, PreSendRead.classify(m, full, LIMIT, T0 + 60 * MIN));
    }

    @Test public void untimedBubblesAfterTheAnchorCountAsNew() {
        ScheduledMessage m = message(timestamp(T0), true);
        assertEquals(Classification.REPLIED, classify(m, in("sure", T0), untimedIn("one more thing")));
    }

    // ------------------------------------------------------------------ two agreeing reads

    @Test public void twoAgreeingReadsAtLeastTheGapApartDecide() {
        PreSendRead r = new PreSendRead();
        List<ThreadBubble> a = Arrays.asList(in("sure", T0));
        assertNull(r.onRead(a, 1_000L));
        assertNull("too soon", r.onRead(new ArrayList<>(a), 1_000L + PreSendRead.MIN_GAP_MS - 1));
        assertNotNull(r.onRead(new ArrayList<>(a), 1_000L + PreSendRead.MIN_GAP_MS));
    }

    @Test public void aChangedOrMissingReadRestartsThePairing() {
        PreSendRead r = new PreSendRead();
        List<ThreadBubble> half = Collections.<ThreadBubble>emptyList();
        List<ThreadBubble> full = Arrays.asList(in("sure", T0), in("new", T0 + 5 * MIN));
        assertNull(r.onRead(half, 0L));
        assertNull("the page finished rendering: a new pair starts", r.onRead(full, 2_000L));
        assertNull(r.onRead(null, 3_000L));
        assertNull(r.onRead(full, 3_500L));
        assertNotNull(r.onRead(full, 5_000L));
    }

    // ------------------------------------------------------------------ verdicts and the dispatch rule

    @Test public void unreadableThreadVerdictDependsOnTheMode() {
        ScheduledMessage m = message(timestamp(T0), true);
        assertEquals(PreSendCheck.Verdict.HOLD_UNVERIFIED,
                PreSendCheck.required(OutboundPolicy.STILL_SEND).decide(m, Classification.UNVERIFIED));
        assertEquals(PreSendCheck.Verdict.CANNOT_CHECK,
                PreSendCheck.check(OutboundPolicy.STILL_SEND).decide(m, Classification.UNVERIFIED));
        assertEquals(PreSendCheck.Verdict.SEND, PreSendCheck.off().decide(m, Classification.REPLIED));
    }

    @Test public void missingListenerEvidenceIsReplacedOnlyForACheckingSenderAtAnAutonomousRung() {
        assertTrue(PreSendCheck.checkReplacesMissingEvidence(Classification.UNVERIFIED, Rung.R1, true));
        assertTrue(PreSendCheck.checkReplacesMissingEvidence(Classification.UNVERIFIED, Rung.R0, true));
        assertFalse(PreSendCheck.checkReplacesMissingEvidence(Classification.UNVERIFIED, Rung.R1, false));
        assertFalse(PreSendCheck.checkReplacesMissingEvidence(Classification.UNVERIFIED, Rung.R2, true));
        assertFalse(PreSendCheck.checkReplacesMissingEvidence(Classification.UNVERIFIED, Rung.R3, true));
        assertFalse("listener evidence applies first",
                PreSendCheck.checkReplacesMissingEvidence(Classification.REPLIED, Rung.R1, true));
        assertFalse(PreSendCheck.checkReplacesMissingEvidence(Classification.UNCHANGED, Rung.R1, true));
    }

    @Test public void verdictDetailsRoundTripAndReachTheEngine() {
        for (PreSendCheck.Verdict v : PreSendCheck.Verdict.values()) {
            if (v == PreSendCheck.Verdict.SEND) {
                assertNull(v.detail());
                continue;
            }
            assertEquals(v, PreSendCheck.Verdict.fromDetail(v.detail()));
            String toEngine = SendDetails.forEngine(SendOutcome.CANNOT_SHOW, v.detail());
            if (v == PreSendCheck.Verdict.CANNOT_CHECK) {
                assertEquals("could_not_send", toEngine);
            } else {
                assertEquals(v.detail(), toEngine);
            }
        }
        assertEquals("could_not_send",
                SendDetails.forEngine(SendOutcome.CANNOT_SHOW, ScheduledSendController.DETAIL_PRESEND_SLOW));
        assertNull(PreSendCheck.Verdict.fromDetail("could_not_send"));
        assertNull(PreSendCheck.Verdict.fromDetail(null));
    }

    // ------------------------------------------------------------------ the Send gate

    @Test public void noClickIsGrantedWhileTheCheckIsPending() {
        ScheduledCompose c = new ScheduledCompose(5L);
        c.requirePreSendCheck();
        String token = c.nextInjection();
        assertTrue(c.onTyped(token));
        assertFalse(c.grantClick(token));
        assertTrue(c.wasClickRefusedForCheck());
        assertFalse("the script will not ask again: the job cannot send", c.clearPreSend());
        assertFalse(c.isClickGranted());
    }

    @Test public void aClearedCheckLetsTheClickThroughOnce() {
        ScheduledCompose c = new ScheduledCompose(5L);
        c.requirePreSendCheck();
        String token = c.nextInjection();
        assertTrue(c.isPreSendPending());
        assertTrue(c.clearPreSend());
        assertFalse(c.isPreSendPending());
        assertTrue(c.grantClick(token));
        assertFalse(c.grantClick(token));
    }

    @Test public void aFailureWhileTheCheckIsPendingSentNothing() {
        ScheduledCompose c = new ScheduledCompose(5L);
        c.requirePreSendCheck();
        String token = c.nextInjection();
        assertTrue(c.onTyped(token));
        assertEquals(SendOutcome.CANNOT_SHOW, c.closeForFailure());
        // Without a check the typed rule is unchanged (LIVE-F8).
        ScheduledCompose plain = new ScheduledCompose(6L);
        assertTrue(plain.onTyped(plain.nextInjection()));
        assertEquals(SendOutcome.SENT_UNVERIFIED, plain.closeForFailure());
    }

    @Test public void theCheckOnlyEverAddsHoldsNeverSendsForAHold() {
        // Across every classification, a REPLIED read with holdIfReplied never yields SEND.
        ScheduledMessage m = message(timestamp(T0), true);
        for (PreSendCheck check : Arrays.asList(PreSendCheck.check(OutboundPolicy.STILL_SEND),
                PreSendCheck.required(OutboundPolicy.HOLD_AND_ASK))) {
            assertFalse(check.decide(m, Classification.REPLIED) == PreSendCheck.Verdict.SEND);
            assertFalse(check.decide(m, Classification.SELF_PREEMPTED) == PreSendCheck.Verdict.SEND);
        }
    }
}
