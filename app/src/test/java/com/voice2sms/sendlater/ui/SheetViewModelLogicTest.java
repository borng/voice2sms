package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.PresetSlots;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Acceptance (sheet-ui; R4, R12-R16, I3): the pure model behind SendLaterSheetActivity.
 *
 * <p>Pinned API (com.voice2sms.sendlater.ui.SheetViewModelLogic, pure JVM):
 * <pre>
 *   static SheetViewModelLogic forCapture(String e164OrNull, String[] candidatesOrNull,
 *                                         boolean holdDefault, long nowMs, TimeZone zone, int[] slotMinutes)
 *   static SheetViewModelLogic forEdit(ScheduledMessage existing, long nowMs, TimeZone zone, int[] slotMinutes)
 *   boolean isEditMode()
 *   RecipientState getRecipientState()          // enum RecipientState { RESOLVED, NEEDS_CHOICE, NEEDS_ENTRY }
 *   String getE164()                            // E.164 when RESOLVED, else null
 *   List&lt;String&gt; getCandidates()               // E.164 choices for "Which number?", never null
 *   boolean chooseCandidate(String e164)        // only a listed candidate is accepted
 *   boolean enterNumber(String raw)             // manual entry, must normalize to E.164
 *   List&lt;PresetSlots.Preset&gt; getPresets()      // exactly PresetSlots.compute(now, zone, slots)
 *   void selectPreset(int index)
 *   boolean pickDateTime(long timeMs, long nowMs)  // false (and nothing changes) unless timeMs > nowMs
 *   long getSelectedTimeMs()
 *   boolean isScheduleEnabled()
 *   String getPrimaryButtonLabel()              // "Schedule for &lt;time&gt;" (capture) / "Update" (edit)
 *   String getSecondaryButtonLabel()            // "Cancel" (capture) / "Cancel message" (edit)
 *   boolean isHoldIfReplied(); void setHoldIfReplied(boolean)
 *   List&lt;LatenessPolicy&gt; getIfLateOptions(); LatenessPolicy getLatenessPolicy(); void setLatenessPolicy(LatenessPolicy)
 * </pre>
 * All numbers below are fictional 555-01xx numbers.
 */
public class SheetViewModelLogicTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    private static final int[] SLOTS = PresetSlots.DEFAULT_SLOT_MINUTES.clone();
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;
    private static final long NOW = at(2026, Calendar.JUNE, 10, 13, 30); // Wed 1:30 PM

    private static final String NUM_A = "+14355550101";
    private static final String NUM_B = "+14355550102";
    private static final String NUM_C = "+14355550103";

    private static long at(int y, int month, int d, int h, int m) {
        Calendar c = Calendar.getInstance(ZONE);
        c.clear();
        c.set(y, month, d, h, m, 0);
        return c.getTimeInMillis();
    }

    private static String clock(long t) {
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.getDefault());
        f.setTimeZone(ZONE);
        return f.format(new Date(t));
    }

    private static SheetViewModelLogic capture(String e164, String[] candidates) {
        return SheetViewModelLogic.forCapture(e164, candidates, true, NOW, ZONE, SLOTS);
    }

    private static ScheduledMessage existing() {
        return new ScheduledMessage.Builder()
                .id(42L)
                .createdAt(NOW - HOUR)
                .scheduledFor(NOW + 2 * DAY)
                .text("see you at the trailhead")
                .recipientE164(NUM_B)
                .recipientDisplayName("Test Contact")
                .source(ScheduledMessage.Source.WEBVIEW)
                .holdIfReplied(false)
                .latenessPolicy(LatenessPolicy.NEVER_LATE)
                .status(ScheduledMessage.Status.SCHEDULED)
                .build();
    }

    // ------------------------------------------------------------------ recipient (R4, I3)

    @Test public void scheduleEnabledImmediatelyWhenE164Given() {
        SheetViewModelLogic m = capture(NUM_A, null);
        assertEquals(SheetViewModelLogic.RecipientState.RESOLVED, m.getRecipientState());
        assertEquals(NUM_A, m.getE164());
        assertTrue("e164 known + default time -> Schedule enabled", m.isScheduleEnabled());
    }

    @Test public void e164WinsOverCandidatesAndIsNeverReAsked() {
        SheetViewModelLogic m = capture(NUM_A, new String[] {NUM_B, NUM_C});
        assertEquals(SheetViewModelLogic.RecipientState.RESOLVED, m.getRecipientState());
        assertEquals(NUM_A, m.getE164());
        assertTrue(m.isScheduleEnabled());
    }

    @Test public void severalCandidatesNeedChoiceAndScheduleStaysDisabledUntilPicked() {
        SheetViewModelLogic m = capture(null, new String[] {NUM_A, NUM_B, NUM_C});
        assertEquals(SheetViewModelLogic.RecipientState.NEEDS_CHOICE, m.getRecipientState());
        assertEquals(Arrays.asList(NUM_A, NUM_B, NUM_C), m.getCandidates());
        assertNull(m.getE164());
        assertFalse("Which number? unanswered -> disabled", m.isScheduleEnabled());

        m.selectPreset(0);
        assertFalse("picking a time does not resolve the recipient", m.isScheduleEnabled());

        assertTrue(m.chooseCandidate(NUM_B));
        assertEquals(SheetViewModelLogic.RecipientState.RESOLVED, m.getRecipientState());
        assertEquals(NUM_B, m.getE164());
        assertTrue(m.isScheduleEnabled());
    }

    @Test public void choosingANumberThatIsNotACandidateIsRejected() {
        SheetViewModelLogic m = capture(null, new String[] {NUM_A, NUM_B});
        assertFalse(m.chooseCandidate(NUM_C));
        assertFalse(m.chooseCandidate("Test Contact"));
        assertFalse(m.chooseCandidate(null));
        assertNull(m.getE164());
        assertFalse(m.isScheduleEnabled());
    }

    @Test public void singleCandidateIsChosenSilently() {
        SheetViewModelLogic m = capture(null, new String[] {NUM_C});
        assertEquals(SheetViewModelLogic.RecipientState.RESOLVED, m.getRecipientState());
        assertEquals(NUM_C, m.getE164());
        assertTrue(m.isScheduleEnabled());
    }

    @Test public void noCandidatesNeedsNumberEntryAndDisabledUntilAValidNumberIsEntered() {
        SheetViewModelLogic m = capture(null, null);
        assertEquals(SheetViewModelLogic.RecipientState.NEEDS_ENTRY, m.getRecipientState());
        assertTrue(m.getCandidates().isEmpty());
        assertFalse(m.isScheduleEnabled());

        assertFalse("a name is not a number", m.enterNumber("Test Contact"));
        assertFalse(m.enterNumber(""));
        assertFalse(m.isScheduleEnabled());

        assertTrue(m.enterNumber("(435) 555-0103"));
        assertEquals(SheetViewModelLogic.RecipientState.RESOLVED, m.getRecipientState());
        assertEquals(NUM_C, m.getE164());
        assertTrue(m.isScheduleEnabled());
    }

    @Test public void displayNameAloneIsNeverARecipient() {
        // A name smuggled into the e164 slot or the candidate list must not resolve (I3).
        SheetViewModelLogic m = capture("Test Contact", new String[] {"Test Contact", "Pharmacy"});
        assertNotEquals(SheetViewModelLogic.RecipientState.RESOLVED, m.getRecipientState());
        assertNull(m.getE164());
        for (String c : m.getCandidates()) assertTrue("not E.164: " + c, c.matches("\\+[1-9][0-9]{6,14}"));
        assertFalse(m.isScheduleEnabled());
    }

    @Test public void emptyCandidateArrayIsNeedsEntry() {
        SheetViewModelLogic m = capture("", new String[0]);
        assertEquals(SheetViewModelLogic.RecipientState.NEEDS_ENTRY, m.getRecipientState());
        assertFalse(m.isScheduleEnabled());
    }

    // ------------------------------------------------------------------ presets + button label (R12-R14)

    @Test public void presetsAreThePresetSlotsRowsForNow() {
        SheetViewModelLogic m = capture(NUM_A, null);
        List<PresetSlots.Preset> expected = PresetSlots.compute(NOW, ZONE, SLOTS);
        List<PresetSlots.Preset> got = m.getPresets();
        assertEquals(expected.size(), got.size());
        assertTrue("In 1 hour + 3-4 slot rows", got.size() >= 4 && got.size() <= 5);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).getLabel(), got.get(i).getLabel());
            assertEquals(expected.get(i).getTimeMs(), got.get(i).getTimeMs());
        }
    }

    @Test public void initialSelectionIsAFuturePresetAndLabelNamesIt() {
        SheetViewModelLogic m = capture(NUM_A, null);
        long sel = m.getSelectedTimeMs();
        assertTrue("selected time must be in the future", sel > NOW);
        boolean isPreset = false;
        for (PresetSlots.Preset p : m.getPresets()) isPreset |= p.getTimeMs() == sel;
        assertTrue("initial selection is one of the presets", isPreset);
        String label = m.getPrimaryButtonLabel();
        assertTrue(label, label.startsWith("Schedule for "));
        assertTrue(label + " should name " + clock(sel), label.contains(clock(sel)));
    }

    @Test public void buttonLabelFollowsTheSelectedPreset() {
        SheetViewModelLogic m = capture(NUM_A, null);
        List<PresetSlots.Preset> presets = m.getPresets();
        for (int i = 0; i < presets.size(); i++) {
            m.selectPreset(i);
            long t = presets.get(i).getTimeMs();
            assertEquals(t, m.getSelectedTimeMs());
            String label = m.getPrimaryButtonLabel();
            assertTrue(label, label.startsWith("Schedule for "));
            assertTrue(label + " should name " + clock(t), label.contains(clock(t)));
        }
        m.selectPreset(1);
        String a = m.getPrimaryButtonLabel();
        m.selectPreset(2);
        assertNotEquals("different presets give different labels", a, m.getPrimaryButtonLabel());
    }

    @Test public void futurePickerTimeIsAcceptedAndUpdatesTheButton() {
        SheetViewModelLogic m = capture(NUM_A, null);
        long picked = at(2026, Calendar.JUNE, 12, 15, 45); // Fri 3:45 PM, not a preset
        assertTrue(m.pickDateTime(picked, NOW));
        assertEquals(picked, m.getSelectedTimeMs());
        assertTrue(m.getPrimaryButtonLabel(), m.getPrimaryButtonLabel().startsWith("Schedule for "));
        assertTrue(m.getPrimaryButtonLabel(), m.getPrimaryButtonLabel().contains(clock(picked)));
        assertTrue(m.isScheduleEnabled());
    }

    @Test public void pastOrPresentPickerTimeIsRejectedAndNothingChanges() {
        SheetViewModelLogic m = capture(NUM_A, null);
        long before = m.getSelectedTimeMs();
        String labelBefore = m.getPrimaryButtonLabel();

        assertFalse("past time rejected", m.pickDateTime(NOW - MIN, NOW));
        assertFalse("now is not the future", m.pickDateTime(NOW, NOW));
        assertFalse("yesterday rejected", m.pickDateTime(NOW - DAY, NOW));
        assertEquals(before, m.getSelectedTimeMs());
        assertEquals(labelBefore, m.getPrimaryButtonLabel());
        assertTrue(m.isScheduleEnabled());
    }

    @Test public void pickerJudgesTheFutureAgainstTheSuppliedNow() {
        SheetViewModelLogic m = capture(NUM_A, null);
        long t = NOW + 10 * MIN;
        assertFalse("sheet left open: t is already past at the later now", m.pickDateTime(t, NOW + 20 * MIN));
        assertTrue(m.pickDateTime(t, NOW));
        assertEquals(t, m.getSelectedTimeMs());
    }

    // ------------------------------------------------------------------ hold checkbox + If late (R12, D10)

    @Test public void holdCheckboxDefaultsToTheSuppliedSetting() {
        assertTrue(SheetViewModelLogic.forCapture(NUM_A, null, true, NOW, ZONE, SLOTS).isHoldIfReplied());
        assertFalse(SheetViewModelLogic.forCapture(NUM_A, null, false, NOW, ZONE, SLOTS).isHoldIfReplied());
    }

    @Test public void holdCheckboxDefaultIsOnWhenTheSettingIsUnset() {
        boolean fromSettings = SendLaterSettings.Parse.holdDefault(new HashMap<String, Object>());
        SheetViewModelLogic m = SheetViewModelLogic.forCapture(NUM_A, null, fromSettings, NOW, ZONE, SLOTS);
        assertTrue("default ON (R12)", m.isHoldIfReplied());
        m.setHoldIfReplied(false);
        assertFalse(m.isHoldIfReplied());
        assertTrue("unchecking hold does not block scheduling", m.isScheduleEnabled());
    }

    @Test public void ifLateOffersEveryPolicyAndDefaultsToTheD10Policy() {
        SheetViewModelLogic m = capture(NUM_A, null);
        List<LatenessPolicy> opts = m.getIfLateOptions();
        assertEquals(LatenessPolicy.values().length, opts.size());
        assertTrue(opts.containsAll(Arrays.asList(LatenessPolicy.values())));
        assertEquals(LatenessPolicy.DEFAULT, m.getLatenessPolicy());
        m.setLatenessPolicy(LatenessPolicy.WITHIN_30_MIN);
        assertEquals(LatenessPolicy.WITHIN_30_MIN, m.getLatenessPolicy());
    }

    // ------------------------------------------------------------------ capture vs edit labels (R15, R16)

    @Test public void captureModeButtonsAreScheduleAndCancel() {
        SheetViewModelLogic m = capture(NUM_A, null);
        assertFalse(m.isEditMode());
        assertTrue(m.getPrimaryButtonLabel().startsWith("Schedule for "));
        assertEquals("Cancel", m.getSecondaryButtonLabel());
    }

    @Test public void editModeIsPrefilledAndExposesUpdateAndCancelMessage() {
        ScheduledMessage msg = existing();
        SheetViewModelLogic m = SheetViewModelLogic.forEdit(msg, NOW, ZONE, SLOTS);
        assertTrue(m.isEditMode());
        assertEquals("Update", m.getPrimaryButtonLabel());
        assertEquals("Cancel message", m.getSecondaryButtonLabel());
        assertEquals(SheetViewModelLogic.RecipientState.RESOLVED, m.getRecipientState());
        assertEquals(NUM_B, m.getE164());
        assertEquals(msg.getScheduledFor(), m.getSelectedTimeMs());
        assertFalse("hold prefilled from the record", m.isHoldIfReplied());
        assertEquals(LatenessPolicy.NEVER_LATE, m.getLatenessPolicy());
        assertTrue(m.isScheduleEnabled());
    }

    @Test public void editModeKeepsUpdateLabelWhenTimeChanges() {
        SheetViewModelLogic m = SheetViewModelLogic.forEdit(existing(), NOW, ZONE, SLOTS);
        m.selectPreset(0);
        assertEquals(m.getPresets().get(0).getTimeMs(), m.getSelectedTimeMs());
        assertEquals("Update", m.getPrimaryButtonLabel());
        assertTrue(m.pickDateTime(NOW + 3 * DAY, NOW));
        assertEquals("Update", m.getPrimaryButtonLabel());
        assertFalse(m.pickDateTime(NOW - HOUR, NOW));
        assertEquals(NOW + 3 * DAY, m.getSelectedTimeMs());
    }

    @Test public void editModeHoldDefaultComesFromTheRecordNotTheSetting() {
        ScheduledMessage msg = existing().toBuilder().holdIfReplied(true).build();
        assertTrue(SheetViewModelLogic.forEdit(msg, NOW, ZONE, SLOTS).isHoldIfReplied());
        assertNotNull(SheetViewModelLogic.forEdit(msg, NOW, ZONE, SLOTS).getCandidates());
    }
}
