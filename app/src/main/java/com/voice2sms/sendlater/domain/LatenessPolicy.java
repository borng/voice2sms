package com.voice2sms.sendlater.domain;

/**
 * Per-message "If late" choice (D10, R12, R27, I2). Values pinned (OD8/OD14/OD15); persist by
 * {@link #name()}.
 *
 * <ul>
 *   <li>{@link #DEFAULT}: the D10 table in {@link LatenessBudget}.</li>
 *   <li>{@link #WITHIN_30_MIN}: 30 min regardless of lead time.</li>
 *   <li>{@link #NEVER_LATE}: budget 0, never auto-send late.</li>
 *   <li>{@link #UP_TO_24H}: UI label "Send anyway"; still hard-capped at 24 h (I2).</li>
 * </ul>
 */
public enum LatenessPolicy {
    DEFAULT,
    WITHIN_30_MIN,
    NEVER_LATE,
    UP_TO_24H;

    /** Alias of {@link #DEFAULT} (name used by the acceptance tests). Not a separate value. */
    public static final LatenessPolicy AUTO = DEFAULT;
    /** Alias of {@link #NEVER_LATE}. Not a separate value. */
    public static final LatenessPolicy NEVER = NEVER_LATE;
    /** Alias of {@link #UP_TO_24H} ("Send anyway" in the UI). Not a separate value; still capped at 24 h. */
    public static final LatenessPolicy SEND_ANYWAY = UP_TO_24H;
}
