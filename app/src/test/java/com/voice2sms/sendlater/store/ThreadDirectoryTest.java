package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ConversationRef;

import org.junit.Test;

import java.util.Arrays;

public class ThreadDirectoryTest {

    @Test public void normalizesLikeConversationRef() {
        String raw = "  JOSÉ   Example\tPerson ";
        assertEquals("jose example person", ThreadDirectory.normalizeName(raw));
        assertEquals(new ConversationRef(null, raw).key(), "name:" + ThreadDirectory.normalizeName(raw));
        assertEquals(ThreadDirectory.normalizeName("jose example person"), ThreadDirectory.normalizeName(raw));
        assertEquals("", ThreadDirectory.normalizeName(null));
    }

    @Test public void entryRequiresNameAndE164() {
        assertNull(ThreadDirectory.entry(" ", "+15555550100", 1));
        assertNull(ThreadDirectory.entry("Test Contact", "5555550100", 1));
        assertNull(ThreadDirectory.entry("Test Contact", null, 1));
        ThreadDirectoryEntity e = ThreadDirectory.entry(" Test  Contact ", " +15555550100 ", 7);
        assertEquals("test contact", e.nameKey);
        assertEquals("+15555550100", e.e164);
        assertEquals(7, e.lastSeenMs);
        assertTrue(ThreadDirectory.isE164("+445555550100"));
        assertFalse(ThreadDirectory.isE164("+0555"));
    }

    @Test public void candidatesAreDistinctInOrder() {
        assertEquals(Arrays.asList("+15555550101", "+15555550100"), ThreadDirectory.distinct(
                Arrays.asList("+15555550101", "+15555550100", "+15555550101", null)));
        assertTrue(ThreadDirectory.distinct(null).isEmpty());
    }
}
