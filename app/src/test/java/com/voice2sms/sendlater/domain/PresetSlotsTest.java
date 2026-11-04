package com.voice2sms.sendlater.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;

import org.junit.Test;

/** R13 / AC5: rolling day-part presets for clocks at 07:00, 13:30 and 22:30. */
public class PresetSlotsTest {

    private static final String ZONE_ID = "America/Denver";
    private static final TimeZone ZONE = TimeZone.getTimeZone(ZONE_ID);
    private static final LocalDate DAY = LocalDate.of(2026, 6, 10); // a Wednesday, no DST edge
    private static final int[] DEFAULT_SLOTS = {8 * 60, 13 * 60, 18 * 60, 21 * 60};
    private static final long HOUR = 3_600_000L;

    private static long at(LocalDate d, int h, int m) {
        return LocalDateTime.of(d, java.time.LocalTime.of(h, m)).atZone(ZoneId.of(ZONE_ID)).toInstant().toEpochMilli();
    }

    private static List<PresetSlots.Preset> compute(int h, int m) {
        return PresetSlots.compute(at(DAY, h, m), ZONE, DEFAULT_SLOTS);
    }

    private static void assertFirstIsInOneHour(List<PresetSlots.Preset> p, long now) {
        assertTrue("need 'In 1 hour' plus 3-4 slots, got " + p.size(), p.size() >= 4 && p.size() <= 5);
        assertTrue(p.get(0).getLabel(), p.get(0).getLabel().startsWith("In 1 hour"));
        assertEquals(now + HOUR, p.get(0).getTimeMs());
    }

    private static void assertSlot(PresetSlots.Preset p, String labelPrefix, long timeMs) {
        assertTrue("label '" + p.getLabel() + "' should start with '" + labelPrefix + "'",
                p.getLabel().startsWith(labelPrefix));
        assertEquals("time for " + labelPrefix, timeMs, p.getTimeMs());
    }

    @Test
    public void at0700_rollsMorningAfternoonEvening() {
        List<PresetSlots.Preset> p = compute(7, 0);
        assertFirstIsInOneHour(p, at(DAY, 7, 0));
        assertSlot(p.get(1), "This morning", at(DAY, 8, 0));
        assertSlot(p.get(2), "This afternoon", at(DAY, 13, 0));
        assertSlot(p.get(3), "This evening", at(DAY, 18, 0));
        if (p.size() > 4) assertSlot(p.get(4), "Tonight", at(DAY, 21, 0));
    }

    @Test
    public void at1330_rollsEveningNightIntoTomorrow() {
        List<PresetSlots.Preset> p = compute(13, 30);
        assertFirstIsInOneHour(p, at(DAY, 13, 30));
        assertSlot(p.get(1), "This evening", at(DAY, 18, 0));
        assertSlot(p.get(2), "Tonight", at(DAY, 21, 0));
        assertSlot(p.get(3), "Tomorrow morning", at(DAY.plusDays(1), 8, 0));
        if (p.size() > 4) assertSlot(p.get(4), "Tomorrow afternoon", at(DAY.plusDays(1), 13, 0));
    }

    @Test
    public void at2230_allSlotsAreTomorrow() {
        List<PresetSlots.Preset> p = compute(22, 30);
        assertFirstIsInOneHour(p, at(DAY, 22, 30));
        assertSlot(p.get(1), "Tomorrow morning", at(DAY.plusDays(1), 8, 0));
        assertSlot(p.get(2), "Tomorrow afternoon", at(DAY.plusDays(1), 13, 0));
        assertSlot(p.get(3), "Tomorrow evening", at(DAY.plusDays(1), 18, 0));
        if (p.size() > 4) assertSlot(p.get(4), "Tomorrow night", at(DAY.plusDays(1), 21, 0));
    }

    @Test
    public void slotLessThan10MinutesAhead_isSkipped() {
        List<PresetSlots.Preset> p = compute(12, 51); // afternoon 13:00 is 9 min ahead
        assertFirstIsInOneHour(p, at(DAY, 12, 51));
        assertSlot(p.get(1), "This evening", at(DAY, 18, 0));
    }

    @Test
    public void slotExactly10MinutesAhead_isKept() {
        List<PresetSlots.Preset> p = compute(12, 50);
        assertFirstIsInOneHour(p, at(DAY, 12, 50));
        assertSlot(p.get(1), "This afternoon", at(DAY, 13, 0));
    }

    @Test
    public void slotTimesAreParameters() {
        int[] custom = {9 * 60, 14 * 60 + 30, 19 * 60, 22 * 60};
        List<PresetSlots.Preset> p = PresetSlots.compute(at(DAY, 7, 0), ZONE, custom);
        assertFirstIsInOneHour(p, at(DAY, 7, 0));
        assertSlot(p.get(1), "This morning", at(DAY, 9, 0));
        assertSlot(p.get(2), "This afternoon", at(DAY, 14, 30));
        assertSlot(p.get(3), "This evening", at(DAY, 19, 0));
    }

    @Test
    public void slotsAreStrictlyAscendingAndInTheFuture() {
        for (int[] hm : new int[][] {{7, 0}, {13, 30}, {22, 30}, {0, 5}, {20, 55}}) {
            long now = at(DAY, hm[0], hm[1]);
            List<PresetSlots.Preset> p = PresetSlots.compute(now, ZONE, DEFAULT_SLOTS);
            for (int i = 1; i < p.size(); i++) {
                assertTrue("slot " + i + " must be >= 10 min ahead", p.get(i).getTimeMs() - now >= 10 * 60_000L);
                if (i > 1) assertTrue("slots ascend", p.get(i).getTimeMs() > p.get(i - 1).getTimeMs());
            }
        }
    }
}
