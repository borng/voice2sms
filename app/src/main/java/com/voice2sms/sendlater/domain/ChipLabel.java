package com.voice2sms.sendlater.domain;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * In-thread chip text (R17): "Scheduled · Sat 9:00 AM" for one pending message, "N scheduled"
 * for several, "Held · <Name> replied" (or "... called" for REACHED_OUT) whenever any is held.
 * Returns null when there is nothing to show.
 *
 * <p>Deliver fixes (UI M5, M6): a SCHEDULED message the engine already reminded about will not
 * send itself, so it reads "Reminder sent · Sat 9:00 AM" instead of "Scheduled · ..."; several
 * with any reminded read "3 scheduled · 1 waiting for you". The time carries the date beyond the
 * coming week ("Mon, Nov 23, 9:00 AM"), the same rule as the sheet's time label.
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
     * As the full overload with nothing reminded and the time read without a date (the time is
     * measured against itself, so it always falls in "this week").
     */
    public static String format(int pendingCount, int heldCount, long nextTimeMs, String heldName,
                                String heldE164, HoldReason heldReason, TimeZone zone) {
        return format(pendingCount, heldCount, nextTimeMs, heldName, heldE164, heldReason,
                0, nextTimeMs, zone);
    }

    /**
     * @param heldName      display name as captured (may be null)
     * @param heldE164      number used when there is no name, formatted (NXX) NXX-XXXX for +1 numbers
     * @param heldReason    reason of the held message shown; REACHED_OUT reads "called"
     * @param remindedCount how many of the pending (SCHEDULED) messages were already reminded
     *                      about and wait for the user (see {@link #isWaitingForUser})
     * @param nowMs         now, for the date rule of the time label
     */
    public static String format(int pendingCount, int heldCount, long nextTimeMs, String heldName,
                                String heldE164, HoldReason heldReason, int remindedCount,
                                long nowMs, TimeZone zone) {
        if (heldCount > 0) {
            String who = heldDisplayName(heldName, heldE164);
            String verb = verb(heldReason);
            StringBuilder sb = new StringBuilder("Held");
            if (!who.isEmpty()) sb.append(SEP).append(who);
            if (verb != null) sb.append(who.isEmpty() ? SEP : " ").append(verb);
            return sb.toString();
        }
        if (pendingCount > 1) {
            String n = pendingCount + " scheduled";
            return remindedCount > 0 ? n + SEP + Math.min(remindedCount, pendingCount) + " waiting for you" : n;
        }
        if (pendingCount == 1) {
            return (remindedCount > 0 ? "Reminder sent" : "Scheduled") + SEP
                    + timeLabel(nextTimeMs, nowMs, zone != null ? zone : TimeZone.getDefault());
        }
        return null;
    }

    /**
     * A SCHEDULED message that will not send itself: the engine reminded the user about it
     * ({@code remindedMark}, the engine's "reminded" mark, R29). Any other status is false.
     */
    public static boolean isWaitingForUser(ScheduledMessage m, boolean remindedMark) {
        return m != null && m.getStatus() == ScheduledMessage.Status.SCHEDULED && remindedMark;
    }

    /**
     * "Wed 1:30 PM" within the coming week (today plus six days), "Wed, Jun 24, 1:30 PM" further
     * out. Same rule as the sheet's time label ({@code SheetViewModelLogic.timeLabel}, R17).
     */
    public static String timeLabel(long t, long nowMs, TimeZone zone) {
        Calendar now = Calendar.getInstance(zone);
        now.setTimeInMillis(nowMs);
        now.set(Calendar.HOUR_OF_DAY, 0);
        now.set(Calendar.MINUTE, 0);
        now.set(Calendar.SECOND, 0);
        now.set(Calendar.MILLISECOND, 0);
        now.add(Calendar.DAY_OF_MONTH, 6);
        boolean thisWeek = t < now.getTimeInMillis() + 24L * 60 * 60 * 1000;
        SimpleDateFormat f = new SimpleDateFormat(thisWeek ? "EEE h:mm a" : "EEE, MMM d, h:mm a",
                Locale.getDefault());
        f.setTimeZone(zone);
        return f.format(new Date(t));
    }

    /**
     * Label of a just-scheduled message (the SCHEDULED broadcast's undo/clear text): {@code label}
     * (EXTRA_LABEL) when it is not blank, else {@code scheduledFor} (EXTRA_SCHEDULED_FOR) as
     * "EEE h:mm a" in {@code zone}, else "later" when there is no time ({@code <= 0}).
     */
    public static String scheduledLabel(String label, long scheduledFor, TimeZone zone) {
        if (label != null && !label.trim().isEmpty()) return label;
        if (scheduledFor <= 0) return "later";
        SimpleDateFormat f = new SimpleDateFormat("EEE h:mm a", Locale.getDefault());
        f.setTimeZone(zone);
        return f.format(new Date(scheduledFor));
    }

    /** REPLIED -> "replied", REACHED_OUT -> "called"; other reasons have no verb ("Held · Name"). */
    private static String verb(HoldReason r) {
        if (r == HoldReason.REPLIED) return "replied";
        if (r == HoldReason.REACHED_OUT) return "called";
        return null;
    }

    /**
     * D39: how a recipient is shown anywhere outside the chip: the name as captured (trimmed),
     * else the number via {@link #formatNumber}, else {@code unknown} (no name and no number).
     * Never truncated; only the chip ({@link #heldDisplayName}) truncates.
     */
    public static String recipient(String displayName, String e164, String unknown) {
        if (displayName != null && !displayName.trim().isEmpty()) return displayName.trim();
        if (e164 == null || e164.trim().isEmpty()) return unknown;
        return formatNumber(e164);
    }

    /** Name as captured, else the formatted number; truncated to 20 chars (ellipsis included). */
    public static String heldDisplayName(String displayName, String e164) {
        String s = recipient(displayName, e164, "");
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
