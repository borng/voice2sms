package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.PresetSlots;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** Implementer extras (sheet-ui): behaviour beyond the acceptance suite. Fictional 555-01xx numbers. */
public class SheetViewModelLogicExtrasTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    private static final int[] SLOTS = PresetSlots.DEFAULT_SLOT_MINUTES.clone();
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private static final long NOW;
    static {
        Calendar c = Calendar.getInstance(ZONE);
        c.clear();
        c.set(2026, Calendar.JUNE, 10, 13, 30, 0);
        NOW = c.getTimeInMillis();
    }

    @Test public void editingTheTypedNumberToSomethingInvalidDisablesScheduleAgain() {
        SheetViewModelLogic m = SheetViewModelLogic.forCapture(null, null, true, NOW, ZONE, SLOTS);
        assertTrue(m.enterNumber("435 555 0103"));
        assertTrue(m.isScheduleEnabled());
        assertFalse(m.enterNumber("435 555"));
        assertNull(m.getE164());
        assertFalse(m.isScheduleEnabled());
    }

    @Test public void aGivenNumberCannotBeOverriddenByChoiceOrEntry() {
        SheetViewModelLogic m = SheetViewModelLogic.forCapture("+14355550101",
                new String[] {"+14355550102"}, true, NOW, ZONE, SLOTS);
        assertFalse(m.showsCandidateChoice());
        assertFalse(m.showsNumberEntry());
        assertFalse(m.chooseCandidate("+14355550102"));
        assertFalse(m.enterNumber("+14355550103"));
        assertEquals("+14355550101", m.getE164());
    }

    @Test public void choiceCanBeChangedAmongCandidates() {
        SheetViewModelLogic m = SheetViewModelLogic.forCapture(null,
                new String[] {"+14355550101", "+14355550102"}, true, NOW, ZONE, SLOTS);
        assertTrue(m.showsCandidateChoice());
        assertTrue(m.chooseCandidate("+14355550101"));
        assertTrue(m.chooseCandidate("+14355550102"));
        assertEquals("+14355550102", m.getE164());
    }

    @Test public void pickedTimeMatchingAPresetSelectsThatRow() {
        SheetViewModelLogic m = SheetViewModelLogic.forCapture("+14355550101", null, true, NOW, ZONE, SLOTS);
        long t = m.getPresets().get(2).getTimeMs();
        assertTrue(m.pickDateTime(t, NOW));
        assertEquals(2, m.getSelectedPresetIndex());
        assertTrue(m.pickDateTime(t + 7 * 60_000L, NOW));
        assertEquals(-1, m.getSelectedPresetIndex());
    }

    @Test public void selectedTimeCanGoStaleWhileTheSheetIsOpen() {
        SheetViewModelLogic m = SheetViewModelLogic.forCapture("+14355550101", null, true, NOW, ZONE, SLOTS);
        assertTrue(m.isSelectedTimeInFuture(NOW));
        assertFalse(m.isSelectedTimeInFuture(m.getSelectedTimeMs()));
    }

    @Test public void labelAddsTheDateBeyondTheComingWeek() {
        String near = SheetViewModelLogic.timeLabel(NOW + DAY, NOW, ZONE);
        String far = SheetViewModelLogic.timeLabel(NOW + 10 * DAY, NOW, ZONE);
        assertFalse(near, near.contains(","));
        assertTrue(far, far.contains(","));
    }

    @Test public void outOfRangePresetIndexIsIgnored() {
        SheetViewModelLogic m = SheetViewModelLogic.forCapture("+14355550101", null, true, NOW, ZONE, SLOTS);
        long before = m.getSelectedTimeMs();
        m.selectPreset(-1);
        m.selectPreset(99);
        assertEquals(before, m.getSelectedTimeMs());
    }
}
