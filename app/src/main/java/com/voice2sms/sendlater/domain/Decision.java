package com.voice2sms.sendlater.domain;

import java.util.Objects;

/**
 * Result of {@link LadderEvaluator} (R25): SEND, HOLD(HoldReason), REMIND(RemindReason),
 * CANCEL(Classification.SELF_PREEMPTED), WAIT(untilMs), EXPIRED (R29). Immutable.
 */
public final class Decision {

    public enum Kind { SEND, HOLD, REMIND, CANCEL, WAIT, EXPIRED }

    private static final Decision SEND = new Decision(Kind.SEND, null, 0L);
    private static final Decision EXPIRED = new Decision(Kind.EXPIRED, null, 0L);

    private final Kind kind;
    private final Enum<?> reason;
    private final long untilMs;

    private Decision(Kind kind, Enum<?> reason, long untilMs) {
        this.kind = kind;
        this.reason = reason;
        this.untilMs = untilMs;
    }

    public static Decision send() { return SEND; }
    public static Decision expired() { return EXPIRED; }
    public static Decision hold(HoldReason r) { return new Decision(Kind.HOLD, nonNull(r), 0L); }
    public static Decision remind(RemindReason r) { return new Decision(Kind.REMIND, nonNull(r), 0L); }
    public static Decision cancel(Classification r) { return new Decision(Kind.CANCEL, nonNull(r), 0L); }
    /** Retry no later than {@code untilMs}; re-evaluating then yields REMIND if still blocked. */
    public static Decision waitUntil(RemindReason r, long untilMs) {
        return new Decision(Kind.WAIT, nonNull(r), untilMs);
    }

    /** WAIT with no blocking fact: the message is simply not due yet. */
    public static Decision waitUntil(long untilMs) {
        return new Decision(Kind.WAIT, null, untilMs);
    }

    public Kind getKind() { return kind; }

    /** HoldReason for HOLD, RemindReason for REMIND and a blocked WAIT (null when merely not due), Classification for CANCEL, else null. */
    public Enum<?> getReason() { return reason; }

    public HoldReason getHoldReason() { return reason instanceof HoldReason ? (HoldReason) reason : null; }
    public RemindReason getRemindReason() { return reason instanceof RemindReason ? (RemindReason) reason : null; }

    /** For WAIT: the wall-clock time (ms) by which to re-evaluate; 0 otherwise. */
    public long getUntilMs() { return untilMs; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Decision)) return false;
        Decision d = (Decision) o;
        return kind == d.kind && reason == d.reason && untilMs == d.untilMs;
    }

    @Override public int hashCode() { return Objects.hash(kind, reason, untilMs); }

    @Override public String toString() {
        return kind + (reason != null ? "(" + reason + ")" : "") + (kind == Kind.WAIT ? "@" + untilMs : "");
    }

    private static <T> T nonNull(T t) {
        if (t == null) throw new IllegalArgumentException("reason");
        return t;
    }
}
