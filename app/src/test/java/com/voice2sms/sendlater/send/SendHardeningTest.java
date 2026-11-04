package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEvent.Direction;
import com.voice2sms.sendlater.engine.RemindCause;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * send-hardening (OD26): R1 armed follow-up read, R3 renderer-crash relaunch, R4 no id shortcut,
 * R5 composer busy, the engine detail contract, and the cover-on-every-job wiring. The pure
 * lock-cover policy is in {@link LockCoverPolicyTest}.
 */
public class SendHardeningTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long CHECK = NOW - 20_000L;
    /** The engine's watermark for the in-flight message (its thread check). */
    private static final long WATERMARK = CHECK;
    private static final String BODY = "Running ten minutes late, save me a seat please";
    private static final String HASH = ThreadEvent.hashOf(BODY);
    private static final String E164 = "+15550100000";
    private static final Path SEND = Paths.get("src/main/java/com/voice2sms/sendlater/send");
    private static final Path CONTROLLER = SEND.resolve("ScheduledSendController.java");
    private static final Path ACTIVITY = Paths.get("src/main/java/com/voice2sms/GVoiceWebViewActivity.java");

    @After public void reset() {
        WebViewThreadEventSource.disarmPostSendRead();
        ForegroundUse.reset();
    }

    private static ThreadBubble out(Long t, String text, String id) { return new ThreadBubble(Direction.OUT, t, text, id); }
    private static ThreadBubble out(Long t, String text) { return out(t, text, null); }
    private static ThreadBubble in(Long t, String text) { return new ThreadBubble(Direction.IN, t, text, null); }
    private static List<ThreadBubble> list(ThreadBubble... b) { return new ArrayList<>(Arrays.asList(b)); }

    private static String code(Path p) throws IOException {
        return SendPathSourceTest.stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    private static String body(String src, String name) {
        String b = SendPathSourceTest.methodBody(src, name);
        assertNotNull(name + " missing", b);
        return b;
    }

    /** What the engine's ownBubbleSeen could use: an own outbound at or after the watermark. */
    private static boolean ownOutboundAfterWatermark(List<ThreadEvent> events) {
        for (ThreadEvent e : events) {
            if (e.getDirection() == Direction.OUT && HASH.equals(e.getTextHash()) && e.getTimeMs() >= WATERMARK) {
                return true;
            }
        }
        return false;
    }

    /** The engine's read through the source, after the controller armed it at NOW. */
    private static List<ThreadEvent> engineRead(List<ThreadBubble> page) {
        WebViewThreadEventSource.PostSendRead armed = WebViewThreadEventSource.takePostSendRead(E164, NOW + 500L);
        assertNotNull("the controller armed the engine's read", armed);
        return WebViewThreadEventSource.engineEvents(page, armed, 0L, NOW + 500L);
    }

    // ================================================================== (2) R1 armed follow-up read

    @Test public void r1NoBaselineAnOldIdenticalUntimedBubbleCannotVerifyThroughTheEngine() {
        List<ThreadBubble> page = list(out(NOW - 86_400_000L, "earlier"), in(null, "ok"), out(null, BODY));
        // Before: toEvents dated the old untimed copy "now", past the watermark.
        assertTrue(ownOutboundAfterWatermark(WebViewThreadEventSource.engineEvents(page, null, 0L, NOW)));
        // SENT_UNVERIFIED with no baseline is still armed, and armed means all old.
        WebViewThreadEventSource.armPostSendRead(E164, null, false, NOW);
        assertFalse(ownOutboundAfterWatermark(engineRead(page)));
    }

    @Test public void r1AnUnsettledNewBubbleBeyondTheBaselineCannotVerifyThroughTheEngine() {
        List<ThreadBubble> before = list(in(NOW - 3_600_000L, "see you"));
        ThreadBaseline baseline = new ThreadBaseline(before);
        List<ThreadBubble> page = list(in(NOW - 3_600_000L, "see you"), out(null, BODY));
        WebViewThreadEventSource.armPostSendRead(E164, baseline, false, NOW);
        assertFalse("never settled: the optimistic bubble is dated old", ownOutboundAfterWatermark(engineRead(page)));
    }

    @Test public void r1AnyNonVerifiedArmDatesEveryBubbleOld() {
        List<ThreadBubble> page = list(in(NOW - 3_600_000L, "see you"), in(null, "wait"), out(null, BODY));
        WebViewThreadEventSource.armPostSendRead(E164, new ThreadBaseline(list(in(NOW - 3_600_000L, "see you"))),
                false, NOW);
        List<ThreadEvent> got = engineRead(page);
        List<ThreadEvent> allOld = ThreadBaseline.stamp(page, -1, NOW + 500L);
        assertEquals(allOld.size(), got.size());
        for (int i = 0; i < got.size(); i++) assertEquals(allOld.get(i).getTimeMs(), got.get(i).getTimeMs());
    }

    @Test public void r1OnlyASettledVerifiedSendDatesNewBubblesAgainstTheBaseline() {
        List<ThreadBubble> before = list(in(NOW - 3_600_000L, "see you"));
        List<ThreadBubble> page = list(in(NOW - 3_600_000L, "see you"), in(null, "wait"), out(null, BODY));
        WebViewThreadEventSource.armPostSendRead(E164, new ThreadBaseline(before), true, NOW);
        List<ThreadEvent> got = engineRead(page);
        assertEquals("a reply that landed during the send stays new (late reply)", NOW + 500L, got.get(1).getTimeMs());
        assertEquals("the old reply stays old", NOW - 3_600_000L, got.get(0).getTimeMs());

        WebViewThreadEventSource.armPostSendRead(E164, null, true, NOW);
        assertTrue("verified but no baseline: still all old, never toEvents",
                WebViewThreadEventSource.takePostSendRead(E164, NOW).allOld());
    }

    @Test public void r1ATimedOwnBubbleKeepsItsServerTime() {
        // A server time is real proof; dating "old" never rewrites it.
        List<ThreadBubble> page = list(out(NOW - 2_000L, BODY));
        WebViewThreadEventSource.armPostSendRead(E164, null, false, NOW);
        assertEquals(NOW - 2_000L, engineRead(page).get(0).getTimeMs());
    }

    @Test public void r1TheControllerArmsEveryOutcomeTheEngineAnswersWithARead() throws IOException {
        String ctrl = code(CONTROLLER);
        Matcher m = Pattern.compile("SendLaterEngine\\.onSendOutcome\\(").matcher(ctrl);
        int n = 0;
        while (m.find()) n++;
        assertEquals("every controller outcome goes through toEngine", 1, n);
        String to = body(ctrl, "toEngine");
        int arm = to.indexOf("armPostSendRead(");
        assertTrue(arm >= 0 && arm < to.indexOf("SendLaterEngine.onSendOutcome("));
        assertTrue("armed whatever the baseline", to.contains("job.watch.baseline() : null"));
        assertTrue("only SENT_VERIFIED dates against the baseline", to.contains("outcome == SendOutcome.SENT_VERIFIED"));
        assertTrue("CANNOT_SHOW has no engine read", to.contains("outcome != SendOutcome.CANNOT_SHOW"));
        assertTrue(body(ctrl, "onHardTimeout").contains("toEngine("));
        assertTrue(body(ctrl, "abandonAll").contains("toEngine("));
        assertTrue(body(ctrl, "report").contains("toEngine("));
        String src = code(SEND.resolve("WebViewThreadEventSource.java"));
        String read = body(src, "readSince");
        assertFalse("readSince never stamps an armed read with toEvents itself", read.contains("toEvents("));
        assertTrue(read.contains("engineEvents("));
        assertTrue(body(src, "engineEvents").contains("armed != null ? armed.stamp("));
    }

    // ================================================================== (3) R4 no id shortcut

    @Test public void r4AnIdAttributeAloneNeverVerifies() {
        List<ThreadBubble> base = list(in(NOW - 3_600_000L, "see you"));
        List<ThreadBubble> page = list(in(NOW - 3_600_000L, "see you"), out(null, BODY, "m-42"));
        SendWatch w = new SendWatch(HASH);
        assertNull(w.onPoll(base, true, NOW - 5_000L));
        for (long t = 0; t < SendWatch.SETTLE_MS; t += 500L) {
            List<ThreadEvent> ev = w.onPoll(page, false, NOW + t);
            assertNull("an id is no server confirmation", w.settle(SendVerifier.verify(HASH, ev, CHECK, NOW + t), false, NOW + t));
        }
        SendWatch pending = new SendWatch(HASH);
        pending.onPoll(base, true, NOW - 5_000L);
        for (long t = 0; t <= 10_000L; t += 1_000L) {
            List<ThreadEvent> ev = pending.onPoll(page, true, NOW + t);
            assertNull("text still in the box: never", pending.settle(SendVerifier.verify(HASH, ev, CHECK, NOW + t), true, NOW + t));
        }
    }

    @Test public void r4SendWatchNoLongerReadsTheBubbleId() throws IOException {
        assertFalse(code(SEND.resolve("SendWatch.java")).contains("getId()"));
    }

    // ================================================================== (4) R3 crash relaunch

    @Test public void r3AnUnclaimedLaunchClearsTheFlagsAndFinishesWithoutLoadingWhileLocked() throws IOException {
        String ctrl = code(CONTROLLER);
        String handle = body(ctrl, "handle");
        int take = handle.indexOf("LaunchClaims.INSTANCE.take(");
        int unclaimed = handle.indexOf("onUnclaimed()");
        assertTrue(take >= 0 && unclaimed > take);
        assertTrue("the keyguard is re-read before deciding", handle.indexOf("refreshLockState()") < take);
        String u = body(ctrl, "onUnclaimed");
        int clear = u.indexOf("applyLockFlags(false)");
        int locked = u.indexOf("lockedNow");
        int fin = u.indexOf("endJob(true)");
        assertTrue("flags cleared first, then finish while locked", clear >= 0 && locked > clear && fin > locked);
        assertTrue(u.contains("skipPageLoad = true"));

        String act = code(ACTIVITY);
        String onCreate = body(act, "onCreate");
        int skip = onCreate.indexOf("skipsPageLoad()");
        assertTrue("onCreate checks the skip before any load", skip >= 0 && skip < onCreate.indexOf("loadGoogleVoice()"));
        assertTrue(skip < onCreate.indexOf("attemptAccountAuth()"));

        String destroy = body(ctrl, "onDestroy");
        int d = destroy.indexOf("applyLockFlags(false)");
        assertTrue("a recreate() never carries the flags over", d >= 0 && d < destroy.indexOf("cover.hide()"));
    }

    // ================================================================== (5) R5 composer busy

    @Test public void r5BusyWhenResumedAndTheComposerHoldsText() {
        long longAgo = NOW - 10 * 60_000L;
        assertTrue("draft typed on the soft keyboard", ForegroundUse.isBusy(true, longAgo, true, NOW - 1_000L, NOW));
        assertTrue("never touched at all", ForegroundUse.isBusy(true, -1L, true, NOW, NOW));
        assertFalse("not resumed", ForegroundUse.isBusy(false, longAgo, true, NOW, NOW));
        assertFalse("empty box", ForegroundUse.isBusy(true, longAgo, false, NOW, NOW));
        assertFalse("stale sample", ForegroundUse.isBusy(true, longAgo, true,
                NOW - ForegroundUse.COMPOSER_SAMPLE_MAX_AGE_MS - 1L, NOW));
        assertFalse("no sample", ForegroundUse.isBusy(true, longAgo, true, -1L, NOW));
        assertTrue("a touch still counts", ForegroundUse.isBusy(true, NOW - 1_000L, false, -1L, NOW));

        ForegroundUse.noteResumed(true);
        ForegroundUse.noteComposer(true, NOW);
        assertTrue(ForegroundUse.isUserBusy(NOW + 1_000L));
        ForegroundUse.noteComposer(false, NOW + 1_000L);
        assertFalse(ForegroundUse.isUserBusy(NOW + 1_000L));
        ForegroundUse.noteComposer(true, NOW);
        ForegroundUse.noteResumed(false);
        assertFalse(ForegroundUse.isUserBusy(NOW));
    }

    @Test public void r5TheActivityProbesTheComposerGuardedAndIgnoresOurOwnText() throws IOException {
        String act = code(ACTIVITY);
        int r = act.indexOf("Runnable composerProbe");
        assertTrue(r >= 0);
        String run = act.substring(r, act.indexOf("};", r));
        int alive = run.indexOf("isActivityAlive()");
        int eval = run.indexOf("webView.evaluateJavascript(");
        assertTrue("the WebView touch is guarded", alive >= 0 && eval > alive);
        assertTrue("the probe answers a boolean only", run.contains("SCHEDULED_COMPOSER_PROBE_JS"));
        assertTrue("a scheduled send's own text is not a draft",
                run.contains("scheduledSend.isActive()") && run.contains("ForegroundUse.noteComposer(false"));
        assertTrue(body(act, "onResume").contains("composerProbeHandler.post(composerProbe)"));
        assertTrue(body(act, "onPause").contains("composerProbeHandler.removeCallbacks(composerProbe)"));
        assertTrue(body(act, "onDestroy").contains("composerProbeHandler.removeCallbacks(composerProbe)"));
    }

    // ================================================================== engine detail contract

    @Test public void cannotShowCausesMapToTheEnginesThreeDetails() {
        assertEquals("no_session", SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "no_session"));
        assertEquals("no_session", SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "auth"));
        assertEquals("no_session", SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "compose_timeout"));
        assertEquals("daily_cap", SendDetails.forEngine(SendOutcome.CANNOT_SHOW, WebViewScheduledSender.DETAIL_DAILY_CAP));
        assertEquals("user_busy", SendDetails.forEngine(SendOutcome.CANNOT_SHOW, ForegroundUse.DETAIL_USER_BUSY));
        for (String other : new String[] {"timeout", "inject_failed", "paused", "launch_timeout", "cannot_show",
                "activity_gone", "other_intent", null}) {
            assertNull(other, SendDetails.forEngine(SendOutcome.CANNOT_SHOW, other));
        }
        assertEquals(RemindCause.NO_SESSION, RemindCause.fromDetail(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "auth")));
        assertEquals(RemindCause.LOCKED, RemindCause.fromDetail(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "timeout")));
        assertEquals("late_reply", SendDetails.forEngine(SendOutcome.SENT_VERIFIED, ScheduledSendController.DETAIL_LATE_REPLY));
        assertEquals("no_match", SendDetails.forEngine(SendOutcome.SENT_UNVERIFIED, "no_match"));
    }

    @Test public void everyOutcomeInTheSendPackageCarriesAMappedDetail() throws IOException {
        Pattern call = Pattern.compile("SendLaterEngine\\.onSendOutcome\\(");
        int total = 0;
        for (Path p : new Path[] {CONTROLLER, SEND.resolve("WebViewScheduledSender.java")}) {
            String src = code(p);
            Matcher m = call.matcher(src);
            while (m.find()) {
                total++;
                String args = src.substring(m.end(), src.indexOf(';', m.end()));
                assertTrue(p.getFileName() + ": detail not mapped: " + args, args.contains("SendDetails.forEngine("));
            }
        }
        assertTrue(total >= 5);
    }

    // ================================================================== (1) cover on every job: wiring

    @Test public void theKeyguardIsReReadOnResumePauseFocusAndScreenBroadcasts() throws IOException {
        String ctrl = code(CONTROLLER);
        for (String m : new String[] {"onResume", "onPauseOrStop", "onWindowFocusChanged", "onJobEnded", "onHardTimeout"}) {
            assertTrue(m, body(ctrl, m).contains("refreshLockState()"));
        }
        String screen = body(ctrl, "listenForScreen");
        assertTrue(screen.contains("ACTION_SCREEN_OFF") && screen.contains("ACTION_USER_PRESENT"));
        assertTrue(screen.contains("refreshLockState()"));
        assertTrue(screen.contains("RECEIVER_NOT_EXPORTED"));
        assertTrue(body(code(ACTIVITY), "onWindowFocusChanged").contains("scheduledSend.onWindowFocusChanged()"));
        String pause = body(ctrl, "onPauseOrStop");
        assertTrue("a pause ends every running job", pause.contains("LockCoverPolicy.endsJob("));
        assertTrue(body(ctrl, "onHardTimeout").contains("LockCoverPolicy.endsJob("));
    }

    @Test public void flagsComeDownBeforeTheCoverEverywhere() throws IOException {
        String ctrl = code(CONTROLLER);
        String end = body(ctrl, "endJob");
        int clear = end.indexOf("applyLockFlags(false)");
        assertTrue(clear >= 0 && clear < end.indexOf("updateCover()"));
        String other = body(ctrl, "onOtherIntent");
        assertTrue(other.indexOf("applyLockFlags(false)") < other.indexOf("cover.hide()"));
        String settle = body(ctrl, "settle");
        assertTrue("every job waits for the engine's read under its cover", settle.contains("listenForEngine()"));
    }
}
