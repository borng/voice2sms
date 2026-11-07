package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.a11y.A11yWarning;
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
 * simplify-send: the scheduled-send seam after the cleanup pass. Origin checks use OriginGuard
 * (look-alike hosts rejected), onNoThread shares onWrongPage's one reload, the dead boolean Send
 * gate is gone from the bridge and the script, inject.js is cached, the input/resize ticks are
 * coalesced, and the accessibility check has one helper with an explicit failure answer.
 * Paths relative to app/.
 */
public class SimplifySendTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path CONTROLLER = MAIN.resolve("sendlater/send/ScheduledSendController.java");
    private static final Path SETTINGS = MAIN.resolve("sendlater/ui/SendLaterSettingsController.java");
    private static final Path HOOKS = MAIN.resolve("sendlater/web/SendLaterWebHooks.java");
    private static final Path INJECT = Paths.get("src/main/assets/inject.js");
    private static final String E164 = "+15550100";
    private static final String GV = "https:/" + "/voice.google.com/u/0/messages";

    // ------------------------------------------------------------------ origin checks (3)

    @Test public void reinjectionNeedsATrustedGvPage() {
        assertTrue(ScheduledCompose.shouldReinject(GV, 1, false, false));
        assertTrue(ScheduledCompose.shouldReinject(GV, E164, 1, false, false));
        String lookAlike = "https:/" + "/voice.google.com.example.net/u/0/messages";
        assertFalse(ScheduledCompose.shouldReinject(lookAlike, 1, false, false));
        assertFalse(ScheduledCompose.shouldReinject(lookAlike, E164, 1, false, false));
        String userInfo = "https:/" + "/voice.google.com@example.net/u/0/messages";
        assertFalse(ScheduledCompose.shouldReinject(userInfo, E164, 1, false, false));
        assertFalse(ScheduledCompose.shouldReinject(null, E164, 1, false, false));
        assertFalse(ScheduledCompose.shouldReinject("http:/" + "/voice.google.com/u/0/messages", 1, false, false));
    }

    @Test public void noSplitPrefixLiteralsAreLeft() throws IOException {
        String compose = code(MAIN.resolve("sendlater/send/ScheduledCompose.java"));
        String controller = code(CONTROLLER);
        assertFalse(compose.contains("GV_PREFIX"));
        assertFalse(controller.contains("GV_PAGE_PREFIX"));
        assertTrue(block(controller, "public boolean reloadsThreadInsteadOf\\s*\\(String url\\)\\s*\\{")
                .contains("OriginGuard.isTrustedGvUrl(url)"));
    }

    // ------------------------------------------------------------------ one reload budget (2)

    @Test public void noThreadOnTheJobThreadSpendsTheWrongPageBudget() {
        ScheduledCompose c = new ScheduledCompose(1L);
        String t = c.nextInjection();
        assertEquals(NoThread.RELOAD, c.onNoThread(t, true));
        assertEquals("the budget is shared", NoThread.FALLBACK, c.onWrongPage(t));
        assertEquals(NoThread.FALLBACK, c.onNoThread(t, true));

        ScheduledCompose w = new ScheduledCompose(2L);
        String u = w.nextInjection();
        assertEquals(NoThread.RELOAD, w.onWrongPage(u));
        assertEquals(NoThread.FALLBACK, w.onNoThread(u, true));
    }

    @Test public void noThreadOffTheJobThreadFallsBackWithoutSpendingTheReload() {
        ScheduledCompose c = new ScheduledCompose(3L);
        String t = c.nextInjection();
        assertEquals(NoThread.FALLBACK, c.onNoThread(t, false));
        assertFalse(c.wasThreadReloaded());
        assertEquals(NoThread.RELOAD, c.onWrongPage(t));
    }

    @Test public void noThreadAtTheInjectionBoundFallsBack() {
        ScheduledCompose c = new ScheduledCompose(4L);
        String t = null;
        for (int i = 0; i < ScheduledCompose.MAX_INJECTIONS; i++) t = c.nextInjection();
        assertEquals(NoThread.FALLBACK, c.onNoThread(t, true));
        c.close();
        assertEquals(NoThread.IGNORE, c.onNoThread(t, true));
    }

    // ------------------------------------------------------------------ dead boolean gate (1)

    @Test public void theBooleanSendGateIsGone() throws IOException {
        String act = code(ACTIVITY);
        assertFalse(act.contains("scheduledMayClick"));
        assertFalse(code(CONTROLLER).contains("mayClick"));
        String js = code(INJECT);
        assertFalse(js.contains("scheduledMayClick"));
        String entry = block(js, "(?m)^function v2sScheduledCompose\\s*\\(phone, body, autoSendDelay, token\\)\\s*\\{");
        String ask = block(entry, "function askClick\\s*\\(\\s*\\)\\s*\\{");
        assertTrue("no bridge ask answers no", ask.trim().endsWith("return 'no';\n    }"));
    }

    // ------------------------------------------------------------------ session test (4)

    @Test public void theScheduledColdLoadUsesTheEnginesSessionTest() throws IOException {
        String h = block(code(ACTIVITY), "private void loadForScheduledJob\\s*\\(Voice2SmsApplication app\\)\\s*\\{");
        int session = h.indexOf("if (!GvSession.hasWebSession(this)) {");
        assertTrue(session >= 0 && session < h.indexOf("scheduledSend.onWebFailure(\"no_session\")"));
        assertFalse("no substring cookie test", h.contains("contains(\"SID\")"));
    }

    // ------------------------------------------------------------------ asset cache (5)

    @Test public void injectJsIsReadThroughTheSharedCache() throws IOException {
        String act = code(ACTIVITY);
        String read = block(act, "private String readAssetScript\\s*\\(String name\\)\\s*\\{");
        assertTrue(read.contains("AssetScripts.read(getAssets()::open, name)"));
        assertFalse(read.contains("readLine"));
        String module = block(code(HOOKS), "public String moduleScript\\s*\\(\\s*\\)\\s*\\{");
        assertTrue(module.contains("AssetScripts.read(activity.getAssets()::open, \"inject.js\")"));
    }

    // ------------------------------------------------------------------ inject.js (6, 7)

    @Test public void theScheduledPathUsesTheSharedComposerSelector() throws IOException {
        String js = code(INJECT);
        assertFalse(js.contains("_V2S_SCHEDULED_COMPOSER"));
        assertEquals("declared once", 1, count(js, "var V2S_COMPOSER_SELECTOR"));
        String holds = block(js, "(?m)^function v2sScheduledCompose\\s*\\(phone, body, autoSendDelay, token\\)\\s*\\{");
        assertTrue(holds.contains("document.querySelector(V2S_COMPOSER_SELECTOR)"));
    }

    @Test public void pageEventsCoalesceIntoOneTrailingTick() throws IOException {
        String js = code(INJECT);
        String init = block(js, "(?m)^function v2sSendLaterInit\\s*\\(\\s*\\)\\s*\\{");
        assertTrue(init.contains("document.addEventListener('input', _v2sSlTickSoon, true);"));
        assertTrue(init.contains("window.addEventListener('resize', _v2sSlTickSoon);"));
        assertFalse("no synchronous tick per keystroke", init.contains("function() { _v2sSlTick(false); }"));
        assertTrue("the forced tick stays", init.contains("_v2sSlTick(true);"));
        String soon = block(js, "(?m)^function _v2sSlTickSoon\\s*\\(\\s*\\)\\s*\\{");
        assertTrue("one pending tick at a time", soon.contains("st.tickSoon ||"));
        assertTrue("nothing while off or hidden", soon.contains("!st.enabled || document.hidden"));
        assertTrue(soon.contains("setTimeout(") && soon.contains("V2S_SL_COALESCE_MS")
                && soon.contains("_v2sSlTick(false)"));
        Matcher m = Pattern.compile("var\\s+V2S_SL_COALESCE_MS\\s*=\\s*(\\d+)\\s*;").matcher(js);
        assertTrue(m.find());
        int ms = Integer.parseInt(m.group(1));
        assertTrue(ms > 0 && ms <= 500);
    }

    // ------------------------------------------------------------------ a11y (8)

    @Test public void theAccessibilityCheckAnswersItsFailureMode() {
        assertTrue("the warning fails open", A11yWarning.isServiceEnabled(null));
        assertTrue(A11yWarning.isServiceEnabled(null, true));
        assertFalse(A11yWarning.isServiceEnabled(null, false));
    }

    @Test public void theSettingsRowUsesTheSharedCheckFailClosed() throws IOException {
        String s = code(SETTINGS);
        assertTrue(s.contains("A11yWarning.isServiceEnabled(c, false)"));
        assertFalse(s.contains("getEnabledAccessibilityServiceList"));
    }

    // ------------------------------------------------------------------ helpers

    private static String code(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*//[^\\n]*", " ");
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
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
