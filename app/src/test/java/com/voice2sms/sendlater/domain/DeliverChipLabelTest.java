package com.voice2sms.sendlater.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.TimeZone;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Deliver fixes UI M5 (reminded reads "Reminder sent") and UI M6 (date beyond the week). */
public class DeliverChipLabelTest {

    private static final String ZONE_ID = "America/Denver";
    private static final TimeZone ZONE = TimeZone.getTimeZone(ZONE_ID);
    private static final String DOT = " · ";

    private static long at(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.of(ZONE_ID)).toInstant().toEpochMilli();
    }

    // Wednesday 2026-11-04 10:00 local.
    private static final long NOW = at(2026, 11, 4, 10, 0);

    private Locale saved;

    @Before public void pinLocale() { saved = Locale.getDefault(); Locale.setDefault(Locale.US); }
    @After public void restoreLocale() { Locale.setDefault(saved); }

    private static String norm(String s) {
        return s == null ? null : s.replace(' ', ' ').replace(' ', ' ');
    }

    private static String fmt(int pending, int held, long next, int reminded, long now) {
        return norm(ChipLabel.format(pending, held, next, null, null, HoldReason.REPLIED, reminded, now, ZONE));
    }

    @Test public void m6_withinTheWeek_dayAndTimeOnly() {
        assertEquals("Scheduled" + DOT + "Sat 9:00 AM", fmt(1, 0, at(2026, 11, 7, 9, 0), 0, NOW));
        // Today + 6 days, late evening: still "this week".
        assertEquals("Scheduled" + DOT + "Tue 11:00 PM", fmt(1, 0, at(2026, 11, 10, 23, 0), 0, NOW));
    }

    @Test public void m6_beyondTheWeek_addsTheDate() {
        assertEquals("Scheduled" + DOT + "Wed, Nov 11, 12:00 AM", fmt(1, 0, at(2026, 11, 11, 0, 0), 0, NOW));
        assertEquals("Scheduled" + DOT + "Mon, Nov 23, 9:00 AM", fmt(1, 0, at(2026, 11, 23, 9, 0), 0, NOW));
    }

    @Test public void m6_timeLabelRuleMatchesTheSameBoundary() {
        long lastThisWeek = at(2026, 11, 10, 23, 59);
        long firstNextWeek = at(2026, 11, 11, 0, 0);
        assertFalse(norm(ChipLabel.timeLabel(lastThisWeek, NOW, ZONE)).contains(","));
        assertTrue(norm(ChipLabel.timeLabel(firstNextWeek, NOW, ZONE)).contains("Nov 11"));
    }

    @Test public void m5_oneReminded_readsReminderSent_neverScheduledWithPastTime() {
        long past = at(2026, 11, 4, 9, 0);
        String label = fmt(1, 0, past, 1, NOW);
        assertEquals("Reminder sent" + DOT + "Wed 9:00 AM", label);
        assertFalse(label.startsWith("Scheduled"));
    }

    @Test public void m5_severalWithReminded_sayHowManyWait() {
        assertEquals("3 scheduled" + DOT + "1 waiting for you", fmt(3, 0, NOW, 1, NOW));
        assertEquals("2 scheduled", fmt(2, 0, NOW, 0, NOW));
    }

    @Test public void m5_heldStillWins() {
        assertEquals("Held" + DOT + "replied", fmt(1, 1, NOW, 1, NOW));
    }

    @Test public void m5_waitingForUser_onlyForScheduledWithTheMark() {
        ScheduledMessage scheduled = new ScheduledMessage.Builder().id(1).scheduledFor(NOW).text("t")
                .status(ScheduledMessage.Status.SCHEDULED).build();
        ScheduledMessage held = scheduled.toBuilder().status(ScheduledMessage.Status.HELD)
                .holdReason(HoldReason.REPLIED).build();
        assertTrue(ChipLabel.isWaitingForUser(scheduled, true));
        assertFalse(ChipLabel.isWaitingForUser(scheduled, false));
        assertFalse(ChipLabel.isWaitingForUser(held, true));
        assertFalse(ChipLabel.isWaitingForUser(null, true));
    }

    @Test public void labelsAreDisplayWordsNotEnumNames() {
        for (String s : new String[]{fmt(1, 0, NOW, 1, NOW), fmt(3, 0, NOW, 2, NOW), fmt(1, 0, NOW, 0, NOW)}) {
            for (ScheduledMessage.Status st : ScheduledMessage.Status.values()) {
                assertFalse(s + " shows " + st, s.contains(st.name()));
            }
        }
    }
}
