package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Acceptance (webview-capture, R4/R10/R11/R17/R18/I8): Java-side wiring, read as source text
 * (same approach as SourceSafetyTest). Pinned interfaces: V2SBridge.requestSendLater(String),
 * sendLaterChipState(String) -> String, requestSendLaterChipTap(String), requestSendLaterUndo(long),
 * onThreadRead(String), onThreadDirectory(String); GVoiceWebViewActivity.readThread(String, int,
 * ThreadReadCallback); thin activity edits delegating to com.voice2sms.sendlater.web.SendLaterWebHooks.
 */
public class SendLaterWebJavaSourceTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path WEB_PKG = MAIN.resolve("sendlater/web");
    private static final Path HOOKS = WEB_PKG.resolve("SendLaterWebHooks.java");

    private static String read(Path p) throws IOException {
        assertTrue("source file missing: " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)^\\s*//[^\\n]*", "").replaceAll("\\s//[^\\n\"]*", "");
    }

    /** Activity plus every source file in sendlater/web. */
    private static String webSources() throws IOException {
        StringBuilder sb = new StringBuilder(stripComments(read(ACTIVITY)));
        sb.append('\n').append(webPackageOnly());
        return sb.toString();
    }

    private static String webPackageOnly() throws IOException {
        File dir = WEB_PKG.toFile();
        assertTrue("package com.voice2sms.sendlater.web missing", dir.isDirectory());
        StringBuilder sb = new StringBuilder();
        File[] files = dir.listFiles();
        assertNotNull(files);
        for (File f : files) {
            if (f.getName().endsWith(".java")) sb.append(stripComments(read(f.toPath()))).append('\n');
        }
        return sb.toString();
    }

    private static int closingBrace(String src, int from) {
        int open = src.indexOf('{', from);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static String bridge(String activity) {
        int start = activity.indexOf("class V2SBridge");
        assertTrue("V2SBridge class not found", start > 0);
        return activity.substring(start, closingBrace(activity, start) + 1);
    }

    /** Body of the first method declaration matching {@code signature} (a regex), or null. */
    private static String body(String src, String signature) {
        Matcher m = Pattern.compile(signature).matcher(src);
        if (!m.find()) return null;
        int end = closingBrace(src, m.end());
        return end < 0 ? null : src.substring(m.start(), end + 1);
    }

    private static final String[][] BRIDGE_METHODS = {
            {"requestSendLater", "String"},
            {"sendLaterChipState", "String"},
            {"requestSendLaterChipTap", "String"},
            {"requestSendLaterUndo", "long"},
            {"onThreadRead", "String"},
            {"onThreadDirectory", "String"},
    };

    private static String sig(String name, String argType) {
        // deliver sec-M1: every Send Later bridge method takes the page nonce first.
        return "@JavascriptInterface\\s+public\\s+[\\w<>]+\\s+" + name
                + "\\s*\\(\\s*(final\\s+)?String\\s+nonce\\s*,\\s*(final\\s+)?" + argType
                + "\\s+\\w+\\s*\\)\\s*\\{";
    }

    @Test
    public void v2sBridge_exposesThePinnedJavascriptInterfaceMethods() throws IOException {
        String bridge = bridge(stripComments(read(ACTIVITY)));
        for (String[] m : BRIDGE_METHODS) {
            assertNotNull("V2SBridge lacks @JavascriptInterface " + m[0] + "(" + m[1] + ")",
                    body(bridge, sig(m[0], m[1])));
        }
        assertTrue("sendLaterChipState(String e164) must return String (chip JSON)",
                Pattern.compile("@JavascriptInterface\\s+public\\s+String\\s+sendLaterChipState\\s*\\(")
                        .matcher(bridge).find());
    }

    @Test
    public void newBridgeMethods_touchWebViewOnlyThroughGuardedRunOnUiThread() throws IOException {
        String bridge = bridge(stripComments(read(ACTIVITY)));
        for (String[] m : BRIDGE_METHODS) {
            String b = body(bridge, sig(m[0], m[1]));
            assertNotNull(m[0], b);
            if (Pattern.compile("\\bwebView\\b|evaluateJavascript").matcher(b).find()) {
                assertTrue(m[0] + " touches the WebView off the UI thread (binder thread)",
                        b.contains("runOnUiThread("));
                int runs = b.split("runOnUiThread\\(", -1).length - 1;
                int guards = b.split("isActivityAlive\\(\\)", -1).length - 1;
                assertTrue(m[0] + ": every runOnUiThread needs isActivityAlive()", guards >= runs);
            }
            assertFalse(m[0] + " must not use force_auto_send",
                    b.contains("force_auto_send") || b.contains("EXTRA_FORCE_AUTO_SEND"));
        }
    }

    @Test
    public void readThread_hasThePinnedSignatureAndIsGuarded() throws IOException {
        String activity = stripComments(read(ACTIVITY));
        String b = body(activity,
                "public\\s+(static\\s+)?void\\s+readThread\\s*\\(\\s*(final\\s+)?String\\s+\\w+\\s*,\\s*"
                        + "(final\\s+)?int\\s+\\w+\\s*,\\s*(final\\s+)?[\\w.]*ThreadReadCallback\\s+\\w+\\s*\\)\\s*\\{");
        assertNotNull("GVoiceWebViewActivity.readThread(String e164, int limit, ThreadReadCallback cb) missing", b);
        assertTrue("readThread must check isActivityAlive() before using the WebView",
                b.contains("isActivityAlive()"));
        assertFalse("readThread must never navigate as a side effect", b.contains("loadUrl("));
        assertTrue("ThreadReadCallback interface not declared",
                Pattern.compile("interface\\s+ThreadReadCallback\\b").matcher(webSources()).find());
    }

    @Test
    public void onPageFinished_installsTheSendLaterModule() throws IOException {
        String activity = stripComments(read(ACTIVITY));
        String b = body(activity, "public\\s+void\\s+onPageFinished\\s*\\(\\s*WebView\\s+\\w+\\s*,\\s*String\\s+\\w+\\s*\\)\\s*\\{");
        assertNotNull("onPageFinished not found", b);
        assertTrue("onPageFinished must install Send Later (SendLaterWebHooks / v2sSendLaterInit)",
                b.contains("SendLaterWebHooks") || b.contains("sendLaterHooks") || b.contains("v2sSendLaterInit"));
        assertTrue("nobody calls v2sSendLaterInit()", webSources().contains("v2sSendLaterInit("));
    }

    @Test
    public void activityDelegatesToSendLaterWebHooks() throws IOException {
        assertTrue("com.voice2sms.sendlater.web.SendLaterWebHooks missing", Files.exists(HOOKS));
        assertTrue("GVoiceWebViewActivity must delegate to SendLaterWebHooks",
                stripComments(read(ACTIVITY)).contains("SendLaterWebHooks"));
    }

    @Test
    public void stateReceiver_registeredWhileResumed_notExported_andRefreshesTheChip() throws IOException {
        String all = webSources();
        for (String needle : new String[]{"SendLaterIntents.ACTION_STATE_CHANGED", "SendLaterIntents.ACTION_SCHEDULED",
                "registerReceiver", "RECEIVER_NOT_EXPORTED", "unregisterReceiver", "v2sSendLaterRefresh"}) {
            assertTrue("Send Later WebView wiring lacks " + needle, all.contains(needle));
        }
        String activity = stripComments(read(ACTIVITY));
        String onResume = body(activity, "protected\\s+void\\s+onResume\\s*\\(\\s*\\)\\s*\\{");
        String onPause = body(activity, "protected\\s+void\\s+onPause\\s*\\(\\s*\\)\\s*\\{");
        assertNotNull(onResume);
        assertNotNull(onPause);
        assertTrue("onResume must register the Send Later receiver",
                Pattern.compile("(?i)sendlater").matcher(onResume).find());
        assertTrue("onPause must unregister the Send Later receiver",
                Pattern.compile("(?i)sendlater").matcher(onPause).find());
    }

    @Test
    public void capture_opensTheSheetAndThreadDirectoryIsUpserted() throws IOException {
        String all = webSources();
        assertTrue("capture must start the sheet with SendLaterIntents.ACTION_CAPTURE",
                all.contains("SendLaterIntents.ACTION_CAPTURE"));
        assertTrue("onThreadDirectory must upsert the ThreadDirectory (R4)",
                all.contains("ThreadDirectory") && all.contains(".upsert("));
        assertTrue("requestSendLater must validate through CapturePayload",
                all.contains("CapturePayload.parse("));
    }

    @Test
    public void webPackage_obeysSendLaterCodeConstraints() throws IOException {
        String pkg = webPackageOnly();
        for (String banned : new String[]{"force_auto_send", "EXTRA_FORCE_AUTO_SEND", "AutoSendRateLimiter",
                "java.time.", "List.of(", "Map.of(", "Set.of("}) {
            assertFalse("sendlater/web must not use " + banned, pkg.contains(banned));
        }
        Matcher log = Pattern.compile("Log\\.[a-z]+\\s*\\(([^;]*)\\);").matcher(pkg);
        while (log.find()) {
            String args = log.group(1);
            assertFalse("sendlater/web logs draft/name content: " + args,
                    Pattern.compile("(?i)draft|displayName|getText\\(|\\bbody\\b").matcher(args).find());
        }
    }

    @Test
    public void autoSendChokepoint_isUnchanged() throws IOException {
        String activity = stripComments(read(ACTIVITY));
        String b = body(activity, "private\\s+void\\s+injectComposer\\s*\\(\\s*\\)\\s*\\{");
        assertNotNull("injectComposer() missing", b);
        assertTrue("injectComposer must still gate auto-send on AutoSendRateLimiter.allow()",
                b.contains("AutoSendRateLimiter.allow()"));
        assertFalse("injectComposer must not call into Send Later",
                Pattern.compile("(?i)sendlater").matcher(b).find());
    }
}
