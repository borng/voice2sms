package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.HOUR;
import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.inboundSms;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;
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
import java.util.Arrays;
import java.util.List;

/**
 * Engine follow-ups (OD21; R24, R28, R31, I5): outbound-capable retry rule, fresh snooze watermark,
 * no duplicate last call, hold/remind alert once, "may be a duplicate" label.
 */
public class SendLaterEngineFollowupsTest {

    private static final long ID = 7L;
    private EngineFakes.World w;
    private EngineMarks marks;
    /** Stands in for NotificationEventSource: sees inbound messages only. */
    private InboundOnlySource listener;

    /** A plain ThreadEventSource (not outbound-capable), like the notification listener's. */
    static final class InboundOnlySource implements ThreadEventSource {
        boolean available = true;
        final List<ThreadEvent> events = new ArrayList<>();
        final EngineFakes.Journal j;

        InboundOnlySource(EngineFakes.Journal j) { this.j = j; }

        @Override public boolean isAvailable() { return available; }

        @Override public List<ThreadEvent> readSince(ConversationRef ref, Watermark wm) {
            j.add("listener.read");
            return new ArrayList<>(events);
        }
    }

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        listener = new InboundOnlySource(w.j);
        marks = new EngineMarks.InMemory();
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
                .marks(marks)
                .build());
        SendLaterEngine.setSender(w.sender);
    }

    // ================================================================== (1) retry rule

    @Test public void notificationEventSourceIsNotOutboundCapable() {
        assertFalse(OutboundCapableSource.class.isAssignableFrom(NotificationEventSource.class));
        assertTrue(ThreadEventSource.class.isAssignableFrom(OutboundCapableSource.class));
    }

    @Test public void inFlightWithOnlyTheListenerSourceHoldsUnverifiedAndNeverRetries() {
        install(listener);
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        w.cond.now = T + MIN;
        SendLaterEngine.resolveInFlight(null);
        assertTrue("the thread was read: " + w.j, w.j.indexOf("listener.read") >= 0);
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.notifier.posted.toString(), w.notifier.has("mayHaveSent " + ID));
        assertEquals(0, w.j.count("store.releaseForRetry"));
        assertTrue(w.sender.started.isEmpty());
    }

    @Test public void inFlightWithAnOutboundCapableSourceShowingNoBubbleRetriesOnce() {
        install(w.source);
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        w.cond.now = T + MIN;
        SendLaterEngine.resolveInFlight(null);
        assertEquals(1, w.j.count("store.releaseForRetry"));
        assertEquals("one retry within budget", 1, w.sender.started.size());
        assertTrue("read before retry: " + w.j, w.j.indexOf("source.read") < w.j.indexOf("sender.start"));
        assertFalse(w.notifier.has("mayHaveSent " + ID));
    }

    @Test public void listenerPlusAvailableOutboundCapableSourceRetries() {
        install(listener, w.source);
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        w.cond.now = T + MIN;
        SendLaterEngine.resolveInFlight(null);
        assertEquals(1, w.sender.started.size());
    }

    @Test public void unavailableOutboundCapableSourceDoesNotCount() {
        w.source.available = false;
        install(listener, w.source);
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        w.cond.now = T + MIN;
        SendLaterEngine.resolveInFlight(null);
        assertTrue(w.sender.started.isEmpty());
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.notifier.has("mayHaveSent " + ID));
    }

    @Test public void failedOutcomeWithOnlyTheListenerSourceHoldsUnverified() {
        install(listener);
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(1, w.sender.started.size());
        w.cond.now = T + 30_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.FAILED, "x");
        assertEquals("never retried", 1, w.sender.started.size());
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.notifier.has("mayHaveSent " + ID));
        assertEquals(0, w.j.count("store.releaseForRetry"));
    }

    @Test public void failedOutcomeWithNoSourceAtAllHoldsUnverified() {
        listener.available = false;
        install(listener);
        // Not reached through onDue (no source holds at due time); start from IN_FLIGHT directly.
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        w.cond.now = T + 30_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.FAILED, "x");
        assertTrue(w.sender.started.isEmpty());
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.notifier.has("mayHaveSent " + ID));
    }

    @Test public void failedOutcomeWithAnOutboundCapableSourceRetriesOnce() {
        install(w.source);
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        w.cond.now = T + 30_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.FAILED, "x");
        assertEquals(2, w.sender.started.size());
        assertEquals(1, w.j.count("store.releaseForRetry"));
    }

    // ================================================================== (2) snooze watermark

    @Test public void snoozeStoresAFreshDeviceOnlyWatermarkAtNow() {
        install(w.source);
        w.store.put(message(ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        w.cond.now = T + HOUR;
        SendLaterEngine.onSnooze(null, ID);
        Watermark wm = w.store.rows.get(ID).getWatermark();
        assertEquals(Watermark.Quality.DEVICE_ONLY, wm.getQuality());
        assertEquals(T + HOUR, wm.getCapturedAtDevice());
        assertEquals(null, wm.getNewestTs());
    }

    @Test public void theReplyThatCausedTheHoldNoLongerHoldsTheSnoozedMessage() {
        install(w.source);
        w.store.put(message(ID).build());
        // A reply arrives before T: the message is held.
        w.cond.now = T - 20 * MIN;
        w.source.events.add(inboundSms(T - 20 * MIN));
        SendLaterEngine.onInboundEvent(null, EngineFakes.E164, inboundSms(T - 20 * MIN));
        assertEquals(Status.HELD, w.store.status(ID));
        // The user snoozes from the hold notification; at the new time the same reply is still in
        // the thread read, but it predates the fresh watermark.
        w.cond.now = T + 10 * MIN;
        SendLaterEngine.onSnooze(null, ID);
        long due = w.store.rows.get(ID).getScheduledFor();
        assertEquals(T + 70 * MIN, due);
        w.cond.now = due;
        SendLaterEngine.onDue(null, ID);
        assertEquals("sends instead of holding again", 1, w.sender.started.size());
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
    }

    @Test public void aNewReplyAfterTheSnoozeStillHolds() {
        install(w.source);
        w.store.put(message(ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        w.cond.now = T + 10 * MIN;
        SendLaterEngine.onSnooze(null, ID);
        w.cond.now = T + 40 * MIN;
        SendLaterEngine.onInboundEvent(null, EngineFakes.E164, inboundSms(T + 40 * MIN));
        assertEquals(Status.HELD, w.store.status(ID));
    }

    // ================================================================== (3) last call once

    @Test public void reregisterAllTwiceWithinFiveMinutesPostsTheLastCallOnce() {
        install(w.source);
        w.store.put(message(ID).build());
        w.cond.now = T - 3 * MIN;
        SendLaterScheduler.get(null).reregisterAll(null);
        w.cond.now = T - 2 * MIN;
        SendLaterScheduler.get(null).reregisterAll(null);
        assertEquals(w.notifier.posted.toString(), 1, count("lastCall " + ID));
        assertEquals("DUE still re-armed both times", 2, w.alarms.registered(AlarmKind.DUE, ID).size());
    }

    @Test public void lastCallAlarmThenReArmDoesNotPostAgain() {
        install(w.source);
        w.store.put(message(ID).build());
        w.cond.now = T - 5 * MIN;
        SendLaterEngine.onLastCall(null, ID);
        w.cond.now = T - 4 * MIN;
        SendLaterScheduler.get(null).reregisterAll(null);
        SendLaterEngine.onLastCall(null, ID);
        assertEquals(w.notifier.posted.toString(), 1, count("lastCall " + ID));
    }

    @Test public void schedulerWithItsOwnMarksPostsOnceAcrossReArms() {
        SendLaterScheduler s = new SendLaterScheduler(w.alarms, w.store, w.notifier, w.cond, marks);
        w.store.put(message(ID).build());
        w.cond.now = T - 2 * MIN;
        s.registerAlarms(w.store.rows.get(ID));
        s.reregisterAll(null);
        s.reregisterAll(null);
        assertEquals(1, count("lastCall " + ID));
    }

    @Test public void aRescheduleToANewTimePostsAFreshLastCall() {
        SendLaterScheduler s = new SendLaterScheduler(w.alarms, w.store, w.notifier, w.cond, marks);
        w.store.put(message(ID).build());
        w.cond.now = T - 2 * MIN;
        s.registerAlarms(w.store.rows.get(ID));
        w.store.reschedule(ID, T + 2 * MIN, true, w.store.rows.get(ID).getLatenessPolicy(), null);
        s.registerAlarms(w.store.rows.get(ID));
        assertEquals(2, count("lastCall " + ID));
    }

    @Test public void theMarkIsClearedWhenTheRowLeavesScheduled() {
        install(w.source);
        w.store.put(message(ID).build());
        w.cond.now = T - 2 * MIN;
        SendLaterScheduler.get(null).reregisterAll(null);
        assertTrue(marks.isLastCallPosted(ID, T));
        SendLaterEngine.onInboundEvent(null, EngineFakes.E164, inboundSms(T - 2 * MIN));
        assertEquals(Status.HELD, w.store.status(ID));
        assertFalse(marks.isLastCallPosted(ID, T));
    }

    @Test public void theMarkIsClearedWhenTheSendStartsAndOnTerminalStates() {
        install(w.source);
        w.store.put(message(ID).build());
        w.cond.now = T - 2 * MIN;
        SendLaterScheduler.get(null).reregisterAll(null);
        w.cond.now = T;
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertFalse(marks.isLastCallPosted(ID, T));

        EngineMarks.InMemory m = new EngineMarks.InMemory();
        m.setLastCallPosted(9, T);
        m.clear(9);
        assertFalse(m.isLastCallPosted(9, T));
    }

    private int count(String s) {
        int n = 0;
        for (String p : w.notifier.posted) if (p.equals(s)) n++;
        return n;
    }

    // ================================================================== (3) alert once

    @Test public void holdAndRemindAlertOnlyOnceButANewStatePostsFresh() throws IOException {
        String src = read("SendLaterNotifications.java");
        assertTrue(section(src, "public static void hold(").contains(".setOnlyAlertOnce(true)"));
        assertTrue(section(src, "public static void remind(").contains(".setOnlyAlertOnce(true)"));
        assertFalse("same state re-posted stays silent",
                SendLaterNotifications.needsFreshPost("hold:REPLIED", "hold:REPLIED"));
        assertTrue("a hold replacing the last call alerts",
                SendLaterNotifications.needsFreshPost("lastCall", "hold:REPLIED"));
        assertTrue(SendLaterNotifications.needsFreshPost("hold:REPLIED", "hold:REACHED_OUT"));
        assertFalse("nothing showing", SendLaterNotifications.needsFreshPost(null, "remind:LOCKED"));
    }

    // ================================================================== (4) label

    @Test public void sendAnywayLabelAfterMayHaveSentSaysItMayBeADuplicate() {
        ScheduledMessage m = message(1).build();
        assertTrue(SendLaterNotifications.sendAnywayLabel(m, T + 5 * MIN, true).contains("may be a duplicate"));
        assertEquals("Send anyway (may be a duplicate)", SendLaterNotifications.sendAnywayLabel(m, T + 3 * HOUR, true));
        assertEquals("Send anyway", SendLaterNotifications.sendAnywayLabel(m, T + 5 * MIN, false));
        assertEquals("Send anyway (3 h late)", SendLaterNotifications.sendAnywayLabel(m, T + 3 * HOUR, false));
    }

    @Test public void mayHaveSentNotificationUsesTheDuplicateLabel() throws IOException {
        String body = section(read("SendLaterNotifications.java"), "public static void mayHaveSent(");
        assertTrue(body.contains("sendAnywayLabel(m, System.currentTimeMillis(), true)"));
        assertFalse(body.contains("\"Send anyway\""));
    }

    // ================================================================== helpers

    private static String read(String file) throws IOException {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater/engine", file);
        if (!Files.exists(p)) p = Paths.get("app/src/main/java/com/voice2sms/sendlater/engine", file);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** From {@code start} to the next "public static" (one method's body). */
    private static String section(String src, String start) {
        int a = src.indexOf(start);
        assertTrue("found " + start, a >= 0);
        int b = src.indexOf("public static", a + start.length());
        return src.substring(a, b < 0 ? src.length() : b);
    }
}
