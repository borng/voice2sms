package com.voice2sms.sendlater.engine;

import android.content.Context;
import android.webkit.CookieManager;

/**
 * Whether the app's WebView holds a Google web session for Voice: a {@code SID} cookie visible to
 * https://voice.google.com. False on any exception (no WebView, before first unlock, JVM stub).
 * Never logs or returns cookie values.
 */
public final class GvSession {

    static final String GV_URL = "https://voice.google.com";

    private GvSession() {}

    public static boolean hasWebSession(Context context) {
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm == null) return false;
            return hasSidCookie(cm.getCookie(GV_URL));
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the Cookie header has a cookie named exactly "SID" with a non-empty value. */
    static boolean hasSidCookie(String cookieHeader) {
        if (cookieHeader == null) return false;
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith("SID=") && p.length() > 4) return true;
        }
        return false;
    }
}
