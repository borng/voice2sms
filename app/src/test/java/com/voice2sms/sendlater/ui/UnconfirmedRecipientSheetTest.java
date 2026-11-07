package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.PresetSlots;

import org.junit.Test;

import java.util.Collections;
import java.util.TimeZone;

/**
 * deliver sec-M1: unconfirmed (thread-directory-only) candidates are never chosen silently; a
 * single one is shown as "Which number?" and Schedule waits for the pick. Fictional numbers.
 */
public class UnconfirmedRecipientSheetTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    private static final int[] SLOTS = PresetSlots.DEFAULT_SLOT_MINUTES.clone();
    private static final long NOW = 1_780_000_000_000L;
    private static final String A = "+14355550101";

    @Test
    public void singleUnconfirmedCandidateMustBePicked() {
        SheetViewModelLogic m = SheetViewModelLogic.forCapture(null, new String[] {A}, true, true, NOW, ZONE, SLOTS);
        assertEquals(SheetViewModelLogic.RecipientState.NEEDS_CHOICE, m.getRecipientState());
        assertTrue(m.showsCandidateChoice());
        assertEquals(Collections.singletonList(A), m.getCandidates());
        assertNull(m.getE164());
        assertFalse("Schedule disabled until the number is confirmed", m.isScheduleEnabled());
        assertFalse(m.chooseCandidate("+14355550102"));
        assertTrue(m.chooseCandidate(A));
        assertEquals(A, m.getE164());
        assertTrue(m.isScheduleEnabled());
    }

    @Test
    public void confirmedPathsAreUnchanged() {
        assertEquals(SheetViewModelLogic.RecipientState.RESOLVED, SheetViewModelLogic.forCapture(
                null, new String[] {A}, false, true, NOW, ZONE, SLOTS).getRecipientState());
        assertEquals(SheetViewModelLogic.RecipientState.RESOLVED, SheetViewModelLogic.forCapture(
                null, new String[] {A}, true, NOW, ZONE, SLOTS).getRecipientState());
        SheetViewModelLogic given = SheetViewModelLogic.forCapture(A, new String[] {A}, true, true, NOW, ZONE, SLOTS);
        assertEquals("a given number still wins", A, given.getE164());
        assertFalse(given.showsCandidateChoice());
        assertEquals(SheetViewModelLogic.RecipientState.NEEDS_ENTRY, SheetViewModelLogic.forCapture(
                null, null, true, true, NOW, ZONE, SLOTS).getRecipientState());
    }
}
