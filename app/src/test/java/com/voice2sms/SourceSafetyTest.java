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

    /**
     * The Wearable listener MUST remain an intent trampoline — it must hand off
     * to SmsHandlerActivity via ACTION_SENDTO and must NOT reach into the GV
     * WebView stack directly. Touching GVoiceWebViewActivity / V2SBridge /
     * the singleton WebView from a background WearableListenerService would
     * re-introduce the exact lifecycle bugs the singleton guard was designed
     * to prevent (WebView nulled by onRenderProcessGone while the listener
     * still holds references).
     */
    @Test
    public void wearListener_isIntentTrampoline_noWebViewCoupling() throws IOException {
        // Scan both the service and the validator — the trampoline rule applies to
        // the whole wear/ module, not just the service file. Any future extension
        // that couples the Wear layer to WebView internals would bypass the
        // singleton WebView guards and re-introduce the lifecycle bugs they prevent.
        String[] forbidden = {
                "GVoiceWebViewActivity",
                "V2SBridge",
                "Voice2SmsApplication",
                "setWebView",
                "getWebView"};
        for (String file : new String[]{
                "wear/WearSmsListenerService.java",
                "wear/WearPayloadValidator.java"}) {
            String src = stripComments(read(file));
            for (String f : forbidden) {
                assertFalse(
                        file + " references " + f
                                + " — wear/ must stay decoupled from the WebView stack",
                        src.contains(f));
            }
        }
        String service = stripComments(read("wear/WearSmsListenerService.java"));
        assertTrue(
                "WearSmsListenerService must hand off to SmsHandlerActivity",
                service.contains("SmsHandlerActivity"));
        assertTrue(
                "WearSmsListenerService must build an ACTION_SENDTO intent",
                service.contains("ACTION_SENDTO"));
    }

    /**
     * requestId is the deduplication key across the Wear ↔ phone protocol.
     * If it becomes optional or the dedup map disappears, duplicate MessageClient
     * deliveries or user double-taps on the watch will produce duplicate SMS
     * dispatches. Adversarial debate (Codex + Gemini) called this out explicitly.
     */
    @Test
    public void wearListener_requestId_mandatoryWithDedup() throws IOException {
        String validator = stripComments(read("wear/WearPayloadValidator.java"));
        assertTrue(
                "WearPayloadValidator must enforce requestId via REQUEST_ID_RE",
                validator.contains("REQUEST_ID_RE"));
        assertTrue(
                "WearPayloadValidator must reject missing_requestId",
                validator.contains("missing_requestId"));

        String service = stripComments(read("wear/WearSmsListenerService.java"));
        assertTrue(
                "WearSmsListenerService must maintain a seen-set for requestId dedup",
                service.contains("SEEN") && service.contains("putIfAbsent"));
        assertTrue(
                "WearSmsListenerService must emit a duplicate ack status",
                service.contains("STATUS_DUPLICATE"));
    }

    /**
     * Background Activity Launch restrictions can block startActivity() from a
     * WearableListenerService on Android 14+. The listener must post a
     * user-visible notification alongside the launch attempt so the user has
     * a recovery path when BAL kicks in. Removing this fallback would let
     * watch sends silently fail while acks still report "dispatched".
     */
    @Test
    public void wearListener_alwaysPostsNotificationFallback() throws IOException {
        String src = stripComments(read("wear/WearSmsListenerService.java"));
        assertTrue(
                "WearSmsListenerService must post a notification (BAL fallback)",
                src.contains("postSendNotification"));
        assertTrue(
                "Notification must be created with a channel id",
                src.contains("NOTIF_CHANNEL_ID"));
        // Ensure the notification carries a PendingIntent back into SmsHandlerActivity
        // via the SAME intent shape startActivity uses.
        assertTrue(
                "Notification content intent must route to SmsHandlerActivity",
                src.contains("PendingIntent.getActivity"));
        // Android 13+ requires POST_NOTIFICATIONS runtime permission. Without the
        // guard, nm.notify() silently no-ops and the BAL-fallback promise is broken.
        assertTrue(
                "postSendNotification must guard on POST_NOTIFICATIONS for API 33+",
                src.contains("POST_NOTIFICATIONS"));
        assertTrue(
                "postSendNotification must check permission before posting",
                src.contains("checkSelfPermission"));
    }

    /**
     * Watch-origin requests represent user intent confirmed on the watch UI
     * (Assistant recognized the command, user tapped Send). The phone pipeline
     * must honor that confirmation rather than prompt a second time in the
     * WebView — otherwise the watch UX is a lie ("Sent" on watch, draft sitting
     * open on phone). The trampoline intent must include force_auto_send=true.
     */
    @Test
    public void wearListener_setsForceAutoSend() throws IOException {
        String src = stripComments(read("wear/WearSmsListenerService.java"));
        assertTrue(
                "buildSendIntent must set EXTRA_FORCE_AUTO_SEND=true so the watch-confirmed "
                        + "send actually sends without a second confirmation in the WebView",
                src.contains("EXTRA_FORCE_AUTO_SEND"));

        // And the constant must exist in SmsHandlerActivity — this is the contract
        // for cross-component auto-send opt-in. The Wear listener, GeminiSmsInterceptService,
        // and RespondViaMessageService all reference the same flag.
        String handler = stripComments(read("SmsHandlerActivity.java"));
        assertTrue(
                "SmsHandlerActivity must expose EXTRA_FORCE_AUTO_SEND as a constant",
                handler.contains("EXTRA_FORCE_AUTO_SEND"));
    }

    /**
     * DataClient items survive process death and re-sync after BT reconnect. If
     * we delete with the local-node authority, the watch-authored copy persists
     * and re-triggers onDataChanged after reconnect — re-firing the SMS send.
     * The delete must use a wildcard authority so all replicas are cleaned up.
     */
    @Test
    public void wearListener_consumeDataItem_wildcardAuthority() throws IOException {
        String src = stripComments(read("wear/WearSmsListenerService.java"));
        assertTrue(
                "consumeDataItem must delete with wildcard authority to prevent "
                        + "re-sync after BT reconnect",
                src.contains("authority(\"*\")"));
        assertTrue(
                "consumeDataItem must use FILTER_LITERAL with the wildcard",
                src.contains("FILTER_LITERAL"));
    }

    /**
     * SettingsActivity surfaces version / build / commit / date for bug-report
     * triage. The build.gradle injects GIT_SHA and BUILD_DATE as BuildConfig
     * fields — if someone deletes the gradle lines without updating the
     * settings code (or vice versa), the compile breaks. This guard prevents
     * the subtler regression of silently reverting to just version+build.
     */
    @Test
    public void settings_versionStamp_includesShaAndDate() throws IOException {
        String src = stripComments(read("SettingsActivity.java"));
        assertTrue(
                "SettingsActivity version stamp must reference BuildConfig.GIT_SHA",
                src.contains("BuildConfig.GIT_SHA"));
        assertTrue(
                "SettingsActivity version stamp must reference BuildConfig.BUILD_DATE",
                src.contains("BuildConfig.BUILD_DATE"));
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
