package com.voice2sms.sendlater.voice.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.TimeZone;

/** Deliver fixes for the native chip: UI M5 (reminded), UI M6 (date), OD31 PQ4 (held tap). */
public class DeliverPendingIndexTest {

    private static final String A = "+14355550101";
    private static final TimeZone ZONE = TimeZone.getTimeZone("UTC");
    private static final long DAY = 86_400_000L;
    // Wednesday 2026-11-04 12:00 UTC.
    private static final long NOW = 1_793_793_600_000L;

    private Locale saved;

    @Before public void pinLocale() { saved = Locale.getDefault(); Locale.setDefault(Locale.US); }
    @After public void restoreLocale() { Locale.setDefault(saved); }

    private static String norm(String s) {
        return s == null ? null : s.replace(' ', ' ').replace(' ', ' ');
    }

    private static ScheduledMessage msg(long id, long at, ScheduledMessage.Status status) {
        return new ScheduledMessage.Builder().id(id).createdAt(1000L).scheduledFor(at).text("t")
                .recipientE164(A).recipientDisplayName("Alex Example").holdIfReplied(false).status(status)
                .holdReason(status == ScheduledMessage.Status.HELD ? HoldReason.REPLIED : null).build();
    }

    @Test public void m5_remindedSingleReadsReminderSent() {
        PendingIndex idx = PendingIndex.build(Collections.singletonList(
                msg(1, NOW - 60_000L, ScheduledMessage.Status.SCHEDULED)), null,
                new HashSet<>(Collections.singletonList(1L)));
        PendingIndex.Entry e = idx.lookup(A);
        assertEquals(1, e.remindedCount());
        assertTrue(norm(e.chipLabel(ZONE, NOW)), norm(e.chipLabel(ZONE, NOW)).startsWith("Reminder sent · Wed"));
    }

    @Test public void m5_notRemindedStaysScheduled() {
        PendingIndex idx = PendingIndex.build(Collections.singletonList(
                msg(1, NOW + DAY, ScheduledMessage.Status.SCHEDULED)), null, new HashSet<Long>());
        assertTrue(norm(idx.lookup(A).chipLabel(ZONE, NOW)).startsWith("Scheduled · Thu"));
    }

    @Test public void m6_beyondTheWeekCarriesTheDate() {
        PendingIndex idx = PendingIndex.build(Collections.singletonList(
                msg(1, NOW + 19 * DAY, ScheduledMessage.Status.SCHEDULED)), null);
        assertEquals("Scheduled · Mon, Nov 23, 12:00 PM", norm(idx.lookup(A).chipLabel(ZONE, NOW)));
    }

    @Test public void pq4_singleHeldOpensUpcoming_singleScheduledOpensEdit() {
        PendingIndex held = PendingIndex.build(null,
                Collections.singletonList(msg(7, NOW, ScheduledMessage.Status.HELD)));
        assertEquals(7L, held.lookup(A).singleMessageId());
        assertEquals("a single HELD message does not open the edit sheet", -1L, held.lookup(A).editMessageId());

        PendingIndex one = PendingIndex.build(Collections.singletonList(
                msg(8, NOW, ScheduledMessage.Status.SCHEDULED)), null);
        assertEquals(8L, one.lookup(A).editMessageId());

        PendingIndex two = PendingIndex.build(Arrays.asList(
                msg(8, NOW, ScheduledMessage.Status.SCHEDULED), msg(9, NOW, ScheduledMessage.Status.SCHEDULED)), null);
        assertEquals(-1L, two.lookup(A).editMessageId());
    }

    @Test public void controllerUsesEditTargetRemindedMarksAndNow() throws IOException {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/voice/overlay/VoiceOverlayController.java")),
                StandardCharsets.UTF_8);
        int tap = src.indexOf("private void onChipTap()");
        String body = src.substring(tap, src.indexOf("\n    }\n", tap));
        assertTrue("chip tap edits only a single SCHEDULED message", body.contains("e.editMessageId()"));
        assertTrue("one or more otherwise -> Upcoming", body.contains("e.total() >= 1"));
        assertTrue("index carries the engine's reminded marks",
                src.contains("SendLaterEngine.remindedIds(c, pending)"));
        assertTrue("chip label measured against now", src.contains("chipLabel(TimeZone.getDefault(), System.currentTimeMillis())"));
    }
}
