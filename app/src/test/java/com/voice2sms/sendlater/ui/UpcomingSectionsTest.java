package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.Watermark;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Acceptance (upcoming-settings; R35, AC11): the pure model behind UpcomingActivity.
 *
 * <p>Pinned API (com.voice2sms.sendlater.ui.UpcomingSections, pure JVM, no android.*):
 * <pre>
 *   static final long RECENT_WINDOW_MS                 // 30 days (A2)
 *   static UpcomingSections build(List&lt;ScheduledMessage&gt; records, String e164FilterOrNull, long nowMs)
 *   List&lt;ScheduledMessage&gt; getPending()               // status SCHEDULED
 *   List&lt;ScheduledMessage&gt; getHeld()                  // status HELD (row shows getHoldReason())
 *   List&lt;ScheduledMessage&gt; getRecent()                // SENT_VERIFIED, CANCELED, EXPIRED, SELF_PREEMPTED,
 *                                                     //   scheduledFor within the last 30 days
 *   boolean isEmpty()                                  // all three sections empty
 *   static Set&lt;RowAction&gt; actionsFor(Status status)    // enum UpcomingSections.RowAction
 *                                                     //   { CHANGE_TIME, CANCEL, SEND_NOW }
 * </pre>
 * Domain records carry no terminal timestamp, so the Recent cutoff is measured on
 * {@code scheduledFor}; fixtures stay days away from the boundary so either reading of
 * "last 30 days" agrees. Section order is not asserted (ids are compared as sets). A null
 * filter means "all conversations"; a non-null filter keeps only records whose
 * {@code recipientE164} equals it. All numbers are fictional 555-01xx numbers.
 */
public class UpcomingSectionsTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;
    /** Arbitrary fixed epoch (2026-06-10T12:00Z). */
    private static final long NOW = 1_781_092_800_000L;

    private static final String A = "+15555550101";
    private static final String B = "+15555550102";

    private static long nextId = 1;

    private static ScheduledMessage msg(Status status, String e164, long scheduledFor) {
        ScheduledMessage.Builder b = new ScheduledMessage.Builder()
                .id(nextId++)
                .createdAt(scheduledFor - 2 * HOUR)
                .scheduledFor(scheduledFor)
                .text("Placeholder message line one\nline two")
                .recipientE164(e164)
                .recipientDisplayName("Test Contact")
                .source(ScheduledMessage.Source.NATIVE_GV)
                .rung(Rung.R1)
                .holdIfReplied(true)
                .latenessPolicy(LatenessPolicy.DEFAULT)
                .status(status)
                .attemptCount(0)
                .watermark(new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null,
                        scheduledFor - 2 * HOUR, null));
        if (status == Status.HELD) b.holdReason(HoldReason.REPLIED);
        return b.build();
    }

    private static Set<Long> ids(List<ScheduledMessage> list) {
        assertNotNull("section list must never be null", list);
        Set<Long> out = new HashSet<>();
        for (ScheduledMessage m : list) out.add(m.getId());
        assertEquals("a record appears at most once per section", list.size(), out.size());
        return out;
    }

    private static Set<Long> idsOf(ScheduledMessage... ms) {
        Set<Long> out = new HashSet<>();
        for (ScheduledMessage m : ms) out.add(m.getId());
        return out;
    }

    // ------------------------------------------------------------------ bucketing by status

    @Test
    public void scheduledGoesToPending_heldToHeld_terminalToRecent() {
        ScheduledMessage p1 = msg(Status.SCHEDULED, A, NOW + HOUR);
        ScheduledMessage p2 = msg(Status.SCHEDULED, B, NOW + 3 * DAY);
        ScheduledMessage h1 = msg(Status.HELD, A, NOW - HOUR);
        ScheduledMessage sent = msg(Status.SENT_VERIFIED, A, NOW - 2 * DAY);
        ScheduledMessage canceled = msg(Status.CANCELED, B, NOW - 3 * DAY);
        ScheduledMessage expired = msg(Status.EXPIRED, A, NOW - 5 * DAY);
        ScheduledMessage preempted = msg(Status.SELF_PREEMPTED, B, NOW - 6 * DAY);

        UpcomingSections s = UpcomingSections.build(
                Arrays.asList(sent, p2, h1, canceled, p1, expired, preempted), null, NOW);

        assertEquals(idsOf(p1, p2), ids(s.getPending()));
        assertEquals(idsOf(h1), ids(s.getHeld()));
        assertEquals(idsOf(sent, canceled, expired, preempted), ids(s.getRecent()));
        assertFalse(s.isEmpty());
    }

    @Test
    public void heldRowKeepsItsReason() {
        ScheduledMessage h = msg(Status.HELD, A, NOW - HOUR).toBuilder()
                .holdReason(HoldReason.UNVERIFIED).build();
        UpcomingSections s = UpcomingSections.build(Arrays.asList(h), null, NOW);
        assertEquals(1, s.getHeld().size());
        assertEquals(HoldReason.UNVERIFIED, s.getHeld().get(0).getHoldReason());
    }

    @Test
    public void noTerminalRecordEverAppearsInPendingOrHeld() {
        List<ScheduledMessage> all = new ArrayList<>();
        for (Status st : Status.values()) {
            if (st.isTerminal()) all.add(msg(st, A, NOW - DAY));
        }
        UpcomingSections s = UpcomingSections.build(all, null, NOW);
        assertTrue(s.getPending().isEmpty());
        assertTrue(s.getHeld().isEmpty());
    }

    @Test
    public void pendingAndHeldAreNeverCutByTheRecentWindow() {
        ScheduledMessage farFuture = msg(Status.SCHEDULED, A, NOW + 60 * DAY);
        ScheduledMessage oldHeld = msg(Status.HELD, A, NOW - 40 * DAY);
        UpcomingSections s = UpcomingSections.build(Arrays.asList(farFuture, oldHeld), null, NOW);
        assertEquals(idsOf(farFuture), ids(s.getPending()));
        assertEquals(idsOf(oldHeld), ids(s.getHeld()));
    }

    // ------------------------------------------------------------------ 30-day cutoff

    @Test
    public void recentWindowIsThirtyDays() {
        assertEquals(30L * DAY, UpcomingSections.RECENT_WINDOW_MS);
    }

    @Test
    public void terminalRecordsOlderThanThirtyDaysAreDropped() {
        ScheduledMessage inside = msg(Status.SENT_VERIFIED, A, NOW - 29 * DAY + HOUR);
        ScheduledMessage oldSent = msg(Status.SENT_VERIFIED, A, NOW - 40 * DAY);
        ScheduledMessage oldCanceled = msg(Status.CANCELED, A, NOW - 31 * DAY - HOUR);
        ScheduledMessage oldExpired = msg(Status.EXPIRED, B, NOW - 90 * DAY);
        ScheduledMessage oldPreempted = msg(Status.SELF_PREEMPTED, B, NOW - 45 * DAY);

        UpcomingSections s = UpcomingSections.build(
                Arrays.asList(oldSent, inside, oldCanceled, oldExpired, oldPreempted), null, NOW);

        assertEquals(idsOf(inside), ids(s.getRecent()));
        assertTrue(s.getPending().isEmpty());
        assertTrue(s.getHeld().isEmpty());
    }

    @Test
    public void onlyStaleRecordsMeansEmpty() {
        UpcomingSections s = UpcomingSections.build(
                Arrays.asList(msg(Status.SENT_VERIFIED, A, NOW - 60 * DAY)), null, NOW);
        assertTrue(s.isEmpty());
    }

    @Test
    public void nullOrEmptyInputGivesEmptySections() {
        UpcomingSections a = UpcomingSections.build(null, null, NOW);
        assertTrue(a.isEmpty());
        assertEquals(0, ids(a.getPending()).size());
        assertEquals(0, ids(a.getHeld()).size());
        assertEquals(0, ids(a.getRecent()).size());
        assertTrue(UpcomingSections.build(new ArrayList<ScheduledMessage>(), A, NOW).isEmpty());
    }

    // ------------------------------------------------------------------ e164 filter (EXTRA_E164)

    @Test
    public void e164FilterKeepsOnlyThatConversationInEverySection() {
        ScheduledMessage pa = msg(Status.SCHEDULED, A, NOW + HOUR);
        ScheduledMessage pb = msg(Status.SCHEDULED, B, NOW + HOUR);
        ScheduledMessage ha = msg(Status.HELD, A, NOW - HOUR);
        ScheduledMessage hb = msg(Status.HELD, B, NOW - HOUR);
        ScheduledMessage ra = msg(Status.SENT_VERIFIED, A, NOW - DAY);
        ScheduledMessage rb = msg(Status.CANCELED, B, NOW - DAY);
        List<ScheduledMessage> all = Arrays.asList(pa, pb, ha, hb, ra, rb);

        UpcomingSections onlyA = UpcomingSections.build(all, A, NOW);
        assertEquals(idsOf(pa), ids(onlyA.getPending()));
        assertEquals(idsOf(ha), ids(onlyA.getHeld()));
        assertEquals(idsOf(ra), ids(onlyA.getRecent()));

        UpcomingSections onlyB = UpcomingSections.build(all, B, NOW);
        assertEquals(idsOf(pb), ids(onlyB.getPending()));
        assertEquals(idsOf(hb), ids(onlyB.getHeld()));
        assertEquals(idsOf(rb), ids(onlyB.getRecent()));
    }

    @Test
    public void nullFilterShowsEveryConversation() {
        ScheduledMessage pa = msg(Status.SCHEDULED, A, NOW + HOUR);
        ScheduledMessage pb = msg(Status.SCHEDULED, B, NOW + HOUR);
        UpcomingSections s = UpcomingSections.build(Arrays.asList(pa, pb), null, NOW);
        assertEquals(idsOf(pa, pb), ids(s.getPending()));
    }

    @Test
    public void filterWithNoMatchesIsEmpty() {
        ScheduledMessage pa = msg(Status.SCHEDULED, A, NOW + HOUR);
        UpcomingSections s = UpcomingSections.build(Arrays.asList(pa), "+15555550199", NOW);
        assertTrue(s.isEmpty());
    }

    @Test
    public void filterDoesNotMatchRecordsWithNoNumber() {
        ScheduledMessage noNumber = msg(Status.SCHEDULED, null, NOW + HOUR);
        ScheduledMessage pa = msg(Status.SCHEDULED, A, NOW + HOUR);
        UpcomingSections s = UpcomingSections.build(Arrays.asList(noNumber, pa), A, NOW);
        assertEquals(idsOf(pa), ids(s.getPending()));
        UpcomingSections all = UpcomingSections.build(Arrays.asList(noNumber, pa), null, NOW);
        assertEquals(idsOf(noNumber, pa), ids(all.getPending()));
    }

    // ------------------------------------------------------------------ row actions per status

    @Test
    public void scheduledAndHeldOfferChangeTimeCancelAndSendNow() {
        EnumSet<UpcomingSections.RowAction> all = EnumSet.of(
                UpcomingSections.RowAction.CHANGE_TIME,
                UpcomingSections.RowAction.CANCEL,
                UpcomingSections.RowAction.SEND_NOW);
        assertEquals(all, EnumSet.copyOf(withNone(UpcomingSections.actionsFor(Status.SCHEDULED))));
        assertEquals(all, EnumSet.copyOf(withNone(UpcomingSections.actionsFor(Status.HELD))));
    }

    @Test
    public void sendNowOnlyForScheduledAndHeld() {
        for (Status st : Status.values()) {
            Set<UpcomingSections.RowAction> a = UpcomingSections.actionsFor(st);
            assertNotNull("actionsFor(" + st + ") must not be null", a);
            boolean expected = st == Status.SCHEDULED || st == Status.HELD;
            assertEquals("SEND_NOW for " + st, expected,
                    a.contains(UpcomingSections.RowAction.SEND_NOW));
        }
    }

    @Test
    public void inFlightCannotBeSentNowOrRescheduled() {
        Set<UpcomingSections.RowAction> a = UpcomingSections.actionsFor(Status.IN_FLIGHT);
        assertFalse(a.contains(UpcomingSections.RowAction.SEND_NOW));
        assertFalse(a.contains(UpcomingSections.RowAction.CHANGE_TIME));
    }

    @Test
    public void terminalRecordsOfferNoActions() {
        for (Status st : Status.values()) {
            if (!st.isTerminal()) continue;
            assertTrue("no row actions for terminal " + st,
                    UpcomingSections.actionsFor(st).isEmpty());
        }
    }

    /** EnumSet.copyOf rejects an empty plain collection; keep the assertion message useful. */
    private static Set<UpcomingSections.RowAction> withNone(Set<UpcomingSections.RowAction> s) {
        assertNotNull(s);
        assertFalse("expected actions, got none", s.isEmpty());
        return s;
    }
}
