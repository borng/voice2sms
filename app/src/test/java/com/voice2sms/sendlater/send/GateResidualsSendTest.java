package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.LatenessBudget;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.engine.RemindCause;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * gate-residuals (I2, R27, OD30): a corrupt row reports the generic reminder from start(), and the
 * launch-time lateness re-checks allow {@link LaunchRecheck#QUEUE_SLACK_MS} past the deadline
 * while the strict check stays exact. Fictional numbers only.
 */
public class GateResidualsSendTest {

    private static final Path SEND = Paths.get("src/main/java/com/voice2sms/sendlater/send");
    private static final Path CONTROLLER = SEND.resolve("ScheduledSendController.java");
    private static final Path SENDER = SEND.resolve("WebViewScheduledSender.java");

    private static final long MIN = 60_000L;
    private static final long T0 = 1_781_092_800_000L;
    private static final long T = T0 + 2 * 60 * MIN;

    private static ScheduledMessage never() {
        return new ScheduledMessage.Builder()
                .id(9L).createdAt(T0).scheduledFor(T)
                .text("See you at 9").recipientE164("+15555550100")
                .source(ScheduledMessage.Source.NATIVE_GV)
                .latenessPolicy(LatenessPolicy.NEVER_LATE)
                .status(ScheduledMessage.Status.IN_FLIGHT)
                .build();
    }

    private static String code(Path p) throws IOException {
        return SendPathSourceTest.stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    private static String body(String src, String name) {
        String b = SendPathSourceTest.methodBody(src, name);
        assertNotNull(name, b);
        return b;
    }

    // ================================================================== OD30 queue slack

    @Test public void theSlackIsTenSeconds() {
        assertEquals(10_000L, LaunchRecheck.QUEUE_SLACK_MS);
    }

    @Test public void aNeverLateLaunchTwoSecondsPastTheDeadlineStillSends() {
        ScheduledMessage m = never();
        assertEquals(T, LatenessBudget.deadlineMs(m));
        assertEquals(LaunchRecheck.Verdict.PROCEED,
                LaunchRecheck.check(m, T + 2_000L, LaunchRecheck.QUEUE_SLACK_MS));
        assertFalse(LaunchRecheck.isLate(m, T + 2_000L, LaunchRecheck.QUEUE_SLACK_MS));
        assertEquals("the slack edge is still on time", LaunchRecheck.Verdict.PROCEED,
                LaunchRecheck.check(m, T + LaunchRecheck.QUEUE_SLACK_MS, LaunchRecheck.QUEUE_SLACK_MS));
    }

    @Test public void elevenSecondsPastANeverLateDeadlineRemindsLate() {
        ScheduledMessage m = never();
        assertEquals(LaunchRecheck.Verdict.LATE,
                LaunchRecheck.check(m, T + 11_000L, LaunchRecheck.QUEUE_SLACK_MS));
        assertTrue(LaunchRecheck.isLate(m, T + 11_000L, LaunchRecheck.QUEUE_SLACK_MS));
        assertEquals(RemindCause.LATE, RemindCause.fromDetail(
                SendDetails.forEngine(SendOutcome.CANNOT_SHOW, LaunchRecheck.DETAIL_LATE)));
    }

    @Test public void theStrictCheckIsUnchanged() {
        ScheduledMessage m = never();
        assertEquals(LaunchRecheck.Verdict.LATE, LaunchRecheck.check(m, T + 2_000L));
        assertTrue(LaunchRecheck.isLate(m, T + 1L));
        assertEquals(LaunchRecheck.Verdict.LATE, LaunchRecheck.check(m, T + 1L, 0L));
        assertEquals("a negative slack is no slack", LaunchRecheck.Verdict.LATE,
                LaunchRecheck.check(m, T + 1L, -5_000L));
        assertEquals(LaunchRecheck.Verdict.GONE, LaunchRecheck.check(null, T, LaunchRecheck.QUEUE_SLACK_MS));
        assertFalse(LaunchRecheck.isLate(null, T + 60 * MIN, LaunchRecheck.QUEUE_SLACK_MS));
    }

    @Test public void bothLaunchTimeRechecksUseTheSlack() throws IOException {
        String recheck = body(code(SENDER), "recheckThen");
        assertTrue(recheck, recheck.contains("LaunchRecheck.check(SendLaterRepository.get(app).get(id), System.currentTimeMillis(),"));
        assertTrue(recheck.contains("LaunchRecheck.QUEUE_SLACK_MS)"));
        String loaded = body(code(CONTROLLER), "onLoaded");
        assertTrue(loaded.contains("LaunchRecheck.isLate(m, System.currentTimeMillis(), LaunchRecheck.QUEUE_SLACK_MS)"));
        assertFalse("no strict re-check left at dequeue",
                loaded.contains("LaunchRecheck.isLate(m, System.currentTimeMillis())"));
    }

    @Test public void theDomainEvaluatorHasNoSlack() throws IOException {
        Path domain = Paths.get("src/main/java/com/voice2sms/sendlater/domain");
        try (java.util.stream.Stream<Path> files = Files.list(domain)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                assertFalse(p.toString(), code(p).contains("QUEUE_SLACK"));
            }
        }
    }

    // ================================================================== corrupt row

    @Test public void aCorruptRowReportsCouldNotSendBeforeReturningFalse() throws IOException {
        String start = body(code(SENDER), "start");
        int corrupt = start.indexOf("message.getRecipientE164() == null || message.getText() == null");
        assertTrue(start, corrupt >= 0);
        int close = start.indexOf("}", corrupt);
        String branch = start.substring(corrupt, close);
        assertTrue(branch, branch.contains("return notStarted(app, id, ScheduledSendController.DETAIL_NO_RECIPIENT)"));
        assertFalse("not a bare return false", branch.contains("return false"));
        assertTrue("checked before any queue slot", corrupt < start.indexOf("acquireSlot("));
        // notStarted reports CANNOT_SHOW through SendDetails.forEngine, then returns false.
        String ns = body(code(SENDER), "notStarted");
        assertTrue(ns.contains("SendLaterEngine.onSendOutcome(app, id, SendOutcome.CANNOT_SHOW,"));
        assertTrue(ns.contains("SendDetails.forEngine(SendOutcome.CANNOT_SHOW, detail)"));
        assertTrue(ns.contains("return false;"));
        assertEquals(RemindCause.DETAIL_COULD_NOT_SEND,
                SendDetails.forEngine(SendOutcome.CANNOT_SHOW, ScheduledSendController.DETAIL_NO_RECIPIENT));
        assertEquals(RemindCause.COULD_NOT_SEND, RemindCause.fromDetail(
                SendDetails.forEngine(SendOutcome.CANNOT_SHOW, ScheduledSendController.DETAIL_NO_RECIPIENT)));
    }

    @Test public void noCommentStillSaysTheGenericCausesMeanLocked() throws IOException {
        for (Path p : new Path[] {SENDER, CONTROLLER, SEND.resolve("LaunchPlanner.java")}) {
            String raw = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            assertFalse(p.toString(), raw.contains("(LOCKED reminder)"));
            assertFalse(p.toString(), raw.contains("records the LOCKED reminder"));
        }
    }
}
