package com.voice2sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.web.OriginGuard;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * LIVE-F2 / F8: sign-in without the AccountManager weblogin consent loop. A signed-out WebView
 * lands on Google's sign-in page (continue to GV messages), never on the marketing page.
 */
public class LiveAuthTest {

    private static final Path SRC = Paths.get("src/main/java/com/voice2sms/GVoiceWebViewActivity.java");
    private static final String BASE =
            "https://accounts.google.com/ServiceLogin?continue=https://voice.google.com/u/0/messages";
    // Reserved, non-routable placeholder (RFC 2606 .invalid); never a real account.
    private static final String PLACEHOLDER = "someone@example.invalid";

    private static String code() throws IOException {
        String src = new String(Files.readAllBytes(SRC));
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("//[^\\n]*", "");
    }

    private static String body(String src, String decl) {
        int i = src.indexOf(decl);
        assertTrue("declaration missing: " + decl, i >= 0);
        int open = src.indexOf('{', i);
        int depth = 0;
        for (int j = open; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, j + 1);
        }
        throw new AssertionError("unbalanced: " + decl);
    }

    // --- no weblogin request by default ---

    @Test
    public void webloginToken_isOffByDefault() {
        assertFalse(GVoiceWebViewActivity.USE_WEBLOGIN_TOKEN);
    }

    @Test
    public void attemptAccountAuth_goesStraightToSignInWhenFlagOff() throws IOException {
        String b = body(code(), "void attemptAccountAuth(");
        int gate = b.indexOf("if (!USE_WEBLOGIN_TOKEN)");
        assertTrue("attemptAccountAuth must check USE_WEBLOGIN_TOKEN first", gate >= 0);
        int signIn = b.indexOf("loadSignIn()", gate);
        int am = b.indexOf("AccountManager");
        assertTrue("flag-off path must load the sign-in page", signIn > gate);
        assertTrue("the flag check precedes any AccountManager use", am < 0 || gate < am);
        assertTrue("the flag-off branch returns before AccountManager", b.indexOf("return;", signIn) < am);
    }

    @Test
    public void requestAuthToken_isGatedByTheFlag() throws IOException {
        String b = body(code(), "void requestAuthToken(");
        int gate = b.indexOf("if (!USE_WEBLOGIN_TOKEN)");
        int get = b.indexOf("getAuthToken(");
        assertTrue("requestAuthToken must refuse when the flag is off", gate >= 0);
        assertTrue("the guard precedes getAuthToken", get > gate);
    }

    @Test
    public void weblogin_scopeAppearsOnlyInsideRequestAuthToken() throws IOException {
        String src = code();
        String b = body(src, "void requestAuthToken(");
        assertEquals(1, count(src, "weblogin:service=grandcentral"));
        assertTrue(b.contains("weblogin:service=grandcentral"));
    }

    // --- the sign-in URL ---

    @Test
    public void signInUrl_withoutSavedAccount_isServiceLoginContinuingToMessages() {
        assertEquals(BASE, GVoiceWebViewActivity.signInUrl(null));
        assertEquals(BASE, GVoiceWebViewActivity.signInUrl(""));
        assertEquals(BASE, GVoiceWebViewActivity.signInUrl("   "));
    }

    @Test
    public void signInUrl_withSavedAccount_addsEncodedEmailHint() {
        String url = GVoiceWebViewActivity.signInUrl(PLACEHOLDER);
        assertEquals(BASE + "&Email=someone%40example.invalid", url);
    }

    @Test
    public void signInUrl_hintIsEncodedSoItCannotAddParameters() {
        String url = GVoiceWebViewActivity.signInUrl("a&continue=https://evil.example/#x");
        assertEquals(1, count(url, "continue="));
        assertFalse(url.contains("#"));
        assertTrue(url.startsWith(BASE + "&Email="));
    }

    @Test
    public void signInUrl_passesTheNavigationAllowlist() {
        URI u = URI.create(GVoiceWebViewActivity.signInUrl(PLACEHOLDER));
        assertTrue(OriginGuard.isAllowedNavigation(u.getScheme(), u.getHost()));
        assertEquals("accounts.google.com", u.getHost());
    }

    // --- signed-out detection ---

    @Test
    public void signedOutLanding_marketingAndAboutPages() {
        assertTrue(GVoiceWebViewActivity.isSignedOutLanding("https://workspace.google.com/products/voice/"));
        assertTrue(GVoiceWebViewActivity.isSignedOutLanding("https://workspace.google.com/intl/en/products/voice/?x=1"));
        assertTrue(GVoiceWebViewActivity.isSignedOutLanding("https://voice.google.com/about"));
        assertTrue(GVoiceWebViewActivity.isSignedOutLanding("https://voice.google.com/about?hl=en"));
        assertTrue(GVoiceWebViewActivity.isSignedOutLanding("https://voice.google.com/about/"));
    }

    @Test
    public void signedOutLanding_notForGvAppSignInOrOthers() {
        assertFalse(GVoiceWebViewActivity.isSignedOutLanding("https://voice.google.com/u/0/messages"));
        assertFalse(GVoiceWebViewActivity.isSignedOutLanding("https://voice.google.com/u/0/messages?itemId=t.x"));
        assertFalse(GVoiceWebViewActivity.isSignedOutLanding("https://voice.google.com/aboutness"));
        assertFalse(GVoiceWebViewActivity.isSignedOutLanding("https://accounts.google.com/ServiceLogin"));
        assertFalse(GVoiceWebViewActivity.isSignedOutLanding("https://workspace.google.com.evil.example/"));
        assertFalse(GVoiceWebViewActivity.isSignedOutLanding("http://workspace.google.com/products/voice/"));
        assertFalse(GVoiceWebViewActivity.isSignedOutLanding("not a url"));
        assertFalse(GVoiceWebViewActivity.isSignedOutLanding(null));
    }

    @Test
    public void onPageFinished_redirectsSignedOutLandingToSignIn() throws IOException {
        String b = body(code(), "public void onPageFinished(");
        int detect = b.indexOf("isSignedOutLanding(url)");
        assertTrue("onPageFinished must detect the signed-out landing", detect >= 0);
        assertTrue(b.indexOf("redirectToSignIn()", detect) > detect);
        assertTrue(body(code(), "boolean redirectToSignIn(").contains("loadSignIn()"));
    }

    @Test
    public void signInRedirect_isBoundedAndSkippedForScheduledSends() throws IOException {
        String b = body(code(), "boolean redirectToSignIn(");
        assertTrue(b.contains("MAX_SIGN_IN_REDIRECTS"));
        assertTrue(b.contains("scheduledSend"));
        assertTrue(GVoiceWebViewActivity.MAX_SIGN_IN_REDIRECTS >= 1);
        assertTrue(GVoiceWebViewActivity.MAX_SIGN_IN_REDIRECTS <= 5);
    }

    @Test
    public void loadSignIn_nullChecksWebViewAndNeverLogsTheAccount() throws IOException {
        String b = body(code(), "void loadSignIn(");
        assertTrue(b.contains("webView == null"));
        for (String line : b.split("\n")) {
            if (line.contains("Log.")) {
                assertFalse("never log the account: " + line.trim(),
                        line.contains("account") || line.contains("signInUrl") || line.contains("Email"));
            }
        }
    }

    // --- allowlist unchanged (sec-review-1) ---

    @Test
    public void navigationAllowlist_unchanged() throws IOException {
        String b = body(code(), "public boolean shouldOverrideUrlLoading(");
        assertTrue(b.contains("OriginGuard.isAllowedNavigation(request.getUrl().getScheme(), request.getUrl().getHost())"));
        assertTrue(OriginGuard.isAllowedNavigation("https", "accounts.google.com"));
        assertTrue(OriginGuard.isAllowedNavigation("https", "workspace.google.com"));
        assertTrue(OriginGuard.isAllowedNavigation("https", "accounts.youtube.com"));
        assertFalse(OriginGuard.isAllowedNavigation("https", "evil.example"));
        assertFalse(OriginGuard.isAllowedNavigation("http", "accounts.google.com"));
        assertFalse(OriginGuard.isAllowedNavigation("https", "www.youtube.com"));
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    @Test
    public void bodyHelperSanity() throws IOException {
        assertNotNull(body(code(), "void loadGoogleVoice("));
    }
}
