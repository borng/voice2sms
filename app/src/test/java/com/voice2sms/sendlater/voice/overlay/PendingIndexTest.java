package com.voice2sms.sendlater.voice.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

/** In-memory chip index for the native Voice overlay (R17, R18, R33, R40). Fictional 555-01xx numbers. */
public class PendingIndexTest {

    private static final String A = "+14355550101";
    private static final String B = "+14355550102";
    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");

    private static ScheduledMessage msg(long id, String e164, String name, long at, boolean hold,
                                        ScheduledMessage.Status status) {
        return new ScheduledMessage.Builder().id(id).createdAt(1000L).scheduledFor(at).text("t")
                .recipientE164(e164).recipientDisplayName(name).holdIfReplied(hold).status(status)
                .holdReason(status == ScheduledMessage.Status.HELD ? HoldReason.REPLIED : null).build();
    }

    @Test public void nameTitleFindsMessagesByCapturedName() {
        PendingIndex idx = PendingIndex.build(Arrays.asList(
                msg(1, A, "Alex Example", 5000L, true, ScheduledMessage.Status.SCHEDULED)), null);
        PendingIndex.Entry e = idx.lookup("alex  example");
        assertNotNull("name key is case/space folded", e);
        assertEquals(1, e.total());
        assertEquals(1L, e.singleMessageId());
        assertEquals(A, e.e164());
        assertTrue(e.hasHoldWatch());
        assertTrue(e.chipLabel(ZONE).startsWith("Scheduled"));
    }

    @Test public void numberTitleFindsMessagesByE164() {
        PendingIndex idx = PendingIndex.build(Arrays.asList(
                msg(1, A, "Alex Example", 5000L, false, ScheduledMessage.Status.SCHEDULED),
                msg(2, A, "Alex Example", 6000L, false, ScheduledMessage.Status.SCHEDULED)), null);
        PendingIndex.Entry e = idx.lookup("(435) 555-0101");
        assertNotNull(e);
        assertEquals(2, e.total());
        assertEquals("several -> no single id (Upcoming)", -1L, e.singleMessageId());
        assertEquals("2 scheduled", e.chipLabel(ZONE));
        assertFalse("holdIfReplied off -> no watch", e.hasHoldWatch());
    }

    @Test public void heldWinsTheLabelAndIsNotWatched() {
        PendingIndex idx = PendingIndex.build(
                Arrays.asList(msg(1, B, "Sam Example", 5000L, true, ScheduledMessage.Status.SCHEDULED)),
                Arrays.asList(msg(2, B, "Sam Example", 6000L, true, ScheduledMessage.Status.HELD)));
        PendingIndex.Entry e = idx.lookup("Sam Example");
        assertEquals(2, e.total());
        assertEquals(1, e.heldCount());
        assertTrue(e.chipLabel(ZONE).startsWith("Held"));
        assertEquals("only the SCHEDULED one is watched", 1, e.holdWatches().size());
    }

    @Test public void otherThreadsAndEmptyTitlesFindNothing() {
        PendingIndex idx = PendingIndex.build(Arrays.asList(
                msg(1, A, "Alex Example", 5000L, true, ScheduledMessage.Status.SCHEDULED)),
                Collections.<ScheduledMessage>emptyList());
        assertNull(idx.lookup("Sam Example"));
        assertNull(idx.lookup("(435) 555-0102"));
        assertNull(idx.lookup(null));
        assertNull(idx.lookup("  "));
        assertTrue(PendingIndex.build(null, null).isEmpty());
    }

    @Test public void toStringLeaksNothing() {
        PendingIndex idx = PendingIndex.build(Arrays.asList(
                msg(1, A, "Alex Example", 5000L, true, ScheduledMessage.Status.SCHEDULED)), null);
        String s = idx.toString();
        assertFalse(s.contains(A));
        assertFalse(s.toLowerCase().contains("alex"));
    }
}
