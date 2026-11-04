package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.engine.RemindCause;
import com.voice2sms.sendlater.send.LockCoverPolicy.Event;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * verify-structural (OD27), send side: no arm mechanism, no WebView thread source for the engine,
 * compose_timeout gets the generic wording, and a job launched on a dark screen finishes at its
 * end instead of uncovering.
 */
public class VerifyStructuralSendTest {

    private static final Path SEND = Paths.get("src/main/java/com/voice2sms/sendlater/send");
    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path CONTROLLER = SEND.resolve("ScheduledSendController.java");

    private static String code(Path p) throws IOException {
        return SendPathSourceTest.stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    private static List<Path> java(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toCollection(ArrayList::new));
        }
    }

    // ================================================================== (a) arm + source

    @Test public void noArmOrDisarmApiAndNoWebSourceRemain() throws IOException {
        assertFalse(Files.exists(SEND.resolve("WebViewThreadEventSource.java")));
        for (Path p : java(MAIN)) {
            String s = code(p);
            for (String gone : new String[] {"armPostSendRead", "disarmPostSendRead", "takePostSendRead",
                    "PostSendRead", "POST_SEND_ARM_MS", "WebViewThreadEventSource"}) {
                assertFalse(p.getFileName() + " still has " + gone, s.contains(gone));
            }
        }
        for (Path p : java(SEND)) {
            assertFalse(p.getFileName() + ": the send package registers no engine source",
                    code(p).contains("registerSource("));
        }
        assertFalse(code(MAIN.resolve("Voice2SmsApplication.java")).contains("registerSource("));
    }

    @Test public void theControllerIsTheOnlySendPathProducerOfSentVerified() throws IOException {
        for (Path p : java(SEND)) {
            String s = code(p);
            if (p.equals(CONTROLLER)) continue;
            assertFalse(p.getFileName() + " reports SENT_VERIFIED", s.contains("SendOutcome.SENT_VERIFIED"));
        }
        String ctrl = code(CONTROLLER);
        // Every SENT_VERIFIED report follows the settle gate in onThreadRead.
        String read = SendPathSourceTest.methodBody(ctrl, "onThreadRead");
        int settle = read.indexOf("job.watch.settle(");
        int first = read.indexOf("SendOutcome.SENT_VERIFIED");
        assertTrue(settle >= 0 && first > settle);
        int outside = ctrl.replace(read, "").indexOf("SendOutcome.SENT_VERIFIED");
        assertEquals("SENT_VERIFIED is reported only from onThreadRead", -1, outside);
    }

    // ================================================================== (c) compose_timeout

    @Test public void composeTimeoutGetsTheGenericWording() {
        assertNull(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "compose_timeout"));
        assertEquals(RemindCause.LOCKED,
                RemindCause.fromDetail(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "compose_timeout")));
        assertEquals("auth still offers Sign in", RemindCause.NO_SESSION,
                RemindCause.fromDetail(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "auth")));
    }

    // ================================================================== (d) launched dark

    @Test public void aJobLaunchedDarkFinishesAtItsEnd() {
        assertTrue("dark launch, keyguard not locked yet: finish", LockCoverPolicy.finishNow(Event.JOB_ENDED, false, true));
        assertTrue(LockCoverPolicy.finishNow(Event.TIMEOUT, false, true));
        assertFalse("lit launch, unlocked: uncover and stay", LockCoverPolicy.finishNow(Event.JOB_ENDED, false, false));
        assertFalse(LockCoverPolicy.finishNow(Event.TIMEOUT, false, false));
        assertTrue(LockCoverPolicy.finishNow(Event.JOB_ENDED, true, false));
        assertTrue(LockCoverPolicy.finishNow(Event.PAUSED, false, false));
        assertTrue(LockCoverPolicy.finishNow(Event.STOPPED, false, true));
        assertFalse(LockCoverPolicy.finishNow(null, true, true));
    }

    @Test public void theControllerRecordsADarkLaunchBeforeWakingTheScreen() throws IOException {
        String ctrl = code(CONTROLLER);
        String handle = SendPathSourceTest.methodBody(ctrl, "handle");
        int note = handle.indexOf("noteLaunchScreen()");
        assertTrue("recorded before the lock flags turn the screen on",
                note >= 0 && note < handle.indexOf("applyLockFlags(LockCoverPolicy.applyLockFlags("));
        String claimed = SendPathSourceTest.methodBody(ctrl, "onClaimed");
        assertTrue(claimed.indexOf("noteLaunchScreen()") >= 0
                && claimed.indexOf("noteLaunchScreen()") < claimed.indexOf("applyLockFlags("));
        assertTrue(SendPathSourceTest.methodBody(ctrl, "onJobEnded")
                .contains("finishNow(LockCoverPolicy.Event.JOB_ENDED, refreshLockState(), launchedDark)"));
        assertTrue(SendPathSourceTest.methodBody(ctrl, "onHardTimeout")
                .contains("finishNow(LockCoverPolicy.Event.TIMEOUT, refreshLockState(), launchedDark)"));
        assertTrue(SendPathSourceTest.methodBody(ctrl, "noteLaunchScreen").contains("isScreenDark()"));
    }
}
