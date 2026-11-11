package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.HOUR;
import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.T0;
import static com.voice2sms.sendlater.engine.EngineFakes.TEXT;
import static com.voice2sms.sendlater.engine.EngineFakes.inboundSms;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Acceptance (edit-text; D49, OD40, R12a, R12, OD31): the edit sheet's Update carries new message
 * text through the engine's reschedule path, stored in the same guarded write as the time, hold
 * and If-late, only for SCHEDULED/HELD rows; refused rows keep their text; every due-time reader
 * (send, D30 self-preemption, hold notification) uses the stored, edited text.
 *
 * <p>Pinned API (called through reflection so the suite compiles before it exists):
 * <pre>
 *   SendLaterEngine.reschedule(Context, long id, long scheduledFor, boolean holdIfReplied,
 *                              LatenessPolicy, Watermark baseline, String text) -&gt; TapResult
 *   EngineStore.reschedule(long id, long scheduledFor, boolean holdIfReplied,
 *                          LatenessPolicy, Watermark newWm, String text) -&gt; boolean
 *       // Java 8 default method (EngineFakes.FakeStore overrides it)
 * </pre>
 * Fictional data only (EngineFakes' reserved 555 number, placeholder texts).
 */
public class EditTextRescheduleTest {

    private static final long ID = 91L;
    private static final long NEW_TIME = T + 3 * HOUR;
    private static final String NEW_TEXT = "Changed plan: meet at the north gate instead";
    /** Exact Unicode must survive: ZWJ emoji, non-BMP, combining accent, RTL, internal newlines. */
    private static final String UNICODE_TEXT =
            "Café é 👩‍👩‍👧 𝄞\n"
            + "שלום  two  spaces\n\nlast line — 🚀";

    private EngineFakes.World w;
    private final List<String> sentTexts = new ArrayList<>();
    private final List<String> heldTexts = new ArrayList<>();

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        final EngineFakes.FakeNotifier base = w.notifier;
        EngineNotifier notifier = new EngineNotifier() {
            @Override public void lastCall(ScheduledMessage m) { base.lastCall(m); }
            @Override public void hold(ScheduledMessage m, HoldReason r, long nowMs) {
                heldTexts.add(m.getText());
                base.hold(m, r, nowMs);
            }
            @Override public void remind(ScheduledMessage m, RemindReason r) { base.remind(m, r); }
            @Override public void sent(ScheduledMessage m) { base.sent(m); }
            @Override public void repliedDuringSend(ScheduledMessage m) { base.repliedDuringSend(m); }
            @Override public void selfPreempted(ScheduledMessage m) { base.selfPreempted(m); }
            @Override public void expired(ScheduledMessage m) { base.expired(m); }
            @Override public void mayHaveSent(ScheduledMessage m) { base.mayHaveSent(m); }
            @Override public void dismiss(long id) { base.dismiss(id); }
        };
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store)
                .sources(new ArrayList<>(Collections.singletonList(w.source)))
                .notifier(notifier)
                .broadcaster(w.broadcaster)
                .alarms(w.alarms)
                .conditions(w.cond)
                .networkWaiter(w.network)
                .executor(Runnable::run)
                .build());
        SendLaterEngine.setSender(new ScheduledSender() {
            @Override public boolean start(Context context, ScheduledMessage m) {
                sentTexts.add(m.getText());
                return w.sender.start(context, m);
            }
        });
    }

    @After public void tearDown() { SendLaterEngine.resetForTest(); }

    // ------------------------------------------------------------------ reflection

    private static Method engineReschedule() {
        try {
            return SendLaterEngine.class.getMethod("reschedule", Context.class, long.class, long.class,
                    boolean.class, LatenessPolicy.class, Watermark.class, String.class);
        } catch (NoSuchMethodException e) {
            fail("missing SendLaterEngine.reschedule(Context, long, long, boolean, LatenessPolicy, "
                    + "Watermark, String text) (D49 / R12a)");
            return null;
        }
    }

    private static Watermark baseline(long at) {
        return new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, at, null);
    }

    /** The edit sheet's Update with new text; rethrows what the engine throws. */
    private TapResult edit(long to, boolean hold, LatenessPolicy policy, String text) {
        Method m = engineReschedule();
        try {
            return (TapResult) m.invoke(null, null, ID, to, hold, policy, baseline(w.cond.now), text);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            if (c instanceof Error) throw (Error) c;
            throw new AssertionError(c);
        }
    }

    private TapResult edit(String text) {
        return edit(NEW_TIME, true, LatenessPolicy.DEFAULT, text);
    }

    private ScheduledMessage row() { return w.store.rows.get(ID); }

    // ------------------------------------------------------------------ seam

    @Test public void engineStoreHasTheTextCarryingReschedule() throws Exception {
        Method m;
        try {
            m = EngineStore.class.getMethod("reschedule", long.class, long.class, boolean.class,
                    LatenessPolicy.class, Watermark.class, String.class);
        } catch (NoSuchMethodException e) {
            fail("missing EngineStore.reschedule(long, long, boolean, LatenessPolicy, Watermark, String text)");
            return;
        }
        assertEquals(boolean.class, m.getReturnType());
        assertTrue("must be a Java 8 default method so existing fakes keep compiling", m.isDefault());
    }

    // ------------------------------------------------------------------ atomic update (R12a)

    @Test public void updateStoresTextTimeHoldAndPolicyInOneGuardedWrite() {
        w.store.put(message(ID).build());
        w.cond.now = T - HOUR;
        assertEquals(TapResult.DONE, edit(NEW_TIME, false, LatenessPolicy.NEVER_LATE, NEW_TEXT));
        ScheduledMessage m = row();
        assertEquals(Status.SCHEDULED, m.getStatus());
        assertEquals(NEW_TEXT, m.getText());
        assertEquals(NEW_TIME, m.getScheduledFor());
        assertFalse(m.isHoldIfReplied());
        assertEquals(LatenessPolicy.NEVER_LATE, m.getLatenessPolicy());
        // One store write carries all four (no separate text write that could race a claim).
        assertEquals(w.j.toString(), 1, w.j.count("store.reschedule"));
        assertEquals(w.j.toString(), 1, w.j.count("store.reschedule+text " + ID));
        // The rest of the Update path still runs (re-arm at the new time, broadcast).
        assertEquals(NEW_TIME, w.alarms.timeOf("exact DUE " + ID));
        assertEquals(Collections.singletonList(ID + " " + EngineFakes.E164), w.broadcaster.sent);
    }

    @Test public void updateOfAHeldRowStoresTheTextAndMakesItScheduled() {
        w.store.put(message(ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        w.cond.now = T + 10 * MIN;
        assertEquals(TapResult.DONE, edit(NEW_TEXT));
        assertEquals(Status.SCHEDULED, row().getStatus());
        assertNull(row().getHoldReason());
        assertEquals(NEW_TEXT, row().getText());
        assertEquals(NEW_TIME, row().getScheduledFor());
    }

    @Test public void editedTextIsStoredWithExactUnicode() {
        w.store.put(message(ID).build());
        w.cond.now = T - HOUR;
        assertEquals(TapResult.DONE, edit(UNICODE_TEXT));
        assertEquals(UNICODE_TEXT, row().getText());
    }

    // ------------------------------------------------------------------ refused rows keep their text

    @Test public void updateIsRefusedForAnInFlightRowAndTheTextIsUnchanged() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        w.cond.now = T - HOUR;
        assertEquals(TapResult.ALREADY_SENDING, edit(NEW_TEXT));
        assertEquals(Status.IN_FLIGHT, row().getStatus());
        assertEquals(TEXT, row().getText());
        assertEquals(T, row().getScheduledFor());
        assertEquals(w.j.toString(), 0, w.j.count("store.reschedule"));
        assertTrue(w.alarms.calls.isEmpty());
        assertTrue(w.broadcaster.sent.isEmpty());
    }

    @Test public void updateIsRefusedForEveryTerminalRowAndTheTextIsUnchanged() {
        Status[] terminal = {Status.SENT_VERIFIED, Status.CANCELED, Status.SELF_PREEMPTED,
                Status.EXPIRED, Status.FAILED};
        for (Status s : terminal) {
            w.store.rows.clear();
            w.store.put(message(ID).status(s).build());
            w.cond.now = T - HOUR;
            TapResult r = edit(NEW_TEXT);
            assertNotEquals(s.toString(), TapResult.DONE, r);
            assertEquals(s.toString(), s, row().getStatus());
            assertEquals(s.toString(), TEXT, row().getText());
            assertEquals(s.toString(), T, row().getScheduledFor());
        }
        assertEquals(TapResult.ALREADY_SENT, staleFor(Status.SENT_VERIFIED));
        assertEquals(TapResult.ALREADY_CANCELLED, staleFor(Status.CANCELED));
        w.store.rows.clear();
        assertEquals(TapResult.NOT_PENDING, edit(NEW_TEXT));
        assertTrue(w.alarms.calls.isEmpty());
    }

    private TapResult staleFor(Status s) {
        w.store.rows.clear();
        w.store.put(message(ID).status(s).build());
        return edit(NEW_TEXT);
    }

    /** "Empty text cannot be saved": refused (or rejected) and nothing about the row changes. */
    @Test public void emptyOrWhitespaceTextIsNeverStored() {
        for (String blank : new String[] {"", "   ", " \n\t \n"}) {
            w.store.rows.clear();
            w.store.put(message(ID).build());
            w.cond.now = T - HOUR;
            TapResult r = null;
            try {
                r = edit(blank);
            } catch (IllegalArgumentException expected) {
                // rejecting the argument is fine too
            }
            assertNotEquals("[" + blank + "]", TapResult.DONE, r);
            assertEquals("[" + blank + "]", TEXT, row().getText());
            assertEquals("[" + blank + "]", T, row().getScheduledFor());
            assertEquals(Status.SCHEDULED, row().getStatus());
        }
    }

    // ------------------------------------------------------------------ due time reads the stored text

    @Test public void dueSendTypesTheEditedTextAndClaimsItsHash() {
        w.store.put(message(ID).build());
        w.cond.now = T - HOUR;
        assertEquals(TapResult.DONE, edit(NEW_TEXT));
        w.cond.now = NEW_TIME;
        SendLaterEngine.onDue(null, ID);
        assertEquals(Collections.singletonList(ID), w.sender.started);
        assertEquals(Collections.singletonList(NEW_TEXT), sentTexts);
        assertEquals("the IN_FLIGHT claim hashes the edited text",
                ThreadEvent.hashOf(NEW_TEXT), w.store.bodyHash.get(ID));
    }

    @Test public void selfPreemptionComparesAgainstTheEditedText() {
        w.store.put(message(ID).build());
        w.cond.now = T - HOUR;
        assertEquals(TapResult.DONE, edit(NEW_TEXT));
        // The user sent the edited text by hand after the edit.
        long sentByHand = T0 + 90 * MIN;
        w.source.events.add(new ThreadEvent(ThreadEvent.Direction.OUT, ThreadEvent.Kind.SMS, sentByHand,
                ThreadEvent.hashOf(NEW_TEXT), NEW_TEXT));
        w.cond.now = NEW_TIME;
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.SELF_PREEMPTED, w.store.status(ID));
        assertTrue(w.sender.started.isEmpty());
    }

    @Test public void holdNotificationAfterAnEditCarriesTheEditedText() {
        w.store.put(message(ID).build());
        w.cond.now = T - HOUR;
        assertEquals(TapResult.DONE, edit(NEW_TEXT));
        w.source.events.add(inboundSms(T - HOUR + 10 * MIN)); // a reply after the edit's baseline
        w.cond.now = NEW_TIME;
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.HELD, w.store.status(ID));
        assertTrue(w.sender.started.isEmpty());
        assertEquals(Collections.singletonList(NEW_TEXT), heldTexts);
    }
}
