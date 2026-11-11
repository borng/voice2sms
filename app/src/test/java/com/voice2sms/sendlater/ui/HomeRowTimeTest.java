package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** Implementer's check (home-redesign, D55): day label of the card row time column. */
public class HomeRowTimeTest {

    private static final TimeZone TZ = TimeZone.getTimeZone("America/Denver");

    private static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(y, mo - 1, d, h, mi);
        return c.getTimeInMillis();
    }

    @Test
    public void sameCalendarDayIsToday() {
        long now = at(2026, 11, 11, 0, 5);
        assertEquals(HomeRowTime.Day.TODAY, HomeRowTime.dayKind(at(2026, 11, 11, 23, 59), now, TZ));
        assertEquals(0, HomeRowTime.dayOffset(at(2026, 11, 11, 23, 59), now, TZ));
    }

    @Test
    public void justAfterMidnightIsTomorrow() {
        long now = at(2026, 11, 11, 23, 50);
        assertEquals(HomeRowTime.Day.TOMORROW, HomeRowTime.dayKind(at(2026, 11, 12, 0, 10), now, TZ));
    }

    @Test
    public void twoToSixDaysIsAWeekdayThenADate() {
        long now = at(2026, 11, 11, 9, 0);
        assertEquals(HomeRowTime.Day.WEEKDAY, HomeRowTime.dayKind(at(2026, 11, 13, 9, 0), now, TZ));
        assertEquals(HomeRowTime.Day.WEEKDAY, HomeRowTime.dayKind(at(2026, 11, 17, 9, 0), now, TZ));
        assertEquals(HomeRowTime.Day.DATE, HomeRowTime.dayKind(at(2026, 11, 18, 9, 0), now, TZ));
    }

    @Test
    public void pastTimesShowADate() {
        long now = at(2026, 11, 11, 9, 0);
        assertEquals(-1, HomeRowTime.dayOffset(at(2026, 11, 10, 23, 0), now, TZ));
        assertEquals(HomeRowTime.Day.DATE, HomeRowTime.dayKind(at(2026, 11, 10, 23, 0), now, TZ));
    }

    @Test
    public void dstChangeDoesNotShiftTheDay() {
        // US DST ends 2026-11-01 at 02:00 local: that day has 25 hours.
        long now = at(2026, 10, 31, 23, 30);
        assertEquals(1, HomeRowTime.dayOffset(at(2026, 11, 1, 23, 30), now, TZ));
        assertEquals(2, HomeRowTime.dayOffset(at(2026, 11, 2, 0, 30), now, TZ));
    }
}
