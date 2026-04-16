package com.voice2sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Source-level regression guards for known crash / race-condition patterns.
 *
 * These tests read the Java source files as text and assert structural
 * properties. They run as plain JVM JUnit (no Android/Robolectric), so
 * they're fast and require no emulator.
 *
 * Each test corresponds to a bug we've hit on-device. If you find yourself
 * tempted to "work around" one of these tests, the proper move is to
 * document the new pattern here — don't silence the guard.
 */
public class SourceSafetyTest {

    private static final Path MAIN =
            Paths.get("src/main/java/com/voice2sms");

    private String read(String relative) throws IOException {
        Path p = MAIN.resolve(relative);
        assertTrue("source file missing: " + p, Files.exists(p));
        return new String(Files.readAllBytes(p));
    }

    /**
     * Regression: WV.h22.run NPE (pre-v1.3.0).
     *
     * Voice2SmsApplication.onCreate previously called
     * profile.prefetchUrlAsync(GV_MESSAGES_URL, ...). The prefetch's internal
     * chromium completion Runnable NPE'd 1-2ms after onResult fired because
     * the Activity had already issued loadUrl() on the same URL. Reproduced
     * deterministically on WebView 148 + androidx.webkit 1.15.0.
     *
     * Guard: prefetchUrlAsync must not appear in Voice2SmsApplication.
     */
    @Test
    public void voice2smsApplication_doesNotCallPrefetchUrlAsync() throws IOException {
        String src = stripComments(read("Voice2SmsApplication.java"));
        assertFalse(
                "Voice2SmsApplication re-introduced prefetchUrlAsync — see SourceSafetyTest javadoc",
                src.contains("prefetchUrlAsync"));
        assertFalse(
                "CancellationSignal import implies prefetch is back",
                src.contains("import android.os.CancellationSignal"));
        assertFalse(
                "OutcomeReceiverCompat import implies prefetch is back",
                src.contains("OutcomeReceiverCompat"));
    }

    /**
     * Regression guard: V2SBridge JS->Java callbacks run after Activity is
     * destroyed or after onRenderProcessGone nulls the WebView. Every
     * runOnUiThread body inside V2SBridge must short-circuit if the Activity
     * isn't alive.
     *
     * This test counts runOnUiThread blocks inside the V2SBridge class and
     * verifies isActivityAlive() appears at least once per block.
     */
    @Test
    public void v2sBridge_runOnUiThread_checksActivityAlive() throws IOException {
        String src = read("GVoiceWebViewActivity.java");

        int bridgeStart = src.indexOf("private class V2SBridge");
        assertTrue("V2SBridge class not found", bridgeStart > 0);
        int bridgeEnd = findClosingBrace(src, bridgeStart);
        String bridge = src.substring(bridgeStart, bridgeEnd);

        int runs = countMatches(bridge, "runOnUiThread(");
        int guards = countMatches(bridge, "isActivityAlive()");

        assertTrue(
                "V2SBridge has " + runs + " runOnUiThread blocks but only " + guards
                        + " isActivityAlive guards — see R1 in audit",
                guards >= runs);
    }

    /**
     * typeIntoWebView and typeViaInputConnection post delayed Handlers that
     * capture webView. Activity destruction between post and run → NPE. Each
     * method body must guard with isActivityAlive().
     */
    @Test
    public void typingPaths_checkActivityAlive() throws IOException {
        String src = read("GVoiceWebViewActivity.java");
        String[] methods = {"typeIntoWebView", "typeViaInputConnection", "tapAndType"};
        for (String m : methods) {
            String body = extractMethodBody(src, m);
            assertNotNull("method body not found: " + m, body);
            assertTrue(
                    m + " does not call isActivityAlive() — see R3/R4 in audit",
                    body.contains("isActivityAlive()"));
        }
    }

    /**
     * AccountManager auth callback runs on arbitrary thread and posts to UI;
     * if Activity finished in between, webView.loadUrl NPEs. Guard is the
     * isActivityAlive() check inside the runOnUiThread body.
     */
    @Test
    public void requestAuthToken_callbackChecksActivityAlive() throws IOException {
        String src = read("GVoiceWebViewActivity.java");
        String body = extractMethodBody(src, "requestAuthToken");
        assertNotNull(body);
        assertTrue(
                "requestAuthToken auth-callback is missing isActivityAlive() — see R2 in audit",
                body.contains("isActivityAlive()"));
    }

    /**
     * loadGoogleVoice is called from callbacks that may fire after the Activity
     * is torn down. Simple webView null-check required.
     */
    @Test
    public void loadGoogleVoice_nullChecksWebView() throws IOException {
        String src = read("GVoiceWebViewActivity.java");
        String body = extractMethodBody(src, "loadGoogleVoice");
        assertNotNull(body);
        assertTrue(
                "loadGoogleVoice is missing webView null-check",
                body.contains("webView == null"));
    }

    /**
     * RichContentWebView advertises image MIME types so GBoard shows the
     * sticker panel. Regression guard: the IMAGE_MIME_TYPES array must cover
     * at least png, gif, jpeg, webp — removing any of these breaks a GBoard
     * code path already seen in the wild.
     */
    @Test
    public void richContentWebView_advertisesExpectedMimeTypes() throws IOException {
        String src = read("RichContentWebView.java");
        // Anchor to the IMAGE_MIME_TYPES array literal so a stray MIME string
        // in a log message or comment can't satisfy the test.
        Pattern arr = Pattern.compile(
                "IMAGE_MIME_TYPES\\s*=\\s*\\{([^}]*)\\}", Pattern.DOTALL);
        Matcher m = arr.matcher(src);
        assertTrue("IMAGE_MIME_TYPES array declaration not found", m.find());
        String body = m.group(1);
        for (String mime : new String[]{"image/png", "image/gif", "image/jpeg", "image/webp"}) {
            assertTrue("RichContentWebView.IMAGE_MIME_TYPES lost " + mime,
                    body.contains("\"" + mime + "\""));
        }
    }

    // --- helpers ---

    private static int countMatches(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /**
     * Given a source string and the index of a brace-opened block header,
     * returns the index just after the matching closing brace.
     */
    private static int findClosingBrace(String src, int from) {
        int open = src.indexOf('{', from);
        assertTrue("no opening brace after index " + from, open >= 0);
        int depth = 1, i = open + 1;
        while (i < src.length() && depth > 0) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        assertEquals("unbalanced braces starting at " + open, 0, depth);
        return i;
    }

    /**
     * Extract the body of a method by locating the first occurrence of
     * "methodName(" that is preceded by a Java modifier keyword on the same
     * line (private/public/protected/void/etc), then walking to the matching
     * close brace. Returns null if not found.
     */
    private static String extractMethodBody(String src, String methodName) {
        String needle = " " + methodName + "(";
        int from = 0;
        while (true) {
            int idx = src.indexOf(needle, from);
            if (idx < 0) return null;
            int lineStart = src.lastIndexOf('\n', idx) + 1;
            String line = src.substring(lineStart, idx);
            // Accept only declarations, not calls. Declarations have a return
            // type plus a modifier or type name before the method name.
            if (line.matches("\\s*(private|public|protected)\\s+.*")
                    || line.matches("\\s*(static|final)\\s+.*")
                    || line.matches("\\s*(void|boolean|byte|short|int|long|float|double|char|String|[A-Z]\\w*)\\s+.*")) {
                int end = findClosingBrace(src, lineStart);
                return src.substring(lineStart, end);
            }
            from = idx + needle.length();
        }
    }

    // Strip line and block comments so source text checks do not false-positive
    // on documentation that mentions an API name.
    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("//[^\\n]*", "");
    }
}
