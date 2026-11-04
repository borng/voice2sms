package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.E164;
import static com.voice2sms.sendlater.engine.EngineFakes.HOUR;
import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.inboundSms;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static com.voice2sms.sendlater.engine.EngineFakes.outboundSame;
import static com.voice2sms.sendlater.engine.EngineFakes.watermark;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SessionState;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEventSource;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.listener.ListenerEventStore;
import com.voice2sms.sendlater.listener.NotificationThreadEventSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * engine-followups-2 (OD22 (1), (4), (5), (7)): snooze baselines through the engine, the D23 R3 to
 * R1 upgrade at due time, fail-closed listener availability for a nameless message, and the single
 * listener source. Fictional numbers (555-01xx) and placeholder names only.
 */
public class SendLaterEngineFollowups2Test {

    private static final long ID = 11L;
    private EngineFakes.World w;
    private RecordingSource source;

    /** Outbound-capable source that records the baseline it was asked to read from. */
    static final class RecordingSource implements OutboundCapableSource {
        final List<ThreadEvent> events = new ArrayList<>();
        final List<Watermark> asked = new ArrayList<>();
        @Override public boolean isAvailable() { return true; }
        @Override public List<ThreadEvent> readSince(ConversationRef ref, Watermark wm) {
            asked.add(wm);
            List<ThreadEvent> out = new ArrayList<>();
            for (ThreadEvent e : events) {
                if (wm == null || e.getTimeMs() >= Math.min(wm.getCapturedAtDevice(),
                        wm.getNewestTs() != null ? wm.getNewestTs() : Long.MAX_VALUE) - 15 * MIN) out.add(e);
            }
            return out;
        }
    }

    /** Listener store with nothing recorded and no number match. */
    static final class EmptyListenerStore implements ListenerEventStore {
        final List<String> numberMatches = new ArrayList<>();
        @Override public List<ScheduledMessage> liveMessages() { return new ArrayList<>(); }
        @Override public long recordEvent(ConversationRef ref, ThreadEvent event, String source, long at) { return 1; }
        @Override public List<ThreadEvent> eventsSince(String key, long sinceMs) { return new ArrayList<>(); }
        @Override public int purgeEvents(long olderThanMs, boolean orphans) { return 0; }
        @Override public void noteNumberMatch(String key) { numberMatches.add(key); }
        @Override public boolean hasNumberMatch(String key) { return numberMatches.contains(key); }
    }

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        source = new RecordingSource();
    }

    @After public void tearDown() { SendLaterEngine.resetForTest(); }

    private void install(ThreadEventSource... sources) {
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store)
                .sources(new ArrayList<>(Arrays.asList(sources)))
                .notifier(w.notifier)
                .broadcaster(w.broadcaster)
                .alarms(w.alarms)
                .conditions(w.cond)
                .networkWaiter(w.network)
                .executor(Runnable::run)
                .build());
        SendLaterEngine.setSender(w.sender);
    }

    // ================================================================== (1) snooze baselines

    /** HELD at T because of a reply at T - 1 min, then snoozed at T + 20 min. */
    private long holdThenSnooze() {
        install(source);
        w.store.put(message(ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        source.events.add(inboundSms(T - MIN));
        w.cond.now = T + 20 * MIN;
        SendLaterEngine.onSnooze(null, ID);
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        return T + 20 * MIN + HOUR;
    }

    @Test public void snoozeKeepsTheOriginalWatermarkAsSelfPreemptionBaseline() {
        holdThenSnooze();
        ScheduledMessage m = w.store.rows.get(ID);
        assertEquals(watermark(), m.getOriginalWatermark());
        assertEquals(Watermark.Quality.DEVICE_ONLY, m.getWatermark().getQuality());
        assertEquals(T + 20 * MIN, m.getWatermark().getCapturedAtDevice());
        // A second snooze keeps the first original.
        w.cond.now = T + 2 * HOUR;
        SendLaterEngine.onSnooze(null, ID);
        assertEquals(watermark(), w.store.rows.get(ID).getOriginalWatermark());
    }

    @Test public void similarOutboundBetweenHoldAndSnoozeStillCancelsAfterSnooze() {
        long due = holdThenSnooze();
        source.events.add(outboundSame(T + 5 * MIN)); // sent by hand between the hold and the snooze
        w.cond.now = due;
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.SELF_PREEMPTED, w.store.status(ID));
        assertTrue(w.sender.started.isEmpty());
        // The read reached back to the original watermark.
        assertEquals(watermark(), source.asked.get(source.asked.size() - 1));
    }

    @Test public void replyBeforeTheSnoozeNoLongerHoldsTheSnoozedMessage() {
        long due = holdThenSnooze();
        w.cond.now = due;
        SendLaterEngine.onDue(null, ID);
        assertEquals(Arrays.asList(ID), w.sender.started);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
    }

    @Test public void sendAnywayAfterSnoozeChecksSelfPreemptionFromTheOriginal() {
        holdThenSnooze();
        source.events.add(outboundSame(T + 5 * MIN));
        SendLaterEngine.onSendAnyway(null, ID);
        assertEquals(Status.SELF_PREEMPTED, w.store.status(ID));
        assertTrue(w.sender.started.isEmpty());
    }

    @Test public void readBaselineIsTheEarlierOfTheTwo() {
        Watermark fresh = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, T + HOUR, null);
        ScheduledMessage m = message(ID).watermark(fresh).originalWatermark(watermark()).build();
        assertEquals(watermark(), SendLaterEngine.readBaseline(m));
        assertEquals(watermark(), SendLaterEngine.readBaseline(message(ID).build()));
        assertNull(SendLaterEngine.readBaseline(message(ID).watermark(null).build()));
    }

    // ================================================================== (4) R3 / NO_SESSION -> R1

    private ScheduledMessage r3NoSession() {
        return message(ID).rung(Rung.R3).rungReason(RemindReason.NO_SESSION).build();
    }

    @Test public void r3NoSessionWithASessionAtDueTimeIsEvaluatedAtR1AndStored() {
        install(source);
        w.store.put(r3NoSession());
        w.cond.session = SessionState.READY;
        SendLaterEngine.onDue(null, ID);
        assertTrue("stored rung upgraded: " + w.j, w.j.indexOf("store.upgradeRung " + ID + " R3 R1") >= 0);
        assertEquals(Rung.R1, w.store.rows.get(ID).getRung());
        assertNull(w.store.rows.get(ID).getRungReason());
        assertEquals(Arrays.asList(ID), w.sender.started);
    }

    @Test public void r3NoSessionStillWithoutSessionStaysR3AndReminds() {
        install(source);
        w.store.put(r3NoSession());
        w.cond.session = SessionState.MISSING;
        SendLaterEngine.onDue(null, ID);
        assertEquals(Rung.R3, w.store.rows.get(ID).getRung());
        assertTrue(w.sender.started.isEmpty());
        assertTrue(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " " + RemindReason.NO_SENDER));
    }

    @Test public void r3WithoutTheNoSessionReasonIsNotUpgraded() {
        install(source);
        w.store.put(message(ID).rung(Rung.R3).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(-1, w.j.indexOf("store.upgradeRung"));
        assertEquals(Rung.R3, w.store.rows.get(ID).getRung());
        assertTrue(w.sender.started.isEmpty());
    }

    @Test public void upgradedMessageStillGoesThroughTheThreadCheck() {
        install(source);
        w.store.put(r3NoSession());
        source.events.add(inboundSms(T - 5 * MIN));
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.REPLIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.sender.started.isEmpty());
    }

    @Test public void storeThatCannotUpgradeKeepsTheStoredRung() {
        EngineStore plain = new EngineStore() {
            @Override public ScheduledMessage get(long id) { return w.store.get(id); }
            @Override public boolean markInFlight(long id, String h) { return w.store.markInFlight(id, h); }
            @Override public boolean markSentVerified(long id) { return w.store.markSentVerified(id); }
            @Override public boolean hold(long id, HoldReason r) { return w.store.hold(id, r); }
            @Override public boolean cancel(long id) { return w.store.cancel(id); }
            @Override public boolean selfPreempt(long id) { return w.store.selfPreempt(id); }
            @Override public boolean expire(long id) { return w.store.expire(id); }
            @Override public boolean fail(long id, String e) { return w.store.fail(id, e); }
            @Override public boolean releaseForRetry(long id, String e) { return w.store.releaseForRetry(id, e); }
            @Override public boolean reschedule(long id, long at, boolean h,
                    com.voice2sms.sendlater.domain.LatenessPolicy p, Watermark wm) {
                return w.store.reschedule(id, at, h, p, wm);
            }
            @Override public List<ScheduledMessage> allPending() { return w.store.allPending(); }
            @Override public List<ScheduledMessage> allHeld() { return w.store.allHeld(); }
            @Override public List<ScheduledMessage> inFlight() { return w.store.inFlight(); }
            @Override public List<ScheduledMessage> pendingForThread(String e) { return w.store.pendingForThread(e); }
        };
        assertFalse(plain.upgradeRung(ID, Rung.R3, Rung.R1));
    }

    // ================================================================== (5) fail-closed names

    private NotificationThreadEventSource listenerSource(EmptyListenerStore store) {
        return new NotificationThreadEventSource(store, () -> true);
    }

    @Test public void listenerOnNamelessMessageWithNoNumberMatchHoldsUnverified() {
        install(listenerSource(new EmptyListenerStore()));
        w.cond.listener = true;
        w.store.put(message(ID).recipientDisplayName(null).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.sender.started.isEmpty());
    }

    @Test public void listenerOnBlankNameCountsAsNameless() {
        install(listenerSource(new EmptyListenerStore()));
        w.store.put(message(ID).recipientDisplayName("   ").build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
    }

    @Test public void listenerOnNamelessMessageWithANumberMatchSends() {
        EmptyListenerStore store = new EmptyListenerStore();
        store.noteNumberMatch(E164);
        install(listenerSource(store));
        w.store.put(message(ID).recipientDisplayName(null).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Arrays.asList(ID), w.sender.started);
    }

    @Test public void listenerOnNamedMessageWithNothingRecordedSends() {
        install(listenerSource(new EmptyListenerStore()));
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Arrays.asList(ID), w.sender.started);
    }

    // ================================================================== (7) one listener source

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms/sendlater");

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test public void onlyOneNotificationSourceRemainsAndItIsRegistered() throws IOException {
        assertFalse("engine NotificationEventSource deleted",
                Files.exists(MAIN.resolve("engine/NotificationEventSource.java")));
        List<Path> impls;
        try (Stream<Path> s = Files.walk(MAIN)) {
            impls = s.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return read(p).matches("(?s).*class\\s+\\w*Notification\\w*\\s+implements\\s+"
                            + "(\\w+\\.)*ThreadEventSource\\b.*");
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }).collect(Collectors.toList());
        }
        assertEquals(impls.toString(), 1, impls.size());
        assertTrue(impls.get(0).endsWith(Paths.get("listener/NotificationThreadEventSource.java")));
        String deps = read(MAIN.resolve("engine/AndroidEngineDeps.java"));
        assertTrue(deps.contains("NotificationThreadEventSource.create(app)"));
        assertFalse(deps.contains("new NotificationEventSource"));
    }

    @Test public void registeredListenerSourceIsUsableThroughTheEngineSeam() {
        NotificationThreadEventSource src = listenerSource(new EmptyListenerStore());
        assertNotNull(src);
        assertTrue(src.isAvailable());
        assertFalse(src.isAvailableFor(new ConversationRef(E164, null)));
        assertTrue(src.isAvailableFor(new ConversationRef(E164, "Test Contact")));
        assertFalse(OutboundCapableSource.class.isAssignableFrom(NotificationThreadEventSource.class));
    }
}
