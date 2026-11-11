package com.voice2sms.sendlater.voice.logic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** R6 / I4: restore only when the live draft is empty and the title is unchanged. */
public class UndoGuardTest {

    private static final String TITLE = "Test Contact";

    @Test
    public void restoreAllowedWhenDraftEmptyAndTitleSame() {
        assertTrue(UndoGuard.canRestore(TITLE, TITLE, ""));
    }

    @Test
    public void refusedAfterNewTyping() {
        assertFalse(UndoGuard.canRestore(TITLE, TITLE, "new text typed after scheduling"));
        assertFalse(UndoGuard.canRestore(TITLE, TITLE, "a"));
    }

    @Test
    public void refusedWhenTitleChanged() {
        assertFalse(UndoGuard.canRestore(TITLE, "Other Contact", ""));
        assertFalse(UndoGuard.canRestore(TITLE, "(435) 555-0142", ""));
    }

    @Test
    public void refusedWhenTitleChangedAndDraftTyped() {
        assertFalse(UndoGuard.canRestore(TITLE, "Other Contact", "hello"));
    }

    @Test
    public void refusedWhenLiveTitleUnknown() {
        assertFalse(UndoGuard.canRestore(TITLE, null, ""));
    }
}
