package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.HOUR;
import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static com.voice2sms.sendlater.engine.EngineFakes.outboundSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.AlarmManager;
import android.content.Intent;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LadderEvaluator;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;

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
 * Fix pass 2: process start re-arms alarms (force-stop / exact-alarm revoke), the network wait
 * fires once on a validated network, and the boot receiver's action allowlist.
 */
public class SendLaterEngineFixPass2Test {

    private EngineFakes.World w;
    private EngineMarks marks;

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        marks = new EngineMarks.InMemory();
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store)
                .sources(new ArrayList<>(Collections.singletonList(w.source)))
                .notifier(w.notifier)
                .broadcaster(w.broadcaster)
                .alarms(w.alarms)
                .conditions(w.cond)
                .networkWaiter(w.network)
                .executor(w.queued::add)
                .marks(marks)
                .build());
        SendLaterEngine.setSender(w.sender);
    }

    @After public void tearDown() { SendLaterEngine.resetForTest(); }

    private void drain() {
        for (int guard = 0; !w.queued.isEmpty() && guard < 20; guard++) w.queued.remove(0).run();
    }

    // ================================================================== 1. re-arm at process start

    @Test public void processStartReArmsEveryPendingAlarmOnTheExecutor() {
        w.cond.now = T - HOUR;
        w.store.put(message(1).build());                       // SCHEDULED, not reminded
        w.store.put(message(2).build());                       // SCHEDULED, reminded
        marks.setReminded(2, true);
        w.store.put(message(3).status(Status.HELD).holdReason(HoldReason.REPLIED).build());

        SendLaterEngine.onProcessStart(null);
        assertTrue("nothing on the calling thread", w.alarms.calls.isEmpty());
        drain();

        assertEquals(T, w.alarms.timeOf("exact DUE 1 "));
        assertEquals(T - 5 * MIN, w.alarms.timeOf("exact LAST_CALL 1 "));
        assertTrue("reminded row gets no due alarm (I6): " + w.alarms.calls,
                w.alarms.registered(AlarmKind.DUE, 2).isEmpty());
        assertTrue(w.alarms.registered(AlarmKind.LAST_CALL, 2).isEmpty());
        assertEquals(T + LadderEvaluator.EXPIRY_MS, w.alarms.timeOf("inexact EXPIRE 2 "));
        assertEquals(T + LadderEvaluator.EXPIRY_MS, w.alarms.timeOf("inexact EXPIRE 3 "));
        assertTrue(w.sender.started.isEmpty());
    }

    @Test public void processStartReArmsAfterResolvingAndSweeping() {
        w.cond.now = T + MIN;
        w.store.put(message(1).status(Status.IN_FLIGHT).build());
        w.store.put(message(2).status(Status.HELD).holdReason(HoldReason.REPLIED)
                .scheduledFor(T - 25 * HOUR).createdAt(T - 27 * HOUR).build());
        w.store.put(message(3).scheduledFor(T + HOUR).build());
        w.source.events.add(outboundSame(T + 10_000));

        SendLaterEngine.onProcessStart(null);
        drain();

        assertEquals(Status.SENT_VERIFIED, w.store.status(1));
        assertEquals(Status.EXPIRED, w.store.status(2));
        int resolved = w.j.indexOf("store.markSentVerified 1");
        int swept = w.j.indexOf("store.expire 2");
        int armed = w.j.indexOf("alarm.exact DUE 3");
        assertTrue(w.j.toString(), resolved >= 0 && swept > resolved && armed > swept);
        assertTrue("expired row is not re-armed: " + w.alarms.calls,
                w.alarms.registered(AlarmKind.EXPIRE, 2).isEmpty());
    }

    @Test public void processStartReArmIsIdempotent() {
        w.cond.now = T - HOUR;
        w.store.put(message(1).build());
        SendLaterEngine.onProcessStart(null);
        drain();
        List<String> first = new ArrayList<>(w.alarms.calls);
        w.alarms.calls.clear();
        SendLaterEngine.onProcessStart(null);
        drain();
        assertEquals("same registrations (same PendingIntent identity replaces them)", first, w.alarms.calls);
        assertEquals(Status.SCHEDULED, w.store.status(1));
    }

    @Test public void processStartFallsBackToInexactWhenExactWasRevoked() {
        w.cond.now = T - HOUR;
        w.alarms.exactAllowed = false;
        w.store.put(message(1).build());
        SendLaterEngine.onProcessStart(null);
        drain();
        assertEquals(T, w.alarms.timeOf("inexact DUE 1 "));
        assertTrue(w.alarms.matching("exact ").isEmpty());
    }

    @Test public void alarmIdentityIsKindAndIdWithUpdateCurrentImmutable() throws Exception {
        String src = read(Paths.get("src/main/java/com/voice2sms/sendlater/engine/AndroidAlarmSink.java"));
        assertTrue(src.contains("PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT"));
        assertTrue(src.contains("\"voice2sms-sendlater://alarm/\" + kind.name() + \"/\" + id"));
        String engine = read(Paths.get("src/main/java/com/voice2sms/sendlater/engine/SendLaterEngine.java"));
        int start = engine.indexOf("public static void onProcessStart(");
        int end = engine.indexOf("public static void onDue(");
        String body = engine.substring(start, end);
        assertTrue(body.contains("reregisterAll("));
        assertFalse("no periodic job", engine.contains("JobScheduler") || engine.contains("WorkManager"));
    }

    // ================================================================== 2. network wait gate

    static final class FakeRegistrar implements NetworkWaitGate.Registrar {
        final List<Long> registered = new ArrayList<>();
        final List<Object> unregistered = new ArrayList<>();
        /** When set, report these capabilities from inside register() (callback races the return). */
        boolean reportUsableDuringRegister;
        boolean fail;

        @Override public Object register(long id, NetworkWaitGate gate) {
            if (fail) return null;
            registered.add(id);
            Object handle = "cb" + id + "#" + registered.size();
            if (reportUsableDuringRegister) gate.onCapabilities(id, true, true, 34);
            return handle;
        }

        @Override public void unregister(Object handle) { unregistered.add(handle); }
    }

    static final class FakeTrigger implements NetworkWaitGate.Trigger {
        final List<Long> fired = new ArrayList<>();
        final List<Runnable> pendingDone = new ArrayList<>();
        @Override public void reevaluate(long id, Runnable done) {
            fired.add(id);
            pendingDone.add(done);
        }
    }

    @Test public void gateIgnoresNetworksWithoutInternetOrValidation() {
        FakeRegistrar r = new FakeRegistrar();
        FakeTrigger t = new FakeTrigger();
        NetworkWaitGate g = new NetworkWaitGate(r, t);
        g.await(7);
        for (int i = 0; i < 50; i++) {
            g.onCapabilities(7, false, false, 34);
            g.onCapabilities(7, true, false, 34);  // captive portal / not yet validated
            g.onCapabilities(7, false, true, 34);
        }
        assertTrue("no re-evaluation without INTERNET+VALIDATED", t.fired.isEmpty());
        assertTrue(g.isWaiting(7));
        assertTrue(r.unregistered.isEmpty());
        assertEquals(1, r.registered.size());
    }

    @Test public void gateFiresOnceOnValidatedInternetAndUnregisters() {
        FakeRegistrar r = new FakeRegistrar();
        FakeTrigger t = new FakeTrigger();
        NetworkWaitGate g = new NetworkWaitGate(r, t);
        g.await(7);
        g.onCapabilities(7, true, true, 34);
        g.onCapabilities(7, true, true, 34);
        g.onCapabilities(7, true, true, 34);
        assertEquals(Collections.singletonList(7L), t.fired);
        assertEquals(Collections.<Object>singletonList("cb7#1"), r.unregistered);
        assertFalse(g.isWaiting(7));
    }

    @Test public void gateAllowsOneInFlightReevaluationAndNoReRegisterFromIt() {
        FakeRegistrar r = new FakeRegistrar();
        FakeTrigger t = new FakeTrigger();
        NetworkWaitGate g = new NetworkWaitGate(r, t);
        g.await(7);
        g.onCapabilities(7, true, true, 34);
        assertTrue(g.isReevaluating(7));
        // The re-evaluation returns WAIT again: no new callback while it runs (no spin).
        g.await(7);
        g.await(7);
        g.onCapabilities(7, true, true, 34);
        assertEquals(1, r.registered.size());
        assertEquals(1, t.fired.size());
        // After it finished, a later WAIT registers again.
        t.pendingDone.get(0).run();
        assertFalse(g.isReevaluating(7));
        g.await(7);
        assertEquals(2, r.registered.size());
        assertTrue(g.isWaiting(7));
    }

    @Test public void gateDoesNotDoubleRegisterAndCancelUnregisters() {
        FakeRegistrar r = new FakeRegistrar();
        FakeTrigger t = new FakeTrigger();
        NetworkWaitGate g = new NetworkWaitGate(r, t);
        g.await(7);
        g.await(7);
        assertEquals(1, r.registered.size());
        g.cancel(7);
        assertEquals(Collections.<Object>singletonList("cb7#1"), r.unregistered);
        g.onCapabilities(7, true, true, 34);
        assertTrue("cancelled wait never fires", t.fired.isEmpty());
    }

    @Test public void gateKeepsAUsableReportThatArrivesDuringRegistration() {
        FakeRegistrar r = new FakeRegistrar();
        r.reportUsableDuringRegister = true;
        FakeTrigger t = new FakeTrigger();
        NetworkWaitGate g = new NetworkWaitGate(r, t);
        g.await(7);
        assertEquals(Collections.singletonList(7L), t.fired);
        assertEquals(1, r.unregistered.size());
        assertFalse(g.isWaiting(7));
    }

    @Test public void gateRegistrationFailureLeavesNoWaitAndTriggerFailureFrees() {
        FakeRegistrar r = new FakeRegistrar();
        r.fail = true;
        NetworkWaitGate g = new NetworkWaitGate(r, (id, done) -> { throw new IllegalStateException(); });
        g.await(7);
        assertFalse(g.isWaiting(7));
        r.fail = false;
        g.await(7);
        g.onCapabilities(7, true, true, 34);
        assertFalse("a throwing trigger does not wedge the id", g.isReevaluating(7));
    }

    @Test public void usableNeedsValidatedFromApi23() {
        assertTrue(NetworkWaitGate.usable(true, true, 24));
        assertFalse(NetworkWaitGate.usable(true, false, 24));
        assertFalse(NetworkWaitGate.usable(false, true, 24));
        assertTrue(NetworkWaitGate.usable(true, false, 22));
    }

    @Test public void androidWaiterUsesCapabilitiesNotOnAvailable() throws Exception {
        String src = read(Paths.get("src/main/java/com/voice2sms/sendlater/engine/AndroidEngineDeps.java"));
        int start = src.indexOf("static final class Waiter");
        String waiter = src.substring(start);
        assertTrue(waiter.contains("registerDefaultNetworkCallback"));
        assertTrue(waiter.contains("onCapabilitiesChanged"));
        assertTrue(waiter.contains("NET_CAPABILITY_VALIDATED"));
        assertTrue(waiter.contains("NET_CAPABILITY_INTERNET"));
        assertFalse("onAvailable fires for any default network", waiter.contains("onAvailable"));
        assertTrue(waiter.contains("NetworkWaitGate"));
    }

    // ================================================================== 3. boot receiver allowlist

    @Test public void bootReceiverAllowlistIsExactlyTheFourSystemActions() {
        assertEquals(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                SendLaterBootReceiver.ACTION_EXACT_ALARM_PERMISSION);
        assertTrue(SendLaterBootReceiver.accepts(Intent.ACTION_BOOT_COMPLETED));
        assertTrue(SendLaterBootReceiver.accepts(Intent.ACTION_TIME_CHANGED));
        assertTrue(SendLaterBootReceiver.accepts(Intent.ACTION_TIMEZONE_CHANGED));
        assertTrue(SendLaterBootReceiver.accepts(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED));
        String[] rejected = {null, "", "android.intent.action.LOCKED_BOOT_COMPLETED",
                "android.intent.action.MY_PACKAGE_REPLACED", "android.intent.action.boot_completed",
                "android.intent.action.BOOT_COMPLETED ", "com.voice2sms.sendlater.action.DUE",
                "android.intent.action.QUICKBOOT_POWERON"};
        for (String a : rejected) assertFalse(String.valueOf(a), SendLaterBootReceiver.accepts(a));
    }

    @Test public void bootReceiverReturnsBeforeAnyWorkOnNullOrOtherIntent() throws Exception {
        String src = read(Paths.get("src/main/java/com/voice2sms/sendlater/engine/SendLaterBootReceiver.java"));
        int on = src.indexOf("public void onReceive(");
        assertTrue(on >= 0);
        String body = src.substring(on);
        int guard = body.indexOf("if (intent == null || !accepts(intent.getAction())) return;");
        assertTrue("null-intent and allowlist guard", guard >= 0);
        assertTrue("guard precedes goAsync", guard < body.indexOf("goAsync()"));
        assertTrue("guard precedes re-registration", guard < body.indexOf("reregisterAll("));
        assertTrue("guard precedes any context use", guard < body.indexOf("getApplicationContext()"));
    }

    private static String read(Path rel) throws Exception {
        Path p = Files.exists(rel) ? rel : Paths.get("app").resolve(rel);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
