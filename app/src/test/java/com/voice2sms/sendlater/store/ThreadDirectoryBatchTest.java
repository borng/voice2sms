package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** simplify-shared (6): the batched directory upsert stores the rows the per-pair upsert did. */
public class ThreadDirectoryBatchTest {

    @Test public void entriesAreThePerPairRowsInOrder() {
        List<String[]> pairs = Arrays.asList(
                new String[] {" Test  Contact ", " +15555550100 "},
                new String[] {" ", "+15555550101"},
                null,
                new String[] {"Only name"},
                new String[] {"Test Contact", "5555550102"},
                new String[] {"Other Example", "+15555550103"},
                new String[] {"TEST contact", "+15555550100"});
        List<ThreadDirectoryEntity> rows = ThreadDirectory.entries(pairs, 42L);
        List<ThreadDirectoryEntity> expected = new ArrayList<>();
        for (String[] p : pairs) {
            if (p == null || p.length < 2) continue;
            ThreadDirectoryEntity e = ThreadDirectory.entry(p[0], p[1], 42L);
            if (e != null) expected.add(e);
        }
        assertEquals(3, rows.size());
        assertEquals(expected.size(), rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(expected.get(i).nameKey, rows.get(i).nameKey);
            assertEquals(expected.get(i).e164, rows.get(i).e164);
            assertEquals(expected.get(i).displayName, rows.get(i).displayName);
            assertEquals(expected.get(i).lastSeenMs, rows.get(i).lastSeenMs);
        }
        // Same primary key twice: REPLACE in order leaves the last one, as one-by-one did.
        assertEquals(rows.get(0).nameKey, rows.get(2).nameKey);
        assertEquals("TEST contact", rows.get(2).displayName);
    }

    @Test public void emptyAndNullInputsGiveNoRows() {
        assertTrue(ThreadDirectory.entries(null, 1L).isEmpty());
        assertTrue(ThreadDirectory.entries(new ArrayList<String[]>(), 1L).isEmpty());
        assertNull(ThreadDirectory.entry(null, "+15555550100", 1L));
    }
}
