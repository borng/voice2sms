package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.PresetSlots;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Acceptance (sheet-keep-edits; D52, OD43, R12b, R12a): the sheet model's in-progress state
 * (edited text, chosen preset / picked date-time, hold, If late, which number) survives activity
 * re-creation by round-tripping through a Bundle-like map, in edit and in capture mode. After
 * re-creation the activity rebuilds a fresh model (capture: from the intent; edit: from the
 * reloaded row, after the editable re-check) and restores the saved state over it, so the user's
 * edits win over the stored row's values.
 *
 * <p>Pinned API on {@link SheetViewModelLogic} (called through reflection so the suite compiles
 * before it exists):
 * <pre>
 *   Map&lt;String, Object&gt; saveState()        // Bundle-safe values only: String, Long, Integer,
 *                                              // Boolean (or null); enums by name
 *   boolean restoreState(Map&lt;String, ?&gt; saved) // applies a saveState() map to a freshly built
 *                                              // model of the same mode; false, and nothing
 *                                              // changes, for a null or empty map
 * </pre>
 * Fictional data only (reserved 555-01xx numbers, placeholder text).
 */
public class SheetKeepEditsLogicTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    private static final int[] SLOTS = PresetSlots.DEFAULT_SLOT_MINUTES.clone();
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;
    private static final long NOW = at(2026, Calendar.JUNE, 10, 13, 30);

    private static final String NUM_A = "+14355550102";
    private static final String NUM_B = "+14355550103";
    private static final String STORED = "see you at the trailhead\nbring the blue map";
    private static final String EDITED = "Café é 👩‍👩‍👧 𝄞\nשלום  two  spaces\n\n last line ";

    private static long at(int y, int month, int d, int h, int m) {
        Calendar c = Calendar.getInstance(ZONE);
        c.clear();
        c.set(y, month, d, h, m, 0);
        return c.getTimeInMillis();
    }

    private static ScheduledMessage row(long scheduledFor, boolean hold, LatenessPolicy p, String text) {
        return new ScheduledMessage.Builder()
                .id(42L)
                .createdAt(NOW - HOUR)
                .scheduledFor(scheduledFor)
                .text(text)
                .recipientE164(NUM_A)
                .recipientDisplayName("Test Contact")
                .source(ScheduledMessage.Source.WEBVIEW)
                .holdIfReplied(hold)
                .latenessPolicy(p)
                .status(ScheduledMessage.Status.SCHEDULED)
                .build();
    }

    private static ScheduledMessage stored() {
        return row(NOW + 2 * DAY, true, LatenessPolicy.DEFAULT, STORED);
    }

    private static SheetViewModelLogic edit(ScheduledMessage m) {
        return SheetViewModelLogic.forEdit(m, NOW, ZONE, SLOTS);
    }

    // ------------------------------------------------------------------ reflection

    private static Method method(String name, Class<?>... params) {
        try {
            return SheetViewModelLogic.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            fail("missing SheetViewModelLogic." + name + " (D52 / R12b keep unsaved sheet edits)");
            return null;
        }
    }

    private static Object call(SheetViewModelLogic m, String name, Class<?>[] types, Object... args) {
        try {
            return method(name, types).invoke(m, args);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> save(SheetViewModelLogic m) {
        Object o = call(m, "saveState", new Class<?>[0]);
        assertNotNull("saveState() returns a map", o);
        assertTrue("saveState() returns a Map: " + o.getClass(), o instanceof Map);
        // Copy as a Bundle would: only the values survive, not the model's references.
        Map<String, Object> bundle = new HashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            assertTrue("keys are Strings: " + e.getKey(), e.getKey() instanceof String);
            Object v = e.getValue();
            assertTrue("Bundle-safe value for " + e.getKey() + ": " + (v == null ? null : v.getClass()),
                    v == null || v instanceof String || v instanceof Long || v instanceof Integer
                            || v instanceof Boolean);
            bundle.put((String) e.getKey(), v);
        }
        return bundle;
    }

    private static boolean restore(SheetViewModelLogic m, Map<String, ?> saved) {
        return (Boolean) call(m, "restoreState", new Class<?>[] {Map.class}, saved);
    }

    // ------------------------------------------------------------------ edit mode

    @Test public void editModeRoundTripKeepsTextPickedTimeHoldAndPolicy() {
        SheetViewModelLogic before = edit(stored());
        long picked = NOW + 3 * DAY + 17 * MIN;
        before.setText(EDITED);
        assertTrue(before.pickDateTime(picked, NOW));
        before.setHoldIfReplied(false);
        before.setLatenessPolicy(LatenessPolicy.NEVER_LATE);
        Map<String, Object> saved = save(before);

        SheetViewModelLogic after = edit(stored()); // re-created: row reloaded with stored values
        assertTrue("saved edit state is applied", restore(after, saved));
        assertTrue(after.isEditMode());
        assertEquals("edited text kept exactly (Unicode, newlines, spaces)", EDITED, after.getText());
        assertTrue("still an edit relative to the stored text", after.isTextChanged());
        assertEquals("picked date-time kept", picked, after.getSelectedTimeMs());
        assertEquals("picked time is not a preset", -1, after.getSelectedPresetIndex());
        assertFalse("hold choice kept", after.isHoldIfReplied());
        assertEquals("If late kept", LatenessPolicy.NEVER_LATE, after.getLatenessPolicy());
        assertEquals(NUM_A, after.getE164());
        assertTrue(after.isScheduleEnabled());
    }

    @Test public void editModeRoundTripKeepsTheChosenPreset() {
        SheetViewModelLogic before = edit(stored());
        int idx = before.getPresets().size() - 1;
        assertTrue("fixture needs at least two presets", idx >= 1);
        before.selectPreset(idx);
        long t = before.getSelectedTimeMs();
        Map<String, Object> saved = save(before);

        SheetViewModelLogic after = edit(stored());
        assertTrue(restore(after, saved));
        assertEquals("chosen preset kept", idx, after.getSelectedPresetIndex());
        assertEquals(t, after.getSelectedTimeMs());
    }

    @Test public void editModeRestoreWinsOverTheReloadedRowValues() {
        SheetViewModelLogic before = edit(stored());
        before.setText("unsaved edit");
        before.setHoldIfReplied(false);
        before.setLatenessPolicy(LatenessPolicy.UP_TO_24H);
        long picked = NOW + 5 * DAY + 7 * MIN;
        assertTrue(before.pickDateTime(picked, NOW));
        Map<String, Object> saved = save(before);

        // The reloaded row differs from what the sheet first loaded; the unsaved edits still win.
        SheetViewModelLogic after = edit(row(NOW + 9 * DAY, true, LatenessPolicy.WITHIN_30_MIN, STORED));
        assertTrue(restore(after, saved));
        assertEquals("unsaved edit", after.getText());
        assertEquals(picked, after.getSelectedTimeMs());
        assertFalse(after.isHoldIfReplied());
        assertEquals(LatenessPolicy.UP_TO_24H, after.getLatenessPolicy());
    }

    @Test public void editModeRoundTripKeepsAnEmptiedTextAndUpdateStaysDisabled() {
        SheetViewModelLogic before = edit(stored());
        before.setText("");
        Map<String, Object> saved = save(before);

        SheetViewModelLogic after = edit(stored());
        assertTrue(restore(after, saved));
        assertEquals("an emptied text is an edit too, not 'nothing saved'", "", after.getText());
        assertFalse("empty text still cannot be saved after re-creation", after.isScheduleEnabled());
    }

    @Test public void untouchedEditRoundTripsToTheStoredValues() {
        SheetViewModelLogic before = edit(stored());
        Map<String, Object> saved = save(before);
        SheetViewModelLogic after = edit(stored());
        assertTrue(restore(after, saved));
        assertEquals(STORED, after.getText());
        assertFalse(after.isTextChanged());
        assertEquals(NOW + 2 * DAY, after.getSelectedTimeMs());
        assertTrue(after.isHoldIfReplied());
        assertEquals(LatenessPolicy.DEFAULT, after.getLatenessPolicy());
    }

    @Test public void nothingSavedKeepsTheLoadedValues() {
        SheetViewModelLogic m = edit(stored());
        assertFalse("null: nothing to restore", restore(m, null));
        assertFalse("empty: nothing to restore", restore(m, new HashMap<String, Object>()));
        assertEquals(STORED, m.getText());
        assertEquals(NOW + 2 * DAY, m.getSelectedTimeMs());
        assertTrue(m.isHoldIfReplied());
        assertEquals(LatenessPolicy.DEFAULT, m.getLatenessPolicy());
    }

    // ------------------------------------------------------------------ capture mode

    @Test public void captureModeRoundTripKeepsChosenNumberPickedTimeHoldAndPolicy() {
        String[] candidates = {NUM_A, NUM_B};
        SheetViewModelLogic before = SheetViewModelLogic.forCapture(null, candidates, true, NOW, ZONE, SLOTS);
        assertTrue(before.showsCandidateChoice());
        assertTrue(before.chooseCandidate(NUM_B));
        long picked = NOW + DAY + 11 * MIN;
        assertTrue(before.pickDateTime(picked, NOW));
        before.setHoldIfReplied(false);
        before.setLatenessPolicy(LatenessPolicy.WITHIN_30_MIN);
        Map<String, Object> saved = save(before);

        SheetViewModelLogic after = SheetViewModelLogic.forCapture(null, candidates, true, NOW, ZONE, SLOTS);
        assertNull("fresh model: no number chosen yet", after.getE164());
        assertTrue("saved capture state is applied", restore(after, saved));
        assertFalse(after.isEditMode());
        assertEquals("chosen number kept", NUM_B, after.getE164());
        assertEquals(picked, after.getSelectedTimeMs());
        assertFalse(after.isHoldIfReplied());
        assertEquals(LatenessPolicy.WITHIN_30_MIN, after.getLatenessPolicy());
        assertTrue(after.isScheduleEnabled());
    }

    @Test public void captureModeRoundTripKeepsATypedNumberAndChosenPreset() {
        SheetViewModelLogic before = SheetViewModelLogic.forCapture(null, null, false, NOW, ZONE, SLOTS);
        assertTrue(before.showsNumberEntry());
        assertTrue(before.enterNumber("(435) 555-0104"));
        before.selectPreset(1);
        long t = before.getSelectedTimeMs();
        Map<String, Object> saved = save(before);

        SheetViewModelLogic after = SheetViewModelLogic.forCapture(null, null, false, NOW, ZONE, SLOTS);
        assertTrue(restore(after, saved));
        assertEquals("typed number kept", "+14355550104", after.getE164());
        assertEquals(1, after.getSelectedPresetIndex());
        assertEquals(t, after.getSelectedTimeMs());
        assertFalse("hold default from the first build kept", after.isHoldIfReplied());
    }

    @Test public void captureModeRoundTripKeepsTheConfirmedUnconfirmedNumber() {
        String[] one = {NUM_A};
        SheetViewModelLogic before =
                SheetViewModelLogic.forCapture(null, one, true, true, NOW, ZONE, SLOTS);
        assertNull(before.getE164());
        assertTrue(before.chooseCandidate(NUM_A));
        Map<String, Object> saved = save(before);

        SheetViewModelLogic after = SheetViewModelLogic.forCapture(null, one, true, true, NOW, ZONE, SLOTS);
        assertTrue(restore(after, saved));
        assertEquals("the user's confirmation survives re-creation", NUM_A, after.getE164());
    }

    @Test public void savedStateHoldsOnlyBundleSafeValues() {
        SheetViewModelLogic m = edit(stored());
        m.setText(EDITED);
        m.setLatenessPolicy(LatenessPolicy.UP_TO_24H);
        Map<String, Object> saved = save(m); // asserts value types
        assertFalse("something is saved", saved.isEmpty());
    }
}
