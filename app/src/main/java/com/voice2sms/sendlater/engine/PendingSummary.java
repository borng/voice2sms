package com.voice2sms.sendlater.engine;

import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import java.util.List;
import java.util.TimeZone;

/**
 * R45 / OD39 (D47): pure model of the "N scheduled" notification. Pure JVM, no android.*.
 *
 * <p>N counts every SCHEDULED and HELD record; IN_FLIGHT and terminal records never count.
 * "next" is the soonest SCHEDULED message (the one that will actually send next); when only HELD
 * messages remain it is the soonest HELD one. The recipient is shown per D39
 * ({@link ChipLabel#recipient}: trimmed name, else the formatted number) and the time per the
 * shared label rule ({@link ChipLabel#timeLabel}: "Wed 1:30 PM" this week, a date further out).
 * Message text never appears. {@link #getPublicText()} is the lock-screen version: no names,
 * numbers, digits or text.
 */
public final class PendingSummary {

    /** Lock-screen text (R45): nothing that identifies a person or a message. */
    public static final String PUBLIC_TEXT = "Scheduled messages waiting to send";

    private final int count;
    private final String text;

    private PendingSummary(int count, String text) {
        this.count = count;
        this.text = text;
    }

    /**
     * Null when nothing should show (the notifier cancels): no SCHEDULED/HELD record, or Send
     * Later is off (OD24). {@code records} may be null and may hold any status.
     */
    public static PendingSummary build(List<ScheduledMessage> records, boolean sendLaterEnabled,
                                       long nowMs, TimeZone zone) {
        if (!sendLaterEnabled || records == null) return null;
        int n = 0;
        ScheduledMessage nextScheduled = null;
        ScheduledMessage nextHeld = null;
        for (ScheduledMessage m : records) {
            if (m == null || m.getStatus() == null) continue;
            if (m.getStatus() == ScheduledMessage.Status.SCHEDULED) {
                n++;
                if (nextScheduled == null || m.getScheduledFor() < nextScheduled.getScheduledFor()) {
                    nextScheduled = m;
                }
            } else if (m.getStatus() == ScheduledMessage.Status.HELD) {
                n++;
                if (nextHeld == null || m.getScheduledFor() < nextHeld.getScheduledFor()) {
                    nextHeld = m;
                }
            }
        }
        if (n == 0) return null;
        ScheduledMessage next = nextScheduled != null ? nextScheduled : nextHeld;
        TimeZone tz = zone != null ? zone : TimeZone.getDefault();
        String who = ChipLabel.recipient(next.getRecipientDisplayName(), next.getRecipientE164(),
                "unknown");
        String when = ChipLabel.timeLabel(next.getScheduledFor(), nowMs, tz);
        return new PendingSummary(n, n + " scheduled · next " + who + " " + when);
    }

    /** SCHEDULED + HELD records. */
    public int getCount() {
        return count;
    }

    /** "N scheduled · next &lt;who&gt; &lt;time&gt;" (R45). */
    public String getText() {
        return text;
    }

    /** Redacted lock-screen text (R45). */
    public String getPublicText() {
        return PUBLIC_TEXT;
    }
}
