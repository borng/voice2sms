package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * webview-feature-off (integration-gate F5, F7, F8; I8, R40, R11). Read as source text like
 * SourceSafetyTest, except the navigation allowlist, which is exercised directly:
 * <ul>
 *   <li>no Send Later module is installed on a GV page while the switch is off, and turning it on
 *       installs it on the live page;</li>
 *   <li>inject.js has no fast poll: its only interval is >= 2 s, runs only while on with a 1:1
 *       thread open, and page events do nothing while off;</li>
 *   <li>the activity's composer probe runs only while Send Later is on;</li>
 *   <li>WebView Undo cancels through SendLaterEngine.onCancel;</li>
 *   <li>accounts.youtube.com (Google sign-in hop) is an allowed navigation host, exactly.</li>
 * </ul>
 */
public class WebviewFeatureOffTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path HOOKS = MAIN.resolve("sendlater/web/SendLaterWebHooks.java");

    private static String read(Path p) throws IOException {
        assertTrue("source file missing: " + p, Files.exists(p));
        String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)^\\s*//[^\\n]*", "").replaceAll("\\s//[^\\n\"]*", "");
    }

    /** From the first match of {@code signature} to its matching closing brace. */
    private static String body(String src, String signature) {
        Matcher m = Pattern.compile(signature).matcher(src);
        assertTrue("not found: " + signature, m.find());
        int open = src.indexOf('{', m.end() - 1);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(m.start(), i + 1);
        }
        throw new AssertionError("unbalanced: " + signature);
    }

    // ------------------------------------------------------------------ module install (F5)

    @Test
    public void moduleScript_isNullWhileSendLaterIsOff() throws IOException {
        String b = body(read(HOOKS), "public String moduleScript\\s*\\(\\s*\\)");
        int gate = b.indexOf("if (!SendLaterSettings.isEnabled(app)) return null;");
        assertTrue("moduleScript() must return null while Send Later is off", gate >= 0);
        int assets = b.indexOf("getAssets()");
        assertTrue("the switch is checked before inject.js is read or returned",
                assets > gate && b.indexOf("return moduleScript") > gate);
    }

    @Test
    public void switchingOn_installsTheModuleOnTheLivePage() throws IOException {
        String hooks = read(HOOKS);
        String sync = body(hooks, "private void syncModule\\s*\\(\\s*\\)");
        assertTrue(sync.contains("SendLaterSettings.isEnabled(app)"));
        assertTrue("install only on a trusted GV page", sync.contains("OriginGuard.isTrustedGvUrl(host.currentUrl())"));
        assertTrue("install only when this page load lacks the module", sync.contains("!moduleOnPage"));
        int script = sync.indexOf("moduleScript()");
        int eval = sync.indexOf("host.evaluate(");
        assertTrue("the module is evaluated on the live page", script >= 0 && eval > script);
        assertTrue("the page re-reads the chip state (and stops its tick when off)",
                sync.contains("refreshChip()"));
        assertTrue("the activity hears the switch (composer probe)", sync.contains("host.onSendLaterSwitch("));

        String receiver = body(hooks, "private final BroadcastReceiver receiver");
        int changed = receiver.indexOf("ACTION_STATE_CHANGED");
        assertTrue("ACTION_STATE_CHANGED syncs the module",
                changed >= 0 && receiver.indexOf("syncModule()", changed) > changed);
        assertTrue("resume syncs the module (switch changed in Settings while paused)",
                body(hooks, "public void onResume\\s*\\(\\s*\\)").contains("syncModule()"));
    }

    // ------------------------------------------------------------------ page tick (F5, R40)

    @Test
    public void injectJs_hasNoFastPollAndTheTickStopsWhileOff() throws IOException {
        JsSource js = JsSource.injectJs();
        Matcher ms = Pattern.compile("var\\s+V2S_SL_TICK_MS\\s*=\\s*(\\d+)\\s*;").matcher(js.code);
        assertTrue("V2S_SL_TICK_MS constant missing", ms.find());
        assertTrue("the Send Later timer is >= 2 s", Integer.parseInt(ms.group(1)) >= 2000);

        JsSource.Fn init = js.first("v2sSendLaterInit");
        assertNotNull(init);
        assertFalse("v2sSendLaterInit must not start an interval", init.body.contains("setInterval"));

        // The module's only setInterval is the scheduled tick.
        Matcher si = Pattern.compile("setInterval\\s*\\(").matcher(js.code);
        int moduleStart = js.code.indexOf("function v2sSendLaterInit");
        int inModule = 0;
        while (si.find()) {
            if (si.start() < moduleStart) continue;
            inModule++;
            JsSource.Fn f = js.enclosing(si.start());
            assertNotNull(f);
            assertEquals("only _v2sSlSchedule may start a Send Later interval", "_v2sSlSchedule", f.name);
        }
        assertEquals(1, inModule);
        JsSource.Fn sched = js.first("_v2sSlSchedule");
        assertTrue("the timer runs only while on with a 1:1 thread open",
                sched.body.contains("st.enabled && st.e164"));
        assertTrue("the timer is cleared otherwise", sched.body.contains("clearInterval(st.timer)"));
        assertTrue("the chip answer drives the timer",
                js.first("_v2sSlRefreshChip").body.contains("_v2sSlSchedule(st)"));

        JsSource.Fn tick = js.first("_v2sSlTick");
        assertTrue("page events do nothing while Send Later is off",
                tick.body.contains("if (!force && (!st.enabled || document.hidden)) return;"));
        assertTrue("Java's refresh forces a tick (how an off page learns it is on)",
                js.code.contains("_v2sSlTick(true)"));
    }

    // ------------------------------------------------------------------ composer probe (F5, R40)

    @Test
    public void composerProbe_runsOnlyWhileSendLaterIsOn() throws IOException {
        String act = read(ACTIVITY);
        int r = act.indexOf("Runnable composerProbe");
        assertTrue(r >= 0);
        String run = act.substring(r, act.indexOf("};", r));
        int gate = run.indexOf("SendLaterSettings.isEnabled(");
        int eval = run.indexOf("webView.evaluateJavascript(");
        int repost = run.indexOf("postDelayed(");
        assertTrue("the probe checks the switch before touching the page", gate >= 0 && eval > gate);
        int ret = run.indexOf("return;", gate);
        assertTrue("with the switch off the probe stops (no repost)", ret > gate && ret < repost);

        String sw = body(act, "public void onSendLaterSwitch\\s*\\(\\s*boolean enabled\\s*\\)");
        assertTrue(sw.contains("composerProbeHandler.removeCallbacks(composerProbe)"));
        int on = sw.indexOf("if (enabled");
        assertTrue("restart only when on", on >= 0 && sw.indexOf("composerProbeHandler.post(composerProbe)") > on);
    }

    // ------------------------------------------------------------------ undo (F7, R11)

    @Test
    public void webViewUndo_cancelsThroughTheEngine() throws IOException {
        String b = body(read(HOOKS), "public boolean bridgeUndo\\s*\\(");
        assertTrue("Undo must cancel via SendLaterEngine.onCancel", b.contains("SendLaterEngine.onCancel(app, messageId)"));
        assertFalse("Undo must not cancel the row behind the engine's back", b.contains("repo.cancel("));
        int cancel = b.indexOf("SendLaterEngine.onCancel(");
        assertTrue("the result is read back as CANCELED",
                b.indexOf("Status.CANCELED", cancel) > cancel);
    }

    // ------------------------------------------------------------------ navigation (F8)

    @Test
    public void accountsYoutube_isAnAllowedNavigationHost_exactly() {
        assertTrue(OriginGuard.isAllowedNavigation("https", "accounts.youtube.com"));
        assertTrue(OriginGuard.isAllowedNavigation("HTTPS", "ACCOUNTS.YOUTUBE.COM"));
        String[] blocked = {"youtube.com", "www.youtube.com", "m.youtube.com", "evilaccounts.youtube.com",
                "accounts.youtube.com.evil.example", "x.accounts.youtube.com"};
        for (String h : blocked) assertFalse(h, OriginGuard.isAllowedNavigation("https", h));
        assertFalse(OriginGuard.isAllowedNavigation("http", "accounts.youtube.com"));
    }
}
