package com.voice2sms.sendlater.engine;

import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;

/**
 * What a user tap on a scheduled message (Cancel, Send now / Send anyway, Snooze) did (UI M4,
 * R28, I6). Anything but {@link #DONE} means the row had changed before the tap landed, so the
 * user is told what happened instead of the tap failing silently.
 */
public enum TapResult {
    /** The tap was applied (cancelled, sending, snoozed, or the engine's own self-preemption). */
    DONE,
    /** The message is already being sent (IN_FLIGHT): it can no longer be cancelled. */
    ALREADY_SENDING,
    /** The message was already sent (SENT_VERIFIED). */
    ALREADY_SENT,
    /** The message was already cancelled (CANCELED). */
    ALREADY_CANCELLED,
    /** Expired, not sent because of a similar text, failed, deleted or unreadable. */
    NOT_PENDING;

    /** The stale result for a row that is no longer SCHEDULED or HELD. Null: the row is tappable. */
    static TapResult staleFor(ScheduledMessage m) {
        if (m == null) return NOT_PENDING;
        Status s = m.getStatus();
        if (s == Status.SCHEDULED || s == Status.HELD) return null;
        if (s == Status.IN_FLIGHT) return ALREADY_SENDING;
        if (s == Status.SENT_VERIFIED) return ALREADY_SENT;
        if (s == Status.CANCELED) return ALREADY_CANCELLED;
        return NOT_PENDING;
    }

    /**
     * The short line the user sees for a stale tap, or null for {@link #DONE}. {@code cancelTap}:
     * the tap was Cancel (an IN_FLIGHT row then says it can't be cancelled now).
     */
    public static String feedback(TapResult r, boolean cancelTap) {
        if (r == null || r == DONE) return null;
        switch (r) {
            case ALREADY_SENDING:
                return cancelTap ? "Already sending — it can't be cancelled now" : "Already sending";
            case ALREADY_SENT: return "Already sent";
            case ALREADY_CANCELLED: return "Already cancelled";
            case NOT_PENDING:
            default: return "This message is no longer scheduled";
        }
    }
}
