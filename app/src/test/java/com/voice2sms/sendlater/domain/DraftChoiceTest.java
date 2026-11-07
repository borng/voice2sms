package com.voice2sms.sendlater.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.DraftChoice.Mode;
import com.voice2sms.sendlater.domain.DraftChoice.Step;

import org.junit.Test;

/** D32: what a scheduled compose does with a draft in the box. Fictional texts only. */
public class DraftChoiceTest {

    private static final String BODY = "Running late, be there at 9";
    private static final String DRAFT = "Can you grab milk on the way?";

    @Test public void aDraftStopsAnAskingJobSoTheUserChooses() {
        assertEquals(Step.STOP, DraftChoice.onBox(Mode.ASK, DRAFT, BODY));
        assertEquals("a null mode asks", Step.STOP, DraftChoice.onBox(null, DRAFT, BODY));
    }

    @Test public void replaceAndSendClearsTheDraftForItsJob() {
        assertEquals(Step.REPLACE, DraftChoice.onBox(Mode.REPLACE, DRAFT, BODY));
    }

    @Test public void sendBothKeepsTheDraftForItsJob() {
        assertEquals(Step.BOTH, DraftChoice.onBox(Mode.BOTH, DRAFT, BODY));
    }

    @Test public void anEmptyBoxIsTypedIntoInEveryMode() {
        for (Mode m : Mode.values()) {
            assertEquals(Step.TYPE, DraftChoice.onBox(m, null, BODY));
            assertEquals(Step.TYPE, DraftChoice.onBox(m, "", BODY));
            assertEquals(Step.TYPE, DraftChoice.onBox(m, "  \n ", BODY));
        }
    }

    @Test public void aDraftEqualToOurTextIsOursWithoutAsking() {
        assertEquals(Step.REPLACE, DraftChoice.onBox(Mode.ASK, BODY, BODY));
        assertEquals("spacing differences", Step.REPLACE,
                DraftChoice.onBox(Mode.ASK, "  Running   late,\nbe there at 9 ", BODY));
        assertEquals("even under Send both nothing is kept", Step.REPLACE, DraftChoice.onBox(Mode.BOTH, BODY, BODY));
    }

    @Test public void sameTextIsStrictBeyondSpacing() {
        assertTrue(DraftChoice.sameText(BODY, BODY));
        assertTrue(DraftChoice.sameText("café", "café"));   // NFC
        assertFalse("case counts", DraftChoice.sameText(BODY.toUpperCase(), BODY));
        assertFalse("punctuation counts", DraftChoice.sameText(BODY + "!", BODY));
        assertFalse(DraftChoice.sameText(null, BODY));
        assertFalse(DraftChoice.sameText(BODY, null));
        assertFalse("blank is never ours", DraftChoice.sameText(" ", " "));
        assertEquals(Step.STOP, DraftChoice.onBox(Mode.ASK, BODY + " and more", BODY));
    }

    @Test public void wireStringsMatchInjectJs() {
        assertEquals("type", Step.TYPE.wire());
        assertEquals("replace", Step.REPLACE.wire());
        assertEquals("both", Step.BOTH.wire());
        assertEquals("stop", Step.STOP.wire());
    }
}
