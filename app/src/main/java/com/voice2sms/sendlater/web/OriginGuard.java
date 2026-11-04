package com.voice2sms.sendlater.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Origin checks for the GV WebView (sec-review-1). Pure JVM.
 *
 * <p>{@link #isTrustedGvUrl} gates every Send Later {@code V2SBridge} method on the main-frame
 * URL: scheme https, host exactly voice.google.com, no user info, default port. Only the
 * {@code scheme://authority} prefix is parsed (by {@link URI}), so path or query characters never
 * matter; anything the parser refuses is untrusted (fail closed).
 *
 * <p>Residual: a {@code @JavascriptInterface} is visible to every frame of the page, so this
 * main-frame check does not stop a cross-origin iframe inside a voice.google.com page from calling
 * the bridge. The structural fix is {@code WebViewCompat.addWebMessageListener} with the allowed
 * origin {@code https://voice.google.com}.
 *
 * <p>{@link #isAllowedNavigation} is the {@code shouldOverrideUrlLoading} allowlist: https only,
 * host google.com, googleapis.com or gstatic.com or a subdomain of one of them, or exactly
 * accounts.youtube.com (Google sign-in redirect).
 */
public final class OriginGuard {

    static final String GV_HOST = "voice.google.com";
    private static final String HTTPS = "https";
    private static final String[] NAV_DOMAINS = {"google.com", "googleapis.com", "gstatic.com"};
    /**
     * Exact hosts outside those domains that Google's web sign-in passes the main frame through
     * (SetSID cookie hop; integration-gate F8). Exact match only: no other youtube.com host.
     */
    private static final String[] NAV_EXACT_HOSTS = {"accounts.youtube.com"};

    private OriginGuard() {}

    /** True only for an https://voice.google.com URL (any path, query or fragment). */
    public static boolean isTrustedGvUrl(String url) {
        if (url == null) return false;
        String prefix = HTTPS + "://";
        if (!url.regionMatches(true, 0, prefix, 0, prefix.length())) return false;
        int start = prefix.length();
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = url.substring(start, end);
        if (authority.isEmpty()) return false;
        final URI uri;
        try {
            uri = new URI(HTTPS + "://" + authority + "/");
        } catch (URISyntaxException e) {
            return false;
        }
        if (uri.getRawUserInfo() != null) return false;
        int port = uri.getPort();
        if (port != -1 && port != 443) return false;
        String host = uri.getHost();
        return host != null && GV_HOST.equals(host.toLowerCase(Locale.ROOT));
    }

    /** Navigation allowlist for the GV WebView: https and a Google host (see class comment). */
    public static boolean isAllowedNavigation(String scheme, String host) {
        if (scheme == null || host == null || !HTTPS.equals(scheme.toLowerCase(Locale.ROOT))) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String d : NAV_DOMAINS) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        for (String e : NAV_EXACT_HOSTS) {
            if (h.equals(e)) return true;
        }
        return false;
    }
}
