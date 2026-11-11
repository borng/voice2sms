package com.voice2sms.sendlater.ui;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Pure helper for the time column of a Scheduled messages card row (D55): how many calendar days
 * after "now" a send time falls, so the row can say Today / Tomorrow / a weekday / a date above
 * the bold time. No Android types, so it is unit-tested on the JVM.
 */
public final class HomeRowTime {

    /** Day label kinds for the time column. */
    public enum Day { TODAY, TOMORROW, WEEKDAY, DATE }

    private HomeRowTime() {
    }

    /** Calendar days from {@code nowMs} to {@code whenMs} in {@code tz} (negative = before today). */
    public static int dayOffset(long whenMs, long nowMs, TimeZone tz) {
        return (int) (dayNumber(whenMs, tz) - dayNumber(nowMs, tz));
    }

    /** Which label the time column shows: today, tomorrow, a weekday (2..6 days) or a date. */
    public static Day dayKind(long whenMs, long nowMs, TimeZone tz) {
        int d = dayOffset(whenMs, nowMs, tz);
        if (d == 0) return Day.TODAY;
        if (d == 1) return Day.TOMORROW;
        if (d >= 2 && d <= 6) return Day.WEEKDAY;
        return Day.DATE;
    }

    /** Days since the epoch of the local calendar date of {@code ms} (DST-safe). */
    private static long dayNumber(long ms, TimeZone tz) {
        Calendar local = Calendar.getInstance(tz);
        local.setTimeInMillis(ms);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
        return Math.floorDiv(utc.getTimeInMillis(), 86_400_000L);
    }
}
