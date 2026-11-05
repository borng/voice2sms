package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.send.LockCoverPolicy.JobState;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * live-send-path (LIVE-F6, LIVE-F7, LIVE-F8; R30, R31, OD27, OD28, OD31 PQ3): the scheduled
 * composer is injected again after a cold WebView's later main-frame loads; "typed" means the page
 * confirmed our body is in the message box; the Send tap is gated by Java (at most once per job,
 * never after Stop or a failure); Stop stays on the cover until typed. Paths relative to app/.
 */
public class LiveSendPathTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path SEND = MAIN.resolve("sendlater/send");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path INJECT = Paths.get("src/main/assets/inject.js");

    private static final String LIST = "https://voice.google.com/u/0/messages";
    private static final String THREAD = "https://voice.google.com/u/0/messages?itemId=t.%2B15550000000";

    // ================================================================== re-inject decision (LIVE-F6)

    @Test public void aLaterMessagesListLoadReinjectsWhileNoClickWasGranted() {
        assertTrue(ScheduledCompose.shouldReinject(LIST, 1, false, false));
        assertTrue(ScheduledCompose.shouldReinject(LIST + "?pli=1", 3, false, false));
    }

    @Test public void neverReinjectsAfterAGrantedClickOrAClosedGate() {
        assertFalse("no double send", ScheduledCompose.shouldReinject(LIST, 1, true, false));
        assertFalse("Stop / failure / outcome", ScheduledCompose.shouldReinject(LIST, 1, false, true));
    }

    @Test public void reinjectNeedsAFirstInjectionAndStaysBounded() {
        assertFalse("the first injection is the normal dispatch", ScheduledCompose.shouldReinject(LIST, 0, false, false));
        assertTrue(ScheduledCompose.shouldReinject(LIST, ScheduledCompose.MAX_INJECTIONS - 1, false, false));
        assertFalse(ScheduledCompose.shouldReinject(LIST, ScheduledCompose.MAX_INJECTIONS, false, false));
    }

    @Test public void reinjectOnlyOnGvNonThreadPages() {
        assertFalse(ScheduledCompose.shouldReinject(null, 1, false, false));
        assertFalse(ScheduledCompose.shouldReinject("https://accounts.google.com/signin", 1, false, false));
        assertFalse("a voice.google.com look-alike host", ScheduledCompose.shouldReinject(
                "https://voice.google.com.example.org/u/0/messages", 1, false, false));
        assertFalse("our own run opened the thread", ScheduledCompose.shouldReinject(THREAD, 1, false, false));
    }

    @Test public void eachInjectionHasAFreshTokenAndStartsUntyped() {
        ScheduledCompose c = new ScheduledCompose(7L);
        String t1 = c.nextInjection();
        assertNotNull(t1);
        assertTrue(c.onTyped(t1));
        assertTrue(c.isTyped());
        String t2 = c.nextInjection();
        assertNotNull(t2);
        assertNotEquals(t1, t2);
        assertFalse("the wiped document's text is gone", c.isTyped());
        assertFalse("an answer from the older run is ignored", c.onTyped(t1));
        assertEquals(2, c.injections());
    }

    @Test public void injectionsStopAtTheBound() {
        ScheduledCompose c = new ScheduledCompose(1L);
        for (int i = 0; i < ScheduledCompose.MAX_INJECTIONS; i++) assertNotNull(c.nextInjection());
        assertFalse(c.mayInject());
        assertNull(c.nextInjection());
    }

    // ================================================================== click gate

    @Test public void sendIsGrantedOnceForTheCurrentTokenOnly() {
        ScheduledCompose c = new ScheduledCompose(2L);
        String t1 = c.nextInjection();
        String t2 = c.nextInjection();
        assertFalse("stale token", c.grantClick(t1));
        assertFalse(c.grantClick(null));
        assertTrue(c.grantClick(t2));
        assertFalse("never twice", c.grantClick(t2));
        assertTrue("a granted click counts as typed", c.isTyped());
        assertFalse("no injection after a click", c.mayInject());
        assertNull(c.nextInjection());
    }

    @Test public void aClosedGateGrantsNothing() {
        ScheduledCompose c = new ScheduledCompose(3L);
        String t = c.nextInjection();
        assertTrue("nothing was granted: nothing can be sent now", c.close());
        assertFalse(c.grantClick(t));
        assertFalse(c.onTyped(t));
        assertFalse(c.mayInject());
    }

    @Test public void closeAfterAGrantSaysTooLate() {
        ScheduledCompose c = new ScheduledCompose(4L);
        String t = c.nextInjection();
        assertTrue(c.grantClick(t));
        assertFalse(c.close());
    }

    // ================================================================== typed-flag mapping (LIVE-F8)

    @Test public void failureBeforeTheBodyIsConfirmedIsCannotShow() {
        assertEquals(SendOutcome.CANNOT_SHOW, ScheduledCompose.outcomeOnFailure(false, false));
        ScheduledCompose c = new ScheduledCompose(5L);
        c.nextInjection();   // injected, but the page never confirmed the body
        assertEquals(SendOutcome.CANNOT_SHOW, c.closeForFailure());
        assertTrue(c.isClosed());
    }

    @Test public void failureAfterTheBodyIsConfirmedIsSentUnverified() {
        assertEquals(SendOutcome.SENT_UNVERIFIED, ScheduledCompose.outcomeOnFailure(true, false));
        assertEquals(SendOutcome.SENT_UNVERIFIED, ScheduledCompose.outcomeOnFailure(false, true));
        ScheduledCompose c = new ScheduledCompose(6L);
        assertTrue(c.onTyped(c.nextInjection()));
        assertEquals(SendOutcome.SENT_UNVERIFIED, c.closeForFailure());
    }

    @Test public void reinjectedAndNotRetypedIsCannotShowAgain() {
        ScheduledCompose c = new ScheduledCompose(8L);
        assertTrue(c.onTyped(c.nextInjection()));
        c.nextInjection();   // a reload wiped the text before any click
        assertEquals(SendOutcome.CANNOT_SHOW, c.closeForFailure());
    }

    // ================================================================== Stop visibility (LIVE-F7)

    /** The controller's stopAllowed with typingStarted = the confirmed body (job.injected). */
    private static boolean stop(boolean locked, ScheduledCompose c) {
        return LockCoverPolicy.showStop(true, JobState.RUNNING, true, locked, true, c.isTyped());
    }

    @Test public void stopStaysThroughInjectionsUntilTheBodyIsConfirmed() {
        ScheduledCompose c = new ScheduledCompose(9L);
        assertTrue("row loading", stop(false, c));
        String t1 = c.nextInjection();
        assertTrue("injected is not typed (LIVE-F7: Stop went away at injection)", stop(false, c));
        c.nextInjection();
        assertTrue("re-injected after a cold reload", stop(false, c));
        assertFalse(c.onTyped(t1));
        assertTrue(stop(false, c));
        assertFalse("locked: never", stop(true, c));
    }

    @Test public void stopGoesOnceTyped() {
        ScheduledCompose c = new ScheduledCompose(10L);
        assertTrue(c.onTyped(c.nextInjection()));
        assertFalse(stop(false, c));
    }

    // ================================================================== wiring (source)

    private static String code(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*//[^\\n]*", " ");
    }

    private static String block(String src, String sig) {
        Matcher m = Pattern.compile(sig).matcher(src);
        assertTrue("not found: " + sig, m.find());
        int open = src.indexOf('{', m.end() - 1);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced " + sig);
    }

    @Test public void controllerTypedMeansConfirmedAndStopClosesTheGate() throws IOException {
        String c = code(SEND.resolve("ScheduledSendController.java"));
        String typed = block(c, "public void onComposerInjected\\s*\\(\\s*String token\\s*\\)\\s*\\{");
        assertTrue("typed only when the page confirmed this injection",
                typed.indexOf("job.compose.onTyped(token)") >= 0
                        && typed.indexOf("job.compose.onTyped(token)") < typed.indexOf("job.injected = true"));
        String inject = block(c, "public String onComposeInjection\\s*\\([^)]*\\)\\s*\\{");
        assertTrue("an injection is not typing", inject.contains("job.injected = false"));
        assertTrue(inject.contains("updateStop()"));
        String stop = block(c, "void onStopTapped\\s*\\(\\s*\\)\\s*\\{");
        assertTrue("Stop shuts the Send gate before ending the job",
                stop.indexOf("closeForStop()") >= 0 && stop.indexOf("closeForStop()") < stop.indexOf("abandonAll("));
        assertTrue("every failure closes the gate", block(c, "SendOutcome failureOutcome\\s*\\(\\s*Job job\\s*\\)\\s*\\{")
                .contains("closeForFailure()"));
        assertTrue("the outcome closes the gate", block(c, "private void toEngine\\s*\\([^)]*\\)\\s*\\{")
                .contains("closeCompose(job)"));
    }

    @Test public void activityReinjectsBeforeTheDispatchAndInjectsTheGatedEntry() throws IOException {
        String a = code(ACTIVITY);
        String finished = block(a, "public void onPageFinished\\s*\\([^)]*\\)\\s*\\{");
        int reinject = finished.indexOf("scheduledSend.wantsReinject(url)");
        int dispatch = finished.indexOf("injectScheduledComposer()");
        assertTrue("re-inject check precedes the dispatch", reinject >= 0 && reinject < dispatch);
        String inject = block(a, "private void injectScheduledComposer\\s*\\(\\s*\\)\\s*\\{");
        assertTrue(inject.contains("scheduledSend.onComposeInjection("));
        assertTrue(inject.contains("v2sScheduledCompose("));
        assertFalse("the scheduled path never runs voice2sms' own auto-send",
                Pattern.compile("voice2sms\\(").matcher(inject).find());
        for (String m : new String[]{"onScheduledTyped", "scheduledMayClick"}) {
            String b = block(a, "@JavascriptInterface\\s+public\\s+\\w+\\s+" + m + "\\s*\\([^)]*\\)\\s*\\{");
            int guard = b.indexOf("OriginGuard.isTrustedGvUrl(mainFrameUrl)");
            assertTrue(m + " checks the origin first", guard >= 0);
            int act = Math.max(b.indexOf("runOnUiThread"), b.indexOf("ScheduledSendController.mayClick"));
            assertTrue(m + " origin before acting", act > guard);
        }
    }

    @Test public void injectJsScheduledEntryTypesWithoutAutoSendAndClicksOnlyWhenGranted() throws IOException {
        String js = new String(Files.readAllBytes(INJECT), StandardCharsets.UTF_8);
        String entry = block(js, "(?m)^function v2sScheduledCompose\\s*\\(phone, body, autoSendDelay, token\\)\\s*\\{");
        assertTrue("runs the unchanged compose flow without its auto-send",
                entry.contains("voice2sms(phone, body, false, 0)"));
        assertTrue("reports typed only after checking the box", entry.contains("V2SBridge.onScheduledTyped(token)")
                && entry.indexOf("holdsBody(") < entry.indexOf("V2SBridge.onScheduledTyped(token)"));
        int click = entry.indexOf("sendBtn.click()");
        int gate = entry.indexOf("V2SBridge.scheduledMayClick(token)");
        assertTrue("one click, after the Java gate", click > 0 && gate > 0 && gate < click
                && entry.indexOf(".click()") == click + "sendBtn".length()
                && entry.lastIndexOf(".click()") == click + "sendBtn".length());
        assertTrue("cancelled by a newer run", entry.contains("window._v2sGeneration !== myGeneration"));
        assertFalse("never logs the text", Pattern.compile("console\\.log\\([^;]*\\b(body|phone)\\b").matcher(entry).find());
    }

    @Test public void stopPopupRetriesUntilTheWindowCanTakeIt() throws IOException {
        String l = code(SEND.resolve("LockCover.java"));
        String retry = block(l, "private void tryShowStop\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(retry.contains("postDelayed("));
        assertTrue(retry.contains("STOP_MAX_ATTEMPTS"));
        assertTrue(l.contains("anchor.post(() -> tryShowStop(anchor))"));
    }
}
