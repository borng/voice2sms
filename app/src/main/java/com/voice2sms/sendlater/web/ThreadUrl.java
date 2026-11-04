package com.voice2sms.sendlater.web;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1:1 thread detection from a Google Voice web URL (R10, F7 - URL shape unverified on device).
 * Mirrors the inject.js gate: the {@code itemId} query parameter, percent-decoded, must match
 * {@code ^t\.\+\d{7,15}$}. Group ids ({@code t.g.*}), missing ids and anything else are "not 1:1".
 * Never guesses. Pure JVM.
 */
public final class ThreadUrl {

    private static final Pattern ONE_TO_ONE = Pattern.compile("^t\\.(\\+\\d{7,15})$");

    private ThreadUrl() {}

    /** E.164 ("+" + digits) of a 1:1 thread URL, else null. */
    public static String oneToOneE164(String url) {
        String id = itemId(url);
        if (id == null) return null;
        Matcher m = ONE_TO_ONE.matcher(id);
        return m.matches() ? m.group(1) : null;
    }

    /** True when {@code url} is the 1:1 thread of {@code e164}. */
    public static boolean isThreadOf(String url, String e164) {
        return e164 != null && e164.equals(oneToOneE164(url));
    }

    /** Decoded itemId query value (a literal '+' kept as '+'), or null. */
    static String itemId(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        if (q < 0) return null;
        int hash = url.indexOf('#', q);
        String query = hash < 0 ? url.substring(q + 1) : url.substring(q + 1, hash);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (!"itemId".equals(pair.substring(0, eq))) continue;
            String raw = pair.substring(eq + 1);
            if (raw.isEmpty()) return null;
            try {
                // Form decoding turns '+' into a space; GV's E.164 plus must survive either spelling.
                return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
