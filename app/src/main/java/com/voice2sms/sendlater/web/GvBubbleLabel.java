package com.voice2sms.sendlater.web;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time of a GV web message bubble from its screen-reader label (LIVE-F9). Pure JVM.
 *
 * <p>GV web gives a bubble no time element, timestamp attribute or id. Its only time is the
 * {@code div.cdk-visually-hidden} label: {@code "Message from you, <text>, <Weekday>, <Month> <D>
 * <YYYY>, <h:mm AM|PM>."} (outgoing) or {@code "Message from <sender>, <text>, ..."} (incoming).
 * Only the English tail is read, at minute precision, in the device time zone. Anything else
 * (another locale, "Today", a weekday that does not match the date, an impossible date) gives
 * null: no time is better than a wrong one.
 *
 * <p>The label carries the message text and the sender: never log it.
 */
public final class GvBubbleLabel {

    private static final String SP = "[\\s\\u00A0\\u202F]";

    private static final String[] MONTHS = {
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"};

    /** Calendar.SUNDAY = 1 ... SATURDAY = 7. */
    private static final String[] WEEKDAYS = {
            "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};

    private static final Pattern TAIL = Pattern.compile(
            "(?:^|,)" + SP + "*([A-Za-z]+)," + SP + "+([A-Za-z]+)\\.?" + SP + "+(\\d{1,2}),?" + SP + "+(\\d{4}),"
                    + SP + "+(\\d{1,2}):(\\d{2})" + SP + "*([AaPp])\\.?[Mm]\\.?" + SP + "*\\.?" + SP + "*$");

    private GvBubbleLabel() {}

    /** Epoch ms of the label's minute in {@code tz}, or null when the tail is not recognised. */
    public static Long parseTimeMs(String label, TimeZone tz) {
        if (label == null || tz == null || label.length() > 20_000) return null;
        Matcher m = TAIL.matcher(label);
        if (!m.find()) return null;
        int weekday = indexOf(WEEKDAYS, m.group(1));
        int month = indexOf(MONTHS, m.group(2));
        if (weekday < 0 || month < 0) return null;
        int day;
        int year;
        int hour;
        int minute;
        try {
            day = Integer.parseInt(m.group(3));
            year = Integer.parseInt(m.group(4));
            hour = Integer.parseInt(m.group(5));
            minute = Integer.parseInt(m.group(6));
        } catch (NumberFormatException e) {
            return null;
        }
        if (day < 1 || day > 31 || year < 2000 || year > 2999) return null;
        if (hour < 1 || hour > 12 || minute < 0 || minute > 59) return null;
        boolean pm = Character.toLowerCase(m.group(7).charAt(0)) == 'p';
        int hour24 = (hour % 12) + (pm ? 12 : 0);
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.clear();
        c.setLenient(false);
        c.set(year, month, day, hour24, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        long ms;
        int dow;
        try {
            ms = c.getTimeInMillis();
            dow = c.get(Calendar.DAY_OF_WEEK);
        } catch (IllegalArgumentException e) {
            return null;   // e.g. February 30
        }
        if (dow - 1 != weekday) return null;   // the weekday must agree with the date
        return ms;
    }

    /** Full English name or its three-letter abbreviation ("Sept" too), case-insensitive. */
    private static int indexOf(String[] names, String word) {
        String w = word.toLowerCase(Locale.US);
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(w)) return i;
            if (w.length() >= 3 && names[i].startsWith(w) && (w.length() == 3 || "sept".equals(w))) return i;
        }
        return -1;
    }
}
