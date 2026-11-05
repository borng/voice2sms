package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.send.ScheduledCompose.Mode;
import com.voice2sms.sendlater.send.ScheduledCompose.NoThread;
import com.voice2sms.sendlater.send.ScheduledCompose.ThreadPage;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * live-thread-load (LIVE-F11; R30, R31, D30): a scheduled launch loads the job's conversation
 * page, cold or warm, and never the messages list over it; a page that is not the job's thread is
 * loaded again (bounded, the single reload budget) instead of an immediate new-conversation
 * fallback; non-scheduled page loads are unchanged. Fictional numbers only. Paths relative to app/.
 */
public class LiveThreadLoadTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path CONTROLLER = MAIN.resolve("sendlater/send/ScheduledSendController.java");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path INJECT = Paths.get("src/main/assets/inject.js");

    private static final String E164 = "+15555550123";
    private static final String OTHER = "+15555550199";
    private static final String THREAD = "https://voice.google.com/u/0/messages?itemId=t.%2B15555550123";
    private static final long SETTLE = ScheduledCompose.THREAD_SETTLE_MS;

    // ================================================================== pure decision

    @Test public void aPageThatIsNotTheJobsThreadIsAWrongPageNotAFallback() {
        assertEquals("the messages list", ThreadPage.WRONG_PAGE, ScheduledCompose.pageState(null, E164, true, 0L, SETTLE));
        assertEquals("even long after the injection", ThreadPage.WRONG_PAGE,
                ScheduledCompose.pageState(null, E164, false, SETTLE * 3, SETTLE));
        assertEquals("another number's thread", ThreadPage.WRONG_PAGE, ScheduledCompose.pageState(OTHER, E164, true, 0L, SETTLE));
        assertEquals("no job number", ThreadPage.WRONG_PAGE, ScheduledCompose.pageState(E164, null, true, 0L, SETTLE));
    }

    @Test public void onTheJobsThreadThePageStateIsUnchanged() {
        assertEquals(ThreadPage.THREAD, ScheduledCompose.pageState(E164, E164, true, 0L, SETTLE));
        assertEquals(ThreadPage.WAIT, ScheduledCompose.pageState(E164, E164, false, SETTLE - 1L, SETTLE));
        assertEquals("settled without the conversation", ThreadPage.FALLBACK,
                ScheduledCompose.pageState(E164, E164, false, SETTLE, SETTLE));
    }

    @Test public void wrongPageReloadsTheThreadOnceThenFallsBack() {
        ScheduledCompose c = new ScheduledCompose(1L);
        String t1 = c.nextInjection();
        assertEquals(NoThread.RELOAD, c.onWrongPage(t1));
        assertTrue(c.wasThreadReloaded());
        String t2 = c.nextInjection();
        assertEquals("budget spent", NoThread.FALLBACK, c.onWrongPage(t2));
        assertEquals("before any injection too", NoThread.FALLBACK, c.onWrongPage());
        assertEquals(2, ScheduledCompose.MAX_THREAD_LOADS);
    }

    @Test public void wrongPageBeforeTheFirstInjectionReloads() {
        ScheduledCompose c = new ScheduledCompose(1L);
        assertEquals("onPageFinished of the list before any injection", NoThread.RELOAD, c.onWrongPage());
        c.nextInjection();
        assertEquals(NoThread.FALLBACK, c.onWrongPage());
    }

    @Test public void theReloadBudgetIsSharedWithTheNoThreadReload() {
        ScheduledCompose a = new ScheduledCompose(1L);
        String t = a.nextInjection();
        assertEquals(NoThread.RELOAD, a.onWrongPage(t));
        String t2 = a.nextInjection();
        assertEquals("the thread settled empty after the wrong-page reload", NoThread.FALLBACK, a.onNoThread(t2, true));

        ScheduledCompose b = new ScheduledCompose(2L);
        String u = b.nextInjection();
        assertEquals(NoThread.RELOAD, b.onNoThread(u, true));
        String u2 = b.nextInjection();
        assertEquals(NoThread.FALLBACK, b.onWrongPage(u2));
    }

    @Test public void wrongPageIgnoredForAStaleTokenAGrantOrAClosedGate() {
        ScheduledCompose c = new ScheduledCompose(1L);
        String t1 = c.nextInjection();
        c.nextInjection();
        assertEquals(NoThread.IGNORE, c.onWrongPage(t1));
        assertEquals(NoThread.IGNORE, c.onWrongPage((String) null));
        assertFalse(c.wasThreadReloaded());
        ScheduledCompose g = new ScheduledCompose(2L);
        String k = g.nextInjection();
        assertTrue(g.grantClick(k));
        assertEquals(NoThread.IGNORE, g.onWrongPage(k));
        assertEquals(NoThread.IGNORE, g.onWrongPage());
        ScheduledCompose x = new ScheduledCompose(3L);
        x.nextInjection();
        x.close();
        assertEquals(NoThread.IGNORE, x.onWrongPage());
    }

    @Test public void noReloadOnceTheInjectionBoundIsReached() {
        ScheduledCompose c = new ScheduledCompose(1L);
        String t = null;
        for (int i = 0; i < ScheduledCompose.MAX_INJECTIONS; i++) t = c.nextInjection();
        assertEquals(NoThread.FALLBACK, c.onWrongPage(t));
    }

    @Test public void wrongPageThenReloadThenTheThreadComposesInThreadMode() {
        ScheduledCompose c = new ScheduledCompose(1L);
        c.requirePreSendCheck();
        String t1 = c.nextInjection();
        // GV showed the messages list: not a fallback, the thread is loaded again.
        assertEquals(ThreadPage.WRONG_PAGE, ScheduledCompose.pageState(null, E164, true, 0L, SETTLE));
        assertEquals(NoThread.RELOAD, c.onWrongPage(t1));
        // The reload finished on the job's thread: re-injected, thread mode, the conversation shows.
        assertTrue(ScheduledCompose.shouldReinject(THREAD, E164, c.injections(), c.isClickGranted(), c.isClosed()));
        String t2 = c.nextInjection();
        assertEquals(Mode.THREAD, ScheduledCompose.composeMode(THREAD, E164, false));
        assertEquals(ThreadPage.THREAD, ScheduledCompose.pageState(E164, E164, true, 1_000L, SETTLE));
        assertTrue(c.onTyped(t2));
        assertEquals(ScheduledCompose.ClickState.WAIT, c.clickState(t2));
        assertTrue(c.clearPreSend());
        assertEquals(ScheduledCompose.ClickState.YES, c.clickState(t2));
    }

    @Test public void theExtraLoadFitsTheComposeAndHardTimeouts() {
        assertTrue(ScheduledSendController.RELOAD_COMPOSE_GRACE_MS >= ScheduledCompose.THREAD_SETTLE_MS);
        assertTrue(ScheduledSendController.COMPOSE_TIMEOUT_MS + ScheduledSendController.RELOAD_COMPOSE_GRACE_MS
                < LockCoverPolicy.HARD_TIMEOUT_MS);
    }

    // ================================================================== activity: scheduled loads

    @Test public void aClaimedScheduledLaunchLoadsNothingButTheJobsThread() throws IOException {
        String act = code(ACTIVITY);
        String onCreate = block(act, "protected void onCreate\\s*\\(Bundle savedInstanceState\\)\\s*\\{");
        int skip = onCreate.indexOf("scheduledSend.skipsPageLoad()");
        int sched = onCreate.indexOf("} else if (scheduledLaunch && scheduledSend.isActive()) {");
        int warm = onCreate.indexOf("} else if (app.isSpaLoaded() && recipient != null) {");
        int cold = onCreate.indexOf("} else if (!app.isSpaLoaded()) {");
        assertTrue("the R3 skip stays first", skip >= 0 && skip < sched);
        assertTrue("the scheduled branch precedes the warm and cold branches", sched > 0 && sched < warm && warm < cold);
        String branch = onCreate.substring(sched, warm);
        assertTrue(branch.contains("loadForScheduledJob(app)"));
        assertFalse("never the messages list", branch.contains("loadGoogleVoice()") || branch.contains("GV_MESSAGES_URL"));
        assertFalse("no load of its own", branch.contains("loadUrl("));
    }

    @Test public void theScheduledLoadHelperNeverLoadsTheMessagesList() throws IOException {
        String h = block(code(ACTIVITY), "private void loadForScheduledJob\\s*\\(Voice2SmsApplication app\\)\\s*\\{");
        assertTrue(h.indexOf("isActivityAlive()") < h.indexOf("scheduledSend.loadForJob()"));
        assertTrue("a cold WebView without a session fails the job", h.contains("scheduledSend.onWebFailure(\"no_session\")"));
        assertFalse(h.contains("loadGoogleVoice()") || h.contains("GV_MESSAGES_URL") || h.contains("loadUrl("));
        assertFalse("never the account picker", h.contains("attemptAccountAuth"));
    }

    @Test public void onlyOneLoadPerStart() throws IOException {
        String c = code(CONTROLLER);
        String load = block(c, "public void loadForJob\\s*\\(\\)\\s*\\{");
        int early = load.indexOf("if (job == null || job.e164 == null || job.compose == null) return;");
        assertTrue("before the row is read beginCompose makes the one load", early >= 0
                && early < load.indexOf("job.compose.onWrongPage()"));
        String begin = block(code(ACTIVITY), "public boolean beginCompose\\s*\\(String e164, String text\\)\\s*\\{");
        assertEquals("beginCompose loads exactly once", count(begin, "webView.loadUrl("), 1);
        String loaded = block(c, "private void onLoaded\\s*\\([^)]*\\)\\s*\\{");
        assertEquals(1, count(loaded, "host.beginCompose("));
    }

    @Test public void theAuthContinuationGoesToTheJobNotTheList() throws IOException {
        String act = code(ACTIVITY);
        int merge = act.indexOf("Auth MergeSession loaded, redirecting to GV");
        String after = act.substring(merge, act.indexOf("doUpdateVisitedHistory", merge));
        int sched = after.indexOf("if (scheduledSend != null && scheduledSend.isActive()) loadForScheduledJob(null);");
        int list = after.indexOf("else view.loadUrl(GV_MESSAGES_URL);");
        assertTrue(sched > 0 && list > sched);
    }

    @Test public void aWrongGvPageFinishingReloadsTheThreadBeforeAnyInjection() throws IOException {
        String act = code(ACTIVITY);
        assertTrue("the onPageFinished dispatch is unchanged", act.contains(
                "if (scheduledSend == null || !scheduledSend.isActive()) injectComposer();\n"
                + "                    else if (scheduledSend.isComposing()) injectScheduledComposer();"));
        int finished = act.indexOf("public void onPageFinished(WebView view, String url) {");
        int setUrl = act.indexOf("mainFrameUrl = url;", finished);
        assertTrue("the finished page's URL is what the check sees", setUrl > finished
                && setUrl < act.indexOf("injectScheduledComposer();", finished));
        String inj = block(act, "private void injectScheduledComposer\\s*\\(\\)\\s*\\{");
        int alive = inj.indexOf("if (!isActivityAlive() || scheduledSend == null) return;");
        int check = inj.indexOf("if (scheduledSend.reloadsThreadInsteadOf(mainFrameUrl)) {");
        int token = inj.indexOf("scheduledSend.onComposeInjection(");
        assertTrue("checked before any token or script", alive >= 0 && check > alive && check < token
                && check < inj.indexOf("readAssetScript("));

        String r = block(code(CONTROLLER), "public boolean reloadsThreadInsteadOf\\s*\\(String url\\)\\s*\\{");
        assertTrue("after the fallback the list gets the new-conversation flow", r.contains("job.gvShowedNoThread) return false;"));
        assertTrue("the job's own thread is injected", r.contains("ThreadUrl.isThreadOf(url, job.e164)) return false;"));
        int decide = r.indexOf("job.compose.onWrongPage()");
        int reload = r.indexOf("reloadThread(job)");
        assertTrue(decide > 0 && reload > decide);
        assertTrue("budget spent: inject here in the new-conversation flow", r.contains("fallBackToNewConversation(job, false)"));
    }

    @Test public void theScriptsWrongPageReportReloadsOrFallsBack() throws IOException {
        String c = code(CONTROLLER);
        String w = block(c, "public void onWrongPage\\s*\\(String token\\)\\s*\\{");
        assertTrue(w.contains("job.compose.onWrongPage(token)"));
        assertTrue(w.contains("reloadThread(job)") && w.contains("fallBackToNewConversation(job, true)"));
        String f = block(c, "private void fallBackToNewConversation\\s*\\(Job job, boolean loadList\\)\\s*\\{");
        assertTrue(f.contains("job.gvShowedNoThread = true;"));
        assertTrue(f.indexOf("host.isAlive()") < f.indexOf("host.loadMessagesList()"));
        String r = block(c, "private void reloadThread\\s*\\(Job job\\)\\s*\\{");
        assertTrue("the extra load extends the compose timeout", r.contains("job.composeGraceMs = RELOAD_COMPOSE_GRACE_MS;"));

        String a = code(ACTIVITY);
        String b = block(a, "@JavascriptInterface\\s+public\\s+void\\s+onScheduledWrongPage\\s*\\(String token\\)\\s*\\{");
        int origin = b.indexOf("OriginGuard.isTrustedGvUrl(mainFrameUrl)");
        int ui = b.indexOf("runOnUiThread(");
        int alive = b.indexOf("isActivityAlive()");
        assertTrue(origin >= 0 && origin < ui && ui < alive && alive < b.indexOf("scheduledSend.onWrongPage(token)"));
    }

    @Test public void theScriptWaitsOnAWrongPageAndAsksForTheThread() throws IOException {
        String js = code(INJECT);
        String entry = block(js, "(?m)^function v2sScheduledCompose\\s*\\(phone, body, autoSendDelay, token\\)\\s*\\{");
        String state = block(entry, "function threadPageState\\s*\\(\\s*\\)\\s*\\{");
        assertTrue(state.contains("if (!pageE164 || pageE164 !== jobE164) return 'wrong';"));
        assertEquals("only the settled thread falls back", 1, count(state, "'fallback'"));
        assertTrue(state.indexOf("'fallback'") > state.indexOf("gv-message-list"));
        String fill = block(entry, "function fillThread\\s*\\(polls\\)\\s*\\{");
        String wrong = block(fill, "if \\(state === 'wrong'\\)\\s*\\{");
        assertTrue(wrong.contains("V2SBridge.onScheduledWrongPage(token)"));
        assertTrue("once per run", wrong.contains("if (!wrongPageReported) {") && wrong.contains("wrongPageReported = true;"));
        assertTrue("then waits", wrong.contains("state = 'wait';"));
        assertFalse(wrong.contains("onScheduledNoThread"));
        assertTrue(fill.indexOf("if (state === 'wrong')") < fill.indexOf("if (state === 'fallback')"));
    }

    // ================================================================== non-scheduled unchanged

    @Test public void nonScheduledLoadPathsAreUnchanged() throws IOException {
        String act = code(ACTIVITY);
        String onCreate = block(act, "protected void onCreate\\s*\\(Bundle savedInstanceState\\)\\s*\\{");
        int warm = onCreate.indexOf("} else if (app.isSpaLoaded() && recipient != null) {");
        int cold = onCreate.indexOf("} else if (!app.isSpaLoaded()) {");
        assertTrue("warm start reloads the list", onCreate.substring(warm, cold).contains("loadGoogleVoice();"));
        String coldBranch = onCreate.substring(cold);
        assertTrue(coldBranch.contains("if (cookies != null && cookies.contains(\"SID\")) {\n                loadGoogleVoice();"));
        assertTrue(coldBranch.contains("attemptAccountAuth();"));

        String onNew = block(act, "protected void onNewIntent\\s*\\(Intent intent\\)\\s*\\{");
        int handled = onNew.indexOf("if (scheduledSend != null && scheduledSend.handle(intent, false)) return;");
        int other = onNew.indexOf("scheduledSend.onOtherIntent();");
        int load = onNew.lastIndexOf("loadGoogleVoice();");
        assertTrue("a normal intent ends the scheduled work, then reloads the list", handled >= 0 && other > handled && load > other);

        String lg = block(act, "private void loadGoogleVoice\\s*\\(\\)\\s*\\{");
        assertTrue(lg.contains("webView.loadUrl(GV_MESSAGES_URL);"));
        String reload = block(act, "public void requestPageReload\\s*\\(\\)\\s*\\{");
        assertTrue(reload.contains("webView.loadUrl(GV_MESSAGES_URL);"));
    }

    // ================================================================== helpers

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) n++;
        return n;
    }

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
}
