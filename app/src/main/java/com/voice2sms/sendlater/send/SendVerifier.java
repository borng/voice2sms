package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.domain.ThreadEvent;

import java.util.List;

/**
 * Schrodinger verification after a scheduled send (R31, I5). Pure JVM.
 *
 * <p>The send counts as verified only when the thread shows an OUTBOUND bubble whose text hash is
 * the message's body hash, timed no earlier than the thread check (minus {@link #CLOCK_SLACK_MS}
 * for server/device clock skew, so an older bubble with the same text never counts). An inbound
 * bubble never verifies, whatever its text. When an inbound bubble is timed after the thread
 * check and no later than the send (the earlier of {@code sendTimeMs} and our own bubble), the
 * result is {@link Result#VERIFIED_WITH_LATE_REPLY}: the message went out, but the other side
 * wrote just before it did.
 */
public final class SendVerifier {

    /** Outcome of one verification read. Values pinned by SendVerifierTest. */
    public enum Result { VERIFIED, UNVERIFIED, VERIFIED_WITH_LATE_REPLY }

    /** Own bubble may be timed this much before the thread check (GV server vs device clock). */
    public static final long CLOCK_SLACK_MS = 2 * 60_000L;

    private SendVerifier() {}

    public static Result verify(String bodyHash, List<ThreadEvent> threadEvents, long checkTimeMs, long sendTimeMs) {
        if (bodyHash == null || threadEvents == null) return Result.UNVERIFIED;
        Long ownTime = null;
        for (ThreadEvent e : threadEvents) {
            if (e == null || e.getDirection() != ThreadEvent.Direction.OUT) continue;
            if (!bodyHash.equals(e.getTextHash())) continue;
            if (e.getTimeMs() < checkTimeMs - CLOCK_SLACK_MS) continue;
            if (ownTime == null || e.getTimeMs() < ownTime) ownTime = e.getTimeMs();
        }
        if (ownTime == null) return Result.UNVERIFIED;
        long upper = Math.min(sendTimeMs, ownTime);
        for (ThreadEvent e : threadEvents) {
            if (e == null || e.getDirection() != ThreadEvent.Direction.IN) continue;
            if (e.getTimeMs() > checkTimeMs && e.getTimeMs() <= upper) return Result.VERIFIED_WITH_LATE_REPLY;
        }
        return Result.VERIFIED;
    }
}
