package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.HOUR;
import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.inboundSms;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LadderEvaluator;
import com.voice2sms.sendlater.domain.NetworkState;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.SessionState;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
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
 * master-switch-serial (F2; N9a, N9b; OD24, OD29; R36, AC11, I6): the Send Later master switch
 * is one serialized decision ({@link SendLaterEngine#applySwitch}) that re-reads the switch on
 * the engine executor, and it reaches process start, early hold and alarm registration.
 * Fictional numbers only.
 */
public class SendLaterMasterSwitchTest {

    private static final long ID = 41L;
    private static final long HELD_ID = 42L;

    private EngineFakes.World w;
    private SendLaterEngineFollowups3Test.Switchable cond;
    private SendLaterEngineFollowups3Test.RecordingWaiter waiter;
    private SendLaterEngineFollowups3Test.CauseNotifier notifier;
    /** The engine executor: tasks queue here and run when the test drains them, in order. */
    private final List<Runnable> executor = new ArrayList<>();
    private int broadcasts;

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        cond = new SendLaterEngineFollowups3Test.Switchable(w.cond);
        waiter = new SendLaterEngineFollowups3Test.RecordingWaiter();
        notifier = new SendLaterEngineFollowups3Test.CauseNotifier(w.notifier);
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store)
                .sources(new ArrayList<ThreadEventSource>(Collections.singletonList(w.source)))
                .notifier(notifier)
                .broadcaster(w.broadcaster)
                .alarms(w.alarms)
                .conditions(cond)
                .networkWaiter(waiter)
                .executor(executor::add)
                .build());
        SendLaterEngine.setSender(w.sender);
    }

    @After public void tearDown() { SendLaterEngine.resetForTest(); }

    private void drain() {
        while (!executor.isEmpty()) executor.remove(0).run();
    }

    /** The user flips the switch: the preference is stored first, then the controller's call. */
    private void toggle(boolean on) {
        cond.enabled = on;
        SendLaterEngine.applySwitch(null, () -> broadcasts++);
    }

    private void seedLive() {
        w.store.put(message(ID).scheduledFor(T + HOUR).build());
        w.store.put(message(HELD_ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
    }

    private List<String> registrations() {
        List<String> out = new ArrayList<>(w.alarms.matching("exact "));
        out.addAll(w.alarms.matching("inexact "));
        return out;
    }

    /** Index of the last alarm call starting with {@code prefix}, or -1. */
    private int lastCall(String prefix) {
        for (int i = w.alarms.calls.size() - 1; i >= 0; i--) {
            if (w.alarms.calls.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }

    // ================================================================== N9a: interleavings

    @Test public void offThenOnQuicklyEndsOnWithAlarmsArmed() {
        seedLive();
        toggle(false);
        toggle(true);
        assertTrue("nothing runs on the caller's thread", w.alarms.calls.isEmpty());
        drain();
        // The switch ends on: the last DUE / EXPIRE registration comes after the last cancel.
        int due = Math.max(lastCall("exact DUE " + ID), lastCall("inexact DUE " + ID));
        assertTrue("DUE armed: " + w.alarms.calls, due >= 0);
        assertTrue("armed after every cancel", due > lastCall("cancel DUE " + ID));
        int exp = lastCall("inexact EXPIRE " + HELD_ID);
        assertTrue("HELD expiry armed", exp >= 0 && exp > lastCall("cancel EXPIRE " + HELD_ID));
        assertEquals("one state broadcast per toggle", 2, broadcasts);
    }

    @Test public void onThenOffQuicklyEndsOffWithNothingArmed() {
        seedLive();
        cond.enabled = false;
        toggle(true);
        toggle(false);
        drain();
        // Both tasks read the switch when they run: it is off, so neither re-arms.
        assertTrue("no alarm registered: " + w.alarms.calls, registrations().isEmpty());
        assertFalse(w.alarms.matching("cancel DUE " + ID).isEmpty());
        assertFalse(w.alarms.matching("cancel EXPIRE " + HELD_ID).isEmpty());
        assertTrue(notifier.dismissed.contains(ID));
        assertTrue(notifier.dismissed.contains(HELD_ID));
        assertTrue(waiter.cancels.contains(ID));
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertEquals(Status.HELD, w.store.status(HELD_ID));
        assertEquals(2, broadcasts);
    }

    @Test public void onThenOffAfterTheOnRanEndsWithEveryAlarmCancelled() {
        seedLive();
        toggle(true);
        drain();
        assertFalse(registrations().isEmpty());
        toggle(false);
        drain();
        for (AlarmKind k : AlarmKind.values()) {
            String prefix = "cancel " + k + " " + ID;
            int c = lastCall(prefix);
            assertTrue(prefix, c >= 0);
            assertTrue(k + " not re-armed after the cancel",
                    Math.max(lastCall("exact " + k + " " + ID), lastCall("inexact " + k + " " + ID)) < c);
        }
    }

    @Test public void switchOnRunsTheExpirySweepBeforeReArming() {
        w.store.put(message(HELD_ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        w.cond.now = T + LadderEvaluator.EXPIRY_MS + MIN;
        toggle(true);
        drain();
        assertEquals(Status.EXPIRED, w.store.status(HELD_ID));
        assertTrue("expired with its notification while on", w.notifier.has("expired " + HELD_ID));
        assertTrue("no expiry alarm for an expired row: " + w.alarms.calls,
                w.alarms.matching("inexact EXPIRE " + HELD_ID).isEmpty());
    }

    // ================================================================== N9b: silent sweep while off

    @Test public void processStartWhileOffSweepsSilently() {
        w.store.put(message(HELD_ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        w.store.put(message(ID).scheduledFor(T + HOUR).build());
        cond.enabled = false;
        w.cond.now = T + LadderEvaluator.EXPIRY_MS + MIN;
        SendLaterEngine.onProcessStart(null);
        drain();
        assertEquals("past 24 h -> EXPIRED", Status.EXPIRED, w.store.status(HELD_ID));
        assertEquals("not yet 24 h past its time", Status.SCHEDULED, w.store.status(ID));
        assertTrue("no notification while off: " + w.notifier.posted, w.notifier.posted.isEmpty());
        assertTrue("no alarm registered: " + w.alarms.calls, registrations().isEmpty());
    }

    @Test public void processStartWhileOffStillPostsMayHaveSent() {
        // OD29 (Q3): "may have already sent" is safety information and posts while off.
        w.store.put(message(43).status(Status.IN_FLIGHT).scheduledFor(T).build());
        cond.enabled = false;
        w.cond.now = T + MIN;
        SendLaterEngine.onProcessStart(null);
        drain();
        assertEquals(Status.HELD, w.store.status(43));
        assertTrue(w.notifier.has("mayHaveSent 43"));
        assertFalse("the silent sweep leaves it up", notifier.dismissed.contains(43L));
        assertTrue(registrations().isEmpty());
    }

    // ================================================================== F2(c): silent hold while off

    @Test public void inboundWhileOffHoldsSilently() {
        w.store.put(message(ID).build());
        cond.enabled = false;
        SendLaterEngine.onInboundEvent(null, EngineFakes.E164, inboundSms(T - 20 * MIN));
        drain();
        assertEquals("still a step down (I6)", Status.HELD, w.store.status(ID));
        assertTrue("no hold notification: " + w.notifier.posted, w.notifier.posted.isEmpty());
        assertTrue("no expiry alarm: " + w.alarms.calls, registrations().isEmpty());
        assertFalse("chip refresh still broadcast", w.broadcaster.sent.isEmpty());
        // Turning it on re-arms the held row's expiry.
        toggle(true);
        drain();
        assertFalse(w.alarms.matching("inexact EXPIRE " + ID).isEmpty());
    }

    @Test public void inboundWhileOnStillNotifiesAndArmsExpiry() {
        w.store.put(message(ID).build());
        SendLaterEngine.onInboundEvent(null, EngineFakes.E164, inboundSms(T - 20 * MIN));
        drain();
        assertEquals(Status.HELD, w.store.status(ID));
        assertTrue(w.notifier.has("hold " + ID + " REPLIED"));
        assertFalse(w.alarms.matching("inexact EXPIRE " + ID).isEmpty());
    }

    // ================================================================== registerAlarms / boot

    @Test public void registerAlarmsIsANoOpWhileOff() {
        w.store.put(message(ID).scheduledFor(T + 2 * MIN).build()); // inside the last-call window
        cond.enabled = false;
        SendLaterEngine.scheduler(null).registerAlarms(w.store.rows.get(ID));
        assertTrue("no alarm: " + w.alarms.calls, registrations().isEmpty());
        assertTrue("no last call: " + w.notifier.posted, w.notifier.posted.isEmpty());
        cond.enabled = true;
        SendLaterEngine.scheduler(null).registerAlarms(w.store.rows.get(ID));
        assertFalse(registrations().isEmpty());
    }

    @Test public void snoozeWhileOffArmsNothingUntilSwitchedOn() {
        w.store.put(message(HELD_ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        cond.enabled = false;
        SendLaterEngine.onSnooze(null, HELD_ID);
        drain();
        assertEquals(Status.SCHEDULED, w.store.status(HELD_ID));
        assertTrue("no alarm while off: " + w.alarms.calls, registrations().isEmpty());
        toggle(true);
        drain();
        assertTrue(lastCall("exact DUE " + HELD_ID) >= 0 || lastCall("inexact DUE " + HELD_ID) >= 0);
    }

    @Test public void engineReregisterAllFollowsTheSwitch() {
        seedLive();
        cond.enabled = false;
        SendLaterEngine.reregisterAll(null);
        assertTrue(registrations().isEmpty());
        cond.enabled = true;
        SendLaterEngine.reregisterAll(null);
        assertFalse(registrations().isEmpty());
    }

    @Test public void switchReadThatThrowsCountsAsOff() {
        seedLive();
        SendLaterScheduler s = new SendLaterScheduler(w.alarms, w.store, w.notifier,
                new EngineConditions() {
                    @Override public long nowMs() { return w.cond.nowMs(); }
                    @Override public SessionState session() { return w.cond.session(); }
                    @Override public NetworkState network() { return w.cond.network(); }
                    @Override public boolean listenerEnabled() { return w.cond.listenerEnabled(); }
                    @Override public OutboundPolicy outboundPolicy() { return w.cond.outboundPolicy(); }
                    @Override public boolean sendLaterEnabled() { throw new IllegalStateException(); }
                });
        s.registerAlarms(w.store.rows.get(ID));
        s.reregisterAll(null);
        assertTrue(registrations().isEmpty());
    }

    // ================================================================== source shape

    private static String read(String rel) throws IOException {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater/").resolve(rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test public void bootReceiverReArmsUnderTheEngineLock() throws IOException {
        String boot = read("engine/SendLaterBootReceiver.java");
        assertTrue(boot.contains("SendLaterEngine.reregisterAll(app)"));
        assertFalse(boot.contains("SendLaterScheduler.get(app).reregisterAll"));
        String engine = read("engine/SendLaterEngine.java");
        int at = engine.indexOf("public static void reregisterAll(");
        assertTrue(at > 0);
        String body = engine.substring(at, engine.indexOf("\n    }\n", at));
        assertTrue(body.contains("synchronized (LOCK)"));
    }

    @Test public void settingsControllerUsesOnlyApplySwitch() throws IOException {
        String c = read("ui/SendLaterSettingsController.java");
        int at = c.indexOf("static void applyEnabled(");
        assertTrue(at > 0);
        String body = c.substring(at, c.indexOf("\n    }\n", at));
        int apply = body.indexOf("SendLaterEngine.applySwitch(app, broadcast)");
        assertTrue("applySwitch with the broadcast as its completion", apply > 0);
        int fail = body.indexOf("catch (RuntimeException");
        assertTrue("no raw Thread or direct re-arm before the engine call",
                body.substring(0, fail).indexOf("new Thread(") < 0
                        && !body.contains("reregisterAll") && !body.contains("onDisabled"));
        assertTrue(body.contains("ACTION_STATE_CHANGED") && body.contains("setPackage("));
    }
}
