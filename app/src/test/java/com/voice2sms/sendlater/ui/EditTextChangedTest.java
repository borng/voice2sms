package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.PresetSlots;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.Test;

import java.util.TimeZone;

/**
 * Implementer's tests (edit-text; D49, R12a): an untouched text is reported unchanged (the sheet
 * then keeps the stored text), an exact-match re-entry is unchanged, any other edit is changed,
 * and capture mode ignores setText. Fictional data only.
 */
public class EditTextChangedTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("UTC");
    private static final long NOW = 1_780_000_000_000L;
    private static final String STORED = "placeholder text\nsecond line";

    private static SheetViewModelLogic edit() {
        ScheduledMessage m = new ScheduledMessage.Builder()
                .id(7L).createdAt(NOW - 1000).scheduledFor(NOW + 86_400_000L)
                .text(STORED).recipientE164("+14355550103")
                .source(ScheduledMessage.Source.WEBVIEW).holdIfReplied(false)
                .latenessPolicy(LatenessPolicy.DEFAULT).status(ScheduledMessage.Status.SCHEDULED)
                .build();
        return SheetViewModelLogic.forEdit(m, NOW, ZONE, PresetSlots.DEFAULT_SLOT_MINUTES.clone());
    }

    @Test public void untouchedTextIsUnchanged() {
        assertFalse(edit().isTextChanged());
    }

    @Test public void editIsChangedAndRetypingTheStoredTextIsNot() {
        SheetViewModelLogic m = edit();
        m.setText(STORED + " ");
        assertTrue("a trailing space is an edit (exact text)", m.isTextChanged());
        m.setText(STORED);
        assertFalse(m.isTextChanged());
    }

    @Test public void captureModeIgnoresSetText() {
        SheetViewModelLogic m = SheetViewModelLogic.forCapture("+14355550103", null, true, NOW, ZONE,
                PresetSlots.DEFAULT_SLOT_MINUTES.clone());
        m.setText("x");
        assertNull(m.getText());
        assertFalse(m.isTextChanged());
    }

    @Test public void nonBreakingSpaceOnlyTextDisablesUpdate() {
        SheetViewModelLogic m = edit();
        m.setText("   ");
        assertFalse(m.isScheduleEnabled());
        m.setText(" ok");
        assertTrue(m.isScheduleEnabled());
        assertEquals(" ok", m.getText());
    }
}
