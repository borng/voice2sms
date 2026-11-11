package com.voice2sms.sendlater.voice.logic;

/**
 * Conservative phone-number normalizer (R4a), no libphonenumber. Only NANP numbers are produced for
 * the "US" region; anything that is not unmistakably a full number returns null. A string with any
 * letter in it is never a number, so a display name can never turn into a recipient (I3).
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /** Longest E.164 number: 15 digits after the "+" (ITU-T E.164). */
    public static final int E164_MAX_DIGITS = 15;
    /** Shortest number accepted anywhere in the app: 7 digits after the "+". */
    public static final int E164_MIN_DIGITS = 7;

    /**
     * The one E.164 rule (D38): "+", then a digit 1-9, then digits only, 7-15 digits in all.
     * No spaces, no separators, no "+0..." (no country code starts with 0). Every capture, URL and
     * directory path uses this; {@code ThreadDirectory.isE164} delegates here. inject.js mirrors it.
     */
    public static boolean isE164(String s) {
        if (s == null) return false;
        int n = s.length() - 1;
        if (n < E164_MIN_DIGITS || n > E164_MAX_DIGITS || s.charAt(0) != '+') return false;
        if (s.charAt(1) < '1' || s.charAt(1) > '9') return false;
        for (int i = 2; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * @param raw    text such as "(xxx) xxx-xxxx", "xxx-xxx-xxxx", "1 xxx xxx xxxx", "+1xxxxxxxxxx"
     * @param region default region for numbers without "+"; only "US" (or null, meaning US) is
     *               supported, other regions accept "+" forms only
     * @return E.164 ("+" and digits) or null
     */
    public static String toE164(String raw, String region) {
        if (raw == null) return null;
        StringBuilder digits = new StringBuilder();
        boolean plus = false;
        boolean seenDigit = false;
        String s = raw.trim();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
                seenDigit = true;
            } else if (c == '+') {
                if (plus || seenDigit) return null;
                plus = true;
            } else if (!isSeparator(c)) {
                return null;
            }
        }
        if (digits.length() == 0) return null;
        String d = digits.toString();
        if (plus) {
            if (d.charAt(0) == '1') return nanp(d.substring(1));
            // Non-NANP international: plausible length only.
            if (d.charAt(0) == '0' || d.length() < 8 || d.length() > 15) return null;
            return "+" + d;
        }
        if (region != null && !"US".equalsIgnoreCase(region)) return null;
        if (d.length() == 10) return nanp(d);
        if (d.length() == 11 && d.charAt(0) == '1') return nanp(d.substring(1));
        return null;
    }

    /** True when {@code raw} normalizes to a number for {@code region}. */
    public static boolean isPhoneNumber(String raw, String region) {
        return toE164(raw, region) != null;
    }

    /** Ten NANP digits: area code and exchange both start with 2-9. */
    private static String nanp(String ten) {
        if (ten.length() != 10) return null;
        if (ten.charAt(0) < '2' || ten.charAt(3) < '2') return null;
        return "+1" + ten;
    }

    private static boolean isSeparator(char c) {
        switch (c) {
            case ' ': case '(': case ')': case '-': case '.': case '/':
            case ' ': // no-break space
            case ' ': case ' ': // figure / narrow no-break space
            case '‐': case '‑': case '‒': case '–': case '—': case '−':
            case '‎': case '‏': // LRM / RLM
            case '‪': case '‫': case '‬': case '‭': case '‮': // bidi embeds
            case '⁦': case '⁧': case '⁨': case '⁩': // bidi isolates
                return true;
            default:
                return false;
        }
    }
}
