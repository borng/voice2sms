package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.HOUR;
import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEventSource;
import com.voice2sms.sendlater.domain.Watermark;

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
 * Deliver M2: edits from the sheet go through the engine under its lock
 * ({@link SendLaterEngine#reschedule}, {@link SendLaterEngine#registerAlarms}), and the send
 * start only takes the row version the engine evaluated (guarded markInFlight; a changed row is
 * evaluated again, once). Fictional data only.
 */
public class EditUnderEngineLockTest {

    private static final long ID = 71L;
    private static final long NEW_TIME = T + 3 * HOUR;

    private EngineFakes.World w;
    private final List<Long> dismissed = new ArrayList<>();
    private EditingSource editing;

    /** A thread source that edits the row during the engine's read (an edit landing mid-evaluation). */
    private final class EditingSource implements ThreadEventSource {
        int reads;
        int editsLeft;
        long editTo = NEW_TIME;
        boolean editHoldFlag;

        @Override public boolean isAvailable() { return true; }

        @Override public List<ThreadEvent> readSince(ConversationRef ref, Watermark wm) {
            reads++;
            if (editsLeft > 0) {
                editsLeft--;
                ScheduledMessage m = w.store.rows.get(ID);
                // Straight to the store, as an edit that bypassed the engine lock would.
                w.store.reschedule(ID, editTo, editHoldFlag ? !m.isHoldIfReplied() : m.isHoldIfReplied(),
                        m.getLatenessPolicy(), null);
            }
            return new ArrayList<>();
        }
    }

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        editing = new EditingSource();
        final EngineFakes.FakeNotifier base = w.notifier;
        EngineNotifier notifier = new EngineNotifier() {
            @Override public void lastCall(ScheduledMessage m) { base.lastCall(m); }
            @Override public void hold(ScheduledMessage m, HoldReason r, long nowMs) { base.hold(m, r, nowMs); }
            @Override public void remind(ScheduledMessage m, com.voice2sms.sendlater.domain.RemindReason r) {
                base.remind(m, r);
            }
            @Override public void sent(ScheduledMessage m) { base.sent(m); }
            @Override public void repliedDuringSend(ScheduledMessage m) { base.repliedDuringSend(m); }
            @Override public void selfPreempted(ScheduledMessage m) { base.selfPreempted(m); }
            @Override public void expired(ScheduledMessage m) { base.expired(m); }
            @Override public void mayHaveSent(ScheduledMessage m) { base.mayHaveSent(m); }
            @Override public void dismiss(long id) { dismissed.add(id); }
        };
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store)
                .sources(new ArrayList<ThreadEventSource>(Collections.singletonList(editing)))
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

    private static Watermark baseline(long at) {
        return new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, at, null);
    }

    private TapResult edit(long to) {
        return SendLaterEngine.reschedule(null, ID, to, true, LatenessPolicy.DEFAULT, baseline(w.cond.now));
    }

    private EngineMarks marks() {
        return SendLaterEngine.deps(null).marks;
    }

    // ------------------------------------------------------------------ engine reschedule

    @Test public void rescheduleRefusesARowThatIsAlreadySendingAndTouchesNothing() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        w.cond.now = T - HOUR;
        assertEquals(TapResult.ALREADY_SENDING, edit(NEW_TIME));
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertEquals(T, w.store.rows.get(ID).getScheduledFor());
        assertTrue(w.alarms.calls.isEmpty());
        assertTrue(dismissed.isEmpty());
        assertTrue(w.broadcaster.sent.isEmpty());
        assertEquals(-1, w.j.indexOf("store.reschedule"));
    }

    @Test public void rescheduleRefusesTerminalAndMissingRows() {
        w.store.put(message(ID).status(Status.CANCELED).build());
        assertEquals(TapResult.ALREADY_CANCELLED, edit(NEW_TIME));
        w.store.put(message(ID).status(Status.SENT_VERIFIED).build());
        assertEquals(TapResult.ALREADY_SENT, edit(NEW_TIME));
        w.store.rows.clear();
        assertEquals(TapResult.NOT_PENDING, edit(NEW_TIME));
        assertTrue(w.alarms.calls.isEmpty());
    }

    @Test public void rescheduleStoresTheNewTimeDismissesReArmsAndBroadcasts() {
        w.store.put(message(ID).build());
        w.cond.now = T - HOUR;
        assertEquals(TapResult.DONE, edit(NEW_TIME));
        ScheduledMessage m = w.store.rows.get(ID);
        assertEquals(Status.SCHEDULED, m.getStatus());
        assertEquals(NEW_TIME, m.getScheduledFor());
        assertEquals(Collections.singletonList(ID), dismissed);
        assertEquals(Collections.singletonList(ID), w.network.cancelled);
        assertFalse("EXPIRE cancelled", w.alarms.matching("cancel " + AlarmKind.EXPIRE + " " + ID).isEmpty());
        assertEquals(NEW_TIME, w.alarms.timeOf("exact DUE " + ID));
        assertEquals(Collections.singletonList(ID + " " + EngineFakes.E164), w.broadcaster.sent);
        // order: store write, then cancel-all, then the new DUE
        int write = w.j.indexOf("store.reschedule");
        int cancel = w.j.indexOf("alarm.cancel EXPIRE " + ID);
        int arm = w.j.lastIndexOf("alarm.exact DUE " + ID);
        assertTrue(w.j.toString(), write >= 0 && cancel > write && arm > cancel);
    }

    @Test public void rescheduleOfAHeldRowMakesItScheduledAndDropsItsExpiry() {
        w.store.put(message(ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        w.cond.now = T + 10 * MIN;
        assertEquals(TapResult.DONE, edit(NEW_TIME));
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertFalse(w.alarms.matching("cancel EXPIRE " + ID).isEmpty());
        assertEquals(NEW_TIME, w.alarms.timeOf("exact DUE " + ID));
    }

    /** M2 (b): a reminder that got there first never wipes the edit; the edit clears "reminded". */
    @Test public void rescheduleOfARemindedRowClearsTheMarkSoItSendsAtTheNewTime() {
        w.store.put(message(ID).build());
        marks().setReminded(ID, true);
        marks().setLastCallPosted(ID, T);
        w.cond.now = T - HOUR;
        assertEquals(TapResult.DONE, edit(NEW_TIME));
        assertFalse(marks().isReminded(ID));
        assertFalse(marks().isLastCallPosted(ID, T));
        w.cond.now = NEW_TIME;
        SendLaterEngine.onDue(null, ID);
        assertEquals(Collections.singletonList(ID), w.sender.started);
    }

    /** M2 (a): an alarm for the old time after an edit never sends; the new time does. */
    @Test public void staleDueAlarmAfterAnEditWaitsForTheNewTime() {
        w.store.put(message(ID).build());
        w.cond.now = T - HOUR;
        assertEquals(TapResult.DONE, edit(NEW_TIME));
        w.cond.now = T;
        SendLaterEngine.onDue(null, ID);
        assertTrue(w.sender.started.isEmpty());
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertEquals(NEW_TIME, w.alarms.timeOf("exact DUE " + ID));
    }

    @Test public void rescheduleRunsUnderTheEngineLock() throws IOException {
        String src = read("engine/SendLaterEngine.java");
        int at = src.indexOf("public static TapResult reschedule(");
        assertTrue(at > 0);
        String body = src.substring(at, src.indexOf("\n    }\n", at));
        assertTrue(body.contains("synchronized (LOCK)"));
        assertTrue(body.contains("doReschedule("));
        int reg = src.indexOf("public static void registerAlarms(final Context");
        assertTrue(reg > 0);
        String regBody = src.substring(reg, src.indexOf("\n    }\n", reg));
        assertTrue(regBody.contains("synchronized (LOCK)"));
    }

    // ------------------------------------------------------------------ capture arming

    @Test public void captureArmingUnderTheLockArmsAScheduledRow() {
        ScheduledMessage m = message(ID).build();
        w.store.put(m);
        w.cond.now = T - HOUR;
        SendLaterEngine.registerAlarms(null, m);
        assertEquals(T, w.alarms.timeOf("exact DUE " + ID));
    }

    @Test public void captureArmingNeverRevivesARowCancelledOrRemindedFirst() {
        ScheduledMessage m = message(ID).build();
        w.store.put(m.toBuilder().status(Status.CANCELED).build());
        w.cond.now = T - HOUR;
        SendLaterEngine.registerAlarms(null, m);
        assertTrue(w.alarms.calls.isEmpty());
        w.store.put(m);
        marks().setReminded(ID, true);
        SendLaterEngine.registerAlarms(null, m);
        assertTrue(w.alarms.calls.isEmpty());
        assertTrue(marks().isReminded(ID));
        SendLaterEngine.registerAlarms(null, null); // no-op, never throws
    }

    // ------------------------------------------------------------------ guarded markInFlight

    @Test public void rowEditedToALaterTimeDuringEvaluationIsNotSentAtTheOldTime() {
        w.store.put(message(ID).build());
        editing.editsLeft = 1;
        SendLaterEngine.onDue(null, ID);
        assertTrue("never sent at the old time", w.sender.started.isEmpty());
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(w.j.toString(), w.j.indexOf("store.markInFlight.refused " + ID) >= 0);
        assertEquals("evaluated again once", 2, editing.reads);
        assertEquals("re-evaluation re-arms the new due time", NEW_TIME, w.alarms.timeOf("exact DUE " + ID));
    }

    @Test public void rowEditedButStillDueIsEvaluatedAgainAndSentAsStored() {
        w.store.put(message(ID).build());
        editing.editsLeft = 1;
        editing.editTo = T;
        editing.editHoldFlag = true;
        SendLaterEngine.onDue(null, ID);
        assertEquals(2, editing.reads);
        assertEquals(Collections.singletonList(ID), w.sender.started);
        assertEquals(Collections.singletonList(Status.IN_FLIGHT), w.sender.statusAtStart);
        assertEquals("the stored (edited) flag went in flight", false,
                w.store.rows.get(ID).isHoldIfReplied());
    }

    @Test public void reEvaluationHappensOnlyOnce() {
        w.store.put(message(ID).build());
        editing.editsLeft = 5;
        editing.editTo = T;
        editing.editHoldFlag = true;
        SendLaterEngine.onDue(null, ID);
        assertEquals(2, editing.reads);
        assertTrue(w.sender.started.isEmpty());
        assertEquals(Status.SCHEDULED, w.store.status(ID));
    }

    @Test public void unchangedRowStillGoesInFlight() {
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(1, editing.reads);
        assertEquals(Collections.singletonList(ID), w.sender.started);
        assertEquals(-1, w.j.indexOf("store.markInFlight.refused"));
    }

    @Test public void engineStartsSendsOnlyThroughTheGuardedMarkInFlight() throws IOException {
        String src = read("engine/SendLaterEngine.java");
        assertFalse("no unguarded markInFlight(id, hash) left in the engine",
                src.matches("(?s).*store\\.markInFlight\\(id, ThreadEvent\\.hashOf\\(m\\.getText\\(\\)\\)\\)\\).*"));
        assertTrue(src.contains("m.getScheduledFor(), m.getUpdatedAt())"));
    }

    @Test public void defaultGuardedMarkInFlightComparesTheSnapshot() {
        final EngineFakes.FakeStore s = w.store;
        EngineStore plain = new EngineStore() {
            @Override public ScheduledMessage get(long id) { return s.get(id); }
            @Override public boolean markInFlight(long id, String h) { return s.markInFlight(id, h); }
            @Override public boolean markSentVerified(long id) { return s.markSentVerified(id); }
            @Override public boolean hold(long id, HoldReason r) { return s.hold(id, r); }
            @Override public boolean cancel(long id) { return s.cancel(id); }
            @Override public boolean selfPreempt(long id) { return s.selfPreempt(id); }
            @Override public boolean expire(long id) { return s.expire(id); }
            @Override public boolean fail(long id, String e) { return s.fail(id, e); }
            @Override public boolean releaseForRetry(long id, String e) { return s.releaseForRetry(id, e); }
            @Override public boolean reschedule(long id, long at, boolean h, LatenessPolicy p, Watermark wm) {
                return s.reschedule(id, at, h, p, wm);
            }
            @Override public List<ScheduledMessage> allPending() { return s.allPending(); }
            @Override public List<ScheduledMessage> allHeld() { return s.allHeld(); }
            @Override public List<ScheduledMessage> inFlight() { return s.inFlight(); }
            @Override public List<ScheduledMessage> pendingForThread(String e) { return s.pendingForThread(e); }
        };
        s.put(message(ID).updatedAt(5).build());
        assertFalse(plain.markInFlight(ID, "h", T + 1, 5));
        assertFalse(plain.markInFlight(ID, "h", T, 6));
        assertEquals(Status.SCHEDULED, s.status(ID));
        assertTrue(plain.markInFlight(ID, "h", T, 5));
        assertEquals(Status.IN_FLIGHT, s.status(ID));
    }

    static String read(String rel) throws IOException {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater/" + rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
