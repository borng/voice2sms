package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.engine.RemindCause;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * send-polish (OD28): a corrupt row is CANNOT_SHOW (generic reminder), "launched dark" comes from
 * the planning-time intent extra, and a non-scheduled intent over the keyguard keeps the cover up
 * until onStop with the lock flags cleared first.
 */
public class SendPolishTest {

    private static final Path SEND = Paths.get("src/main/java/com/voice2sms/sendlater/send");
    private static final Path CONTROLLER = SEND.resolve("ScheduledSendController.java");
    private static final Path SENDER = SEND.resolve("WebViewScheduledSender.java");

    private static String code(Path p) throws IOException {
        return SendPathSourceTest.stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    private static String body(String src, String name) {
        String b = SendPathSourceTest.methodBody(src, name);
        assertNotNull(name, b);
        return b;
    }

    // ================================================================== (1) corrupt row

    @Test public void aCorruptRowIsCannotShowWithTheGenericReminder() throws IOException {
        String loaded = body(code(CONTROLLER), "onLoaded");
        int corrupt = loaded.indexOf("m.getRecipientE164() == null || m.getText() == null");
        assertTrue(corrupt >= 0);
        String branch = loaded.substring(corrupt, loaded.indexOf("return;", corrupt));
        assertTrue(branch, branch.contains("report(job, SendOutcome.CANNOT_SHOW, DETAIL_NO_RECIPIENT)"));
        assertFalse("never FAILED", branch.contains("FAILED"));
        assertEquals("no_recipient", ScheduledSendController.DETAIL_NO_RECIPIENT);
        // The engine gets no detail: the LOCKED default, never Sign in, never "may have sent".
        assertNull(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, ScheduledSendController.DETAIL_NO_RECIPIENT));
        assertEquals(RemindCause.LOCKED, RemindCause.fromDetail(
                SendDetails.forEngine(SendOutcome.CANNOT_SHOW, ScheduledSendController.DETAIL_NO_RECIPIENT)));
    }

    @Test public void theSendPackageNeverReportsFailed() throws IOException {
        for (Path p : new Path[] {CONTROLLER, SENDER}) {
            assertFalse(p.getFileName().toString(), code(p).contains("SendOutcome.FAILED"));
        }
    }

    @Test public void sendDetailsNoLongerMentionsAFailedCause() throws IOException {
        String raw = new String(Files.readAllBytes(SEND.resolve("SendDetails.java")), StandardCharsets.UTF_8);
        assertFalse(raw.contains("FAILED cause"));
    }

    // ================================================================== (2) launched dark

    @Test public void thePlanningTimeExtraWinsOverTheActivityReading() {
        assertTrue(ScheduledSendController.darkAtLaunch(Boolean.TRUE, false));
        assertFalse(ScheduledSendController.darkAtLaunch(Boolean.FALSE, true));
        assertTrue("absent -> the activity's own reading", ScheduledSendController.darkAtLaunch(null, true));
        assertFalse(ScheduledSendController.darkAtLaunch(null, false));
    }

    @Test public void theSenderRecordsTheScreenWhenItPlansAndPassesItAsAnExtra() throws IOException {
        String sender = code(SENDER);
        String launch = body(sender, "launch");
        int dark = launch.indexOf("isScreenOn(app)");
        assertTrue("screen read when the launch is planned", dark >= 0 && dark < launch.indexOf("switch ("));
        assertTrue(launch.contains("launchDirect(app, id, token, checkTime, dark)"));
        assertTrue(launch.contains("postFullScreenIntent(app, id, token, checkTime, dark)"));
        assertTrue(body(sender, "launchIntent")
                .contains("putExtra(ScheduledSendController.EXTRA_LAUNCHED_DARK, launchedDark)"));
        assertTrue(body(sender, "launchDirect").contains("launchIntent(app, id, token, checkTime, launchedDark)"));
        assertTrue(body(sender, "postFullScreenIntent").contains("launchIntent(app, id, token, checkTime, launchedDark)"));
    }

    @Test public void theControllerUsesTheExtraAndFallsBackOnlyWhenAbsent() throws IOException {
        String ctrl = code(CONTROLLER);
        String handle = body(ctrl, "handle");
        int read = handle.indexOf("hasExtra(EXTRA_LAUNCHED_DARK)");
        assertTrue("extra read in handle", read >= 0);
        assertTrue("one-shot: removed like the other extras", handle.contains("removeExtra(EXTRA_LAUNCHED_DARK)"));
        assertTrue("read before the claim notes the screen", read < handle.indexOf("noteLaunchScreen()"));
        String note = body(ctrl, "noteLaunchScreen");
        assertTrue(note, note.contains("darkAtLaunch(planned, planned == null && isScreenDark())"));
        assertTrue("consumed once", note.contains("plannedDark = null"));
        assertTrue("a hand-off has no extra", body(ctrl, "onClaimed").indexOf("plannedDark = null")
                < body(ctrl, "onClaimed").indexOf("noteLaunchScreen()"));
    }

    // ================================================================== (3) other intent over the keyguard

    @Test public void keepTheCoverUntilStopOnlyWhenCoveredAndLocked() {
        assertTrue(LockCoverPolicy.keepCoverUntilStop(true, true));
        assertFalse(LockCoverPolicy.keepCoverUntilStop(true, false));
        assertFalse(LockCoverPolicy.keepCoverUntilStop(false, true));
        assertFalse(LockCoverPolicy.keepCoverUntilStop(false, false));
    }

    @Test public void anOtherIntentOverTheKeyguardClearsFlagsThenKeepsTheCoverUntilOnStop() throws IOException {
        String ctrl = code(CONTROLLER);
        String other = body(ctrl, "onOtherIntent");
        int flags = other.indexOf("applyLockFlags(false)");
        int keep = other.indexOf("LockCoverPolicy.keepCoverUntilStop(cover.isShown(), refreshLockState())");
        int hide = other.indexOf("if (!coverUntilStop) cover.hide()");
        assertTrue(other, flags >= 0 && keep > flags && hide > keep);
        String stop = body(ctrl, "onPauseOrStop");
        int lift = stop.indexOf("if (stopped && coverUntilStop)");
        assertTrue(stop, lift >= 0 && stop.indexOf("cover.hide()", lift) > lift);
        assertTrue("re-read before deciding", stop.indexOf("refreshLockState()") < lift);
        assertTrue("a new claim starts fresh", body(ctrl, "handle").contains("coverUntilStop = false"));
    }
}
