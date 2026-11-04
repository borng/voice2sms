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

    private LaunchRecheck() {}

    public static Verdict check(ScheduledMessage row, long nowMs) {
        if (row == null || row.getStatus() != ScheduledMessage.Status.IN_FLIGHT) return Verdict.GONE;
        return isLate(row, nowMs) ? Verdict.LATE : Verdict.PROCEED;
    }

    /** {@code nowMs} is past the row's lateness deadline (inclusive deadline, as the evaluator). */
    public static boolean isLate(ScheduledMessage row, long nowMs) {
        return row != null && nowMs > LatenessBudget.deadlineMs(row);
    }
}
