package com.voice2sms.sendlater.domain;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * In-thread chip text (R17): "Scheduled · Sat 9:00 AM" for one pending message, "N scheduled"
 * for several, "Held · <Name> replied" (or "... called" for REACHED_OUT) whenever any is held.
 * Returns null when there is nothing to show.
 */
public final class ChipLabel {

    public static final int MAX_NAME_CHARS = 20;
    private static final String SEP = " · ";

    private ChipLabel() {}

    /** Held variant assumes a text reply (REPLIED). */
    public static String format(int pendingCount, int heldCount, long nextTimeMs, String heldName, TimeZone zone) {
        return format(pendingCount, heldCount, nextTimeMs, heldName, null, HoldReason.REPLIED, zone);
    }

    /**
     * @param heldName   display name as captured (may be null)
     * @param heldE164   number used when there is no name, formatted (NXX) NXX-XXXX for +1 numbers
     * @param heldReason reason of the held message shown; REACHED_OUT reads "called"
     */
    public static String format(int pendingCount, int heldCount, long nextTimeMs, String heldName,
                                String heldE164, HoldReason heldReason, TimeZone zone) {
        if (heldCount > 0) {
            String who = heldDisplayName(heldName, heldE164);
            String verb = verb(heldReason);
            StringBuilder sb = new StringBuilder("Held");
            if (!who.isEmpty()) sb.append(SEP).append(who);
            if (verb != null) sb.append(who.isEmpty() ? SEP : " ").append(verb);
            return sb.toString();
        }
        if (pendingCount > 1) return pendingCount + " scheduled";
        if (pendingCount == 1) {
            SimpleDateFormat f = new SimpleDateFormat("EEE h:mm a", Locale.getDefault());
            f.setTimeZone(zone != null ? zone : TimeZone.getDefault());
            return "Scheduled" + SEP + f.format(new Date(nextTimeMs));
        }
        return null;
    }

    /** REPLIED -> "replied", REACHED_OUT -> "called"; other reasons have no verb ("Held · Name"). */
    private static String verb(HoldReason r) {
        if (r == HoldReason.REPLIED) return "replied";
        if (r == HoldReason.REACHED_OUT) return "called";
        return null;
    }

    /** Name as captured, else the formatted number; truncated to 20 chars (ellipsis included). */
    public static String heldDisplayName(String displayName, String e164) {
        String s = displayName != null && !displayName.trim().isEmpty()
                ? displayName.trim() : formatNumber(e164);
        if (s.length() > MAX_NAME_CHARS) s = s.substring(0, MAX_NAME_CHARS - 1) + "…";
        return s;
    }

    /** "+1NXXNXXXXXX" (or 10 digits) -> "(NXX) NXX-XXXX"; anything else unchanged; null -> "". */
    public static String formatNumber(String e164) {
        if (e164 == null) return "";
        String digits = e164.replaceAll("[^0-9]", "");
        if (digits.length() == 11 && digits.charAt(0) == '1') digits = digits.substring(1);
        if (digits.length() == 10 && (e164.startsWith("+1") || !e164.startsWith("+"))) {
            return "(" + digits.substring(0, 3) + ") " + digits.substring(3, 6) + "-" + digits.substring(6);
        }
        return e164.trim();
    }
}
