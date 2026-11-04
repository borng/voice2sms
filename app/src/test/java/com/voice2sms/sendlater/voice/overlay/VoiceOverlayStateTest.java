package com.voice2sms.sendlater.voice.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.voice.logic.VoiceScreenSignature;

import org.junit.Test;

/**
 * VoiceOverlayState: the pure state machine behind the native Voice edge tab and chip
 * (R1, R2, R17, R19, D12/OD13, AC1, AC6, AC11).
 *
 * <p>Pinned API: {@code VoiceOverlayState.compute(Level signature, boolean draftNonEmpty,
 * boolean isGroup, boolean enabled, int pendingCount)} returns a value with
 * {@code isTabVisible()} and {@code isChipVisible()}; {@code VoiceOverlayState.isGroupTitle(String)}
 * is the OD13 group heuristic (true = treat as group = no tab, no chip).
 */
public class VoiceOverlayStateTest {

    private static final VoiceScreenSignature.Level HIGH = VoiceScreenSignature.Level.HIGH;
    private static final VoiceScreenSignature.Level NONE = VoiceScreenSignature.Level.NONE;

    // ---------------------------------------------------------------- tab (R1)

    @Test public void tabShowsOnHighOneToOneNonEmptyDraftWhenEnabled() {
        assertTrue(VoiceOverlayState.compute(HIGH, true, false, true, 0).isTabVisible());
    }

    @Test public void noTabWhenDraftEmpty() {
        assertFalse(VoiceOverlayState.compute(HIGH, false, false, true, 0).isTabVisible());
    }

    @Test public void noTabInGroupThread() {
        assertFalse(VoiceOverlayState.compute(HIGH, true, true, true, 0).isTabVisible());
    }

    @Test public void noTabBelowHighConfidence() {
        assertFalse(VoiceOverlayState.compute(NONE, true, false, true, 0).isTabVisible());
    }

    @Test public void noTabWhenSendLaterDisabled() {
        assertFalse(VoiceOverlayState.compute(HIGH, true, false, false, 0).isTabVisible());
    }

    @Test public void tabIsIndependentOfPendingCount() {
        assertTrue(VoiceOverlayState.compute(HIGH, true, false, true, 2).isTabVisible());
    }

    // ---------------------------------------------------------------- chip (R17, R19)

    @Test public void chipShowsWithPendingEvenWhenDraftEmpty() {
        VoiceOverlayState s = VoiceOverlayState.compute(HIGH, false, false, true, 1);
        assertTrue("R17: chip is independent of the draft", s.isChipVisible());
        assertFalse("no tab with an empty draft", s.isTabVisible());
    }

    @Test public void chipShowsWithPendingAndNonEmptyDraft() {
        VoiceOverlayState s = VoiceOverlayState.compute(HIGH, true, false, true, 3);
        assertTrue(s.isChipVisible());
        assertTrue(s.isTabVisible());
    }

    @Test public void noChipWithoutPending() {
        assertFalse(VoiceOverlayState.compute(HIGH, true, false, true, 0).isChipVisible());
    }

    @Test public void noChipInGroupThread() {
        assertFalse(VoiceOverlayState.compute(HIGH, true, true, true, 2).isChipVisible());
    }

    @Test public void noChipBelowHighConfidence() {
        assertFalse(VoiceOverlayState.compute(NONE, true, false, true, 2).isChipVisible());
    }

    @Test public void noChipWhenSendLaterDisabled() {
        assertFalse("AC11: disabling Send Later removes the chip",
                VoiceOverlayState.compute(HIGH, false, false, false, 2).isChipVisible());
    }

    @Test public void nothingShowsWhenScreenIsNotAThread() {
        VoiceOverlayState s = VoiceOverlayState.compute(NONE, false, false, true, 5);
        assertFalse(s.isTabVisible());
        assertFalse(s.isChipVisible());
    }

    /** Every input combination against the acceptance formula. */
    @Test public void exhaustiveTruthTable() {
        VoiceScreenSignature.Level[] levels = {HIGH, NONE};
        boolean[] bools = {true, false};
        int[] counts = {0, 1, 3};
        for (VoiceScreenSignature.Level level : levels) {
            for (boolean draft : bools) {
                for (boolean group : bools) {
                    for (boolean enabled : bools) {
                        for (int pending : counts) {
                            VoiceOverlayState s = VoiceOverlayState.compute(level, draft, group, enabled, pending);
                            boolean base = level == HIGH && !group && enabled;
                            String ctx = level + " draft=" + draft + " group=" + group
                                    + " enabled=" + enabled + " pending=" + pending;
                            assertEquals("tab " + ctx, base && draft, s.isTabVisible());
                            assertEquals("chip " + ctx, base && pending > 0, s.isChipVisible());
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- group heuristic (D12, OD13)

    @Test public void twoWordNameIsOneToOne() {
        assertFalse(VoiceOverlayState.isGroupTitle("Alex Example"));
    }

    @Test public void singleWordNameIsOneToOne() {
        assertFalse(VoiceOverlayState.isGroupTitle("Alex"));
    }

    @Test public void phoneNumberTitleIsOneToOne() {
        assertFalse(VoiceOverlayState.isGroupTitle("(435) 555-0123"));
        assertFalse(VoiceOverlayState.isGroupTitle("+1 435-555-0123"));
    }

    @Test public void commaMeansGroup() {
        assertTrue(VoiceOverlayState.isGroupTitle("Alex, Sam"));
    }

    @Test public void ampersandMeansGroup() {
        assertTrue(VoiceOverlayState.isGroupTitle("Alex & Sam"));
    }

    @Test public void wordAndMeansGroup() {
        assertTrue(VoiceOverlayState.isGroupTitle("Alex and Sam"));
    }

    @Test public void moreThanTwoCapitalisedWordsMeansGroup() {
        assertTrue(VoiceOverlayState.isGroupTitle("Alex Sam Robin"));
    }

    @Test public void unsureTitleIsTreatedAsGroup() {
        assertTrue("null title: unsure -> group", VoiceOverlayState.isGroupTitle(null));
        assertTrue("empty title: unsure -> group", VoiceOverlayState.isGroupTitle(""));
        assertTrue("blank title: unsure -> group", VoiceOverlayState.isGroupTitle("   "));
    }
}
