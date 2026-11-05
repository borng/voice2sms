package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.Classification;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.send.ScheduledCompose.ClickState;
import com.voice2sms.sendlater.send.ScheduledCompose.Mode;
import com.voice2sms.sendlater.send.ScheduledCompose.ThreadPage;
import com.voice2sms.sendlater.web.ThreadBubble;
import com.voice2sms.sendlater.web.ThreadUrl;

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
 * live-thread-compose (LIVE-F10; R30, R31, D30, OD26, OD27): the scheduled send composes on the
 * job's own conversation page (full load of its thread URL), the Send ask is tri-state so a
 * pre-send check still pending after typing no longer costs the send, the new-conversation flow
 * is only a fallback (its conversation counts as empty), and a job that ends unsent clears our
 * text from GV's box. Fictional numbers and texts only. Paths relative to app/.
 */
public class LiveThreadComposeTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path CONTROLLER = MAIN.resolve("sendlater/send/ScheduledSendController.java");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path INJECT = Paths.get("src/main/assets/inject.js");

    private static final String E164 = "+15555550123";
    private static final String OTHER = "+15555550199";
    private static final String LIST = "https://voice.google.com/u/0/messages";
    private static final String THREAD = "https://voice.google.com/u/0/messages?itemId=t.%2B15555550123";
    private static final String OTHER_THREAD = "https://voice.google.com/u/0/messages?itemId=t.%2B15555550199";
    private static final String DRAFT = "https://voice.google.com/u/0/messages?itemId=draft";

    private static final long MIN = 60_000L;
    private static final long T0 = 1_781_092_800_000L;

    // ================================================================== tri-state Send gate

    @Test public void waitsWhileThePreSendCheckIsPending() {
        ScheduledCompose c = new ScheduledCompose(1L);
        c.requirePreSendCheck();
        String t = c.nextInjection();
        assertTrue(c.onTyped(t));
        assertEquals(ClickState.WAIT, c.clickState(t));
        assertEquals("asking again while pending still waits", ClickState.WAIT, c.clickState(t));
        assertFalse(c.isClickGranted());
        assertFalse("a WAIT is never remembered as a refusal", c.wasClickRefusedForCheck());
    }

    @Test public void yesOnceAfterTheCheckClears() {
        ScheduledCompose c = new ScheduledCompose(2L);
        c.requirePreSendCheck();
        String t = c.nextInjection();
        assertTrue(c.onTyped(t));
        assertEquals(ClickState.WAIT, c.clickState(t));
        assertTrue("asks that came while waiting do not cost the send", c.clearPreSend());
        assertEquals(ClickState.YES, c.clickState(t));
        assertTrue(c.isClickGranted());
        assertEquals("never twice", ClickState.NO, c.clickState(t));
        assertFalse("no injection after a click", c.mayInject());
    }

    @Test public void yesAtOnceWithoutACheck() {
        ScheduledCompose c = new ScheduledCompose(3L);
        String t = c.nextInjection();
        assertEquals(ClickState.YES, c.clickState(t));
        assertTrue("a granted click counts as typed", c.isTyped());
    }

    @Test public void noAfterCloseOrStop() {
        ScheduledCompose pending = new ScheduledCompose(4L);
        pending.requirePreSendCheck();
        String t = pending.nextInjection();
        assertEquals(ClickState.WAIT, pending.clickState(t));
        assertTrue("Stop / hold: nothing was granted", pending.close());
        assertEquals(ClickState.NO, pending.clickState(t));
        assertFalse(pending.clearPreSend());
        assertEquals(ClickState.NO, pending.clickState(t));

        ScheduledCompose cleared = new ScheduledCompose(5L);
        String u = cleared.nextInjection();
        cleared.closeForFailure();
        assertEquals(ClickState.NO, cleared.clickState(u));
    }

    @Test public void aStaleOrMissingTokenIsNo() {
        ScheduledCompose c = new ScheduledCompose(6L);
        c.requirePreSendCheck();
        String t1 = c.nextInjection();
        String t2 = c.nextInjection();
        assertEquals("stale token, even while pending", ClickState.NO, c.clickState(t1));
        assertEquals(ClickState.NO, c.clickState(null));
        assertEquals(ClickState.WAIT, c.clickState(t2));
        c.clearPreSend();
        assertEquals(ClickState.NO, c.clickState(t1));
        assertEquals(ClickState.YES, c.clickState(t2));
        assertFalse(c.isCurrent(t1));
    }

    @Test public void wireValuesAreWhatTheScriptCompares() {
        assertEquals("yes", ClickState.YES.wire());
        assertEquals("wait", ClickState.WAIT.wire());
        assertEquals("no", ClickState.NO.wire());
        assertEquals("thread", Mode.THREAD.wire());
        assertEquals("new", Mode.NEW_CONVERSATION.wire());
    }

    @Test public void theScriptWaitsLongerThanThePreSendCheckCanTake() {
        assertTrue(ScheduledSendController.clickWaitMs() > ScheduledSendController.PRESEND_TIMEOUT_MS);
        assertEquals(500L, ScheduledCompose.CLICK_POLL_MS);
    }

    // ================================================================== thread vs fallback

    @Test public void theJobsOwnThreadPageComposesInTheThread() {
        assertEquals(Mode.THREAD, ScheduledCompose.composeMode(THREAD, E164, false));
        assertEquals(Mode.THREAD, ScheduledCompose.composeMode(
                "https://voice.google.com/u/0/messages?itemId=t.+15555550123", E164, false));
    }

    @Test public void anyOtherPageOrANoThreadVerdictUsesTheNewConversationFlow() {
        assertEquals("GV showed no conversation", Mode.NEW_CONVERSATION, ScheduledCompose.composeMode(THREAD, E164, true));
        assertEquals("redirected to the list", Mode.NEW_CONVERSATION, ScheduledCompose.composeMode(LIST, E164, false));
        assertEquals(Mode.NEW_CONVERSATION, ScheduledCompose.composeMode(OTHER_THREAD, E164, false));
        assertEquals(Mode.NEW_CONVERSATION, ScheduledCompose.composeMode(DRAFT, E164, false));
        assertEquals(Mode.NEW_CONVERSATION, ScheduledCompose.composeMode(null, E164, false));
    }

    @Test public void threadPageDecision() {
        long settle = ScheduledCompose.THREAD_SETTLE_MS;
        assertEquals(ThreadPage.THREAD, ScheduledCompose.threadPageState(E164, E164, true, 0L, settle));
        assertEquals("the list after a slow cold load", ThreadPage.THREAD,
                ScheduledCompose.threadPageState(E164, E164, true, settle + 5_000L, settle));
        assertEquals(ThreadPage.WAIT, ScheduledCompose.threadPageState(E164, E164, false, settle - 1L, settle));
        assertEquals("settled with no conversation", ThreadPage.FALLBACK,
                ScheduledCompose.threadPageState(E164, E164, false, settle, settle));
        assertEquals("GV went elsewhere", ThreadPage.FALLBACK,
                ScheduledCompose.threadPageState(null, E164, true, 0L, settle));
        assertEquals("another number's thread", ThreadPage.FALLBACK,
                ScheduledCompose.threadPageState(OTHER, E164, true, 0L, settle));
    }

    @Test public void threadUrlIsTheFullLoadUrlOfTheJobsThread() {
        assertEquals(THREAD, ThreadUrl.threadUrlOf(E164));
        assertTrue(ThreadUrl.isThreadOf(ThreadUrl.threadUrlOf(E164), E164));
        assertFalse(ThreadUrl.isThreadOf(ThreadUrl.threadUrlOf(E164), OTHER));
        assertNull(ThreadUrl.threadUrlOf(null));
        assertNull(ThreadUrl.threadUrlOf("15555550123"));
        assertNull(ThreadUrl.threadUrlOf("+1555"));
        assertNull(ThreadUrl.threadUrlOf("+1555555012&x=1"));
    }

    // ================================================================== re-inject on the job's thread

    @Test public void aReloadOfTheJobsOwnThreadReinjects() {
        assertTrue(ScheduledCompose.shouldReinject(THREAD, E164, 1, false, false));
        assertTrue("the messages list still does", ScheduledCompose.shouldReinject(LIST, E164, 2, false, false));
    }

    @Test public void otherItemPagesNeverReinject() {
        assertFalse("the new-conversation draft", ScheduledCompose.shouldReinject(DRAFT, E164, 1, false, false));
        assertFalse(ScheduledCompose.shouldReinject(OTHER_THREAD, E164, 1, false, false));
        assertFalse("no job number", ScheduledCompose.shouldReinject(THREAD, null, 1, false, false));
        assertFalse(ScheduledCompose.shouldReinject("https://accounts.google.com/signin", E164, 1, false, false));
    }

    @Test public void theOwnThreadNeverReinjectsAfterAGrantedClickOrClosedGate() {
        assertFalse("no double send", ScheduledCompose.shouldReinject(THREAD, E164, 1, true, false));
        assertFalse(ScheduledCompose.shouldReinject(THREAD, E164, 1, false, true));
        assertFalse(ScheduledCompose.shouldReinject(THREAD, E164, 0, false, false));
        assertFalse(ScheduledCompose.shouldReinject(THREAD, E164, ScheduledCompose.MAX_INJECTIONS, false, false));
        // The old rule is unchanged: without the job's number every thread page is refused.
        assertFalse(ScheduledCompose.shouldReinject(THREAD, 1, false, false));
    }

    // ================================================================== new-conversation pre-send read

    private static ScheduledMessage message(Watermark wm) {
        return new ScheduledMessage.Builder()
                .id(9L).createdAt(T0 + MIN).scheduledFor(T0 + 60 * MIN).text("See you at the station").recipientE164(E164)
                .source(ScheduledMessage.Source.WEBVIEW).rung(Rung.R1).holdIfReplied(true)
                .latenessPolicy(LatenessPolicy.DEFAULT).status(ScheduledMessage.Status.IN_FLIGHT)
                .watermark(wm).build();
    }

    @Test public void noConversationIsUnchangedOnlyWithoutANewestMessage() {
        Watermark none = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, T0, null);
        assertEquals(Classification.UNCHANGED,
                PreSendRead.classifyNoConversation(message(none), ScheduledSendController.READ_LIMIT, T0 + 60 * MIN));
        assertEquals(Classification.UNCHANGED,
                PreSendRead.classifyNoConversation(message(null), ScheduledSendController.READ_LIMIT, T0 + 60 * MIN));
        Watermark timed = new Watermark(Watermark.Quality.TIMESTAMP, null, T0, null, T0 + MIN, null);
        assertEquals(Classification.UNVERIFIED,
                PreSendRead.classifyNoConversation(message(timed), ScheduledSendController.READ_LIMIT, T0 + 60 * MIN));
        Watermark deviceNewest = new Watermark(Watermark.Quality.DEVICE_ONLY, null, T0, null, T0 + MIN, null);
        assertEquals("a newest message GV did not show", Classification.UNVERIFIED,
                PreSendRead.classifyNoConversation(message(deviceNewest), ScheduledSendController.READ_LIMIT, T0 + 60 * MIN));
        assertEquals(Classification.UNVERIFIED,
                PreSendRead.classifyNoConversation(null, ScheduledSendController.READ_LIMIT, T0));
    }

    @Test public void newConversationReadIsEmptyOffTheThreadAndAsReadOnIt() {
        List<ThreadBubble> read = new ArrayList<>(Arrays.asList(
                new ThreadBubble(ThreadEvent.Direction.IN, T0, "ok", null, true, false)));
        assertEquals(0, PreSendRead.newConversationRead(null, false).size());
        assertEquals(0, PreSendRead.newConversationRead(read, false).size());
        assertEquals(read, PreSendRead.newConversationRead(read, true));
        assertNull("on the thread an unreadable page stays unreadable", PreSendRead.newConversationRead(null, true));
    }

    // ================================================================== source checks

    private static String code(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
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

    @Test public void scheduledScriptPollsOnWaitAndClicksOnlyOnYes() throws IOException {
        String js = code(INJECT);
        String entry = block(js, "(?m)^function v2sScheduledCompose\\s*\\(phone, body, autoSendDelay, token\\)\\s*\\{");
        assertTrue("asks the tri-state gate", entry.contains("V2SBridge.scheduledClickState(token)"));
        String click = block(entry, "function clickWhenGranted\\s*\\(retries, waitUntil\\)\\s*\\{");
        int recheck = click.indexOf("holdsBody(composer())");
        int ask = click.indexOf("askClick()");
        assertTrue("re-checks the box before each ask", recheck >= 0 && recheck < ask);
        String waitBranch = block(click, "if \\(state === 'wait'\\)\\s*\\{");
        assertTrue("polls again on wait", waitBranch.contains("setTimeout(function() { clickWhenGranted(retries, waitUntil); }, CLICK_POLL_MS)"));
        assertTrue("bounded", waitBranch.contains("WAIT_MS") && waitBranch.contains("Date.now() < waitUntil"));
        assertFalse("never clicks while waiting", waitBranch.contains(".click()"));
        int notYes = click.indexOf("if (state !== 'yes')");
        assertTrue("clicks only after every non-yes answer returned",
                notYes > click.indexOf("if (state === 'wait')") && notYes < click.indexOf("sendBtn.click()"));
        assertTrue("one click in the whole entry", entry.indexOf(".click()") == entry.lastIndexOf(".click()"));
    }

    @Test public void scheduledScriptFillsTheThreadBoxAndFallsBackOnlyWhenNoConversation() throws IOException {
        String js = code(INJECT);
        String entry = block(js, "(?m)^function v2sScheduledCompose\\s*\\(phone, body, autoSendDelay, token\\)\\s*\\{");
        String state = block(entry, "function threadPageState\\s*\\(\\s*\\)\\s*\\{");
        assertTrue(state.contains("_v2sSlThreadE164()") && state.contains("gv-message-list") && state.contains("SETTLE_MS"));
        String fill = block(entry, "function fillThread\\s*\\(polls\\)\\s*\\{");
        assertTrue(fill.contains("V2SBridge.onScheduledNoThread(token)"));
        assertTrue("Angular-safe setter", fill.contains("_v2sScheduledSet(ta, body, true)"));
        assertTrue("the new-conversation flow only outside thread mode",
                entry.contains("if (threadMode) {") && entry.indexOf("voice2sms(phone, body, false, 0)") > entry.indexOf("} else {"));
        assertFalse("never an SPA push to the thread", js.contains("history.pushState"));
    }

    @Test public void clearRemovesOnlyOurExactBody() throws IOException {
        String js = code(INJECT);
        String clear = block(js, "(?m)^function v2sScheduledClear\\s*\\(text\\)\\s*\\{");
        int check = clear.indexOf("if (!_v2sScheduledHolds(el, text)) return false;");
        int set = clear.indexOf("_v2sScheduledSet(el, '', false)");
        assertTrue("only when the box holds our text", check >= 0 && check < set);
        String holds = block(js, "(?m)^function _v2sScheduledHolds\\s*\\(el, text\\)\\s*\\{");
        assertTrue(holds.contains("v === text"));
        assertFalse("never logs the text", Pattern.compile("console\\.log\\([^;]*\\btext\\b").matcher(clear).find());
        String entry = block(js, "(?m)^function v2sScheduledCompose\\s*\\(phone, body, autoSendDelay, token\\)\\s*\\{");
        assertTrue("a no clears our text", block(entry, "if \\(state !== 'yes'\\)\\s*\\{").contains("v2sScheduledClear(body)"));
    }

    @Test public void controllerClearsOnlyWhenNoClickWasGranted() throws IOException {
        String c = code(CONTROLLER);
        String close = block(c, "private void closeCompose\\s*\\(Job job\\)\\s*\\{");
        assertTrue(close.contains("boolean unsent = job.compose.close();"));
        assertTrue(close.contains("if (unsent && !job.composerCleared"));
        assertTrue(close.indexOf("host.isAlive()") < close.indexOf("host.clearComposer("));
        assertTrue("the read in the new-conversation flow", block(c, "private void preSendReadOnce\\s*\\(final Job job\\)\\s*\\{")
                .contains("preSendView(job, bubbles)"));
        assertTrue(block(c, "private void onPreSendRead\\s*\\([^)]*\\)\\s*\\{").contains("PreSendRead.classifyNoConversation("));
        assertTrue(block(c, "public boolean wantsReinject\\s*\\(String url\\)\\s*\\{").contains("job.e164"));
    }

    @Test public void activityLoadsTheThreadByFullLoadAndGuardsTheNewBridgeMethods() throws IOException {
        String a = code(ACTIVITY);
        String begin = block(a, "public boolean beginCompose\\s*\\(String e164, String text\\)\\s*\\{");
        assertTrue(begin.contains("ThreadUrl.threadUrlOf(e164)"));
        assertTrue("full load", begin.contains("webView.loadUrl(thread)"));
        assertTrue(begin.indexOf("isActivityAlive()") < begin.indexOf("webView.loadUrl(thread)"));
        String inject = block(a, "private void injectScheduledComposer\\s*\\(\\s*\\)\\s*\\{");
        assertTrue(inject.contains("scheduledSend.composeModeFor("));
        assertTrue(inject.indexOf("window._v2sScheduledOpts") < inject.indexOf("v2sScheduledCompose("));
        String clear = block(a, "public void clearComposer\\s*\\(String text\\)\\s*\\{");
        assertTrue(clear.indexOf("isActivityAlive()") < clear.indexOf("evaluateJavascript"));
        for (String m : new String[]{"scheduledClickState", "onScheduledNoThread"}) {
            String b = block(a, "@JavascriptInterface\\s+public\\s+\\w+\\s+" + m + "\\s*\\([^)]*\\)\\s*\\{");
            int guard = b.indexOf("OriginGuard.isTrustedGvUrl(mainFrameUrl)");
            assertTrue(m + " checks the origin first", guard >= 0);
            int act = Math.max(b.indexOf("runOnUiThread"), b.indexOf("ScheduledSendController.clickState"));
            assertTrue(m + " origin before acting", act > guard);
        }
        assertNotNull(block(a, "public boolean loadMessagesList\\s*\\(\\s*\\)\\s*\\{"));
    }

    @Test public void geminiReviewComposeIsUntouched() throws IOException {
        String a = code(ACTIVITY);
        String composer = block(a, "private void injectComposer\\s*\\(\\s*\\)\\s*\\{");
        assertFalse(composer.contains("_v2sScheduledOpts"));
        assertFalse(composer.contains("ThreadUrl"));
        String js = code(INJECT);
        String v2s = block(js, "(?m)^function voice2sms\\s*\\(phone, body, autoSend, autoSendDelay\\)\\s*\\{");
        assertFalse(v2s.contains("scheduledClickState"));
        assertFalse(v2s.contains("_v2sScheduledOpts"));
    }
}
