package com.voice2sms.sendlater.domain;

import static org.junit.Assert.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.TimeZone;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** R17: chip text for the open 1:1 thread. */
public class ChipLabelTest {

    private static final String ZONE_ID = "America/Denver";
    private static final TimeZone ZONE = TimeZone.getTimeZone(ZONE_ID);
    private static final String DOT = " · ";
    // Saturday 2026-11-07 09:00 local.
    private static final long SAT_9AM =
            LocalDateTime.of(2026, 11, 7, 9, 0).atZone(ZoneId.of(ZONE_ID)).toInstant().toEpochMilli();

    private Locale saved;

    @Before public void pinLocale() { saved = Locale.getDefault(); Locale.setDefault(Locale.US); }
    @After public void restoreLocale() { Locale.setDefault(saved); }

    /** JDK versions differ on NBSP/NNBSP before AM/PM; the label's words are what matter. */
    private static String norm(String s) {
        return s.replace(' ', ' ').replace(' ', ' ');
    }

    @Test
    public void onePending_showsScheduledWithTime() {
        assertEquals("Scheduled" + DOT + "Sat 9:00 AM", norm(ChipLabel.format(1, 0, SAT_9AM, null, ZONE)));
    }

    @Test
    public void severalPending_showsCount() {
        assertEquals("2 scheduled", ChipLabel.format(2, 0, SAT_9AM, null, ZONE));
        assertEquals("5 scheduled", ChipLabel.format(5, 0, SAT_9AM, null, ZONE));
    }

    @Test
    public void anyHeld_showsHeldVariant() {
        assertEquals("Held" + DOT + "Test Contact replied", ChipLabel.format(0, 1, SAT_9AM, "Test Contact", ZONE));
    }

    @Test
    public void heldWinsOverPending() {
        assertEquals("Held" + DOT + "Test Contact replied", ChipLabel.format(3, 1, SAT_9AM, "Test Contact", ZONE));
    }
}
