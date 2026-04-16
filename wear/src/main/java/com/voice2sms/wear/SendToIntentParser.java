package com.voice2sms.wear;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/** Pure-JVM SENDTO-intent parser — split out so {@link SendToReceiverActivity} stays testable. */
public final class SendToIntentParser {

    public static final class Parsed {
        public final String phone;
        public final String body;
        Parsed(String phone, String body) {
            this.phone = phone;
            this.body = body;
        }
    }

    private SendToIntentParser() {}

    /** {@code bodyExtra} takes precedence over any {@code ?body=…} query string. */
    public static Parsed parse(String schemeSpecificPart, String bodyExtra) {
        String phone = extractPhone(schemeSpecificPart);
        String body = bodyExtra != null ? bodyExtra : extractBodyFromQuery(schemeSpecificPart);
        return new Parsed(phone, body);
    }

    private static String extractPhone(String ssp) {
        if (ssp == null) return null;
        int cut = indexOfAny(ssp, "?&");
        String raw = (cut >= 0 ? ssp.substring(0, cut) : ssp).trim();
        return raw.isEmpty() ? null : raw;
    }

    /** Uri.getQueryParameter() throws UnsupportedOperationException on opaque URIs. */
    private static String extractBodyFromQuery(String ssp) {
        if (ssp == null) return null;
        int q = ssp.indexOf('?');
        if (q < 0) return null;
        String query = ssp.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (!"body".equals(pair.substring(0, eq))) continue;
            String value = pair.substring(eq + 1);
            try {
                return URLDecoder.decode(value, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return value;
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
