package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.inboundSms;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static com.voice2sms.sendlater.engine.EngineFakes.outboundSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;
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
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * verify-structural (OD27): only the scheduled-send controller's SENT_VERIFIED outcome produces
 * SENT_VERIFIED. No thread read (an old identical bubble, a fresh-looking own bubble, any source)
 * verifies or retries a started send; SENT_UNVERIFIED, FAILED after typing and IN_FLIGHT at
 * restart go to HELD(UNVERIFIED) + "may have already sent" with no sender.start.
 */
public class SendLaterEngineVerifyStructuralTest {

    private static final long ID = 41L;
    private static final Path ENGINE = Paths.get("src/main/java/com/voice2sms/sendlater/engine");

    private EngineFakes.World w;
    /** Inbound-only, like the notification listener. */
    private EngineFakes.FakeSource listener;
    /** Sees own outbound bubbles, like the on-screen source. */
    private EngineFakes.FakeOutboundSource onScreen;

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        listener = new EngineFakes.FakeSource(w.j);
        onScreen = new EngineFakes.FakeOutboundSource(w.j);
        install(listener, onScreen);
    }

    /** Every source shows our text twice: an old identical bubble and one that looks fresh. */
    private void seedOwnBubbles() {
        for (EngineFakes.FakeSource s : new EngineFakes.FakeSource[] {listener, onScreen}) {
            s.events.add(outboundSame(T - 3 * 60 * MIN));
            s.events.add(outboundSame(T + 10_000));
        }
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
                .marks(new EngineMarks.InMemory())
                .build());
        SendLaterEngine.setSender(w.sender);
    }

    private void assertNeverVerified() {
        assertFalse("status: " + w.store.status(ID), w.store.status(ID) == Status.SENT_VERIFIED);
        assertEquals(w.j.toString(), 0, w.j.count("store.markSentVerified"));
        assertFalse(w.notifier.posted.toString(), w.notifier.has("sent " + ID));
        assertFalse(w.notifier.posted.toString(), w.notifier.has("repliedDuringSend " + ID));
    }

    private void assertHeldMayHaveSentWithoutSend() {
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.notifier.posted.toString(), w.notifier.has("mayHaveSent " + ID));
        assertTrue("no sender.start: " + w.j, w.sender.started.isEmpty());
        assertEquals("no release for a retry: " + w.j, 0, w.j.count("store.releaseForRetry"));
        assertEquals("no thread read decides it: " + w.j, 0, w.j.count("source.read"));
        assertNeverVerified();
    }

    // ================================================================== the three hold cases

    @Test public void sentUnverifiedHoldsUnverifiedWithoutSending() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        seedOwnBubbles();
        w.cond.now = T + MIN;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_UNVERIFIED, "no_match");
        assertHeldMayHaveSentWithoutSend();
    }

    @Test public void failedAfterTypingHoldsUnverifiedWithoutSending() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        seedOwnBubbles();
        w.cond.now = T + MIN;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.FAILED, "inject_failed");
        assertHeldMayHaveSentWithoutSend();
    }

    @Test public void inFlightAtRestartHoldsUnverifiedWithoutSending() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        seedOwnBubbles();
        w.cond.now = T + MIN;
        SendLaterEngine.resolveInFlight(null);
        assertHeldMayHaveSentWithoutSend();
    }

    @Test public void inFlightAtProcessStartHoldsUnverifiedWithoutSending() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        seedOwnBubbles();
        w.cond.now = T + MIN;
        SendLaterEngine.onProcessStart(null);
        assertHeldMayHaveSentWithoutSend();
    }

    // ================================================================== no other path verifies

    @Test public void anOldIdenticalBubbleOrAnyReadNeverVerifiesThroughTheEngine() {
        // A full attempt: due -> IN_FLIGHT; then every engine entry point a read could reach.
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        seedOwnBubbles();
        w.cond.now = T + 30_000;
        SendLaterEngine.onDue(null, ID);
        SendLaterEngine.onSendAnyway(null, ID);
        SendLaterEngine.onSnooze(null, ID);
        SendLaterEngine.onInboundEvent(null, EngineFakes.E164, inboundSms(T + 20_000));
        SendLaterEngine.onExpireAlarm(null, ID);
        SendLaterEngine.sweepExpired(null);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_UNVERIFIED, null);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.FAILED, null);
        SendLaterEngine.resolveInFlight(null);
        SendLaterEngine.onProcessStart(null);
        assertEquals("only the first due started a send", 1, w.sender.started.size());
        assertNeverVerified();
        assertEquals(Status.HELD, w.store.status(ID));
    }

    @Test public void onlyTheControllersSentVerifiedReachesSentVerified() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        seedOwnBubbles();
        w.cond.now = T + MIN;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, null);
        assertEquals(Status.SENT_VERIFIED, w.store.status(ID));
        assertTrue(w.notifier.has("sent " + ID));
        assertTrue(w.sender.started.isEmpty());
    }

    @Test public void lateReplyNoticeUsesListenerInboundOnly() {
        // An inbound seen only by the outbound-capable source does not word the notice.
        EngineFakes.World w2 = w;
        install(onScreen);
        w2.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        onScreen.events.add(inboundSms(T + 10_000));
        w2.cond.now = T + 30_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, null);
        assertTrue(w2.notifier.posted.toString(), w2.notifier.has("sent " + ID));
        assertFalse(w2.notifier.has("repliedDuringSend " + ID));

        // The same inbound from the listener does.
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        listener = new EngineFakes.FakeSource(w.j);
        install(listener, new EngineFakes.FakeOutboundSource(w.j));
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        listener.events.add(inboundSms(T + 10_000));
        w.cond.now = T + 30_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, null);
        assertTrue(w.notifier.posted.toString(), w.notifier.has("repliedDuringSend " + ID));
    }

    // ================================================================== source guards

    private static String code(String file) throws IOException {
        String s = new String(Files.readAllBytes(ENGINE.resolve(file)), StandardCharsets.UTF_8);
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static int count(String src, String regex) {
        Matcher m = Pattern.compile(regex).matcher(src);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test public void sentVerifiedIsReachedOnlyFromTheSentVerifiedOutcome() throws IOException {
        String src = code("SendLaterEngine.java");
        assertEquals("one markSentVerified call", 1, count(src, "markSentVerified\\("));
        // sentVerified is declared once and called once, inside case SENT_VERIFIED.
        assertEquals(1, count(src, "private static void sentVerified\\("));
        assertEquals(2, count(src, "\\bsentVerified\\("));
        int caseAt = src.indexOf("case SENT_VERIFIED:");
        int call = src.indexOf("sentVerified(d, m, listenerInbound(d, m), detail)");
        assertTrue(caseAt >= 0 && call > caseAt && call < src.indexOf("case CANNOT_SHOW:", caseAt));
    }

    @Test public void noRetryOrReadVerificationMachineryRemains() throws IOException {
        String src = code("SendLaterEngine.java");
        for (String gone : new String[] {"retryAfterRead(", "canRetry(", "canRetryOn(", "ownBubbleSeen(",
                "MAX_RETRIES", "class ThreadRead"}) {
            assertFalse(gone + " must be gone (OD27)", src.contains(gone));
        }
        assertEquals("releaseForRetry only for CANNOT_SHOW (nothing typed)", 1, count(src, "releaseForRetry\\("));
        int cannotShow = src.indexOf("case CANNOT_SHOW:");
        int release = src.indexOf("releaseForRetry(");
        assertTrue(release > cannotShow && release < src.indexOf("case SENT_UNVERIFIED:", cannotShow));
        String store = code("EngineStore.java");
        assertTrue(store.contains("markSentVerified("));
    }
}
