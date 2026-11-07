package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.send.RegularSend.Event;
import com.voice2sms.sendlater.send.RegularSend.LaunchWait;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * deliver-correctness M1: a due scheduled job never takes over the WebView while a regular
 * compose / auto-send (Gemini, Wear, RESPOND_VIA_MESSAGE) runs in it. The regular send is marked
 * from its intent / injectComposer until the script clicks Send (plus a grace), gives up, or
 * {@link RegularSend#MAX_MS}; the launch waits (bounded) and then takes the user-busy path; a
 * claimed launch is refused the same way.
 */
public class AutoSendCollisionTest {

    private static final long T0 = 1_000_000L;

    @Before public void setUp() {
        RegularSend.reset();
    }

    @After public void tearDown() {
        RegularSend.reset();
    }

    // ================================================================== the mark

    @Test public void nothingIsRunningBeforeABegin() {
        assertFalse(RegularSend.isRunning(T0));
    }

    @Test public void anAutoSendHoldsUntilSendIsClickedPlusTheGrace() {
        RegularSend.begin(T0, true);
        assertTrue(RegularSend.isRunning(T0));
        assertTrue(RegularSend.isRunning(T0 + 10_000L));
        // Filling the body does not end an auto-send: Send is still to come.
        RegularSend.onConsole("[Voice2SMS] Found message textarea, filling body", T0 + 10_000L);
        assertTrue(RegularSend.isRunning(T0 + 10_001L));
        RegularSend.onConsole("[Voice2SMS] Clicking send", T0 + 12_000L);
        assertTrue("GV still posts the message", RegularSend.isRunning(T0 + 12_000L + RegularSend.AFTER_CLICK_GRACE_MS - 1));
        assertFalse(RegularSend.isRunning(T0 + 12_000L + RegularSend.AFTER_CLICK_GRACE_MS));
    }

    @Test public void givingUpEndsTheMarkAtOnce() {
        String[] lines = {
                "[Voice2SMS] Could not find send button",
                "[Voice2SMS] Gave up waiting for message textarea",
                "[Voice2SMS] Gave up waiting for recipient field",
                "[Voice2SMS] Page did not become ready in time",
        };
        for (String line : lines) {
            RegularSend.begin(T0, true);
            RegularSend.onConsole(line, T0 + 3_000L);
            assertFalse(line, RegularSend.isRunning(T0 + 3_000L));
        }
    }

    @Test public void aReviewComposeEndsOnceTheBodyIsFilled() {
        RegularSend.begin(T0, false);
        RegularSend.onConsole("[Voice2SMS] Found message textarea, filling body", T0 + 4_000L);
        assertFalse("the user sends by hand; ForegroundUse covers the box from here",
                RegularSend.isRunning(T0 + 4_000L));
        RegularSend.begin(T0, true);
        RegularSend.onConsole("[Voice2SMS] No body to fill, focusing textarea for keyboard", T0 + 4_000L);
        assertFalse("an empty body never reaches Send", RegularSend.isRunning(T0 + 4_000L));
    }

    @Test public void theMarkIsBoundedEvenWithoutAnyReport() {
        RegularSend.begin(T0, true);
        assertTrue(RegularSend.isRunning(T0 + RegularSend.MAX_MS - 1));
        assertFalse(RegularSend.isRunning(T0 + RegularSend.MAX_MS));
        assertTrue("bounded at 60 s", RegularSend.MAX_MS <= 60_000L);
        // A clock that went backwards says nothing.
        assertFalse(RegularSend.isRunning(T0 - 1));
    }

    @Test public void aSecondInjectionRestartsTheMark() {
        RegularSend.begin(T0, true);
        RegularSend.onConsole("[Voice2SMS] Clicking send", T0 + 1_000L);
        RegularSend.begin(T0 + 30_000L, true);
        assertTrue(RegularSend.isRunning(T0 + 30_000L + 20_000L));
    }

    @Test public void otherLinesAndStaleReportsChangeNothing() {
        RegularSend.begin(T0, true);
        RegularSend.onConsole("[Voice2SMS] Clicking: Send to someone", T0 + 1_000L);
        RegularSend.onConsole("[Voice2SMS] Starting compose for: +15555550100", T0 + 1_000L);
        RegularSend.onConsole("Clicking send", T0 + 1_000L);
        RegularSend.onConsole(null, T0 + 1_000L);
        assertTrue(RegularSend.isRunning(T0 + 2_000L));
        // Without a begin a report never starts a mark.
        RegularSend.reset();
        RegularSend.onConsole("[Voice2SMS] Clicking send", T0);
        assertFalse(RegularSend.isRunning(T0));
    }

    @Test public void theFirstEndWins() {
        RegularSend.begin(T0, true);
        RegularSend.onConsole("[Voice2SMS] Could not find send button", T0 + 1_000L);
        RegularSend.onConsole("[Voice2SMS] Clicking send", T0 + 1_500L);
        assertFalse(RegularSend.isRunning(T0 + 1_600L));
    }

    @Test public void aRendererCrashClearsTheMark() {
        RegularSend.begin(T0, true);
        RegularSend.clear();
        assertFalse(RegularSend.isRunning(T0 + 1L));
    }

    @Test public void eventMapping() {
        assertEquals(Event.SENT, RegularSend.eventOf("[Voice2SMS] Clicking send", true));
        assertEquals(Event.SENT, RegularSend.eventOf("[Voice2SMS] Clicking send", false));
        assertEquals(Event.NONE, RegularSend.eventOf("[Voice2SMS] Found message textarea, filling body", true));
        assertEquals(Event.FILLED, RegularSend.eventOf("[Voice2SMS] Found message textarea, filling body", false));
        assertEquals(Event.GAVE_UP, RegularSend.eventOf("[Voice2SMS] Page did not become ready in time", true));
        assertEquals(Event.NONE, RegularSend.eventOf("[Voice2SMS] Send button disabled, retrying...", true));
    }

    // ================================================================== the launch wait

    @Test public void theLaunchProceedsWaitsThenTakesTheUserBusyPath() {
        assertEquals(LaunchWait.PROCEED, RegularSend.launchWait(false, 0L));
        assertEquals(LaunchWait.PROCEED, RegularSend.launchWait(false, 25_000L));
        assertEquals(LaunchWait.WAIT, RegularSend.launchWait(true, 0L));
        assertEquals(LaunchWait.WAIT, RegularSend.launchWait(true, RegularSend.MAX_LAUNCH_WAIT_MS - 1));
        assertEquals(LaunchWait.BUSY, RegularSend.launchWait(true, RegularSend.MAX_LAUNCH_WAIT_MS));
    }

    @Test public void theWaitEndsBeforeTheLaunchWatchdog() {
        assertTrue(RegularSend.MAX_LAUNCH_WAIT_MS + RegularSend.LAUNCH_WAIT_POLL_MS
                < WebViewScheduledSender.CLAIM_TIMEOUT_MS);
        assertTrue(RegularSend.LAUNCH_WAIT_POLL_MS > 0);
    }

    // ================================================================== wiring (source)

    @Test public void injectComposerMarksTheRegularSendBeforeTheScriptRuns() throws IOException {
        String inject = block(code(ACTIVITY), "private void injectComposer\\(\\)\\s*\\{");
        int limiter = inject.indexOf("AutoSendRateLimiter.allow()");
        int begin = inject.indexOf("RegularSend.begin(");
        int eval = inject.indexOf("evaluateJavascript(");
        assertTrue("injectComposer must mark the regular send", begin >= 0);
        assertTrue("after the rate limiter decided autoSend", begin > limiter);
        assertTrue("before the script is injected", begin < eval);
        assertTrue(inject.contains("RegularSend.begin(System.currentTimeMillis(), autoSend)"));
    }

    @Test public void theActivityMarksRegularIntentsAndReadsTheScriptsProgress() throws IOException {
        String a = code(ACTIVITY);
        String newIntent = block(a, "protected void onNewIntent\\(Intent intent\\)\\s*\\{");
        assertTrue("a regular intent is marked when it arrives",
                newIntent.contains("noteRegularCompose()"));
        assertTrue("only when no scheduled job owns the page",
                newIntent.contains("!scheduledSend.isActive()"));
        assertTrue("onCreate marks a regular compose launch",
                a.contains("if (!scheduledLaunch && recipient != null) noteRegularCompose();"));
        assertTrue("console progress ends the mark",
                a.contains("RegularSend.onConsole(cm.message(), System.currentTimeMillis());"));
        String gone = block(a, "public boolean onRenderProcessGone\\(");
        assertTrue("the renderer took the script with it", gone.contains("RegularSend.clear();"));
    }

    @Test public void theLaunchWaitsForARegularSendAndNeverConsumesTheTapGrantWhileWaiting() throws IOException {
        String s = code(SENDER);
        String launch = block(s, "private static void launch\\(final Context app, final long id, final String token, "
                + "final long checkTime,\\s*final long waitStart\\)\\s*\\{");
        int offer = launch.indexOf("ScheduledSendController.offer(");
        int wait = launch.indexOf("RegularSend.launchWait(");
        assertTrue(launch.contains("RegularSend.isRunning(System.currentTimeMillis())"));
        int plan = launch.indexOf("planFor(");
        assertTrue(offer >= 0 && wait > offer && plan > wait);
        assertTrue("WAIT re-posts the same launch",
                launch.contains("MAIN.postDelayed(() -> launch(app, id, token, checkTime, waitStart)"));
        int busyAt = launch.indexOf("regular == RegularSend.LaunchWait.BUSY");
        assertTrue(busyAt > wait);
        String busy = launch.substring(busyAt, plan);
        assertTrue("BUSY is the engine's user-busy path", busy.contains("ForegroundUse.DETAIL_USER_BUSY"));
        assertTrue(busy.contains("LaunchClaims.INSTANCE.take(id, token)"));
        assertTrue("a waiting launch is not decided yet",
                launch.contains("if (!waiting) UserTapGrant.consume("));
    }

    @Test public void aClaimedLaunchIsRefusedWhileARegularSendRuns() throws IOException {
        String handle = block(code(CONTROLLER), "public boolean handle\\(Intent intent, boolean restored\\)\\s*\\{");
        int claim = handle.indexOf("LaunchClaims.INSTANCE.take(id, token)");
        int regular = handle.indexOf("RegularSend.isRunning(");
        int enqueue = handle.indexOf("enqueue(new Job(");
        int flags = handle.indexOf("claimed = true;");
        assertTrue(claim >= 0 && regular > claim);
        assertTrue("before any lock flag, cover or job", regular < flags && regular < enqueue);
        String refusal = handle.substring(regular, flags);
        assertTrue(refusal.contains("ForegroundUse.DETAIL_USER_BUSY"));
        assertTrue(refusal.contains("SendOutcome.CANNOT_SHOW"));
        assertTrue("through the controller's one outcome path", refusal.contains("toEngine(new Job("));
        assertTrue(refusal.contains("return true;"));
        assertFalse("never finishes the activity the regular send runs in",
                refusal.contains("endJob(") || refusal.contains("onUnclaimed(") || refusal.contains(".finish("));
        assertFalse("no lock flags for a refused launch", refusal.contains("applyLockFlags("));
    }

    @Test public void theConsoleLinesTheMarkReadsAreStillPrintedByVoice2sms() throws IOException {
        String js = code(INJECT);
        String v = js.substring(js.indexOf("function voice2sms("), js.indexOf("function _v2sScheduledHolds("));
        String[] lines = {
                RegularSend.CLICKING_SEND, RegularSend.NO_SEND_BUTTON, RegularSend.GAVE_UP,
                RegularSend.PAGE_NOT_READY, RegularSend.FILLING_BODY, RegularSend.NO_BODY};
        for (String line : lines) {
            assertTrue("voice2sms() no longer logs: " + line, v.contains("console.log('" + line));
        }
    }

    // ================================================================== helpers

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path CONTROLLER = MAIN.resolve("sendlater/send/ScheduledSendController.java");
    private static final Path SENDER = MAIN.resolve("sendlater/send/WebViewScheduledSender.java");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path INJECT = Paths.get("src/main/assets/inject.js");

    private static String code(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String block(String src, String sig) {
        Matcher m = Pattern.compile(sig).matcher(src);
        assertTrue("not found: " + sig, m.find());
        int open = src.indexOf('{', m.end() - 1);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced " + sig);
    }
}
