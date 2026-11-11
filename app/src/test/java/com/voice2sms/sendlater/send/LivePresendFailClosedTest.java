package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.Classification;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.PreSendCheck;
import com.voice2sms.sendlater.domain.PreSendCheck.Verdict;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.send.ScheduledCompose.NoThread;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * live-presend-failclosed (R31, D30, OD22, OD27, OD34, LIVE-F10): an empty conversation clears a
 * scheduled send only on fail-closed evidence; the job's thread page is reloaded once before the
 * new-conversation fallback; a draft the user left in the thread's box is never overwritten; every
 * Java -> JS evaluate of the scheduled path checks the page's origin. Fictional numbers and texts
 * only. Paths relative to app/.
 */
public class LivePresendFailClosedTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path CONTROLLER = MAIN.resolve("sendlater/send/ScheduledSendController.java");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path INJECT = Paths.get("src/main/assets/inject.js");

    private static final String E164 = "+15555550123";
    private static final long MIN = 60_000L;
    private static final long T0 = 1_781_092_800_000L;
    private static final long NOW = T0 + 60 * MIN;

    private static final Watermark NOTHING = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, T0, null);

    private static ScheduledMessage message(Watermark wm, Watermark original) {
        return new ScheduledMessage.Builder()
                .id(9L).createdAt(T0 + MIN).scheduledFor(T0 + 60 * MIN).text("See you at the station").recipientE164(E164)
                .source(ScheduledMessage.Source.WEBVIEW).rung(Rung.R1).holdIfReplied(true)
                .latenessPolicy(LatenessPolicy.DEFAULT).status(ScheduledMessage.Status.IN_FLIGHT)
                .watermark(wm).originalWatermark(original).build();
    }

    private static Verdict emptyVerdict(PreSendCheck check, ScheduledMessage m) {
        Classification c = PreSendRead.classifyNoConversation(m, ScheduledSendController.READ_LIMIT, NOW);
        return PreSendRead.noConversationVerdict(check, m, c);
    }

    private static PreSendCheck required(boolean listenerOn, boolean listenerSaw) {
        return PreSendCheck.required(OutboundPolicy.STILL_SEND).withListenerFacts(listenerOn, listenerSaw);
    }

    // ================================================================== clearance truth table

    @Test public void allThreeConditionsClearTheEmptyConversation() {
        ScheduledMessage m = message(NOTHING, null);
        assertTrue(required(true, false).mayClearEmptyConversation(m));
        assertEquals(Verdict.SEND, emptyVerdict(required(true, false), m));
        assertEquals("CHECK mode too", Verdict.SEND, emptyVerdict(
                PreSendCheck.check(OutboundPolicy.STILL_SEND).withListenerFacts(true, false), m));
    }

    @Test public void aNewestTimeAtSchedulingIsUnverified() {
        Watermark timed = new Watermark(Watermark.Quality.TIMESTAMP, null, T0, null, T0 + MIN, null);
        assertEquals(Verdict.HOLD_UNVERIFIED, emptyVerdict(required(true, false), message(timed, null)));
    }

    @Test public void aBubbleHashAtSchedulingIsUnverified() {
        Watermark hashed = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, "abc123", T0, null);
        ScheduledMessage m = message(hashed, null);
        assertFalse(required(true, false).mayClearEmptyConversation(m));
        assertEquals(Verdict.HOLD_UNVERIFIED, emptyVerdict(required(true, false), m));
    }

    @Test public void aNewestServerIdAtSchedulingIsUnverified() {
        Watermark id = new Watermark(Watermark.Quality.DEVICE_ONLY, "srv-1", null, null, T0, null);
        assertEquals(Verdict.HOLD_UNVERIFIED, emptyVerdict(required(true, false), message(id, null)));
    }

    @Test public void aNewestMessageInTheOriginalBaselineIsUnverified() {
        Watermark timed = new Watermark(Watermark.Quality.TIMESTAMP, null, T0, "abc123", T0 + MIN, null);
        assertEquals("the self-preemption baseline counts too", Verdict.HOLD_UNVERIFIED,
                emptyVerdict(required(true, false), message(NOTHING, timed)));
    }

    @Test public void aMissingWatermarkIsUnverified() {
        assertEquals(Verdict.HOLD_UNVERIFIED, emptyVerdict(required(true, false), message(null, null)));
    }

    @Test public void listenerOffIsUnverified() {
        assertEquals(Verdict.HOLD_UNVERIFIED, emptyVerdict(required(false, false), message(NOTHING, null)));
    }

    @Test public void aListenerMatchForTheNumberIsUnverified() {
        assertEquals(Verdict.HOLD_UNVERIFIED, emptyVerdict(required(true, true), message(NOTHING, null)));
    }

    @Test public void noListenerFactsIsUnverified() {
        ScheduledMessage m = message(NOTHING, null);
        assertEquals("the controller's strict default", Verdict.HOLD_UNVERIFIED,
                emptyVerdict(ScheduledSendController.defaultPreSendCheck(), m));
        assertEquals(Verdict.HOLD_UNVERIFIED, emptyVerdict(PreSendCheck.required(OutboundPolicy.STILL_SEND), m));
        assertEquals("CHECK mode holds, never a plain reminder", Verdict.HOLD_UNVERIFIED,
                emptyVerdict(PreSendCheck.check(OutboundPolicy.STILL_SEND), m));
        assertFalse(PreSendCheck.required(OutboundPolicy.STILL_SEND).hasListenerFacts());
        assertTrue("unknown counts as seen", PreSendCheck.required(OutboundPolicy.STILL_SEND).listenerSawConversation());
    }

    @Test public void theUnverifiedVerdictIsAHoldWithNothingSent() {
        assertEquals(com.voice2sms.sendlater.domain.HoldReason.UNVERIFIED, Verdict.HOLD_UNVERIFIED.holdReason());
        assertEquals(Verdict.HOLD_UNVERIFIED.detail(),
                SendDetails.forEngine(SendOutcome.CANNOT_SHOW, Verdict.HOLD_UNVERIFIED.detail()));
    }

    @Test public void listenerFactsAreValueParts() {
        assertFalse(required(true, false).equals(required(true, true)));
        assertFalse(required(true, false).equals(PreSendCheck.required(OutboundPolicy.STILL_SEND)));
        assertEquals(required(true, false), required(true, false));
        assertEquals(required(true, false).hashCode(), required(true, false).hashCode());
    }

    // ================================================================== reload once before the fallback

    @Test public void theThreadPageIsReloadedOnceBeforeTheFallback() {
        ScheduledCompose c = new ScheduledCompose(1L);
        String t1 = c.nextInjection();
        assertEquals(NoThread.RELOAD, c.onNoThread(t1, true));
        assertTrue(c.wasThreadReloaded());
        String t2 = c.nextInjection();
        assertEquals("the second settle falls back", NoThread.FALLBACK, c.onNoThread(t2, true));
        String t3 = c.nextInjection();
        assertEquals("never a second reload", NoThread.FALLBACK, c.onNoThread(t3, true));
    }

    @Test public void gvGoingElsewhereFallsBackWithoutAReload() {
        ScheduledCompose c = new ScheduledCompose(1L);
        String t = c.nextInjection();
        assertEquals(NoThread.FALLBACK, c.onNoThread(t, false));
        assertFalse(c.wasThreadReloaded());
    }

    @Test public void aStaleTokenAClosedGateOrAGrantedClickDoNothing() {
        ScheduledCompose c = new ScheduledCompose(1L);
        String t1 = c.nextInjection();
        c.nextInjection();
        assertEquals(NoThread.IGNORE, c.onNoThread(t1, true));
        assertEquals(NoThread.IGNORE, c.onNoThread(null, true));
        ScheduledCompose granted = new ScheduledCompose(2L);
        String g = granted.nextInjection();
        assertTrue(granted.grantClick(g));
        assertEquals(NoThread.IGNORE, granted.onNoThread(g, true));
        ScheduledCompose closed = new ScheduledCompose(3L);
        String k = closed.nextInjection();
        closed.close();
        assertEquals(NoThread.IGNORE, closed.onNoThread(k, true));
        assertFalse(closed.wasThreadReloaded());
    }

    @Test public void noReloadWhenTheInjectionBoundIsReached() {
        ScheduledCompose c = new ScheduledCompose(1L);
        String t = null;
        for (int i = 0; i < ScheduledCompose.MAX_INJECTIONS; i++) t = c.nextInjection();
        assertEquals(NoThread.FALLBACK, c.onNoThread(t, true));
    }

    @Test public void controllerReloadsBeforeItFallsBack() throws IOException {
        String c = code(CONTROLLER);
        String no = block(c, "public void onNoThread\\s*\\(String token\\)\\s*\\{");
        int decide = no.indexOf("job.compose.onNoThread(token, ThreadUrl.isThreadOf(host.mainFrameUrl(), job.e164))");
        int reload = no.indexOf("reloadThread(job)");
        int fallback = no.indexOf("job.gvShowedNoThread = true");
        assertTrue(decide >= 0 && decide < reload && reload < fallback);
        String r = block(c, "private void reloadThread\\s*\\(Job job\\)\\s*\\{");
        assertTrue("a full load of the job's thread", r.contains("host.beginCompose(job.e164, job.message.getText())"));
        assertTrue(r.indexOf("host.isAlive()") < r.indexOf("host.beginCompose("));
        assertTrue("the reads start over", r.contains("new PreSendRead()") && r.contains("PRESEND_TIMEOUT_MS"));
        assertTrue(r.contains("RELOAD_COMPOSE_GRACE_MS"));
        assertTrue(block(c, "private void onLoaded\\s*\\([^)]*\\)\\s*\\{").contains("extendComposeTimeout(job)"));
        assertTrue(ScheduledSendController.RELOAD_COMPOSE_GRACE_MS > ScheduledCompose.THREAD_SETTLE_MS);
    }

    @Test public void controllerUsesTheFailClosedVerdictForAnEmptyRead() throws IOException {
        String read = block(code(CONTROLLER), "private void onPreSendRead\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(read.contains("PreSendRead.noConversationVerdict(job.check, job.message, c)"));
        assertFalse("the controller reads no store itself", code(CONTROLLER).contains("hasNumberMatch"));
    }

    // ================================================================== OD34 draft_present

    @Test public void aDraftInTheThreadBoxStopsTheJobBeforeTyping() throws IOException {
        String js = code(INJECT);
        String entry = block(js, "(?m)^function v2sScheduledCompose\\s*\\(phone, body, autoSendDelay, token\\)\\s*\\{");
        String fill = block(entry, "function fillThread\\s*\\(polls\\)\\s*\\{");
        String notOurs = block(fill, "if \\(!holdsBody\\(ta\\)\\)\\s*\\{");
        int draft = notOurs.indexOf("current.trim().length > 0");
        int report = notOurs.indexOf("V2SBridge.onScheduledDraftPresent(_v2sBridgeNonce, token)");
        int set = notOurs.indexOf("_v2sScheduledSet(ta, body, true)");
        assertTrue(draft >= 0 && draft < report && report < set);
        String draftBranch = block(notOurs, "if \\(typeof current === 'string' && current.trim\\(\\).length > 0\\)\\s*\\{");
        assertTrue("stops there", draftBranch.contains("return;"));
        assertFalse("never touches the draft", draftBranch.contains("_v2sScheduledSet")
                || draftBranch.contains("v2sScheduledClear"));
    }

    @Test public void draftPresentEndsUnsentWithAReminder() throws IOException {
        assertEquals("draft_present", ScheduledSendController.DETAIL_DRAFT_PRESENT);
        assertEquals("could_not_send", SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "draft_present"));
        String c = code(CONTROLLER);
        String on = block(c, "public void onDraftPresent\\s*\\(String token\\)\\s*\\{");
        assertTrue(on.contains("job.compose.isCurrent(token)"));
        assertTrue(on.contains("stopUnsent(job, DETAIL_DRAFT_PRESENT)"));
        String a = code(ACTIVITY);
        String b = block(a, "@JavascriptInterface\\s+public\\s+void\\s+onScheduledDraftPresent\\s*\\([^)]*\\)\\s*\\{");
        int guard = b.indexOf("OriginGuard.isTrustedGvUrl(mainFrameUrl)");
        assertTrue(guard >= 0 && guard < b.indexOf("runOnUiThread"));
        assertTrue(b.indexOf("isActivityAlive()") < b.indexOf("scheduledSend.onDraftPresent(token)"));
    }

    @Test public void theEndOfJobClearRemovesOnlyOurExactBody() throws IOException {
        String clear = block(code(INJECT), "(?m)^function v2sScheduledClear\\s*\\(text\\)\\s*\\{");
        assertTrue(clear.indexOf("if (!_v2sScheduledHolds(el, text)) return false;")
                < clear.indexOf("_v2sScheduledSet(el, '', false)"));
    }

    // ================================================================== origin checks

    @Test public void clearComposerChecksTheOriginRightBeforeItEvaluates() throws IOException {
        String a = code(ACTIVITY);
        String clear = block(a, "public void clearComposer\\s*\\(String text\\)\\s*\\{");
        String ui = block(clear, "runOnUiThread\\(\\(\\) ->\\s*\\{");
        int alive = ui.indexOf("isActivityAlive()");
        int origin = ui.indexOf("OriginGuard.isTrustedGvUrl(webView.getUrl())");
        int eval = ui.indexOf("webView.evaluateJavascript(");
        assertTrue(alive >= 0 && alive < origin && origin < eval);
    }

    @Test public void everyScheduledEvaluateChecksTheOrigin() throws IOException {
        String a = code(ACTIVITY);
        String inject = block(a, "private void injectScheduledComposer\\s*\\(\\s*\\)\\s*\\{");
        int origin = inject.indexOf("OriginGuard.isTrustedGvUrl(webView.getUrl())");
        assertTrue(origin >= 0 && origin < inject.indexOf("onComposeInjection(")
                && origin < inject.indexOf("webView.evaluateJavascript("));
        String probe = block(a, "public void probeComposer\\s*\\(ScheduledSendController.ComposerProbe cb\\)\\s*\\{");
        int p = probe.indexOf("OriginGuard.isTrustedGvUrl(webView.getUrl())");
        assertTrue(p >= 0 && p < probe.indexOf("webView.evaluateJavascript("));
        String read = block(a, "public void readThread\\s*\\(String e164, int limit, ThreadReadCallback cb\\)\\s*\\{");
        int r = read.indexOf("OriginGuard.isTrustedGvUrl(webView.getUrl())");
        assertTrue(r >= 0 && r < read.indexOf("webView.evaluateJavascript("));
    }

    // ================================================================== helpers

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
