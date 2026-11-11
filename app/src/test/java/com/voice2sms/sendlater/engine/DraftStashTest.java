package com.voice2sms.sendlater.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

/** D32: the in-memory hand-off of a draft from the send path to the engine. */
public class DraftStashTest {

    private static final String DRAFT = "Can you grab milk on the way?";

    @After public void tearDown() {
        DraftStash.clearAll();
    }

    @Test public void takenOnceOnly() {
        DraftStash.put(7L, DRAFT, 1_000L);
        assertEquals(DRAFT, DraftStash.take(7L, 1_500L));
        assertNull(DraftStash.take(7L, 1_600L));
    }

    @Test public void perMessage() {
        DraftStash.put(7L, DRAFT, 1_000L);
        assertNull(DraftStash.take(8L, 1_000L));
        assertEquals(DRAFT, DraftStash.take(7L, 1_000L));
    }

    @Test public void tooOldIsDropped() {
        DraftStash.put(7L, DRAFT, 1_000L);
        assertNull(DraftStash.take(7L, 1_000L + DraftStash.MAX_AGE_MS + 1));
    }

    @Test public void blankIsNotADraft() {
        DraftStash.put(7L, "   ", 1_000L);
        assertNull(DraftStash.take(7L, 1_000L));
        DraftStash.put(7L, DRAFT, 1_000L);
        DraftStash.put(7L, null, 1_000L);
        assertNull("a blank put clears", DraftStash.take(7L, 1_000L));
    }

    @Test public void dropForgets() {
        DraftStash.put(7L, DRAFT);
        DraftStash.drop(7L);
        assertNull(DraftStash.take(7L));
    }
}
