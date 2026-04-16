package com.voice2sms.wear;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * Pure-JVM helper for extracting the phone number and body from an
 * {@code ACTION_SENDTO} intent's scheme-specific-part + body extra.
 *
 * Factored out so parsing can be unit-tested without an Android runtime.
 * {@link SendToReceiverActivity} is the thin Android wrapper.
 */
public final class SendToIntentParser {

    /** Phone + body carrier. Either field may be null if missing from the source. */
    public static final class Parsed {
        public final String phone;
        public final String body;
        Parsed(String phone, String body) {
            this.phone = phone;
            this.body = body;
        }
    }

    private SendToIntentParser() { /* static only */ }

    /**
     * @param schemeSpecificPart URI.getSchemeSpecificPart() (e.g. "+15551234567"
     *                           or "+15551234567?body=hi%20there"). May be null.
     * @param bodyExtra          String body from intent extras (EXTRA_TEXT or
     *                           legacy "sms_body"). Wins over URI-embedded body
     *                           when both are present. May be null.
     */
    public static Parsed parse(String schemeSpecificPart, String bodyExtra) {
        String phone = extractPhone(schemeSpecificPart);
        String body = bodyExtra;
        if (body == null) {
            body = extractBodyFromQuery(schemeSpecificPart);
        }
        return new Parsed(phone, body);
    }

    private static String extractPhone(String ssp) {
        if (ssp == null) return null;
        int cut = indexOfAny(ssp, "?&");
        String raw = (cut >= 0 ? ssp.substring(0, cut) : ssp).trim();
        return raw.isEmpty() ? null : raw;
    }

    /**
     * Some opaque URIs encode the body as "smsto:+1...?body=...". Android's
     * Uri.getQueryParameter() doesn't work on opaque URIs, so parse by hand.
     */
    private static String extractBodyFromQuery(String ssp) {
        if (ssp == null) return null;
        int q = ssp.indexOf('?');
        if (q < 0) return null;
        String query = ssp.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq);
            if (!"body".equals(key)) continue;
            String value = pair.substring(eq + 1);
            try {
                return URLDecoder.decode(value, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return value; // UTF-8 is always available; fall back to raw
            }
        }
        return null;
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int idx = s.indexOf(chars.charAt(i));
            if (idx >= 0 && (best < 0 || idx < best)) best = idx;
        }
        return best;
    }
}
