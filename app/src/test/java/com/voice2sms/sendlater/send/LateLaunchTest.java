package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * reminder-wording-late-launch (F4, F6; I2, R27): a queued launch re-checks the lateness deadline
 * with a fake clock, and every send-path CANNOT_SHOW cause maps to the right engine detail.
 * Fictional numbers only.
 */
public class LateLaunchTest {

    private static final Path SEND = Paths.get("src/main/java/com/voice2sms/sendlater/send");
    private static final Path CONTROLLER = SEND.resolve("ScheduledSendController.java");
    private static final Path SENDER = SEND.resolve("WebViewScheduledSender.java");

    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long T0 = 1_781_092_800_000L;
    private static final long T = T0 + 2 * HOUR;   // 2 h ahead: DEFAULT budget 30 min

    private static ScheduledMessage.Builder row() {
        return new ScheduledMessage.Builder()
                .id(7L)
                .createdAt(T0)
                .scheduledFor(T)
                .text("See you at 9")
                .recipientE164("+15555550100")
                .source(ScheduledMessage.Source.NATIVE_GV)
                .status(ScheduledMessage.Status.IN_FLIGHT);
    }

    private static String code(Path p) throws IOException {
        return SendPathSourceTest.stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    private static String body(String src, String name) {
        String b = SendPathSourceTest.methodBody(src, name);
        assertNotNull(name, b);
        return b;
    }

    // ================================================================== fake-clock recheck (F4)

    @Test public void withinTheBudgetTheLaunchProceeds() {
        ScheduledMessage m = row().build();
        assertEquals(T + 30 * MIN, LatenessBudget.deadlineMs(m));
        assertEquals(LaunchRecheck.Verdict.PROCEED, LaunchRecheck.check(m, T + 2_000L));
        assertEquals("the deadline itself is still on time", LaunchRecheck.Verdict.PROCEED,
                LaunchRecheck.check(m, T + 30 * MIN));
    }

    @Test public void aLaunchDelayedPastTheDeadlineIsLate() {
        ScheduledMessage m = row().build();
        assertEquals(LaunchRecheck.Verdict.LATE, LaunchRecheck.check(m, T + 30 * MIN + 1));
        assertEquals("deep sleep stretched the queue delay", LaunchRecheck.Verdict.LATE,
                LaunchRecheck.check(m, T + 5 * HOUR));
        assertTrue(LaunchRecheck.isLate(m, T + 31 * MIN));
        assertFalse(LaunchRecheck.isLate(m, T + 29 * MIN));
        assertFalse(LaunchRecheck.isLate(null, T + 5 * HOUR));
    }

    @Test public void thePolicyDecidesTheDeadline() {
        ScheduledMessage within30 = row().latenessPolicy(LatenessPolicy.WITHIN_30_MIN).build();
        assertEquals(LaunchRecheck.Verdict.PROCEED, LaunchRecheck.check(within30, T + 29 * MIN));
        assertEquals(LaunchRecheck.Verdict.LATE, LaunchRecheck.check(within30, T + 31 * MIN));
        ScheduledMessage never = row().latenessPolicy(LatenessPolicy.NEVER_LATE).build();
        assertEquals(LaunchRecheck.Verdict.PROCEED, LaunchRecheck.check(never, T));
        assertEquals(LaunchRecheck.Verdict.LATE, LaunchRecheck.check(never, T + 2_000L));
        ScheduledMessage day = row().latenessPolicy(LatenessPolicy.UP_TO_24H).build();
        assertEquals(LaunchRecheck.Verdict.PROCEED, LaunchRecheck.check(day, T + 23 * HOUR));
        assertEquals(LaunchRecheck.Verdict.LATE, LaunchRecheck.check(day, T + 25 * HOUR));
    }

    @Test public void aRowThatMovedOnIsGoneNotLate() {
        assertEquals(LaunchRecheck.Verdict.GONE, LaunchRecheck.check(null, T));
        for (ScheduledMessage.Status s : ScheduledMessage.Status.values()) {
            if (s == ScheduledMessage.Status.IN_FLIGHT) continue;
            assertEquals(s.toString(), LaunchRecheck.Verdict.GONE,
                    LaunchRecheck.check(row().status(s).build(), T + 5 * HOUR));
        }
    }

    @Test public void theLateDetailReachesTheEngineAsLate() {
        assertEquals("late", LaunchRecheck.DETAIL_LATE);
        assertEquals("late", SendDetails.forEngine(SendOutcome.CANNOT_SHOW, LaunchRecheck.DETAIL_LATE));
        assertEquals(RemindCause.LATE,
                RemindCause.fromDetail(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, LaunchRecheck.DETAIL_LATE)));
        assertFalse("never confused with late_reply",
                ScheduledSendController.DETAIL_LATE_REPLY.equals(LaunchRecheck.DETAIL_LATE));
    }

    // ================================================================== wiring (F4)

    @Test public void theQueuedLaunchIsRecheckedBeforeItRuns() throws IOException {
        String src = code(SENDER);
        String start = body(src, "start");
        assertTrue(start, start.contains("MAIN.postDelayed(() -> recheckThen(app, id, token, () -> launch(app, id, token, checkTime)), delayMs)"));
        String recheck = body(src, "recheckThen");
        int io = recheck.indexOf("IO.execute(");
        int read = recheck.indexOf("SendLaterRepository.get(app).get(id)");
        assertTrue("the row is reloaded off the main thread", io >= 0 && read > io);
        assertTrue(recheck.contains("LaunchRecheck.check("));
        int late = recheck.indexOf("case LATE:");
        int report = recheck.indexOf("SendDetails.forEngine(SendOutcome.CANNOT_SHOW, LaunchRecheck.DETAIL_LATE)");
        int proceed = recheck.indexOf("MAIN.post(launch)");
        assertTrue("late -> CANNOT_SHOW late", late >= 0 && report > late);
        assertTrue("the launch runs only after the verdict", proceed > report);
        assertTrue("the claim is taken before reporting",
                recheck.indexOf("LaunchClaims.INSTANCE.take(id, token)", late) < report);
    }

    @Test public void theControllerRechecksEveryDequeuedJobBeforeComposing() throws IOException {
        String loaded = body(code(CONTROLLER), "onLoaded");
        int status = loaded.indexOf("m.getStatus() != ScheduledMessage.Status.IN_FLIGHT");
        // gate-residuals (OD30): the dequeue re-check allows the queue slack past the deadline.
        int late = loaded.indexOf("LaunchRecheck.isLate(m, System.currentTimeMillis(), LaunchRecheck.QUEUE_SLACK_MS)");
        int report = loaded.indexOf("report(job, SendOutcome.CANNOT_SHOW, LaunchRecheck.DETAIL_LATE)");
        int compose = loaded.indexOf("beginCompose(");
        assertTrue(status >= 0 && late > status);
        assertTrue(report > late && compose > report);
        // startNext (every job, including a hand-off) is what loads the row for onLoaded.
        String next = body(code(CONTROLLER), "startNext");
        assertTrue(next.contains("SendLaterRepository.get(app).get(job.id)"));
        assertTrue(next.contains("onLoaded(job, loaded)"));
        assertTrue(body(code(CONTROLLER), "acceptHandOff").contains("enqueue("));
    }

    // ================================================================== CANNOT_SHOW causes (F6)

    @Test public void nonLockCausesMapToCouldNotSend() {
        for (String cause : new String[] {"compose_timeout", "timeout", "inject_failed", "launch_timeout",
                "paused", "activity_gone", "other_intent", ScheduledSendController.DETAIL_NO_RECIPIENT,
                "cannot_show"}) {
            assertEquals(cause, RemindCause.DETAIL_COULD_NOT_SEND, SendDetails.forEngine(SendOutcome.CANNOT_SHOW, cause));
            assertEquals(cause, RemindCause.COULD_NOT_SEND,
                    RemindCause.fromDetail(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, cause)));
        }
    }

    @Test public void thePlannerCauseFollowsTheKeyguard() {
        assertEquals(RemindCause.DETAIL_LOCKED, SendDetails.plannerCause(true));
        assertEquals(RemindCause.LOCKED,
                RemindCause.fromDetail(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, SendDetails.plannerCause(true))));
        assertEquals("cannot_show", SendDetails.plannerCause(false));
        assertEquals(RemindCause.COULD_NOT_SEND,
                RemindCause.fromDetail(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, SendDetails.plannerCause(false))));
    }

    @Test public void theOtherCausesKeepTheirMeaning() {
        assertEquals(RemindCause.NO_SESSION,
                RemindCause.fromDetail(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "auth")));
        assertEquals(RemindCause.DAILY_CAP, RemindCause.fromDetail(
                SendDetails.forEngine(SendOutcome.CANNOT_SHOW, WebViewScheduledSender.DETAIL_DAILY_CAP)));
        assertEquals(RemindCause.BUSY, RemindCause.fromDetail(
                SendDetails.forEngine(SendOutcome.CANNOT_SHOW, ForegroundUse.DETAIL_USER_BUSY)));
        assertNull(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, null));
        assertEquals(RemindCause.LOCKED, RemindCause.fromDetail(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, null)));
    }

    @Test public void bothPlannerRefusalsPassTheKeyguardCause() throws IOException {
        String src = code(SENDER);
        String start = body(src, "start");
        int refuse = start.indexOf("plan == LaunchPlanner.Result.CANNOT_SHOW");
        assertTrue(refuse >= 0);
        assertTrue(start.indexOf("notStarted(app, id, SendDetails.plannerCause(isKeyguardLocked(app)))", refuse) > refuse);
        String launch = body(src, "launch");
        assertTrue(launch.contains("SendDetails.plannerCause(isKeyguardLocked(app))"));
        assertFalse("no raw cannot_show left in the sender", launch.contains("\"cannot_show\""));
    }
}
