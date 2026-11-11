package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.domain.LatenessBudget;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.engine.RemindCause;

/**
 * F4 (I2 "ever", R27): the thread check decides SEND at its own time, but a queued launch (the
 * 2 s queue spacing, a Handler delay that stops in deep sleep, a job queued behind another in the
 * live activity) can run much later. Right before a launch, and when the controller dequeues a
 * job, the row is reloaded and judged again here. Pure JVM; the caller passes the clock.
 */
public final class LaunchRecheck {

    /** What to do with a launch whose row was just reloaded. */
    public enum Verdict {
        /** Still IN_FLIGHT and within its lateness budget: launch. */
        PROCEED,
        /** Gone or no longer IN_FLIGHT (resolved or canceled meanwhile): nothing to send or report. */
        GONE,
        /** Past {@link LatenessBudget#deadlineMs}: report CANNOT_SHOW with {@link #DETAIL_LATE}. */
        LATE
    }

    /** CANNOT_SHOW cause for a late launch; the engine words it with the LATE reminder. */
    public static final String DETAIL_LATE = RemindCause.DETAIL_LATE;

    /**
     * OD30: how far past {@link LatenessBudget#deadlineMs} a launch may still start. The thread
     * check decided SEND on time; the queue spacing (2 s per message due together) and the hop
     * to the activity add a few seconds that must not turn a NEVER_LATE message (deadline = its
     * scheduled time) into a reminder. Only the launch-time re-checks (the sender's slot and the
     * controller's dequeue) pass this; the domain evaluator keeps the exact deadline.
     */
    public static final long QUEUE_SLACK_MS = 10_000L;

    private LaunchRecheck() {}

    /** Exact deadline (no slack). */
    public static Verdict check(ScheduledMessage row, long nowMs) {
        return check(row, nowMs, 0L);
    }

    /** As {@link #check(ScheduledMessage, long)} with {@code slackMs} allowed past the deadline. */
    public static Verdict check(ScheduledMessage row, long nowMs, long slackMs) {
        if (row == null || row.getStatus() != ScheduledMessage.Status.IN_FLIGHT) return Verdict.GONE;
        return isLate(row, nowMs, slackMs) ? Verdict.LATE : Verdict.PROCEED;
    }

    /** {@code nowMs} is past the row's lateness deadline (inclusive deadline, as the evaluator). */
    public static boolean isLate(ScheduledMessage row, long nowMs) {
        return isLate(row, nowMs, 0L);
    }

    /** {@code nowMs} is more than {@code slackMs} past the row's lateness deadline. */
    public static boolean isLate(ScheduledMessage row, long nowMs, long slackMs) {
        return row != null && nowMs > LatenessBudget.deadlineMs(row) + Math.max(0L, slackMs);
    }
}
