package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.NAME;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.ThreadEventSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * dl-actions (UI M4, UI M8; R24, R28, I6): a Cancel / Send now / Send anyway / Snooze tap on a row
 * that changed tells the user what happened; the last-call title states the real time left and
 * the last call loses Cancel / Send now once the send has started. Fictional data only.
 */
public class StaleTapAndLastCallTest {

    private static final long ID = 61L;

    private EngineFakes.World w;
    private final List<String> stale = new ArrayList<>();
    private final List<Long> sending = new ArrayList<>();

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        final EngineFakes.FakeNotifier base = w.notifier;
        EngineNotifier notifier = new EngineNotifier() {
            @Override public void lastCall(ScheduledMessage m) { base.lastCall(m); }
            @Override public void hold(ScheduledMessage m, HoldReason r, long nowMs) { base.hold(m, r, nowMs); }
            @Override public void remind(ScheduledMessage m, RemindReason r) { base.remind(m, r); }
            @Override public void sent(ScheduledMessage m) { base.sent(m); }
            @Override public void repliedDuringSend(ScheduledMessage m) { base.repliedDuringSend(m); }
            @Override public void selfPreempted(ScheduledMessage m) { base.selfPreempted(m); }
            @Override public void expired(ScheduledMessage m) { base.expired(m); }
            @Override public void mayHaveSent(ScheduledMessage m) { base.mayHaveSent(m); }
            @Override public void sending(ScheduledMessage m) { sending.add(m.getId()); }
            @Override public void staleTap(long id, ScheduledMessage m, TapResult r, boolean cancelTap) {
                stale.add(id + " " + r + " " + cancelTap + " " + (m == null ? "null" : m.getStatus()));
            }
        };
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store)
                .sources(new ArrayList<ThreadEventSource>(Collections.singletonList(w.source)))
                .notifier(notifier)
                .broadcaster(w.broadcaster)
                .alarms(w.alarms)
                .conditions(w.cond)
                .networkWaiter(w.network)
                .executor(Runnable::run)
                .build());
        SendLaterEngine.setSender(w.sender);
    }

    @After public void tearDown() { SendLaterEngine.resetForTest(); }

    private void put(Status s) {
        w.store.put(message(ID).status(s).build());
    }

    // ------------------------------------------------------------------ UI M4: stale taps

    @Test public void cancelOnASendingRowSaysItCannotBeCancelledAndChangesNothing() {
        put(Status.IN_FLIGHT);
        assertEquals(TapResult.ALREADY_SENDING,
                SendLaterEngine.onNotificationTap(null, SendLaterIntents.ACTION_CANCEL, ID));
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertEquals(Collections.singletonList(ID + " ALREADY_SENDING true IN_FLIGHT"), stale);
        assertEquals("Already sending — it can't be cancelled now",
                TapResult.feedback(TapResult.ALREADY_SENDING, true));
    }

    @Test public void sendNowAndSendAnywayOnASendingRowSayAlreadySendingAndDoNotStartAgain() {
        put(Status.IN_FLIGHT);
        assertEquals(TapResult.ALREADY_SENDING,
                SendLaterEngine.onNotificationTap(null, SendLaterIntents.ACTION_SEND_NOW, ID));
        assertEquals(TapResult.ALREADY_SENDING,
                SendLaterEngine.onNotificationTap(null, SendLaterIntents.ACTION_SEND_ANYWAY, ID));
        assertTrue(w.sender.started.isEmpty());
        assertEquals(2, stale.size());
        assertTrue(stale.get(0).startsWith(ID + " ALREADY_SENDING false"));
        assertEquals("Already sending", TapResult.feedback(TapResult.ALREADY_SENDING, false));
    }

    @Test public void tapsOnSentCancelledAndGoneRowsNameWhatHappened() {
        put(Status.SENT_VERIFIED);
        assertEquals(TapResult.ALREADY_SENT, SendLaterEngine.onNotificationTap(null, SendLaterIntents.ACTION_CANCEL, ID));
        put(Status.CANCELED);
        assertEquals(TapResult.ALREADY_CANCELLED,
                SendLaterEngine.onNotificationTap(null, SendLaterIntents.ACTION_SEND_ANYWAY, ID));
        put(Status.EXPIRED);
        assertEquals(TapResult.NOT_PENDING, SendLaterEngine.onNotificationTap(null, SendLaterIntents.ACTION_SNOOZE, ID));
        assertEquals(TapResult.NOT_PENDING, SendLaterEngine.onNotificationTap(null, SendLaterIntents.ACTION_CANCEL, 999L));
        assertEquals(4, stale.size());
        assertEquals("999 NOT_PENDING true null", stale.get(3));
        assertEquals("Already sent", TapResult.feedback(TapResult.ALREADY_SENT, true));
        assertEquals("Already cancelled", TapResult.feedback(TapResult.ALREADY_CANCELLED, false));
        assertNull(TapResult.feedback(TapResult.DONE, true));
    }

    @Test public void aTapThatAppliesPostsNoStaleNotice() {
        put(Status.SCHEDULED);
        assertEquals(TapResult.DONE, SendLaterEngine.onNotificationTap(null, SendLaterIntents.ACTION_CANCEL, ID));
        assertEquals(Status.CANCELED, w.store.status(ID));
        put(Status.HELD);
        assertEquals(TapResult.DONE, SendLaterEngine.onNotificationTap(null, SendLaterIntents.ACTION_SEND_ANYWAY, ID));
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertTrue(stale.isEmpty());
    }

    @Test public void sheetCancelReturnsTheResultWithoutANotice() {
        put(Status.IN_FLIGHT);
        assertEquals(TapResult.ALREADY_SENDING, SendLaterEngine.cancel(null, ID));
        put(Status.SCHEDULED);
        assertEquals(TapResult.DONE, SendLaterEngine.cancel(null, ID));
        assertEquals(Status.CANCELED, w.store.status(ID));
        assertTrue("the sheet shows its own inline line", stale.isEmpty());
    }

    @Test public void staleForMapsEveryStatus() {
        assertEquals(TapResult.NOT_PENDING, TapResult.staleFor(null));
        for (Status s : Status.values()) {
            TapResult r = TapResult.staleFor(message(ID).status(s).build());
            if (s == Status.SCHEDULED || s == Status.HELD) assertNull(s.toString(), r);
            else assertTrue(s.toString(), r != null && r != TapResult.DONE);
        }
    }

    @Test public void stoppedSendGetsItsOwnReminderTitle() {
        String t = SendLaterNotifications.cannotSendTitle(message(ID).build(), RemindCause.STOPPED);
        assertTrue(t, t.startsWith("You stopped the send to " + NAME));
        assertFalse(SendLaterNotifications.hasSignInAction(RemindCause.STOPPED));
    }

    // ------------------------------------------------------------------ UI M8: last call

    @Test public void lastCallTitleStatesTheRealTimeLeft() {
        ScheduledMessage m = message(ID).build();
        assertEquals("Sending to " + NAME + " in 5 min", SendLaterNotifications.lastCallTitle(m, T - 5 * MIN));
        assertEquals("Sending to " + NAME + " in 5 min", SendLaterNotifications.lastCallTitle(m, T - 5 * MIN + 2_000L));
        assertEquals("Sending to " + NAME + " in 3 min", SendLaterNotifications.lastCallTitle(m, T - 160_000L));
        assertEquals("Sending to " + NAME + " in 2 min", SendLaterNotifications.lastCallTitle(m, T - 100_000L));
        assertEquals("Sending to " + NAME + " in 1 min", SendLaterNotifications.lastCallTitle(m, T - 10_000L));
        assertEquals("Sending to " + NAME + " now", SendLaterNotifications.lastCallTitle(m, T));
        assertEquals("Sending to " + NAME + " now", SendLaterNotifications.lastCallTitle(m, T + 30_000L));
    }

    @Test public void startedSendReplacesAShownLastCall() {
        put(Status.SCHEDULED);
        w.cond.now = T - 5 * MIN;
        SendLaterEngine.onLastCall(null, ID);
        assertTrue(w.notifier.has("lastCall " + ID));
        w.cond.now = T;
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertEquals(Collections.singletonList(ID), sending);
    }

    @Test public void startedSendWithoutALastCallPostsNoSendingNotice() {
        put(Status.SCHEDULED);
        w.cond.now = T;
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertTrue(sending.isEmpty());
        assertFalse(w.notifier.has("lastCall " + ID));
    }
}
