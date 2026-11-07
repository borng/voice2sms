package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * deliver sec-M1 / sec-L2 wiring (source checks; the logic is BridgeNonceTest): every Send Later
 * and scheduled V2SBridge method takes the nonce first and refuses without it, before any other
 * work, and keeps the origin check; the activity issues the nonce in onPageFinished only on a
 * trusted page, drops it in onPageStarted and when the renderer is gone, and hands it to the page
 * with the module and the scheduled composer; inject.js passes it on every such call;
 * onPageFinished uses OriginGuard (no startsWith), and SendLaterHost.evaluate refuses an
 * untrusted page.
 */
public class BridgeNonceSourceTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path HOOKS = MAIN.resolve("sendlater/web/SendLaterWebHooks.java");
    private static final Path INJECT = Paths.get("src/main/assets/inject.js");

    static final String[] NONCE_METHODS = {
            "requestSendLater", "sendLaterChipState", "requestSendLaterChipTap", "requestSendLaterUndo",
            "onThreadRead", "onThreadDirectory", "onScheduledTyped", "scheduledClickState",
            "scheduledClickStateAt", "scheduledClickStateIn", "onScheduledNoThread", "onScheduledWrongPage",
            "onScheduledDraftPresent", "scheduledDraftStep",
    };

    private static String read(Path p) throws IOException {
        assertTrue("missing: " + p, Files.exists(p));
        String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)^\\s*//[^\\n]*", "").replaceAll("\\s//[^\\n\"']*", "");
    }

    private static String body(String src, String signature) {
        Matcher m = Pattern.compile(signature).matcher(src);
        if (!m.find()) return null;
        int open = src.indexOf('{', m.end() - 1);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(m.start(), i + 1);
        }
        return null;
    }

    private static String bridge(String activity) {
        String b = body(activity, "private\\s+class\\s+V2SBridge\\s*\\{");
        assertNotNull("V2SBridge class not found", b);
        return b;
    }

    @Test
    public void everySendLaterBridgeMethodRequiresTheNonceFirst() throws IOException {
        String bridge = bridge(read(ACTIVITY));
        for (String name : NONCE_METHODS) {
            String b = body(bridge, "@JavascriptInterface\\s+public\\s+\\w+\\s+" + name
                    + "\\s*\\(\\s*String\\s+nonce\\s*,[^)]*\\)\\s*\\{");
            assertNotNull(name + " must take (String nonce, ...)", b);
            b = b.substring(b.indexOf('{') + 1).trim();
            assertTrue(name + ": the first statement checks the nonce and the origin together",
                    Pattern.compile("^(final\\s+String\\s+url\\s*=\\s*mainFrameUrl\\s*;\\s*)?"
                            + "if\\s*\\(\\s*!BRIDGE_NONCE\\.accepts\\(nonce\\)\\s*\\|\\|\\s*"
                            + "!OriginGuard\\.isTrustedGvUrl\\((mainFrameUrl|url)\\)\\s*\\)\\s*return\\b")
                            .matcher(b).find());
        }
        // The non-Send Later methods are unchanged (regular compose / keyboard paths).
        assertTrue(Pattern.compile("public\\s+void\\s+requestShowKeyboard\\s*\\(\\s*\\)").matcher(bridge).find());
    }

    @Test
    public void nonceIsIssuedOnlyOnATrustedFinishAndDroppedOnStartAndRendererGone() throws IOException {
        String a = read(ACTIVITY);
        assertTrue("one process-wide nonce for the singleton WebView",
                Pattern.compile("private\\s+static\\s+final\\s+BridgeNonce\\s+BRIDGE_NONCE\\s*=\\s*new\\s+BridgeNonce\\(\\)")
                        .matcher(a).find());
        String started = body(a, "public\\s+void\\s+onPageStarted\\s*\\([^)]*\\)\\s*\\{");
        assertNotNull(started);
        assertTrue("onPageStarted invalidates the nonce", started.contains("BRIDGE_NONCE.invalidate();"));
        String finished = body(a, "public\\s+void\\s+onPageFinished\\s*\\([^)]*\\)\\s*\\{");
        assertNotNull(finished);
        int issue = finished.indexOf("BRIDGE_NONCE.issueFor(view.getUrl())");
        assertTrue("onPageFinished issues for the WebView's own URL", issue > 0);
        assertTrue("issued only behind the OriginGuard decision",
                finished.lastIndexOf("if (trustedGv)", issue) > 0
                        && finished.indexOf("OriginGuard.isTrustedGvUrl(url)") < issue);
        int install = finished.indexOf("installSendLater()");
        assertTrue("issued before the module (which carries it) is installed", install > issue);
        String gone = body(a, "public\\s+boolean\\s+onRenderProcessGone\\s*\\([^)]*\\)\\s*\\{");
        assertNotNull(gone);
        assertTrue("renderer gone invalidates the nonce", gone.contains("BRIDGE_NONCE.invalidate();"));
    }

    @Test
    public void theNonceReachesThePageWithTheModuleAndTheScheduledComposer() throws IOException {
        String hooks = read(HOOKS);
        String module = body(hooks, "public\\s+String\\s+moduleScript\\s*\\(\\s*\\)\\s*\\{");
        assertNotNull(module);
        int stmt = module.indexOf("BridgeNonce.pageStatement(host.bridgeNonce())");
        assertTrue("the module script sets the nonce", stmt > 0);
        assertTrue("before v2sSendLaterInit runs", stmt < module.indexOf("v2sSendLaterInit();"));

        String a = read(ACTIVITY);
        String sched = body(a, "private\\s+void\\s+injectScheduledComposer\\s*\\(\\s*\\)\\s*\\{");
        assertNotNull(sched);
        int s = sched.indexOf("BridgeNonce.pageStatement(BRIDGE_NONCE.current())");
        assertTrue("the scheduled composer sets the nonce", s > 0);
        assertTrue("before v2sScheduledCompose runs", s < sched.indexOf("v2sScheduledCompose("));
        assertTrue("behind the trusted-page check",
                sched.indexOf("OriginGuard.isTrustedGvUrl(webView.getUrl())") < s);
        assertTrue(a.contains("public String bridgeNonce()") && a.contains("return BRIDGE_NONCE.current();"));
    }

    @Test
    public void injectJsPassesTheNonceOnEveryBridgeCallAndKeepsItAcrossReEvaluation() throws IOException {
        String js = new String(Files.readAllBytes(INJECT), StandardCharsets.UTF_8);
        assertTrue("declared without an initializer (re-evaluation keeps it)",
                Pattern.compile("(?m)^var\\s+_v2sBridgeNonce\\s*;").matcher(js).find());
        assertFalse("never reset by the script itself",
                Pattern.compile("_v2sBridgeNonce\\s*=").matcher(js).find());
        for (String m : NONCE_METHODS) {
            Matcher call = Pattern.compile("V2SBridge\\." + m + "\\(([^,)]*)").matcher(js);
            boolean any = false;
            while (call.find()) {
                any = true;
                assertTrue("V2SBridge." + m + " must pass _v2sBridgeNonce first",
                        call.group(1).trim().equals("_v2sBridgeNonce"));
            }
            assertTrue("inject.js calls V2SBridge." + m, any);
        }
    }

    @Test
    public void onPageFinishedUsesOriginGuardNotAPrefix() throws IOException {
        String a = read(ACTIVITY);
        assertFalse("sec-L2: no startsWith(\"https://voice.google.com\") left",
                a.contains("startsWith(\"https://voice.google.com"));
        String finished = body(a, "public\\s+void\\s+onPageFinished\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(finished.contains("final boolean trustedGv = OriginGuard.isTrustedGvUrl(url);"));
        assertTrue(finished.contains("if (trustedGv && sendLaterHooks != null) installSendLater();"));
        assertTrue(finished.contains("if (trustedGv && !injected && recipient != null) {"));
        assertTrue(finished.contains("} else if (trustedGv && !injected) {"));
    }

    @Test
    public void sendLaterHostEvaluateRefusesAnUntrustedPage() throws IOException {
        String a = read(ACTIVITY);
        String host = body(a, "private\\s+class\\s+SendLaterHost\\s+implements\\s+SendLaterWebHooks\\.Host\\s*\\{");
        assertNotNull(host);
        String eval = body(host, "public\\s+void\\s+evaluate\\s*\\(\\s*String\\s+js\\s*\\)\\s*\\{");
        assertNotNull(eval);
        int guard = eval.indexOf("OriginGuard.isTrustedGvUrl(webView.getUrl())");
        int run = eval.indexOf("webView.evaluateJavascript(");
        assertTrue("sec-L2: evaluate checks the page origin", guard > 0);
        assertTrue("before evaluating", guard < run);
        assertTrue("inside the alive-guarded UI runnable",
                eval.indexOf("isActivityAlive()") > 0 && eval.indexOf("isActivityAlive()") < guard);
    }
}
