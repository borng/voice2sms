package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** LIVE-F9: minute time from the GV web bubble's screen-reader label. Texts are fictional. */
public class GvBubbleLabelTest {

    private static final TimeZone NY = TimeZone.getTimeZone("America/New_York");
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static long at(TimeZone tz, int y, int mon0, int d, int h24, int min) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(y, mon0, d, h24, min, 0);
        return c.getTimeInMillis();
    }

    @Test public void outgoingLabel_pm() {
        assertEquals(Long.valueOf(at(NY, 2026, Calendar.NOVEMBER, 5, 15, 4)), GvBubbleLabel.parseTimeMs(
                "Message from you, see you at the park, Thursday, November 5 2026, 3:04 PM.", NY));
    }

    @Test public void incomingLabel_am() {
        assertEquals(Long.valueOf(at(UTC, 2026, Calendar.NOVEMBER, 4, 9, 30)), GvBubbleLabel.parseTimeMs(
                "Message from Contact A, sounds good, Wednesday, November 4 2026, 9:30 AM.", UTC));
    }

    @Test public void twelveOClock_noonAndMidnight() {
        assertEquals(Long.valueOf(at(UTC, 2026, Calendar.NOVEMBER, 5, 12, 15)), GvBubbleLabel.parseTimeMs(
                "Message from you, lunch, Thursday, November 5 2026, 12:15 PM.", UTC));
        assertEquals(Long.valueOf(at(UTC, 2026, Calendar.NOVEMBER, 5, 0, 15)), GvBubbleLabel.parseTimeMs(
                "Message from you, late, Thursday, November 5 2026, 12:15 AM.", UTC));
    }

    @Test public void singleDigitDay_andCommasInText() {
        assertEquals(Long.valueOf(at(UTC, 2026, Calendar.JANUARY, 1, 8, 5)), GvBubbleLabel.parseTimeMs(
                "Message from you, one, two, Monday, three, Thursday, January 1 2026, 8:05 AM.", UTC));
    }

    @Test public void narrowNoBreakSpaceBeforeMeridiem_andCommaAfterDay() {
        assertEquals(Long.valueOf(at(UTC, 2026, Calendar.NOVEMBER, 5, 15, 4)), GvBubbleLabel.parseTimeMs(
                "Message from you, ok, Thursday, November 5, 2026, 3:04 PM.", UTC));
    }

    @Test public void garbage_isNull() {
        assertNull(GvBubbleLabel.parseTimeMs(null, UTC));
        assertNull(GvBubbleLabel.parseTimeMs("", UTC));
        assertNull(GvBubbleLabel.parseTimeMs("hello world", UTC));
        assertNull(GvBubbleLabel.parseTimeMs("Message from you, ok, Today, 3:04 PM.", UTC));
        assertNull("bad hour", GvBubbleLabel.parseTimeMs("Message from you, ok, Thursday, November 5 2026, 13:04 PM.", UTC));
        assertNull("bad minute", GvBubbleLabel.parseTimeMs("Message from you, ok, Thursday, November 5 2026, 3:64 PM.", UTC));
        assertNull("impossible date", GvBubbleLabel.parseTimeMs("Message from you, ok, Monday, February 30 2026, 3:04 PM.", UTC));
        assertNull("weekday disagrees", GvBubbleLabel.parseTimeMs("Message from you, ok, Friday, November 5 2026, 3:04 PM.", UTC));
        assertNull("no timezone", GvBubbleLabel.parseTimeMs("Message from you, ok, Thursday, November 5 2026, 3:04 PM.", null));
    }

    @Test public void otherLocale_isNull() {
        assertNull(GvBubbleLabel.parseTimeMs("Nachricht von dir, ok, Donnerstag, 5. November 2026, 15:04.", UTC));
        assertNull(GvBubbleLabel.parseTimeMs("Message de vous, ok, jeudi, novembre 5 2026, 3:04 PM.", UTC));
        assertNull(GvBubbleLabel.parseTimeMs("Message from you, ok, Thursday, November 5 2026, 15:04.", UTC));
    }
}
