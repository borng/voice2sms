package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.HOUR;
import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.inboundSms;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static com.voice2sms.sendlater.engine.EngineFakes.outboundSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.NetworkState;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.SessionState;
import com.voice2sms.sendlater.domain.ThreadEventSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * engine-followups-3 (OD23 (2)-(5), OD24, R27, R31, AC11, I6): the Send Later master switch at
 * process start, boot, due, network re-fire and disable; reminder wording by CANNOT_SHOW cause;
 * the bounded "replied just as your message sent" window. Fictional numbers only.
 */
public class SendLaterEngineFollowups3Test {

    private static final long ID = 31L;
    private static final long HELD_ID = 32L;

    private EngineFakes.World w;
    private Switchable cond;
    private RecordingWaiter waiter;
    private CauseNotifier notifier;

    /** Delegates to the world's conditions and adds the master switch. */
    static final class Switchable implements EngineConditions {
        final EngineFakes.FakeConditions base;
        boolean enabled = true;
        Switchable(EngineFakes.FakeConditions base) { this.base = base; }
        @Override public long nowMs() { return base.nowMs(); }
        @Override public SessionState session() { return base.session(); }
        @Override public NetworkState network() { return base.network(); }
        @Override public boolean listenerEnabled() { return base.listenerEnabled(); }
        @Override public OutboundPolicy outboundPolicy() { return base.outboundPolicy(); }
        @Override public boolean sendLaterEnabled() { return enabled; }
    }

    /** Records waits and cancels; {@link #fire} is the production re-fire (onDue). */
    static final class RecordingWaiter implements NetworkWaiter {
        final List<Long> waits = new ArrayList<>();
        final List<Long> cancels = new ArrayList<>();
        @Override public void awaitNetwork(long id) { waits.add(id); }
        @Override public void cancel(long id) { cancels.add(id); }
        void fire(long id) { SendLaterEngine.onDue(null, id); }
    }

    /** The world's notifier plus the cause-worded reminder and dismissals. */
    static final class CauseNotifier implements EngineNotifier {
        final EngineFakes.FakeNotifier base;
        final List<String> causes = new ArrayList<>();
        final List<Long> dismissed = new ArrayList<>();
        CauseNotifier(EngineFakes.FakeNotifier base) { this.base = base; }
        @Override public void lastCall(ScheduledMessage m) { base.lastCall(m); }
        @Override public void hold(ScheduledMessage m, HoldReason r, long nowMs) { base.hold(m, r, nowMs); }
        @Override public void remind(ScheduledMessage m, RemindReason r) { base.remind(m, r); }
        @Override public void sent(ScheduledMessage m) { base.sent(m); }
        @Override public void repliedDuringSend(ScheduledMessage m) { base.repliedDuringSend(m); }
        @Override public void selfPreempted(ScheduledMessage m) { base.selfPreempted(m); }
        @Override public void expired(ScheduledMessage m) { base.expired(m); }
        @Override public void mayHaveSent(ScheduledMessage m) { base.mayHaveSent(m); }
        @Override public void remindCannotSend(ScheduledMessage m, RemindCause cause) {
            causes.add(m.getId() + " " + cause);
            base.remind(m, cause.reason());
        }
        @Override public void dismiss(long id) { dismissed.add(id); }
    }

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        cond = new Switchable(w.cond);
        waiter = new RecordingWaiter();
        notifier = new CauseNotifier(w.notifier);
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store)
                .sources(new ArrayList<ThreadEventSource>(Collections.singletonList(w.source)))
                .notifier(notifier)
                .broadcaster(w.broadcaster)
                .alarms(w.alarms)
                .conditions(cond)
                .networkWaiter(waiter)
                .executor(Runnable::run)
                .build());
        SendLaterEngine.setSender(w.sender);
    }

    @After public void tearDown() { SendLaterEngine.resetForTest(); }

    private List<String> registrations() {
        List<String> out = new ArrayList<>(w.alarms.matching("exact "));
        out.addAll(w.alarms.matching("inexact "));
        return out;
    }

    // ================================================================== (1) master switch

    @Test public void disabledProcessStartRegistersNoAlarmsButStillResolvesInFlight() {
        w.store.put(message(ID).scheduledFor(T + HOUR).build());
        w.store.put(message(HELD_ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        w.store.put(message(33).status(Status.IN_FLIGHT).build());
        w.source.events.add(outboundSame(T + 10_000)); // the interrupted send went out
        cond.enabled = false;
        w.cond.now = T + MIN;
        SendLaterEngine.onProcessStart(null);
        // OD27: resolved to HELD(UNVERIFIED), never verified from the thread.
        assertEquals("IN_FLIGHT still resolved", Status.HELD, w.store.status(33));
        assertTrue("no alarm registered: " + w.alarms.calls, registrations().isEmpty());
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(w.sender.started.isEmpty());
    }

    @Test public void disabledProcessStartDoesNotRetryAnInterruptedSend() {
        w.store.put(message(33).status(Status.IN_FLIGHT).build()); // no own bubble: retry case
        cond.enabled = false;
        SendLaterEngine.onProcessStart(null);
        // OD27: held as "may have already sent", never released for a retry.
        assertEquals("held, never retried", Status.HELD, w.store.status(33));
        assertTrue(w.sender.started.isEmpty());
        assertTrue(registrations().isEmpty());
    }

    @Test public void enabledProcessStartStillReArms() {
        w.store.put(message(ID).scheduledFor(T + HOUR).build());
        SendLaterEngine.onProcessStart(null);
        assertFalse(w.alarms.registered(AlarmKind.DUE, ID).isEmpty());
    }

    @Test public void disabledBootReceiverPathRegistersNoAlarms() {
        w.store.put(message(ID).scheduledFor(T + HOUR).build());
        w.store.put(message(HELD_ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        cond.enabled = false;
        // SendLaterBootReceiver runs exactly this (SendLaterScheduler.get(app).reregisterAll(app)).
        SendLaterEngine.scheduler(null).reregisterAll(null);
        assertTrue("no alarm registered: " + w.alarms.calls, registrations().isEmpty());
        cond.enabled = true;
        SendLaterEngine.scheduler(null).reregisterAll(null);
        assertFalse(w.alarms.registered(AlarmKind.DUE, ID).isEmpty());
        assertFalse(w.alarms.registered(AlarmKind.EXPIRE, HELD_ID).isEmpty());
    }

    @Test public void bootReceiverStillUsesTheGatedReregisterAll() throws IOException {
        String src = read("SendLaterBootReceiver.java");
        assertTrue(src.contains("reregisterAll"));
        String sched = read("SendLaterScheduler.java");
        int at = sched.indexOf("public void reregisterAll(");
        assertTrue(at > 0);
        assertTrue("reregisterAll checks the master switch first",
                sched.indexOf("if (!enabled()) return;", at) > at
                        && sched.indexOf("if (!enabled()) return;", at) < sched.indexOf("store.allPending()", at));
    }

    @Test public void disabledOnDueAndNetworkRefireNeverSend() {
        w.store.put(message(ID).build());
        cond.enabled = false;
        SendLaterEngine.onDue(null, ID);
        SendLaterEngine.onDueAlarm(null, ID);
        waiter.fire(ID);
        assertTrue(w.sender.started.isEmpty());
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(w.notifier.posted.isEmpty());
    }

    @Test public void networkWaitThenDisableThenRefireDoesNotSend() {
        w.store.put(message(ID).build());
        w.cond.network = NetworkState.NONE;
        SendLaterEngine.onDue(null, ID);
        assertEquals(Collections.singletonList(ID), waiter.waits);
        cond.enabled = false;
        SendLaterEngine.onDisabled(null);
        assertTrue("network waiter cancelled", waiter.cancels.contains(ID));
        w.cond.network = NetworkState.CONNECTED;
        waiter.fire(ID);
        assertTrue(w.sender.started.isEmpty());
    }

    @Test public void disabledLastCallDoesNotPost() {
        w.store.put(message(ID).build());
        cond.enabled = false;
        w.cond.now = T - 5 * MIN;
        SendLaterEngine.onLastCall(null, ID);
        assertTrue(w.notifier.posted.isEmpty());
    }

    @Test public void onDisabledCancelsAlarmsAndWaitersAndDismissesEveryLiveRow() {
        w.store.put(message(ID).scheduledFor(T + HOUR).build());
        w.store.put(message(HELD_ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        w.store.put(message(34).status(Status.SENT_VERIFIED).build());
        cond.enabled = false;
        SendLaterEngine.onDisabled(null);
        for (long id : new long[] {ID, HELD_ID}) {
            for (AlarmKind k : AlarmKind.values()) {
                assertFalse(k + " " + id + ": " + w.alarms.calls,
                        w.alarms.matching("cancel " + k + " " + id).isEmpty());
            }
            assertTrue(waiter.cancels.contains(id));
            assertTrue(notifier.dismissed.contains(id));
        }
        assertFalse("terminal rows untouched", notifier.dismissed.contains(34L));
        assertEquals("records kept", Status.SCHEDULED, w.store.status(ID));
        assertEquals(Status.HELD, w.store.status(HELD_ID));
        assertTrue(registrations().isEmpty());
    }

    @Test public void onDisabledRunsUnderTheEngineLock() throws IOException {
        String src = read("SendLaterEngine.java");
        int at = src.indexOf("public static void onDisabled(");
        assertTrue(at > 0);
        String body = src.substring(at, src.indexOf("\n    }\n", at));
        assertTrue("goes through run(...) (LOCK / engine executor)", body.contains("run(context,"));
        assertTrue(body.contains("cancelAll(") && body.contains("networkWaiter.cancel(")
                && body.contains("notifier.dismiss("));
    }

    @Test public void settingsControllerCallsOnDisabled() throws IOException {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater/ui/SendLaterSettingsController.java");
        if (!Files.exists(p)) p = Paths.get("app").resolve(p);
        String c = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        assertTrue(c.contains("SendLaterEngine.onDisabled(app)"));
    }

    // ================================================================== (2) reminder wording by cause

    private void cannotShow(String detail) {
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, detail);
        assertEquals(Status.SCHEDULED, w.store.status(ID));
    }

    @Test public void noSessionDetailRemindsWithSignIn() {
        cannotShow("no_session");
        assertEquals(Collections.singletonList(ID + " NO_SESSION"), notifier.causes);
        assertTrue(w.notifier.has("remind " + ID + " NO_SESSION"));
        assertTrue(SendLaterNotifications.hasSignInAction(RemindCause.NO_SESSION));
        assertTrue(SendLaterNotifications.cannotSendTitle(message(ID).build(), RemindCause.NO_SESSION)
                .startsWith("Sign in to Google Voice in voice2sms"));
    }

    @Test public void dailyCapDetailRemindsWithTheLimitWording() {
        cannotShow("daily_cap");
        assertEquals(Collections.singletonList(ID + " DAILY_CAP"), notifier.causes);
        assertFalse(SendLaterNotifications.hasSignInAction(RemindCause.DAILY_CAP));
        assertEquals("Daily scheduled-send limit reached",
                SendLaterNotifications.cannotSendTitle(message(ID).build(), RemindCause.DAILY_CAP));
    }

    @Test public void userBusyDetailRemindsWithTheBusyWording() {
        cannotShow("user_busy");
        assertEquals(Collections.singletonList(ID + " BUSY"), notifier.causes);
        assertFalse(SendLaterNotifications.hasSignInAction(RemindCause.BUSY));
        assertEquals("You were using voice2sms; tap to send now",
                SendLaterNotifications.cannotSendTitle(message(ID).build(), RemindCause.BUSY));
    }

    @Test public void unknownOrNoDetailKeepsTheLockedWording() {
        cannotShow("activity_gone");
        assertEquals(Collections.singletonList(ID + " LOCKED"), notifier.causes);
        assertTrue(w.notifier.has("remind " + ID + " LOCKED"));
        ScheduledMessage m = message(ID).build();
        assertEquals(SendLaterNotifications.remindTitle(m, RemindReason.LOCKED),
                SendLaterNotifications.cannotSendTitle(m, RemindCause.LOCKED));
        assertFalse(SendLaterNotifications.hasSignInAction(RemindCause.LOCKED));
    }

    @Test public void senderThatDoesNotStartGetsTheLockedReminder() {
        w.store.put(message(ID).build());
        w.sender.result = false;
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertEquals(Collections.singletonList(ID + " LOCKED"), notifier.causes);
    }

    @Test public void causeMapping() {
        assertEquals(RemindCause.NO_SESSION, RemindCause.fromDetail("no_session"));
        assertEquals(RemindCause.DAILY_CAP, RemindCause.fromDetail("daily_cap"));
        assertEquals(RemindCause.BUSY, RemindCause.fromDetail("user_busy"));
        assertEquals(RemindCause.LOCKED, RemindCause.fromDetail(null));
        assertEquals(RemindCause.LOCKED, RemindCause.fromDetail("cannot_show"));
        assertEquals(RemindReason.NO_SESSION, RemindCause.NO_SESSION.reason());
        assertEquals(RemindReason.LOCKED, RemindCause.DAILY_CAP.reason());
        assertEquals(RemindReason.LOCKED, RemindCause.BUSY.reason());
        assertEquals(RemindReason.LOCKED, RemindCause.LOCKED.reason());
    }

    @Test public void aCauseReminderIsStillAReminderThatNeverAutoSends() {
        cannotShow("daily_cap");
        SendLaterEngine.onDue(null, ID); // stray DUE after the reminder (I6)
        assertEquals(1, w.sender.started.size());
        assertFalse(w.alarms.registered(AlarmKind.EXPIRE, ID).isEmpty());
    }

    // ================================================================== (3) bounded late-reply notice

    private void sendAtT() {
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
    }

    @Test public void inboundAfterTheVerifyWindowIsAnOrdinarySend() {
        sendAtT();
        // The outcome arrives at T + 40 s (end of the sender's verify window); an inbound timed
        // past that end plus the clock slack is an ordinary reply to a sent message.
        w.source.events.add(outboundSame(T + 20_000));
        w.source.events.add(inboundSms(T + 40_000 + SendLaterEngine.LATE_REPLY_WINDOW_MS + 60_000));
        w.cond.now = T + 40_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, null);
        assertEquals(Status.SENT_VERIFIED, w.store.status(ID));
        assertTrue(w.notifier.posted.toString(), w.notifier.has("sent " + ID));
        assertFalse(w.notifier.has("repliedDuringSend " + ID));
    }

    @Test public void staleOutcomeIgnoresInboundPastTheCap() {
        sendAtT();
        long cap = SendLaterEngine.LATE_REPLY_MAX_SPAN_MS;
        w.source.events.add(outboundSame(T + 20_000));
        w.source.events.add(inboundSms(T + cap + SendLaterEngine.LATE_REPLY_WINDOW_MS + 1));
        w.cond.now = T + cap + HOUR;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, null);
        assertTrue(w.notifier.posted.toString(), w.notifier.has("sent " + ID));
        assertFalse(w.notifier.has("repliedDuringSend " + ID));
    }

    @Test public void inboundInsideTheWindowStillNotifies() {
        sendAtT();
        w.source.events.add(inboundSms(T + 30_000));
        w.source.events.add(outboundSame(T + 20_000));
        w.cond.now = T + 40_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, null);
        assertTrue(w.notifier.posted.toString(), w.notifier.has("repliedDuringSend " + ID));
    }

    /** Verifier fix pass 1: the 3rd of three messages due together sends about 170 s after its check. */
    @Test public void queuedSendWhoseOutcomeArrivesLateStillNotifiesForAnInboundBeforeIt() {
        sendAtT();
        w.source.events.add(inboundSms(T + 150_000)); // between this job's check and its send
        w.source.events.add(outboundSame(T + 160_000));
        w.cond.now = T + 175_000; // > T + LATE_REPLY_WINDOW_MS
        assertTrue(w.cond.now > T + SendLaterEngine.LATE_REPLY_WINDOW_MS);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, null);
        assertTrue(w.notifier.posted.toString(), w.notifier.has("repliedDuringSend " + ID));
    }

    @Test public void queuedSendLateReplyDetailMoreThanTwoMinutesAfterTheCheckNotifies() {
        sendAtT();
        w.source.events.add(outboundSame(T + 160_000));
        w.cond.now = T + 170_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, SendLaterEngine.DETAIL_LATE_REPLY);
        assertTrue(w.notifier.posted.toString(), w.notifier.has("repliedDuringSend " + ID));
    }

    @Test public void lateReplyDetailOutsideTheWindowIsIgnored() {
        sendAtT();
        w.source.events.add(outboundSame(T + 20_000));
        w.cond.now = T + SendLaterEngine.LATE_REPLY_MAX_SPAN_MS + MIN;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, SendLaterEngine.DETAIL_LATE_REPLY);
        assertTrue(w.notifier.posted.toString(), w.notifier.has("sent " + ID));
        assertFalse(w.notifier.has("repliedDuringSend " + ID));
    }

    @Test public void lateReplyDetailInsideTheWindowNotifies() {
        sendAtT();
        w.source.events.add(outboundSame(T + 20_000));
        w.cond.now = T + 50_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, SendLaterEngine.DETAIL_LATE_REPLY);
        assertTrue(w.notifier.posted.toString(), w.notifier.has("repliedDuringSend " + ID));
    }

    @Test public void windowBoundsArePure() {
        long check = T;
        long slack = SendLaterEngine.LATE_REPLY_WINDOW_MS;
        List<com.voice2sms.sendlater.domain.ThreadEvent> before =
                Collections.singletonList(inboundSms(check - 1));
        assertFalse(SendLaterEngine.repliedJustAsSent(before, null, check, check + 10_000));
        assertTrue(SendLaterEngine.repliedJustAsSent(Collections.singletonList(inboundSms(check)), null,
                check, check + 10_000));
        // The window ends at the outcome's arrival plus the slack.
        long outcome = check + 170_000;
        assertEquals(outcome + slack, SendLaterEngine.lateReplyWindowEnd(check, outcome));
        assertTrue(SendLaterEngine.repliedJustAsSent(Collections.singletonList(inboundSms(outcome + slack)),
                null, check, outcome));
        assertFalse(SendLaterEngine.repliedJustAsSent(Collections.singletonList(inboundSms(outcome + slack + 1)),
                null, check, outcome));
        // A stale outcome is capped.
        long capEnd = check + SendLaterEngine.LATE_REPLY_MAX_SPAN_MS + slack;
        assertEquals(capEnd, SendLaterEngine.lateReplyWindowEnd(check, check + 5 * HOUR));
        assertTrue(SendLaterEngine.repliedJustAsSent(Collections.singletonList(inboundSms(capEnd)), null,
                check, check + 5 * HOUR));
        assertFalse(SendLaterEngine.repliedJustAsSent(Collections.singletonList(inboundSms(capEnd + 1)), null,
                check, check + 5 * HOUR));
        // An outcome stamped before the check (clock change) still gets the slack after the check.
        assertEquals(check + slack, SendLaterEngine.lateReplyWindowEnd(check, check - MIN));
        assertFalse(SendLaterEngine.repliedJustAsSent(null, "other", check, check + 1));
        assertTrue(SendLaterEngine.repliedJustAsSent(null, SendLaterEngine.DETAIL_LATE_REPLY, check, check + 170_000));
        assertFalse(SendLaterEngine.repliedJustAsSent(null, SendLaterEngine.DETAIL_LATE_REPLY, check,
                check + SendLaterEngine.LATE_REPLY_MAX_SPAN_MS + 1));
        assertFalse("outbound never counts", SendLaterEngine.repliedJustAsSent(
                Collections.singletonList(outboundSame(check + 1)), null, check, check + 2));
    }

    private static String read(String name) throws IOException {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater/engine", name);
        if (!Files.exists(p)) p = Paths.get("app").resolve(p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
