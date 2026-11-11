package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.E164;
import static com.voice2sms.sendlater.engine.EngineFakes.HOUR;
import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.OTHER_E164;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.inboundCall;
import static com.voice2sms.sendlater.engine.EngineFakes.inboundSms;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static com.voice2sms.sendlater.engine.EngineFakes.outboundSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.NetworkState;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.SessionState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * SendLaterEngine acceptance (R22-R29, R31, R33, I2, I5, I6) over the fakes in {@link EngineFakes}.
 * Default fixture: id 7, created T0, due T = T0 + 2 h (DEFAULT policy: 30 min lateness budget),
 * holdIfReplied on, rung R1, watermark at T0 - 10 min; listener source available and empty,
 * session READY, network CONNECTED, now = T.
 */
public class SendLaterEngineTest {

    private static final long ID = 7L;
    private EngineFakes.World w;

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        SendLaterEngine.installForTest(w.deps(true));
    }

    @After public void tearDown() {
        SendLaterEngine.resetForTest();
    }

    private void seed(ScheduledMessage m) { w.store.put(m); }

    private void seedDefault() { seed(message(ID).build()); }

    private void withSender() { SendLaterEngine.setSender(w.sender); }

    private void assertNoSend() {
        assertTrue("sender must not be started: " + w.j, w.sender.started.isEmpty());
    }

    private void assertBroadcast(long id) {
        assertTrue("ACTION_STATE_CHANGED {id, e164} after the status change: " + w.broadcaster.sent,
                w.broadcaster.sent.contains(id + " " + E164));
    }

    /** An EXPIRE alarm (inexact) for {@code id} at least 24 h after scheduledFor, at most now + 24 h. */
    private void assertExpiryAlarm(long id, long nowMs) {
        List<String> exp = w.alarms.matching("inexact " + AlarmKind.EXPIRE + " " + id + " ");
        assertFalse("entering HELD/REMIND registers an inexact +24 h expiry alarm: " + w.alarms.calls,
                exp.isEmpty());
        long at = w.alarms.timeOf("inexact " + AlarmKind.EXPIRE + " " + id + " ");
        assertTrue("expiry no earlier than scheduledFor + 24 h, was " + (at - T), at >= T + 24 * HOUR);
        assertTrue("expiry no later than now + 24 h (+1 min slack), was " + (at - nowMs),
                at <= nowMs + 24 * HOUR + MIN);
    }

    // ================================================================== Decision kinds at due time

    @Test public void sendDecisionMarksInFlightWithBodyHashThenStartsSenderOnce() {
        seedDefault();
        withSender();
        SendLaterEngine.onDue(null, ID);
        assertEquals(1, w.sender.started.size());
        assertEquals("IN_FLIGHT is recorded before the send starts (R31)",
                Status.IN_FLIGHT, w.sender.statusAtStart.get(0));
        assertNotNull("IN_FLIGHT carries a body hash (R31)", w.store.bodyHash.get(ID));
        assertTrue("source read before the send (thread check, R25)",
                w.j.indexOf("source.read") >= 0 && w.j.indexOf("source.read") < w.j.indexOf("sender.start"));
        assertBroadcast(ID);
    }

    @Test public void senderReturningTrueDoesNotMarkSentOnlyOnSendOutcomeDoes() {
        seedDefault();
        withSender();
        SendLaterEngine.onDue(null, ID);
        assertEquals("sender.start returning true is not a send result", Status.IN_FLIGHT, w.store.status(ID));
        assertEquals(0, w.j.count("store.markSentVerified"));
        assertFalse(w.notifier.has("sent " + ID));

        w.cond.now = T + 30_000;
        w.source.events.add(outboundSame(T + 20_000));
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, null);
        assertEquals(Status.SENT_VERIFIED, w.store.status(ID));
        assertTrue("\"Sent\" notification: " + w.notifier.posted, w.notifier.has("sent " + ID));
        assertBroadcast(ID);
    }

    @Test public void holdDecisionOnReplySetsHeldPostsHoldNotificationAndExpiryAlarm() {
        seedDefault();
        withSender();
        w.source.events.add(inboundSms(T - 30 * MIN));
        SendLaterEngine.onDue(null, ID);
        assertNoSend();
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.REPLIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.notifier.posted.toString(), w.notifier.has("hold " + ID + " REPLIED"));
        assertBroadcast(ID);
        assertExpiryAlarm(ID, T);
    }

    @Test public void replyWithHoldIfRepliedOffStillSends() {
        seed(message(ID).holdIfReplied(false).build());
        withSender();
        w.source.events.add(inboundSms(T - 30 * MIN));
        SendLaterEngine.onDue(null, ID);
        assertEquals(1, w.sender.started.size());
        assertFalse(w.notifier.has("hold " + ID + " REPLIED"));
    }

    @Test public void cancelDecisionOnSimilarOutboundSetsSelfPreemptedAndInfoNotification() {
        seedDefault();
        withSender();
        w.source.events.add(outboundSame(T - 30_000));
        SendLaterEngine.onDue(null, ID);
        assertNoSend();
        assertEquals(Status.SELF_PREEMPTED, w.store.status(ID));
        assertTrue(w.notifier.posted.toString(), w.notifier.has("selfPreempted " + ID));
        assertBroadcast(ID);
    }

    @Test public void remindOnMissingSessionKeepsScheduledPostsRemindAndExpiryAlarm() {
        seedDefault();
        withSender();
        w.cond.session = SessionState.MISSING;
        SendLaterEngine.onDue(null, ID);
        assertNoSend();
        assertEquals("a reminded message keeps status SCHEDULED", Status.SCHEDULED, w.store.status(ID));
        assertTrue(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " NO_SESSION"));
        assertExpiryAlarm(ID, T);
    }

    @Test public void remindWhenLockedAndSendActivityCannotShow() {
        seedDefault();
        withSender();
        w.cond.session = SessionState.LOCKED_UNSHOWABLE;
        SendLaterEngine.onDue(null, ID);
        assertNoSend();
        assertTrue(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " LOCKED"));
    }

    @Test public void lateBeyondBudgetNeverAutoSendsAndReminds() {
        seedDefault();
        withSender();
        w.cond.now = T + 45 * MIN; // budget 30 min (I2)
        SendLaterEngine.onDue(null, ID);
        assertNoSend();
        assertTrue(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " LATE"));
    }

    @Test public void waitOnNoNetworkRegistersNetworkCallbackAndInexactBudgetDeadlineAlarm() {
        seedDefault();
        withSender();
        w.cond.network = NetworkState.NONE;
        SendLaterEngine.onDue(null, ID);
        assertNoSend();
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue("registerDefaultNetworkCallback for the message", w.network.waits.contains(ID));
        assertEquals("inexact alarm at the budget deadline (T + 30 min)",
                T + 30 * MIN, w.alarms.timeOf("inexact " + AlarmKind.RETRY + " " + ID + " "));
        assertTrue("no remind/hold while waiting: " + w.notifier.posted, w.notifier.posted.isEmpty());
    }

    @Test public void dueAlarmFiringEarlyNeverSendsEarly() {
        seedDefault();
        withSender();
        w.cond.now = T - 10 * MIN;
        SendLaterEngine.onDue(null, ID);
        assertNoSend();
        assertEquals(Status.SCHEDULED, w.store.status(ID));
    }

    @Test public void expiredDecisionAtDueSetsExpiredAndNotifies() {
        seedDefault();
        withSender();
        w.cond.now = T + 25 * HOUR;
        w.cond.session = SessionState.MISSING;
        SendLaterEngine.onDue(null, ID);
        assertNoSend();
        assertEquals(Status.EXPIRED, w.store.status(ID));
        assertTrue("EXPIRED posts a notification: " + w.notifier.posted, w.notifier.has("expired " + ID));
        assertBroadcast(ID);
    }

    @Test public void noAvailableSourceAtDueHoldsUnverifiedWithoutReading() {
        seedDefault();
        withSender();
        w.source.available = false;
        w.cond.listener = false;
        SendLaterEngine.onDue(null, ID);
        assertNoSend();
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.notifier.posted.toString(), w.notifier.has("hold " + ID + " UNVERIFIED"));
    }

    @Test public void withoutARegisteredSenderSendDecisionBecomesRemindNoSender() {
        seedDefault();
        // no setSender: the default records REMIND(NO_SENDER)
        SendLaterEngine.onDue(null, ID);
        assertTrue(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " NO_SENDER"));
        assertEquals("no IN_FLIGHT without a sender", Status.SCHEDULED, w.store.status(ID));
        assertEquals(0, w.j.count("store.markInFlight"));
    }

    @Test public void dueAlarmForTerminalMessageIsIgnored() {
        seed(message(ID).status(Status.CANCELED).build());
        withSender();
        SendLaterEngine.onDue(null, ID);
        SendLaterEngine.onDue(null, 999L); // unknown id
        assertNoSend();
        assertEquals(Status.CANCELED, w.store.status(ID));
        assertTrue(w.notifier.posted.isEmpty());
    }

    // ================================================================== early hold (R33)

    @Test public void inboundTextBeforeDueHoldsCancelsAlarmsNotifiesAndBroadcasts() {
        seedDefault();
        withSender();
        w.cond.now = T - HOUR;
        w.source.events.add(inboundSms(T - HOUR));
        SendLaterEngine.onInboundEvent(null, E164, inboundSms(T - HOUR));
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.REPLIED, w.store.rows.get(ID).getHoldReason());
        assertFalse("last-call alarm cancelled: " + w.alarms.calls,
                w.alarms.matching("cancel " + AlarmKind.LAST_CALL + " " + ID).isEmpty());
        assertFalse("due alarm cancelled: " + w.alarms.calls,
                w.alarms.matching("cancel " + AlarmKind.DUE + " " + ID).isEmpty());
        assertTrue(w.notifier.posted.toString(), w.notifier.has("hold " + ID + " REPLIED"));
        assertBroadcast(ID);
        assertNoSend();
    }

    @Test public void inboundCallBeforeDueHoldsAsReachedOut() {
        seedDefault();
        w.cond.now = T - HOUR;
        w.source.events.add(inboundCall(T - HOUR));
        SendLaterEngine.onInboundEvent(null, E164, inboundCall(T - HOUR));
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.REACHED_OUT, w.store.rows.get(ID).getHoldReason());
    }

    @Test public void earlyHoldRespectsHoldIfRepliedOff() {
        seed(message(ID).holdIfReplied(false).build());
        w.cond.now = T - HOUR;
        w.source.events.add(inboundSms(T - HOUR));
        SendLaterEngine.onInboundEvent(null, E164, inboundSms(T - HOUR));
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(w.alarms.matching("cancel " + AlarmKind.DUE + " " + ID).isEmpty());
        assertFalse(w.notifier.has("hold " + ID + " REPLIED"));
    }

    @Test public void earlyHoldIgnoresOutboundEventsAndOtherConversations() {
        seedDefault();
        w.cond.now = T - HOUR;
        SendLaterEngine.onInboundEvent(null, OTHER_E164, inboundSms(T - HOUR));
        SendLaterEngine.onInboundEvent(null, E164, new com.voice2sms.sendlater.domain.ThreadEvent(
                com.voice2sms.sendlater.domain.ThreadEvent.Direction.OUT,
                com.voice2sms.sendlater.domain.ThreadEvent.Kind.SMS, T - HOUR,
                com.voice2sms.sendlater.domain.ThreadEvent.hashOf("unrelated words here"),
                "unrelated words here"));
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(w.notifier.posted.toString(), w.notifier.posted.isEmpty());
    }

    // ================================================================== Send anyway (R28, I6)

    @Test public void sendAnywaySkipsTheThreadCheckAndLatenessForAHeldMessage() {
        seed(message(ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        withSender();
        w.cond.now = T + 3 * HOUR; // past the 30 min budget: the user's tap may still send
        w.source.events.add(inboundSms(T - 30 * MIN));
        SendLaterEngine.onSendAnyway(null, ID);
        assertEquals("send anyway sends despite the reply: " + w.j, 1, w.sender.started.size());
        assertEquals(Status.IN_FLIGHT, w.sender.statusAtStart.get(0));
    }

    @Test public void sendAnywayIsCancelledBySimilarOutboundWithinTheLastMinute() {
        seed(message(ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        withSender();
        w.cond.now = T + HOUR;
        w.source.events.add(outboundSame(T + HOUR - 30_000));
        SendLaterEngine.onSendAnyway(null, ID);
        assertNoSend();
        assertEquals(Status.SELF_PREEMPTED, w.store.status(ID));
        assertTrue(w.notifier.posted.toString(), w.notifier.has("selfPreempted " + ID));
    }

    @Test public void staleSendAnywayTapsAreDropped() {
        Status[] stale = {Status.CANCELED, Status.SENT_VERIFIED, Status.EXPIRED, Status.IN_FLIGHT,
                Status.SELF_PREEMPTED, Status.FAILED};
        withSender();
        for (int i = 0; i < stale.length; i++) {
            seed(message(100 + i).status(stale[i]).build());
        }
        for (int i = 0; i < stale.length; i++) {
            SendLaterEngine.onSendAnyway(null, 100 + i); // must not throw
            assertEquals(stale[i], w.store.status(100 + i));
        }
        assertNoSend();
    }

    // ================================================================== IN_FLIGHT resolution (R23, R31, I5)

    @Test public void inFlightWithOwnBubbleInTheThreadIsHeldNotVerified() {
        seed(message(ID).status(Status.IN_FLIGHT).build());
        withSender();
        w.cond.now = T + MIN;
        w.source.events.add(outboundSame(T + 10_000));
        SendLaterEngine.resolveInFlight(null);
        // OD27: a thread read never verifies; HELD(UNVERIFIED) + "may have already sent".
        assertEquals("IN_FLIGHT at restart is held", Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.notifier.has("mayHaveSent " + ID));
        assertFalse(w.notifier.has("selfPreempted " + ID));
        assertTrue("no read, no verification: " + w.j,
                w.j.indexOf("source.read") < 0 && w.j.indexOf("store.markSentVerified") < 0);
        assertNoSend();
        assertBroadcast(ID);
    }

    @Test public void inFlightWithNoAvailableSourceHoldsUnverifiedAndNeverRetries() {
        seed(message(ID).status(Status.IN_FLIGHT).build());
        withSender();
        w.cond.now = T + MIN;
        w.source.available = false;
        w.cond.listener = false;
        SendLaterEngine.resolveInFlight(null);
        assertNoSend();
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue("'may have already sent' notification: " + w.notifier.posted,
                w.notifier.has("mayHaveSent " + ID));
        assertEquals(0, w.j.count("store.releaseForRetry"));
    }

    @Test public void inFlightWithoutBubbleIsNeverRetried() {
        seed(message(ID).status(Status.IN_FLIGHT).build());
        withSender();
        w.cond.now = T + MIN;
        SendLaterEngine.resolveInFlight(null);
        // OD27 (amends OD21(1)): no automatic retry; the user's Send anyway is the only retry.
        assertTrue("no retry: " + w.j, w.j.indexOf("sender.start") < 0);
        assertTrue("no release: " + w.j, w.j.indexOf("store.releaseForRetry") < 0);
        assertTrue(w.sender.started.isEmpty());
        assertEquals(Status.HELD, w.store.status(ID));
        assertNotEquals(Status.SENT_VERIFIED, w.store.status(ID));
    }

    @Test public void inFlightWithoutBubbleBeyondBudgetIsNotRetried() {
        seed(message(ID).status(Status.IN_FLIGHT).build());
        withSender();
        w.cond.now = T + 45 * MIN;
        SendLaterEngine.resolveInFlight(null);
        assertNoSend();
        assertNotEquals(Status.SENT_VERIFIED, w.store.status(ID));
        assertFalse("the user is told: " + w.j, w.notifier.posted.isEmpty());
    }

    // ================================================================== onSendOutcome (R31)

    @Test public void replyTimedBetweenCheckAndSendNotifiesRepliedJustAsSent() {
        seedDefault();
        withSender();
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        w.source.events.add(inboundSms(T + 10_000));
        w.source.events.add(outboundSame(T + 20_000));
        w.cond.now = T + 30_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, null);
        assertEquals(Status.SENT_VERIFIED, w.store.status(ID));
        assertTrue(w.notifier.posted.toString(), w.notifier.has("repliedDuringSend " + ID));
    }

    @Test public void failedOutcomeIsNeverBlindlyRetried() {
        seedDefault();
        withSender();
        SendLaterEngine.onDue(null, ID);
        int outcomeAt = w.j.lines.size();
        w.cond.now = T + 30_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.FAILED, "test");
        List<String> after = new ArrayList<>(w.j.lines.subList(outcomeAt, w.j.lines.size()));
        int start = indexOf(after, "sender.start");
        if (start >= 0) {
            int read = indexOf(after, "source.read");
            assertTrue("retry only after a thread read (I5): " + after, read >= 0 && read < start);
        }
        assertNotEquals(Status.SENT_VERIFIED, w.store.status(ID));
    }

    @Test public void outcomeForAMessageNoLongerInFlightChangesNothing() {
        seed(message(ID).status(Status.CANCELED).build());
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, null);
        SendLaterEngine.onSendOutcome(null, 999L, SendOutcome.FAILED, null);
        assertEquals(Status.CANCELED, w.store.status(ID));
    }

    // ================================================================== expiry sweep (R29)

    @Test public void sweepExpiresHeldAndRemindedMessagesAfter24hAndNotifies() {
        seed(message(1).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        seed(message(2).build()); // SCHEDULED, reminded and ignored
        seed(message(3).status(Status.HELD).holdReason(HoldReason.REPLIED).scheduledFor(T + 23 * HOUR).build());
        seed(message(4).status(Status.IN_FLIGHT).build());
        w.cond.now = T + 25 * HOUR;
        w.cond.session = SessionState.MISSING;
        SendLaterEngine.sweepExpired(null);
        assertEquals(Status.EXPIRED, w.store.status(1));
        assertEquals(Status.EXPIRED, w.store.status(2));
        assertEquals("held < 24 h past its time stays held", Status.HELD, w.store.status(3));
        assertNotEquals("IN_FLIGHT is resolved by a read, never expired blind", Status.EXPIRED, w.store.status(4));
        assertTrue(w.notifier.posted.toString(), w.notifier.has("expired 1"));
        assertTrue(w.notifier.posted.toString(), w.notifier.has("expired 2"));
        assertFalse(w.notifier.has("expired 3"));
        assertTrue(w.broadcaster.sent.contains(1 + " " + E164));
    }

    // ================================================================== onProcessStart (R23)

    @Test public void onProcessStartRunsInBackgroundResolvingInFlightBeforeSweeping() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        SendLaterEngine.installForTest(w.deps(false)); // queueing executor
        SendLaterEngine.setSender(w.sender);
        seed(message(1).status(Status.IN_FLIGHT).scheduledFor(T).build());
        seed(message(2).status(Status.HELD).holdReason(HoldReason.REPLIED).scheduledFor(T - 25 * HOUR)
                .createdAt(T - 27 * HOUR).build());
        w.cond.now = T + MIN;
        w.source.events.add(outboundSame(T + 10_000));

        SendLaterEngine.onProcessStart(null);
        assertFalse("work is handed to the background executor", w.queued.isEmpty());
        assertEquals("nothing runs on the calling thread", Status.IN_FLIGHT, w.store.status(1));
        assertEquals(Status.HELD, w.store.status(2));

        for (int guard = 0; !w.queued.isEmpty() && guard < 20; guard++) w.queued.remove(0).run();

        // OD27: held, never verified from the thread.
        assertEquals(Status.HELD, w.store.status(1));
        assertEquals(Status.EXPIRED, w.store.status(2));
        int resolved = w.j.indexOf("store.hold 1 UNVERIFIED");
        assertTrue("IN_FLIGHT resolved before anything else (R23): " + w.j,
                resolved >= 0 && resolved < w.j.indexOf("store.expire 2"));
        assertNoSend();
    }

    private static int indexOf(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(prefix)) return i;
        return -1;
    }
}
