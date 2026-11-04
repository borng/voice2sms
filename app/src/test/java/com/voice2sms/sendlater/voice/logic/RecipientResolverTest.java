package com.voice2sms.sendlater.voice.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

/**
 * R4 / I3: phone-number title wins; else contacts ∩ directory; exactly one -> Resolved;
 * several -> NeedsChoice; none -> NeedsEntry; a display name is never a recipient.
 * All numbers are fictional 555-01xx numbers; the name is a placeholder.
 */
public class RecipientResolverTest {

    private static final String NAME = "Test Contact";
    private static final List<String> FOUR = Arrays.asList(
            "+14355550101", "+14355550102", "+14355550103", "+14355550104");
    private static final List<String> NONE = Collections.<String>emptyList();

    @Test
    public void numberTitleResolvesSilently() {
        assertEquals("RESOLVED", RecipientResolver.resolve("(435) 555-0142", NONE, NONE).getKind().name());
        assertEquals("+14355550142", RecipientResolver.resolve("(435) 555-0142", NONE, NONE).getE164());
    }

    @Test
    public void numberTitleWinsOverContactsAndDirectory() {
        assertEquals("+14355550142",
                RecipientResolver.resolve("(435) 555-0142", FOUR, Arrays.asList("+14355550101")).getE164());
        assertEquals("RESOLVED",
                RecipientResolver.resolve("(435) 555-0142", FOUR, FOUR).getKind().name());
    }

    @Test
    public void nameWithFourContactNumbersAndEmptyDirectoryNeedsChoiceOfTheFour() {
        assertEquals("NEEDS_CHOICE", RecipientResolver.resolve(NAME, FOUR, NONE).getKind().name());
        assertEquals(new HashSet<>(FOUR),
                new HashSet<>(RecipientResolver.resolve(NAME, FOUR, NONE).getCandidates()));
        assertNull(RecipientResolver.resolve(NAME, FOUR, NONE).getE164());
    }

    @Test
    public void nameWithDirectoryContainingExactlyOneContactNumberResolves() {
        List<String> directory = Arrays.asList("+14355550103", "+18015550199");
        assertEquals("RESOLVED", RecipientResolver.resolve(NAME, FOUR, directory).getKind().name());
        assertEquals("+14355550103", RecipientResolver.resolve(NAME, FOUR, directory).getE164());
    }

    @Test
    public void nameWithDirectoryContainingTwoContactNumbersNeedsChoice() {
        List<String> directory = Arrays.asList("+14355550101", "+14355550104");
        assertEquals("NEEDS_CHOICE", RecipientResolver.resolve(NAME, FOUR, directory).getKind().name());
        assertTrue(RecipientResolver.resolve(NAME, FOUR, directory).getCandidates().containsAll(directory));
        assertNull(RecipientResolver.resolve(NAME, FOUR, directory).getE164());
    }

    @Test
    public void noCandidatesNeedsEntry() {
        assertEquals("NEEDS_ENTRY", RecipientResolver.resolve(NAME, NONE, NONE).getKind().name());
        assertNull(RecipientResolver.resolve(NAME, NONE, NONE).getE164());
        assertNull(RecipientResolver.resolve(NAME, new ArrayList<String>(), new ArrayList<String>()).getE164());
    }

    @Test
    public void displayNameIsNeverReturnedAsRecipient() {
        String[] titles = {NAME, "Pharmacy", "Mom", "Test Contact (work)"};
        List<List<String>> lists = Arrays.asList(NONE, FOUR, Arrays.asList("+14355550101"));
        for (String t : titles) {
            for (List<String> c : lists) {
                for (List<String> d : lists) {
                    String e164 = RecipientResolver.resolve(t, c, d).getE164();
                    if (e164 != null) {
                        assertNotEquals(t, e164);
                        assertTrue("not E.164: " + e164, e164.matches("\\+[1-9][0-9]{6,14}"));
                    }
                    if (RecipientResolver.resolve(t, c, d).getCandidates() != null) {
                        for (String cand : RecipientResolver.resolve(t, c, d).getCandidates()) {
                            assertTrue("candidate not E.164: " + cand, cand.matches("\\+[1-9][0-9]{6,14}"));
                        }
                    }
                }
            }
        }
    }

    @Test
    public void nameTitleAloneIsNotResolved() {
        assertNotEquals("RESOLVED", RecipientResolver.resolve(NAME, NONE, NONE).getKind().name());
    }
}
