package com.voice2sms.sendlater.ui;

import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure model behind {@link UpcomingActivity} (R35): buckets records into Pending, Held and
 * Recent, applies the optional conversation filter and says which row actions each status
 * offers. No android.* here, so it is JVM-tested.
 *
 * <ul>
 *   <li>Pending: SCHEDULED, plus IN_FLIGHT (shown as "Sending", no actions), soonest first.</li>
 *   <li>Held: HELD (the row shows {@link ScheduledMessage#getHoldReason()}), soonest first.</li>
 *   <li>Recent: terminal statuses whose {@code scheduledFor} is within the last
 *       {@link #RECENT_WINDOW_MS}, newest first. Pending and Held are never cut by that window.</li>
 * </ul>
 */
public final class UpcomingSections {

    /** How far back Recent reaches (A2). */
    public static final long RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000;

    /** What a row can do. */
    public enum RowAction { CHANGE_TIME, CANCEL, SEND_NOW }

    private final List<ScheduledMessage> pending;
    private final List<ScheduledMessage> held;
    private final List<ScheduledMessage> recent;

    private UpcomingSections(List<ScheduledMessage> pending, List<ScheduledMessage> held,
                             List<ScheduledMessage> recent) {
        this.pending = Collections.unmodifiableList(pending);
        this.held = Collections.unmodifiableList(held);
        this.recent = Collections.unmodifiableList(recent);
    }

    /**
     * @param records          any mix of statuses (null = none); a record id appears once
     * @param e164FilterOrNull null = every conversation; otherwise only records whose
     *                         recipientE164 equals it (records without a number never match)
     * @param nowMs            the Recent cutoff is {@code nowMs - RECENT_WINDOW_MS}
     */
    public static UpcomingSections build(List<ScheduledMessage> records, String e164FilterOrNull,
                                         long nowMs) {
        List<ScheduledMessage> pending = new ArrayList<>();
        List<ScheduledMessage> held = new ArrayList<>();
        List<ScheduledMessage> recent = new ArrayList<>();
        if (records != null) {
            long cutoff = nowMs - RECENT_WINDOW_MS;
            Set<Long> seen = new HashSet<>();
            for (ScheduledMessage m : records) {
                if (m == null || m.getStatus() == null) continue;
                if (e164FilterOrNull != null && !e164FilterOrNull.equals(m.getRecipientE164())) continue;
                if (!seen.add(m.getId())) continue;
                Status st = m.getStatus();
                if (st == Status.SCHEDULED || st == Status.IN_FLIGHT) {
                    pending.add(m);
                } else if (st == Status.HELD) {
                    held.add(m);
                } else if (st.isTerminal() && m.getScheduledFor() >= cutoff) {
                    recent.add(m);
                }
            }
        }
        Collections.sort(pending, SOONEST_FIRST);
        Collections.sort(held, SOONEST_FIRST);
        Collections.sort(recent, Collections.reverseOrder(SOONEST_FIRST));
        return new UpcomingSections(pending, held, recent);
    }

    /** Row actions for a status: all three for SCHEDULED and HELD, none otherwise. */
    public static Set<RowAction> actionsFor(Status status) {
        if (status == Status.SCHEDULED || status == Status.HELD) {
            return Collections.unmodifiableSet(EnumSet.allOf(RowAction.class));
        }
        return Collections.unmodifiableSet(EnumSet.noneOf(RowAction.class));
    }

    /** First line of the message text, trimmed (null-safe; "" when there is none). */
    public static String firstLine(String text) {
        if (text == null) return "";
        String t = text.trim();
        int nl = t.indexOf('\n');
        return (nl >= 0 ? t.substring(0, nl) : t).trim();
    }

    public List<ScheduledMessage> getPending() { return pending; }
    public List<ScheduledMessage> getHeld() { return held; }
    public List<ScheduledMessage> getRecent() { return recent; }

    public boolean isEmpty() {
        return pending.isEmpty() && held.isEmpty() && recent.isEmpty();
    }

    private static final Comparator<ScheduledMessage> SOONEST_FIRST = new Comparator<ScheduledMessage>() {
        @Override
        public int compare(ScheduledMessage a, ScheduledMessage b) {
            int c = Long.compare(a.getScheduledFor(), b.getScheduledFor());
            return c != 0 ? c : Long.compare(a.getId(), b.getId());
        }
    };
}
