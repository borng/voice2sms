package com.voice2sms.sendlater.ui;

import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * What the launcher page's "Scheduled messages" card shows (R35a, D47, OD37). Pure JVM.
 *
 * <p>Only SCHEDULED and HELD records count: the card lists the next {@link #MAX_ROWS} of them by
 * {@code scheduledFor} (held rows interleaved by time, including a held row past its time) and
 * "See all (N)" counts all of them. IN_FLIGHT and terminal records are neither listed nor counted.
 */
public final class ScheduledCardModel {

    /** Rows shown on the card. */
    public static final int MAX_ROWS = 3;

    /** Where a tap on a row goes. */
    public enum Tap {
        /** SCHEDULED: the Send Later sheet in edit mode. */
        EDIT,
        /** HELD (or anything else): the Upcoming screen. */
        UPCOMING
    }

    private static final ScheduledCardModel EMPTY =
            new ScheduledCardModel(Collections.<ScheduledMessage>emptyList(), 0);

    private final List<ScheduledMessage> rows;
    private final int total;

    private ScheduledCardModel(List<ScheduledMessage> rows, int total) {
        this.rows = rows;
        this.total = total;
    }

    /** Builds the card from store records (any statuses, any order; null = none). Never null. */
    public static ScheduledCardModel build(List<ScheduledMessage> records) {
        if (records == null || records.isEmpty()) return EMPTY;
        List<ScheduledMessage> live = new ArrayList<>();
        for (ScheduledMessage m : records) {
            if (m != null && isLive(m)) live.add(m);
        }
        if (live.isEmpty()) return EMPTY;
        Collections.sort(live, new Comparator<ScheduledMessage>() {
            @Override
            public int compare(ScheduledMessage a, ScheduledMessage b) {
                int c = Long.compare(a.getScheduledFor(), b.getScheduledFor());
                return c != 0 ? c : Long.compare(a.getId(), b.getId());
            }
        });
        List<ScheduledMessage> first = new ArrayList<>(live.subList(0, Math.min(MAX_ROWS, live.size())));
        return new ScheduledCardModel(Collections.unmodifiableList(first), live.size());
    }

    private static boolean isLive(ScheduledMessage m) {
        return m.getStatus() == Status.SCHEDULED || m.getStatus() == Status.HELD;
    }

    /** SCHEDULED opens the sheet in edit mode; HELD (and anything else) opens Upcoming. */
    public static Tap tapFor(ScheduledMessage m) {
        return m != null && m.getStatus() == Status.SCHEDULED ? Tap.EDIT : Tap.UPCOMING;
    }

    /** The next {@link #MAX_ROWS} SCHEDULED/HELD records, soonest first. */
    public List<ScheduledMessage> getRows() {
        return rows;
    }

    /** Every SCHEDULED and HELD record (the N of "See all (N)"). */
    public int getTotal() {
        return total;
    }

    /** True when nothing is scheduled or held (the card shows its empty state). */
    public boolean isEmpty() {
        return total == 0;
    }
}
