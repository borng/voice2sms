package com.voice2sms.sendlater.web;

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
 * sec-review-1 (I7, I8, R10, R17, R18), read as source text like SourceSafetyTest:
 * every Send Later V2SBridge method checks the main-frame origin before it reaches the hooks
 * (repository, store, startActivity); the main-frame URL is tracked on every main-frame
 * navigation callback; the navigation filter is the OriginGuard host allowlist; the chip bridge
 * calls are bound to the open thread before the repository is read.
 */
public class SendLaterBridgeOriginSourceTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path HOOKS = MAIN.resolve("sendlater/web/SendLaterWebHooks.java");

    private static final String[] SEND_LATER_BRIDGE = {
            "requestSendLater", "sendLaterChipState", "requestSendLaterChipTap",
            "requestSendLaterUndo", "onThreadRead", "onThreadDirectory",
    };

    private static String read(Path p) throws IOException {
        assertTrue("source file missing: " + p, Files.exists(p));
        String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)^\\s*//[^\\n]*", "").replaceAll("\\s//[^\\n\"]*", "");
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

    private static String body(String src, String signature) {
        Matcher m = Pattern.compile(signature).matcher(src);
        if (!m.find()) return null;
        int end = closingBrace(src, m.end() - 1);
        return end < 0 ? null : src.substring(m.start(), end + 1);
    }

    private static String bridge(String activity) {
        int start = activity.indexOf("class V2SBridge");
        assertTrue("V2SBridge class not found", start > 0);
        return activity.substring(start, closingBrace(activity, start) + 1);
    }

    @Test
    public void everySendLaterBridgeMethod_checksTheOriginFirst() throws IOException {
        String bridge = bridge(read(ACTIVITY));
        for (String name : SEND_LATER_BRIDGE) {
            String b = body(bridge, "@JavascriptInterface\\s+public\\s+\\w+\\s+" + name + "\\s*\\([^)]*\\)\\s*\\{");
            assertNotNull("V2SBridge." + name + " missing", b);
            b = b.substring(b.indexOf('{'));
            int guard = b.indexOf("OriginGuard.isTrustedGvUrl(");
            assertTrue(name + " must check OriginGuard.isTrustedGvUrl", guard >= 0);
            int read = b.indexOf("mainFrameUrl");
            assertTrue(name + " must check the main-frame URL", read >= 0 && read <= guard + 60);
            for (String later : new String[]{"sendLaterHooks.", "SendLaterRepository", "ThreadDirectory",
                    "startActivity", "runOnUiThread"}) {
                int at = b.indexOf(later);
                assertTrue(name + ": origin check must come before " + later, at < 0 || guard < at);
            }
        }
        String chip = body(bridge, "@JavascriptInterface\\s+public\\s+String\\s+sendLaterChipState\\s*\\([^)]*\\)\\s*\\{");
        assertTrue("an untrusted origin gets the disabled chip JSON",
                Pattern.compile("isTrustedGvUrl\\(\\w+\\)\\)\\s*(\\{\\s*)?return\\s+ChipState\\.disabled\\(\\)")
                        .matcher(chip).find());
    }

    @Test
    public void mainFrameUrl_isVolatileAndTrackedOnEveryMainFrameCallback() throws IOException {
        String activity = read(ACTIVITY);
        assertTrue("mainFrameUrl must be a volatile String",
                Pattern.compile("private\\s+volatile\\s+String\\s+mainFrameUrl\\s*;").matcher(activity).find());
        String[][] callbacks = {
                {"onPageStarted", "public\\s+void\\s+onPageStarted\\s*\\([^)]*\\)\\s*\\{"},
                {"onPageFinished", "public\\s+void\\s+onPageFinished\\s*\\([^)]*\\)\\s*\\{"},
                {"doUpdateVisitedHistory", "public\\s+void\\s+doUpdateVisitedHistory\\s*\\([^)]*\\)\\s*\\{"},
        };
        for (String[] c : callbacks) {
            String b = body(activity, c[1]);
            assertNotNull(c[0] + " override missing", b);
            assertTrue(c[0] + " must update mainFrameUrl", Pattern.compile("mainFrameUrl\\s*=\\s*\\w+").matcher(b).find());
        }
    }

    @Test
    public void navigationFilter_usesTheExactHostAllowlist() throws IOException {
        String activity = read(ACTIVITY);
        String b = body(activity, "public\\s+boolean\\s+shouldOverrideUrlLoading\\s*\\([^)]*\\)\\s*\\{");
        assertNotNull("shouldOverrideUrlLoading missing", b);
        assertTrue("shouldOverrideUrlLoading must use OriginGuard.isAllowedNavigation",
                b.contains("OriginGuard.isAllowedNavigation("));
        assertFalse("the substring filter must be gone", b.contains(".contains(\"google.com\")"));
        assertFalse("the substring filter must be gone", b.contains(".contains(\"googleapis.com\")"));
        assertFalse("the substring filter must be gone", b.contains(".contains(\"gstatic.com\")"));
    }

    @Test
    public void chipCalls_areBoundToTheOpenThreadBeforeTheRepository() throws IOException {
        String hooks = read(HOOKS);
        String[][] methods = {
                {"bridgeChipState", "public\\s+String\\s+bridgeChipState\\s*\\([^)]*\\)\\s*\\{"},
                {"bridgeChipTap", "public\\s+void\\s+bridgeChipTap\\s*\\([^)]*\\)\\s*\\{"},
        };
        for (String[] m : methods) {
            String b = body(hooks, m[1]);
            assertNotNull(m[0] + " missing", b);
            int bind = b.indexOf("ThreadUrl.isThreadOf(");
            int repo = b.indexOf("SendLaterRepository");
            assertTrue(m[0] + " must call ThreadUrl.isThreadOf", bind > 0);
            assertTrue(m[0] + " must still read the repository", repo > 0);
            assertTrue(m[0] + ": ThreadUrl.isThreadOf must come before the repository", bind < repo);
        }
        String state = body(hooks, methods[0][1]);
        assertTrue("a thread mismatch answers the disabled chip JSON",
                Pattern.compile("isThreadOf\\([^;]*\\)\\)\\s*(\\{\\s*)?return\\s+ChipState\\.disabled\\(\\)")
                        .matcher(state).find());
    }
}
