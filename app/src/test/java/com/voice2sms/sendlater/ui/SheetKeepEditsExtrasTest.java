package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.PresetSlots;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.Test;

import java.util.Map;
import java.util.TimeZone;

/** Implementer extras (sheet-keep-edits; D52, R12b): restore never crosses modes. Fictional data. */
public class SheetKeepEditsExtrasTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("UTC");
    private static final int[] SLOTS = PresetSlots.DEFAULT_SLOT_MINUTES.clone();
    private static final long NOW = 1_780_000_000_000L;
    private static final long DAY = 86_400_000L;

    private static SheetViewModelLogic edit() {
        return SheetViewModelLogic.forEdit(new ScheduledMessage.Builder()
                .id(7L).createdAt(NOW - DAY).scheduledFor(NOW + DAY).text("placeholder")
                .recipientE164("+14355550102").source(ScheduledMessage.Source.WEBVIEW)
                .holdIfReplied(true).latenessPolicy(LatenessPolicy.DEFAULT)
                .status(ScheduledMessage.Status.SCHEDULED).build(), NOW, ZONE, SLOTS);
    }

    @Test public void captureStateIsNotAppliedToAnEditSheet() {
        SheetViewModelLogic cap = SheetViewModelLogic.forCapture(null, null, false, NOW, ZONE, SLOTS);
        cap.setLatenessPolicy(LatenessPolicy.NEVER_LATE);
        Map<String, Object> saved = cap.saveState();
        SheetViewModelLogic e = edit();
        assertFalse(e.restoreState(saved));
        assertEquals(LatenessPolicy.DEFAULT, e.getLatenessPolicy());
        assertEquals("placeholder", e.getText());
    }

    @Test public void aNumberThatIsNotACandidateIsNotRestored() {
        String[] two = {"+14355550102", "+14355550103"};
        SheetViewModelLogic m = SheetViewModelLogic.forCapture(null, two, false, NOW, ZONE, SLOTS);
        Map<String, Object> saved = m.saveState();
        saved.put(SheetViewModelLogic.KEY_RECIPIENT, "+14355550199");
        m.restoreState(saved);
        assertNull(m.getE164());
    }
}
