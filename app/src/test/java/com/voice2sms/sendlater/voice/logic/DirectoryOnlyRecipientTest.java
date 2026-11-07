package com.voice2sms.sendlater.voice.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * deliver sec-M1: a name whose numbers come only from the GV thread directory (no contact
 * number) is never RESOLVED silently; the user confirms the number (NEEDS_CHOICE, even for one).
 * Fictional 555-01xx numbers; placeholder name.
 */
public class DirectoryOnlyRecipientTest {

    private static final String NAME = "Test Contact";
    private static final List<String> NONE = Collections.<String>emptyList();

    @Test
    public void oneDirectoryNumberWithoutContactsNeedsConfirmation() {
        RecipientResolver.Resolution r =
                RecipientResolver.resolve(NAME, NONE, Collections.singletonList("+14355550101"));
        assertEquals(RecipientResolver.Kind.NEEDS_CHOICE, r.getKind());
        assertNull(r.getE164());
        assertEquals(Collections.singletonList("+14355550101"), r.getCandidates());
        assertEquals(RecipientResolver.Kind.NEEDS_CHOICE,
                RecipientResolver.resolve(NAME, null, Collections.singletonList("(435) 555-0101")).getKind());
    }

    @Test
    public void severalDirectoryNumbersWithoutContactsNeedChoice() {
        List<String> d = Arrays.asList("+14355550101", "+14355550102");
        RecipientResolver.Resolution r = RecipientResolver.resolve(NAME, NONE, d);
        assertEquals(RecipientResolver.Kind.NEEDS_CHOICE, r.getKind());
        assertEquals(d, r.getCandidates());
    }

    @Test
    public void directoryNonNumbersStillNeedEntry() {
        assertEquals(RecipientResolver.Kind.NEEDS_ENTRY,
                RecipientResolver.resolve(NAME, NONE, Arrays.asList(NAME, "")).getKind());
    }

    @Test
    public void screenPathAppliesTheSameRule() {
        RecipientResolver.Resolution r = RecipientResolver.resolveScreen(null,
                Collections.singletonList(NAME), NONE, Collections.singletonList("+14355550102"));
        assertEquals(RecipientResolver.Kind.NEEDS_CHOICE, r.getKind());
        assertNull(r.getE164());
    }

    @Test
    public void contactsConfirmingTheDirectoryOrANumberTitleStillResolve() {
        assertEquals("+14355550102", RecipientResolver.resolve(NAME,
                Arrays.asList("+14355550101", "+14355550102"),
                Collections.singletonList("+14355550102")).getE164());
        assertEquals("+14355550101", RecipientResolver.resolve(NAME,
                Collections.singletonList("+14355550101"), NONE).getE164());
        assertEquals("+14355550142", RecipientResolver.resolve("(435) 555-0142", NONE,
                Collections.singletonList("+14355550101")).getE164());
    }
}
