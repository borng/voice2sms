package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.PresetSlots;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Acceptance (edit-text; D49, OD40, R12a, R12): the sheet model's editable-text state. In edit
 * mode the message text is editable and prefilled with the stored text (exact Unicode, multi-line);
 * empty or whitespace-only text disables Update. Capture ("new schedule") mode stays read-only.
 *
 * <p>Pinned API on {@link SheetViewModelLogic} (called through reflection so the suite compiles
 * before it exists):
 * <pre>
 *   boolean isTextEditable()   // true in edit mode only
 *   String  getText()          // edit mode: the stored text until changed, then exactly what was set
 *   void    setText(String)    // edit mode: the user's edit (return value, if any, is not asserted)
 *   boolean isScheduleEnabled()  // existing gate of the primary button ("Update" in edit mode):
 *                                // false while the edited text is empty or whitespace-only
 * </pre>
 * Fictional data only (reserved 555-01xx numbers, placeholder text).
 */
public class EditTextLogicTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    private static final int[] SLOTS = PresetSlots.DEFAULT_SLOT_MINUTES.clone();
    private static final long HOUR = 60 * 60_000L;
    private static final long DAY = 24 * HOUR;
    private static final long NOW = at(2026, Calendar.JUNE, 10, 13, 30);

    private static final String NUM = "+14355550102";
    private static final String STORED =
            "see you at the trailhead\nbring the blue map — 🗺️";
    private static final String UNICODE_EDIT =
            "Café é 👩‍👩‍👧 𝄞\n"
            + "שלום  two  spaces\n\nlast line";

    private static long at(int y, int month, int d, int h, int m) {
        Calendar c = Calendar.getInstance(ZONE);
        c.clear();
        c.set(y, month, d, h, m, 0);
        return c.getTimeInMillis();
    }

    private static ScheduledMessage stored() {
        return new ScheduledMessage.Builder()
                .id(42L)
                .createdAt(NOW - HOUR)
                .scheduledFor(NOW + 2 * DAY)
                .text(STORED)
                .recipientE164(NUM)
                .recipientDisplayName("Test Contact")
                .source(ScheduledMessage.Source.WEBVIEW)
                .holdIfReplied(true)
                .latenessPolicy(LatenessPolicy.DEFAULT)
                .status(ScheduledMessage.Status.SCHEDULED)
                .build();
    }

    private static SheetViewModelLogic edit() {
        return SheetViewModelLogic.forEdit(stored(), NOW, ZONE, SLOTS);
    }

    // ------------------------------------------------------------------ reflection

    private static Method method(String name, Class<?>... params) {
        try {
            return SheetViewModelLogic.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            fail("missing SheetViewModelLogic." + name + " (D49 / R12a editable-text state)");
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

    private static boolean isTextEditable(SheetViewModelLogic m) {
        return (Boolean) call(m, "isTextEditable", new Class<?>[0]);
    }

    private static String getText(SheetViewModelLogic m) {
        return (String) call(m, "getText", new Class<?>[0]);
    }

    private static void setText(SheetViewModelLogic m, String t) {
        call(m, "setText", new Class<?>[] {String.class}, t);
    }

    // ------------------------------------------------------------------ tests

    @Test public void editModeTextIsEditableAndPrefilledWithTheStoredText() {
        SheetViewModelLogic m = edit();
        assertTrue(m.isEditMode());
        assertTrue("edit mode: the message text is editable", isTextEditable(m));
        assertEquals("prefilled with the stored text, exactly", STORED, getText(m));
        assertTrue("the unchanged stored text can be updated", m.isScheduleEnabled());
    }

    @Test public void editedTextIsKeptExactlyIncludingUnicodeAndNewlines() {
        SheetViewModelLogic m = edit();
        setText(m, UNICODE_EDIT);
        assertEquals(UNICODE_EDIT, getText(m));
        assertTrue(m.isScheduleEnabled());
    }

    @Test public void emptyTextDisablesUpdate() {
        SheetViewModelLogic m = edit();
        setText(m, "");
        assertFalse("empty text cannot be saved", m.isScheduleEnabled());
    }

    @Test public void whitespaceOnlyTextDisablesUpdateAndRealTextEnablesItAgain() {
        SheetViewModelLogic m = edit();
        for (String blank : new String[] {" ", "   ", "\n", " \n\t \n"}) {
            setText(m, blank);
            assertFalse("[" + blank + "] cannot be saved", m.isScheduleEnabled());
        }
        setText(m, "ok");
        assertTrue("non-blank text enables Update again", m.isScheduleEnabled());
        assertEquals("ok", getText(m));
    }

    @Test public void editingTheTextLeavesTimeHoldAndPolicyAsLoaded() {
        SheetViewModelLogic m = edit();
        setText(m, "a different message");
        assertEquals(NOW + 2 * DAY, m.getSelectedTimeMs());
        assertTrue(m.isHoldIfReplied());
        assertEquals(LatenessPolicy.DEFAULT, m.getLatenessPolicy());
        assertEquals("Update", m.getPrimaryButtonLabel());
    }

    @Test public void newScheduleModeTextStaysReadOnly() {
        SheetViewModelLogic m = SheetViewModelLogic.forCapture(NUM, null, true, NOW, ZONE, SLOTS);
        assertFalse(m.isEditMode());
        assertFalse("capture mode keeps the quoted, read-only draft", isTextEditable(m));
        assertTrue("capture-mode Schedule gate is unchanged", m.isScheduleEnabled());
    }
}
