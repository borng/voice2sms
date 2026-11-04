package com.voice2sms.sendlater.domain;

/**
 * Lateness budget (D10, R27, I2): the longest delay past {@code scheduledFor} at which an
 * auto-send is still allowed.
 */
public final class LatenessBudget {

    public static final long MINUTE_MS = 60_000L;
    public static final long HOUR_MS = 60 * MINUTE_MS;
    public static final long DAY_MS = 24 * HOUR_MS;

    /** Absolute ceiling for any policy (I2: "Send anyway" policy is capped at 24 h). */
    public static final long HARD_CAP_MS = DAY_MS;

    private LatenessBudget() {}

    /**
     * @param scheduledAheadMs how far ahead the message was scheduled ({@code scheduledFor - createdAt})
     * @param policy           the message's policy; null means {@link LatenessPolicy#DEFAULT}
     */
    public static long budgetMs(long scheduledAheadMs, LatenessPolicy policy) {
        if (policy == null) policy = LatenessPolicy.DEFAULT;
        switch (policy) {
            case WITHIN_30_MIN:
                return 30 * MINUTE_MS;
            case NEVER_LATE:
                return 0L;
            case UP_TO_24H:
                return HARD_CAP_MS;
            case DEFAULT:
            default:
                if (scheduledAheadMs < HOUR_MS) return 10 * MINUTE_MS;
                if (scheduledAheadMs <= DAY_MS) return 30 * MINUTE_MS;
                return 2 * HOUR_MS;
        }
    }

    /** Budget for a message, from its own lead time and policy. */
    public static long budgetMs(ScheduledMessage m) {
        return budgetMs(m.getScheduledFor() - m.getCreatedAt(), m.getLatenessPolicy());
    }

    /** Latest wall-clock time at which {@code m} may still be auto-sent (inclusive). */
    public static long deadlineMs(ScheduledMessage m) {
        return m.getScheduledFor() + budgetMs(m);
    }
}
