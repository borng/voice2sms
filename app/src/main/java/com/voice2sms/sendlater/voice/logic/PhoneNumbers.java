package com.voice2sms.sendlater.voice.logic;

/**
 * Conservative phone-number normalizer (R4a), no libphonenumber. Only NANP numbers are produced for
 * the "US" region; anything that is not unmistakably a full number returns null. A string with any
 * letter in it is never a number, so a display name can never turn into a recipient (I3).
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

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
