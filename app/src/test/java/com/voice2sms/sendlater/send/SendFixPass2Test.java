package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEvent.Direction;
import com.voice2sms.sendlater.web.ThreadBubble;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** send-path fix pass 2: G1 settle, G2 not-typed failures, G3 no-baseline rule, G4 + OD23 details. */
public class SendFixPass2Test {

    private static final long NOW = 1_800_000_000_000L;
    private static final long CHECK = NOW - 20_000L;
    private static final String BODY = "Running ten minutes late, save me a seat please";
    private static final String HASH = ThreadEvent.hashOf(BODY);
    private static final Path SEND = Paths.get("src/main/java/com/voice2sms/sendlater/send");
    private static final Path ACTIVITY = Paths.get("src/main/java/com/voice2sms/GVoiceWebViewActivity.java");

    @After public void reset() {
        ForegroundUse.reset();
    }

    private static ThreadBubble out(Long t, String text, String id) { return new ThreadBubble(Direction.OUT, t, text, id); }
    private static ThreadBubble out(Long t, String text) { return out(t, text, null); }
    private static ThreadBubble in(Long t, String text) { return new ThreadBubble(Direction.IN, t, text, null); }
    private static List<ThreadBubble> list(ThreadBubble... b) { return new ArrayList<>(Arrays.asList(b)); }

    /** One poll as the controller runs it: onPoll, verify, settle. */
    private static SendVerifier.Result poll(SendWatch w, List<ThreadBubble> bubbles, boolean pending, long now) {
        List<ThreadEvent> ev = w.onPoll(bubbles, pending, now);
        SendVerifier.Result v = ev == null ? null : SendVerifier.verify(HASH, ev, CHECK, now);
        return w.settle(v, pending, now);
    }

    private static final List<ThreadBubble> BASE = list(in(NOW - 3_600_000L, "see you"));

    private static List<ThreadBubble> withOwn(ThreadBubble own) {
        List<ThreadBubble> l = new ArrayList<>(BASE);
        l.add(own);
        return l;
    }

    private static String code(Path p) throws IOException {
        return SendPathSourceTest.stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ G1 settle

    @Test public void oneSightingOfAnOptimisticBubbleDecidesNothing() {
        SendWatch w = new SendWatch(HASH);
        assertNull("baseline", poll(w, BASE, true, NOW - 5_000L));
        assertNull("GV draws the bubble before the server takes it: one sighting is not proof",
                poll(w, withOwn(out(null, BODY)), false, NOW));
    }

    @Test public void aSightingThatPersistsAcrossTheSettlePeriodVerifies() {
        SendWatch w = new SendWatch(HASH);
        poll(w, BASE, true, NOW - 5_000L);
        assertNull(poll(w, withOwn(out(null, BODY)), false, NOW));
        assertNull("still inside the settle period", poll(w, withOwn(out(null, BODY)), false, NOW + 2_999L));
        assertEquals(SendVerifier.Result.VERIFIED,
                poll(w, withOwn(out(null, BODY)), false, NOW + SendWatch.SETTLE_MS));
    }

    @Test public void aBubbleThatDisappearsStartsTheSettleOver() {
        SendWatch w = new SendWatch(HASH);
        poll(w, BASE, true, NOW - 5_000L);
        assertNull(poll(w, withOwn(out(null, BODY)), false, NOW));
        assertNull("bubble gone (send rejected)", poll(w, BASE, false, NOW + 1_000L));
        assertNull("seen again: a new first sighting", poll(w, withOwn(out(null, BODY)), false, NOW + 3_500L));
        assertNull(poll(w, withOwn(out(null, BODY)), false, NOW + 5_000L));
        assertEquals(SendVerifier.Result.VERIFIED, poll(w, withOwn(out(null, BODY)), false, NOW + 6_500L));
    }

    @Test public void textBackInTheBoxOrAnUnansweredProbeNeverSettles() {
        SendWatch w = new SendWatch(HASH);
        poll(w, BASE, true, NOW - 5_000L);
        assertNull(poll(w, withOwn(out(null, BODY)), false, NOW));
        assertNull("composer not empty", poll(w, withOwn(out(null, BODY)), true, NOW + 3_000L));
        assertNull("restarted after the box emptied again", poll(w, withOwn(out(null, BODY)), false, NOW + 4_000L));
        assertNull(w.settle(SendVerifier.Result.VERIFIED, true, NOW + 9_000L));
    }

    @Test public void aMatchWithAServerTimeOrIdVerifiesAtOnce() {
        SendWatch timed = new SendWatch(HASH);
        poll(timed, BASE, true, NOW - 5_000L);
        assertEquals(SendVerifier.Result.VERIFIED, poll(timed, withOwn(out(NOW - 1_000L, BODY)), false, NOW));

        SendWatch withId = new SendWatch(HASH);
        poll(withId, BASE, true, NOW - 5_000L);
        assertEquals(SendVerifier.Result.VERIFIED, poll(withId, withOwn(out(null, BODY, "m-42")), false, NOW));
    }

    @Test public void aLateReplySurvivesTheSettle() {
        SendWatch w = new SendWatch(HASH);
        poll(w, BASE, true, NOW - 5_000L);
        List<ThreadBubble> after = new ArrayList<>(BASE);
        after.add(in(null, "wait"));
        after.add(out(null, BODY));
        assertNull(poll(w, after, false, NOW));
        assertEquals(SendVerifier.Result.VERIFIED_WITH_LATE_REPLY, poll(w, after, false, NOW + 3_000L));
    }

    @Test public void settleNeverReportsUnverifiedItself() {
        SendWatch w = new SendWatch(HASH);
        assertNull(w.settle(SendVerifier.Result.UNVERIFIED, false, NOW));
        assertNull(w.settle(null, false, NOW));
    }

    @Test public void controllerSettlesBeforeVerifyingAndReportsUnverifiedAtTheDeadline() throws IOException {
        String src = code(SEND.resolve("ScheduledSendController.java"));
        String body = SendPathSourceTest.methodBody(src, "onThreadRead");
        assertNotNull(body);
        int settle = body.indexOf(".settle(");
        assertTrue("settle gate in onThreadRead", settle >= 0);
        assertTrue("SENT_VERIFIED only after the settle gate", body.indexOf("SENT_VERIFIED") > settle);
        int deadline = body.indexOf("deadlineMs");
        assertTrue("never settled before the deadline -> SENT_UNVERIFIED",
                deadline >= 0 && body.indexOf("SENT_UNVERIFIED", deadline) > deadline);
    }

    // ------------------------------------------------------------------ G3 no baseline

    @Test public void noBaselineAnOlderIdenticalUntimedBubbleNeverVerifies() {
        SendWatch w = new SendWatch(HASH);
        assertNull("thread unreadable while pending", poll(w, null, true, NOW - 5_000L));
        List<ThreadBubble> thread = list(out(NOW - 86_400_000L, "earlier text"), in(null, "ok"), out(null, BODY));
        for (long t = 0; t <= 10_000L; t += 1_000L) {
            assertNull("an untimed match among other outbound bubbles may be an old copy",
                    poll(w, thread, false, NOW + t));
        }
        List<ThreadEvent> ev = w.onPoll(thread, false, NOW + 11_000L);
        assertEquals(SendVerifier.Result.UNVERIFIED, SendVerifier.verify(HASH, ev, CHECK, NOW + 11_000L));
    }

    @Test public void noBaselineTheOnlyOutboundBubbleVerifiesAfterTheSettle() {
        SendWatch w = new SendWatch(HASH);
        poll(w, null, true, NOW - 5_000L);
        List<ThreadBubble> thread = list(out(null, BODY));
        assertNull(poll(w, thread, false, NOW));
        assertEquals(SendVerifier.Result.VERIFIED, poll(w, thread, false, NOW + 3_000L));
    }

    @Test public void noBaselineATimedBubbleIsJudgedByItsTime() {
        SendWatch recent = new SendWatch(HASH);
        poll(recent, null, true, NOW - 5_000L);
        assertEquals("timed at or after check - slack: verifies", SendVerifier.Result.VERIFIED,
                poll(recent, list(out(NOW - 86_400_000L, "x"), out(CHECK - SendVerifier.CLOCK_SLACK_MS, BODY)), false, NOW));

        SendWatch old = new SendWatch(HASH);
        poll(old, null, true, NOW - 5_000L);
        List<ThreadBubble> t = list(out(CHECK - SendVerifier.CLOCK_SLACK_MS - 1L, BODY));
        assertNull(poll(old, t, false, NOW));
        assertNull(poll(old, t, false, NOW + 5_000L));
    }

    @Test public void noBaselineAnIdDoesNotDateAnUntimedBubble() {
        SendWatch w = new SendWatch(HASH);
        poll(w, null, true, NOW - 5_000L);
        List<ThreadBubble> thread = list(out(null, "other"), out(null, BODY, "m-1"));
        assertNull(poll(w, thread, false, NOW));
        assertNull(poll(w, thread, false, NOW + 5_000L));
    }

    // ------------------------------------------------------------------ G2 not typed

    @Test public void aFailureBeforeTypingIsCannotShowAndAfterTypingUnverified() {
        assertEquals(SendOutcome.CANNOT_SHOW, ScheduledSendController.failureOutcome(false));
        assertEquals(SendOutcome.SENT_UNVERIFIED, ScheduledSendController.failureOutcome(true));
    }

    @Test public void webFailuresAndComposeTimeoutNeverReportFailed() throws IOException {
        String src = code(SEND.resolve("ScheduledSendController.java"));
        String web = SendPathSourceTest.methodBody(src, "onWebFailure");
        assertNotNull(web);
        assertFalse("no session / auth / inject failure is never FAILED", web.contains("FAILED"));
        assertTrue(web.contains("failureOutcome("));
        String loaded = SendPathSourceTest.methodBody(src, "onLoaded");
        int timeout = loaded.indexOf("COMPOSE_TIMEOUT_MS");
        int post = loaded.lastIndexOf("postDelayed(", timeout);
        String timer = loaded.substring(post, timeout);
        assertFalse("compose timeout / auth before injection is never FAILED", timer.contains("FAILED"));
        assertTrue("the cause is named", timer.contains("compose_timeout") || timer.contains("\"auth\""));
        assertTrue("the no-session cold start goes through onWebFailure with its cause",
                code(ACTIVITY).contains("onWebFailure(\"no_session\")"));
    }

    // ------------------------------------------------------------------ G4 + OD23

    @Test public void detailsArePinned() {
        assertEquals("daily_cap", WebViewScheduledSender.DETAIL_DAILY_CAP);
        assertEquals("user_busy", ForegroundUse.DETAIL_USER_BUSY);
        assertEquals(60_000L, ForegroundUse.BUSY_WINDOW_MS);
    }

    @Test public void theUserIsBusyOnlyWhenResumedAndTouchedInTheLastMinute() {
        assertTrue(ForegroundUse.isBusy(true, NOW - 59_999L, NOW));
        assertTrue(ForegroundUse.isBusy(true, NOW, NOW));
        assertFalse("a minute without a touch", ForegroundUse.isBusy(true, NOW - 60_000L, NOW));
        assertFalse("not in the foreground", ForegroundUse.isBusy(false, NOW - 1_000L, NOW));
        assertFalse("never touched", ForegroundUse.isBusy(true, -1L, NOW));
        ForegroundUse.noteResumed(true);
        ForegroundUse.noteTouch(NOW - 10_000L);
        assertTrue(ForegroundUse.isUserBusy(NOW));
        ForegroundUse.noteResumed(false);
        assertFalse(ForegroundUse.isUserBusy(NOW));
    }

    @Test public void senderRefusesWithDetailsAndReturnsFalse() throws IOException {
        String src = code(SEND.resolve("WebViewScheduledSender.java"));
        String start = SendPathSourceTest.methodBody(src, "start");
        assertNotNull(start);
        int busy = start.indexOf("isUserBusy(");
        int slot = start.indexOf("acquireSlot(");
        assertTrue("OD23: the busy check comes before a queue slot is used", busy >= 0 && busy < slot);
        assertTrue(start.contains("DETAIL_USER_BUSY"));
        int refused = start.indexOf("REFUSED");
        assertTrue("G4: the daily cap passes its detail", start.indexOf("DETAIL_DAILY_CAP", refused) > refused);
        String notStarted = SendPathSourceTest.methodBody(src, "notStarted");
        assertNotNull(notStarted);
        assertTrue(notStarted.contains("SendOutcome.CANNOT_SHOW"));
        assertTrue(notStarted.contains("return false"));
        assertTrue("the activity feeds the busy tracker",
                code(ACTIVITY).contains("ForegroundUse.noteTouch(") && code(ACTIVITY).contains("ForegroundUse.noteResumed("));
    }
}
