package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * sec-review-1 (I7, I8, R10, R17): the WebView bridge only serves Send Later while the main frame
 * is https://voice.google.com, and the navigation filter is an exact host allowlist. Pure JVM.
 * Numbers are fictional 555-01xx.
 */
public class OriginGuardTest {

    @Test
    public void gvMessagesUrl_accepted() {
        assertTrue(OriginGuard.isTrustedGvUrl("https://voice.google.com/u/0/messages"));
        assertTrue(OriginGuard.isTrustedGvUrl("https://voice.google.com/u/0/messages?itemId=t.%2B15555550123"));
        assertTrue(OriginGuard.isTrustedGvUrl("https://voice.google.com"));
        assertTrue(OriginGuard.isTrustedGvUrl("https://voice.google.com:443/u/0/messages"));
        assertTrue(OriginGuard.isTrustedGvUrl("HTTPS://Voice.Google.com/u/0/messages"));
        // Odd path/query characters do not make the origin untrusted.
        assertTrue(OriginGuard.isTrustedGvUrl("https://voice.google.com/u/0/messages?q=a b|c#x y"));
    }

    @Test
    public void plainHttp_rejected() {
        assertFalse(OriginGuard.isTrustedGvUrl("http://voice.google.com/u/0/messages"));
    }

    @Test
    public void lookalikeHosts_rejected() {
        assertFalse(OriginGuard.isTrustedGvUrl("https://voice.google.com.evil.example/u/0/messages"));
        assertFalse(OriginGuard.isTrustedGvUrl("https://evil.example/?voice.google.com"));
        assertFalse(OriginGuard.isTrustedGvUrl("https://evil.example/https://voice.google.com/"));
        assertFalse(OriginGuard.isTrustedGvUrl("https://evilvoice.google.com/"));
        assertFalse(OriginGuard.isTrustedGvUrl("https://x.voice.google.com/"));
        assertFalse(OriginGuard.isTrustedGvUrl("https://google.com/"));
        assertFalse(OriginGuard.isTrustedGvUrl("https://voice.google.com./"));
    }

    @Test
    public void userinfoPortAndBackslashTricks_rejected() {
        assertFalse(OriginGuard.isTrustedGvUrl("https://voice.google.com@evil.example/"));
        assertFalse(OriginGuard.isTrustedGvUrl("https://evil.example@voice.google.com/"));
        assertFalse(OriginGuard.isTrustedGvUrl("https://voice.google.com:8443/"));
        assertFalse(OriginGuard.isTrustedGvUrl("https://evil.example\\@voice.google.com/"));
        assertFalse(OriginGuard.isTrustedGvUrl("https://voice.google.com\\@evil.example/"));
    }

    @Test
    public void otherSchemesAndJunk_rejected() {
        assertFalse(OriginGuard.isTrustedGvUrl("javascript:alert('https://voice.google.com/')"));
        assertFalse(OriginGuard.isTrustedGvUrl("javascript://voice.google.com/%0Aalert(1)"));
        assertFalse(OriginGuard.isTrustedGvUrl("data:text/html,https://voice.google.com/"));
        assertFalse(OriginGuard.isTrustedGvUrl("file:///android_asset/voice.google.com"));
        assertFalse(OriginGuard.isTrustedGvUrl("about:blank"));
        assertFalse(OriginGuard.isTrustedGvUrl(" https://voice.google.com/"));
        assertFalse(OriginGuard.isTrustedGvUrl(""));
        assertFalse(OriginGuard.isTrustedGvUrl(null));
        assertFalse(OriginGuard.isTrustedGvUrl("https://"));
        assertFalse(OriginGuard.isTrustedGvUrl("https:///voice.google.com"));
    }

    @Test
    public void navigationAllowlist_exactGoogleHostsOverHttpsOnly() {
        String[] allowed = {"google.com", "accounts.google.com", "voice.google.com", "GOOGLE.com",
                "googleapis.com", "www.googleapis.com", "gstatic.com", "ssl.gstatic.com"};
        for (String h : allowed) assertTrue(h, OriginGuard.isAllowedNavigation("https", h));
        assertTrue(OriginGuard.isAllowedNavigation("HTTPS", "accounts.google.com"));

        String[] blocked = {"evil.example", "google.com.evil.example", "evilgoogle.com", "notgstatic.com",
                "googleapis.com.evil.example", "evil.example.googleapis", "", "google.com."};
        for (String h : blocked) assertFalse(h, OriginGuard.isAllowedNavigation("https", h));
        assertFalse(OriginGuard.isAllowedNavigation("https", null));
        assertFalse(OriginGuard.isAllowedNavigation("http", "accounts.google.com"));
        assertFalse(OriginGuard.isAllowedNavigation("javascript", "google.com"));
        assertFalse(OriginGuard.isAllowedNavigation(null, "google.com"));
    }
}
