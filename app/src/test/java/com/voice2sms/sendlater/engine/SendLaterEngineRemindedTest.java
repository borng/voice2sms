package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.HOUR;
import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LadderEvaluator;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.SessionState;
import com.voice2sms.sendlater.domain.Watermark;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fix pass 1: a reminded message never auto-sends again (I6) — not on a stray DUE, not after
 * boot/time-change re-registration — and keeps only its +24 h expiry; CANNOT_SHOW does not use
 * up the single FAILED retry; FAILED within budget says the send failed; boot receiver action
 * allowlist; Sign-in action on REMIND(NO_SESSION).
 */
public class SendLaterEngineRemindedTest {

    private static final long ID = 7L;
    private EngineFakes.World w;
    private EngineMarks marks;

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        marks = new EngineMarks.InMemory();
        install(w.store);
    }

    @After public void tearDown() { SendLaterEngine.resetForTest(); }

    private void install(EngineStore store) {
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(store)
                .sources(new ArrayList<>(Collections.singletonList(w.source)))
                .notifier(w.notifier)
                .broadcaster(w.broadcaster)
                .alarms(w.alarms)
                .conditions(w.cond)
                .networkWaiter(w.network)
                .executor(Runnable::run)
                .marks(marks)
                .build());
    }

    private void remindNoSession() {
        w.store.put(message(ID).build());
        SendLaterEngine.setSender(w.sender);
        w.cond.session = SessionState.MISSING;
        SendLaterEngine.onDue(null, ID);
        assertTrue(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " NO_SESSION"));
        assertEquals(Status.SCHEDULED, w.store.status(ID));
    }

    @Test public void strayDueAfterARemindNeverSends() {
        remindNoSession();
        w.cond.session = SessionState.READY;
        w.cond.now = T + 5 * MIN;
        SendLaterEngine.onDue(null, ID);
        assertTrue("no auto-send after a reminder (I6)", w.sender.started.isEmpty());
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertEquals(0, w.j.count("store.markInFlight"));
    }

    @Test public void reregisterAllGivesARemindedRowOnlyItsExpiry() {
        remindNoSession();
        w.alarms.calls.clear();
        SendLaterScheduler.get(null).reregisterAll(null);
        assertTrue(w.alarms.calls.toString(), w.alarms.registered(AlarmKind.DUE, ID).isEmpty());
        assertTrue(w.alarms.calls.toString(), w.alarms.registered(AlarmKind.LAST_CALL, ID).isEmpty());
        assertEquals(T + LadderEvaluator.EXPIRY_MS,
                w.alarms.timeOf("inexact " + AlarmKind.EXPIRE + " " + ID + " "));
    }

    @Test public void reregisterAllStillArmsUnremindedRows() {
        w.store.put(message(ID).build());
        w.cond.now = T - HOUR;
        SendLaterScheduler.get(null).reregisterAll(null);
        assertEquals(T, w.alarms.timeOf("exact " + AlarmKind.DUE + " " + ID + " "));
    }

    @Test public void snoozeClearsTheReminderSoTheNewTimeSends() {
        remindNoSession();
        w.cond.session = SessionState.READY;
        w.cond.now = T + 10 * MIN;
        SendLaterEngine.onSnooze(null, ID);
        assertFalse(marks.isReminded(ID));
        w.cond.now = T + 10 * MIN + HOUR;
        SendLaterEngine.onDue(null, ID);
        assertEquals(1, w.sender.started.size());
    }

    @Test public void explicitRegisterAlarmsClearsTheReminder() {
        remindNoSession();
        SendLaterScheduler.get(null).registerAlarms(w.store.rows.get(ID));
        assertFalse(marks.isReminded(ID));
    }

    @Test public void sendAnywayOnARemindedRowSendsAndClearsTheMark() {
        remindNoSession();
        w.cond.session = SessionState.READY;
        SendLaterEngine.onSendAnyway(null, ID);
        assertEquals(1, w.sender.started.size());
        assertFalse(marks.isReminded(ID));
    }

    @Test public void cannotShowMarksRemindedSoReregisterDoesNotResend() {
        w.store.put(message(ID).build());
        SendLaterEngine.setSender(w.sender);
        SendLaterEngine.onDue(null, ID);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, null);
        assertTrue(marks.isReminded(ID));
        SendLaterScheduler.get(null).reregisterAll(null);
        SendLaterEngine.onDue(null, ID);
        assertEquals(1, w.sender.started.size());
    }

    @Test public void terminalStatesDropTheMarks() {
        remindNoSession();
        SendLaterEngine.onCancel(null, ID);
        assertFalse(marks.isReminded(ID));
    }

    @Test public void expiryStillWorksForARemindedRow() {
        remindNoSession();
        w.cond.now = T + LadderEvaluator.EXPIRY_MS;
        SendLaterEngine.onExpireAlarm(null, ID);
        assertEquals(Status.EXPIRED, w.store.status(ID));
        assertTrue(w.notifier.has("expired " + ID));
    }

    // ------------------------------------------------------------------ CANNOT_SHOW vs retry cap

    /** Counts attempts the way SendLaterRepository does (fail / releaseForRetry: +1). */
    private static final class CountingStore implements EngineStore {
        final EngineFakes.FakeStore s;

        CountingStore(EngineFakes.FakeStore s) { this.s = s; }

        private void bump(long id) {
            ScheduledMessage m = s.rows.get(id);
            s.rows.put(id, m.toBuilder().attemptCount(m.getAttemptCount() + 1).build());
        }

        @Override public ScheduledMessage get(long id) { return s.get(id); }
        @Override public boolean markInFlight(long id, String h) { return s.markInFlight(id, h); }
        @Override public boolean markSentVerified(long id) { return s.markSentVerified(id); }
        @Override public boolean hold(long id, HoldReason r) { return s.hold(id, r); }
        @Override public boolean cancel(long id) { return s.cancel(id); }
        @Override public boolean selfPreempt(long id) { return s.selfPreempt(id); }
        @Override public boolean expire(long id) { return s.expire(id); }
        @Override public boolean fail(long id, String e) {
            boolean ok = s.fail(id, e);
            if (ok) bump(id);
            return ok;
        }
        @Override public boolean releaseForRetry(long id, String e) {
            boolean ok = s.releaseForRetry(id, e);
            if (ok) bump(id);
            return ok;
        }
        @Override public boolean reschedule(long id, long at, boolean h, LatenessPolicy p, Watermark wm) {
            return s.reschedule(id, at, h, p, wm);
        }
        @Override public List<ScheduledMessage> allPending() { return s.allPending(); }
        @Override public List<ScheduledMessage> allHeld() { return s.allHeld(); }
        @Override public List<ScheduledMessage> inFlight() { return s.inFlight(); }
        @Override public List<ScheduledMessage> pendingForThread(String e164) { return s.pendingForThread(e164); }
    }

    @Test public void cannotShowThenSendAnywayThenFailureIsNotRetried() {
        install(new CountingStore(w.store));
        w.store.put(message(ID).build());
        SendLaterEngine.setSender(w.sender);
        SendLaterEngine.onDue(null, ID);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, null);
        assertEquals(1, w.store.rows.get(ID).getAttemptCount());
        // The user unlocks and taps Send anyway; that send fails within budget.
        SendLaterEngine.onSendAnyway(null, ID);
        assertEquals(2, w.sender.started.size());
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.FAILED, "x");
        // OD27: no automatic retry after a real failure either.
        assertEquals("no automatic retry", 2, w.sender.started.size());
        assertEquals(Status.HELD, w.store.status(ID));
    }

    @Test public void failedWithinBudgetSaysItMayHaveSent() {
        final List<String> failed = new ArrayList<>();
        EngineNotifier n = new EngineNotifier() {
            @Override public void lastCall(ScheduledMessage m) { w.notifier.lastCall(m); }
            @Override public void hold(ScheduledMessage m, HoldReason r, long now) { w.notifier.hold(m, r, now); }
            @Override public void remind(ScheduledMessage m, RemindReason r) { w.notifier.remind(m, r); }
            @Override public void sent(ScheduledMessage m) { w.notifier.sent(m); }
            @Override public void repliedDuringSend(ScheduledMessage m) { w.notifier.repliedDuringSend(m); }
            @Override public void selfPreempted(ScheduledMessage m) { w.notifier.selfPreempted(m); }
            @Override public void expired(ScheduledMessage m) { w.notifier.expired(m); }
            @Override public void mayHaveSent(ScheduledMessage m) { w.notifier.mayHaveSent(m); }
            @Override public void sendFailed(ScheduledMessage m) { failed.add("sendFailed " + m.getId()); }
        };
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store).sources(new ArrayList<>(Collections.singletonList(w.source)))
                .notifier(n).broadcaster(w.broadcaster).alarms(w.alarms).conditions(w.cond)
                .networkWaiter(w.network).executor(Runnable::run).marks(marks).build());
        w.store.put(message(ID).build());
        SendLaterEngine.setSender(w.sender);
        SendLaterEngine.onDue(null, ID);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.FAILED, "x");
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.FAILED, "x");
        // OD27: a FAILED attempt may have typed: HELD(UNVERIFIED) + "may have already sent".
        assertEquals(Status.HELD, w.store.status(ID));
        assertTrue(failed.isEmpty());
        assertTrue(w.notifier.posted.toString(), w.notifier.has("mayHaveSent " + ID));
        assertFalse(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " NO_SENDER"));
    }

    // ------------------------------------------------------------------ receivers / notifications

    @Test public void bootReceiverAcceptsOnlyTheFourSystemActions() {
        assertTrue(SendLaterBootReceiver.accepts(Intent.ACTION_BOOT_COMPLETED));
        assertTrue(SendLaterBootReceiver.accepts("android.intent.action.TIME_SET"));
        assertTrue(SendLaterBootReceiver.accepts("android.intent.action.TIMEZONE_CHANGED"));
        assertTrue(SendLaterBootReceiver.accepts("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"));
        assertFalse(SendLaterBootReceiver.accepts(null));
        assertFalse(SendLaterBootReceiver.accepts("com.example.ANYTHING"));
        assertFalse(SendLaterBootReceiver.accepts("android.intent.action.USER_UNLOCKED"));
    }

    @Test public void onlyNoSessionRemindersGetASignInAction() throws Exception {
        assertTrue(SendLaterNotifications.hasSignInAction(RemindReason.NO_SESSION));
        for (RemindReason r : RemindReason.values()) {
            if (r != RemindReason.NO_SESSION) assertFalse(r.name(), SendLaterNotifications.hasSignInAction(r));
        }
        String n = read("SendLaterNotifications.java");
        assertTrue(n.contains("\"Sign in\""));
        assertTrue(SendLaterNotifications.sendFailedTitle(message(1).build()).startsWith("Couldn't send"));
    }

    private static String read(String name) throws Exception {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater/engine", name);
        if (!Files.exists(p)) p = Paths.get("app").resolve(p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
