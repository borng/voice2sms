package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.inboundSms;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.PreSendCheck;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * live-presend (D30, LIVE-F3; OD22(5), OD27, R28): the engine's dispatch rule and the outcome
 * mapping of the sender's pre-send check. Fictional data only (EngineFakes).
 */
public class LivePresendEngineTest {

    private static final long ID = 83L;

    private EngineFakes.World w;
    private CheckingSender webView;

    /** A sender that reads the thread before it sends, like WebViewScheduledSender. */
    static final class CheckingSender implements ScheduledSender {
        final List<PreSendCheck> checks = new ArrayList<>();
        final EngineFakes.FakeStore store;
        boolean checksThread = true;

        CheckingSender(EngineFakes.FakeStore store) { this.store = store; }

        @Override public boolean start(Context context, ScheduledMessage m) {
            throw new AssertionError("the engine must pass the pre-send check");
        }

        @Override public boolean start(Context context, ScheduledMessage m, PreSendCheck check) {
            checks.add(check);
            return true;
        }

        @Override public boolean checksThreadBeforeSend() { return checksThread; }
    }

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        SendLaterEngine.installForTest(w.deps(true));
        webView = new CheckingSender(w.store);
        SendLaterEngine.setSender(webView);
    }

    @After public void tearDown() {
        SendLaterEngine.resetForTest();
    }

    // ------------------------------------------------------------------ dispatch rule (OD22(5), D30)

    @Test public void noListenerEvidenceAndAWebViewSenderSendsWithARequiredCheck() {
        w.source.available = false;   // the listener cannot vouch for this number
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertEquals(1, webView.checks.size());
        assertEquals(PreSendCheck.Mode.REQUIRED, webView.checks.get(0).getMode());
        assertFalse(w.notifier.has("hold " + ID + " UNVERIFIED"));
    }

    @Test public void theRequiredCheckCarriesTheOutboundPolicy() {
        w.source.available = false;
        w.cond.outbound = OutboundPolicy.HOLD_AND_ASK;
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(OutboundPolicy.HOLD_AND_ASK, webView.checks.get(0).getOutboundPolicy());
    }

    @Test public void listenerEvidenceSendsWithAnAddingCheck() {
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertEquals(PreSendCheck.Mode.CHECK, webView.checks.get(0).getMode());
    }

    @Test public void aListenerReplyStillHoldsBeforeAnySend() {
        w.source.events.add(inboundSms(T - 10 * MIN));
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.REPLIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(webView.checks.isEmpty());
        assertTrue(w.notifier.has("hold " + ID + " REPLIED"));
    }

    @Test public void aSenderThatCannotReadTheThreadStillHoldsUnverified() {
        webView.checksThread = false;
        w.source.available = false;
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(webView.checks.isEmpty());
    }

    @Test public void reminderRungsAreNotTurnedIntoSends() {
        w.source.available = false;
        w.store.put(message(ID).rung(Rung.R2).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(webView.checks.isEmpty());
    }

    @Test public void lateWithoutEvidenceStillReminds() {
        w.source.available = false;
        w.cond.now = T + 2 * EngineFakes.HOUR;   // past the DEFAULT 30 min budget
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(webView.checks.isEmpty());
    }

    @Test public void sendAnywayDispatchesWithoutACheck() {
        w.store.put(message(ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        SendLaterEngine.onSendAnyway(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertTrue(webView.checks.get(0).isOff());
    }

    // ------------------------------------------------------------------ outcome mapping

    private void dispatched() {
        w.source.available = false;
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
    }

    @Test public void aPreSendReplyHoldsWithTheNormalReplyNotification() {
        dispatched();
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, PreSendCheck.Verdict.HOLD_REPLIED.detail());
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.REPLIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.notifier.has("hold " + ID + " REPLIED"));
        assertFalse(w.notifier.has("mayHaveSent " + ID));
        assertFalse(w.alarms.registered(AlarmKind.EXPIRE, ID).isEmpty());
    }

    @Test public void anUnreadableRequiredCheckHoldsUnverifiedWithoutMayHaveSent() {
        dispatched();
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW,
                PreSendCheck.Verdict.HOLD_UNVERIFIED.detail());
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.notifier.has("hold " + ID + " UNVERIFIED"));
        assertFalse(w.notifier.has("mayHaveSent " + ID));
    }

    @Test public void anOutboundUnderHoldAndAskHoldsOutboundOnly() {
        dispatched();
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW,
                PreSendCheck.Verdict.HOLD_OUTBOUND_ONLY.detail());
        assertEquals(HoldReason.OUTBOUND_ONLY, w.store.rows.get(ID).getHoldReason());
    }

    @Test public void theUsersOwnSimilarTextSelfPreempts() {
        dispatched();
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW,
                PreSendCheck.Verdict.SELF_PREEMPTED.detail());
        assertEquals(Status.SELF_PREEMPTED, w.store.status(ID));
        assertTrue(w.notifier.has("selfPreempted " + ID));
    }

    @Test public void anUnreadableCheckWithEvidenceReminds() {
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        // The send path maps presend_unreadable to could_not_send (SendDetails).
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, RemindCause.DETAIL_COULD_NOT_SEND);
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertFalse(w.notifier.has("mayHaveSent " + ID));
        assertFalse(w.notifier.has("hold " + ID + " UNVERIFIED"));
    }
}
