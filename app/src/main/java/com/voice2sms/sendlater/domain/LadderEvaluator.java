package com.voice2sms.sendlater.domain;

import java.util.List;

/**
 * The degrade ladder (R25-R29, I2, I6) as a pure function. Facts can only make the outcome more
 * cautious; nothing here can turn a HOLD/REMIND back into SEND (only the user's tap, via
 * {@link #evaluateSendAnyway}).
 *
 * <p>Order for a SCHEDULED (or IN_FLIGHT, after its thread read) message:
 * <ol>
 *   <li>SELF_PREEMPTED: CANCEL.</li>
 *   <li>REPLIED / REACHED_OUT with holdIfReplied: HOLD. OUTBOUND_ONLY under HOLD_AND_ASK: HOLD.</li>
 *   <li>UNVERIFIED: HOLD within budget, REMIND(LATE) beyond.</li>
 *   <li>Before scheduledFor: WAIT(scheduledFor) (never send early).</li>
 *   <li>Beyond the lateness budget: REMIND(LATE) (I2).</li>
 *   <li>Rung R2/R3 has no autonomous sender: REMIND(NO_SENDER).</li>
 *   <li>Session missing/expired: REMIND(NO_SESSION); send activity unshowable: REMIND(LOCKED).</li>
 *   <li>No network: WAIT(deadline) while inside the budget, else REMIND(NO_NETWORK).</li>
 *   <li>Otherwise SEND.</li>
 * </ol>
 * A HELD message stays HELD (CANCEL if the user has since sent a similar text) and becomes EXPIRED
 * once 24 h have passed since its scheduled time (R29). A SCHEDULED message whose result would be
 * REMIND (a reminded message keeps status SCHEDULED) also becomes EXPIRED once 24 h have passed
 * since its scheduled time (R29, "HELD or reminded").
 */
public final class LadderEvaluator {

    public static final long EXPIRY_MS = 24 * LatenessBudget.HOUR_MS;
    /**
     * R28's original 60 s re-check window. Since OD20(2) "Send anyway" re-checks every outbound
     * event since the watermark, so this constant no longer bounds the check; kept for callers.
     */
    public static final long SEND_ANYWAY_RECHECK_MS = 60_000L;

    private LadderEvaluator() {}

    /** {@link #evaluate(ScheduledMessage, Rung, List, long, SessionState, NetworkState, boolean, OutboundPolicy)} with STILL_SEND (D8 default). */
    public static Decision evaluate(ScheduledMessage message, Rung rung, List<ThreadEvent> events, long nowMs,
                                    SessionState session, NetworkState network, boolean listenerEnabled) {
        return evaluate(message, rung, events, nowMs, session, network, listenerEnabled, OutboundPolicy.STILL_SEND);
    }

    /**
     * Evaluates the ladder for a SCHEDULED, HELD or IN_FLIGHT message.
     *
     * <p>IN_FLIGHT (R31): an IN_FLIGHT message is evaluated like a SCHEDULED one, so once its own
     * outbound bubble appears in the thread the result is {@code CANCEL(SELF_PREEMPTED)}. For an
     * IN_FLIGHT message that result means the send went out: the engine must map it to
     * SENT_VERIFIED (R31), never to the SELF_PREEMPTED or CANCELED status.
     *
     * @param events null when no ThreadEventSource was available (classifies as UNVERIFIED)
     * @throws IllegalArgumentException for a message already in a terminal status
     */
    public static Decision evaluate(ScheduledMessage message, Rung rung, List<ThreadEvent> events, long nowMs,
                                    SessionState session, NetworkState network, boolean listenerEnabled,
                                    OutboundPolicy outboundPolicy) {
        if (message == null) throw new IllegalArgumentException("message");
        return evaluate(message, rung, events, nowMs, session, network, listenerEnabled, outboundPolicy,
                message.getWatermark(), message.getSelfPreemptionBaseline());
    }

    /**
     * {@link #evaluate(ScheduledMessage, Rung, List, long, SessionState, NetworkState, boolean, OutboundPolicy)}
     * with the two baselines given explicitly (OD22(1)): replies (and other thread activity) are
     * judged from {@code replyBaseline}, a similar outbound text from {@code selfPreemptionBaseline}.
     * After a snooze these differ: the snooze's fresh watermark and the message's original one.
     */
    public static Decision evaluate(ScheduledMessage message, Rung rung, List<ThreadEvent> events, long nowMs,
                                    SessionState session, NetworkState network, boolean listenerEnabled,
                                    OutboundPolicy outboundPolicy, Watermark replyBaseline,
                                    Watermark selfPreemptionBaseline) {
        Decision d = evaluateLadder(message, rung, events, nowMs, session, network, listenerEnabled, outboundPolicy,
                replyBaseline, selfPreemptionBaseline);
        // R29: a reminded message (status stays SCHEDULED) ignored for 24 h expires, like a HELD one.
        if (d.getKind() == Decision.Kind.REMIND
                && message.getStatus() == ScheduledMessage.Status.SCHEDULED
                && nowMs - message.getScheduledFor() >= EXPIRY_MS) {
            return Decision.expired();
        }
        return d;
    }

    private static Decision evaluateLadder(ScheduledMessage message, Rung rung, List<ThreadEvent> events, long nowMs,
                                           SessionState session, NetworkState network, boolean listenerEnabled,
                                           OutboundPolicy outboundPolicy, Watermark replyBaseline,
                                           Watermark selfPreemptionBaseline) {
        if (message == null) throw new IllegalArgumentException("message");
        ScheduledMessage.Status status = message.getStatus();
        if (status.isTerminal()) {
            throw new IllegalArgumentException("cannot evaluate a message in status " + status);
        }
        if (rung == null) rung = message.getRung();
        if (outboundPolicy == null) outboundPolicy = OutboundPolicy.STILL_SEND;

        Classification c = ThreadEventClassifier.classify(message, events, listenerEnabled,
                replyBaseline, selfPreemptionBaseline);

        if (status == ScheduledMessage.Status.HELD) {
            if (nowMs - message.getScheduledFor() >= EXPIRY_MS) return Decision.expired();
            if (c == Classification.SELF_PREEMPTED) return Decision.cancel(c);
            HoldReason r = message.getHoldReason();
            return Decision.hold(r != null ? r : HoldReason.UNVERIFIED);
        }

        long deadline = LatenessBudget.deadlineMs(message);
        boolean late = nowMs > deadline;

        switch (c) {
            case SELF_PREEMPTED:
                return Decision.cancel(c);
            case REPLIED:
                if (message.isHoldIfReplied()) return Decision.hold(HoldReason.REPLIED);
                break;
            case REACHED_OUT:
                if (message.isHoldIfReplied()) return Decision.hold(HoldReason.REACHED_OUT);
                break;
            case OUTBOUND_ONLY:
                if (outboundPolicy == OutboundPolicy.HOLD_AND_ASK) return Decision.hold(HoldReason.OUTBOUND_ONLY);
                break;
            case UNVERIFIED:
                return late ? Decision.remind(RemindReason.LATE) : Decision.hold(HoldReason.UNVERIFIED);
            case UNCHANGED:
            default:
                break;
        }

        if (nowMs < message.getScheduledFor()) {
            return Decision.waitUntil(message.getScheduledFor());
        }
        if (late) return Decision.remind(RemindReason.LATE);
        if (rung == Rung.R2 || rung == Rung.R3) return Decision.remind(RemindReason.NO_SENDER);

        if (session == null || session == SessionState.MISSING || session == SessionState.EXPIRED) {
            return Decision.remind(RemindReason.NO_SESSION);
        }
        if (session == SessionState.LOCKED_UNSHOWABLE) return Decision.remind(RemindReason.LOCKED);

        if (network != NetworkState.CONNECTED) {
            return nowMs < deadline
                    ? Decision.waitUntil(RemindReason.NO_NETWORK, deadline)
                    : Decision.remind(RemindReason.NO_NETWORK);
        }
        return Decision.send();
    }

    /**
     * R28 "Send anyway" (the user's tap): skips the thread check and the lateness budget, except
     * that a similar outbound text since the watermark cancels (the user already sent it
     * elsewhere; OD20(2): every outbound event since the watermark, not only the last 60 s).
     *
     * <p>Only a SCHEDULED or HELD message can be sent anyway. Like {@link #evaluate}, which refuses
     * terminal statuses, this refuses CANCELED, SENT_VERIFIED, SELF_PREEMPTED, EXPIRED, FAILED and
     * also IN_FLIGHT (a send is already under way) by throwing, so a stale "Send anyway" action can
     * never yield SEND for them. Callers handling a notification tap should check the stored
     * status first (or catch the exception) and drop the tap.
     *
     * @param events events just read for the conversation, or null when none could be read
     * @throws IllegalArgumentException for a terminal or IN_FLIGHT message
     */
    public static Decision evaluateSendAnyway(ScheduledMessage message, List<ThreadEvent> events, long nowMs) {
        if (message == null) throw new IllegalArgumentException("message");
        return evaluateSendAnyway(message, events, nowMs, message.getSelfPreemptionBaseline());
    }

    /**
     * {@link #evaluateSendAnyway(ScheduledMessage, List, long)} with an explicit self-preemption
     * baseline (OD22(1): the ORIGINAL watermark, also after a snooze).
     */
    public static Decision evaluateSendAnyway(ScheduledMessage message, List<ThreadEvent> events, long nowMs,
                                              Watermark selfPreemptionBaseline) {
        if (message == null) throw new IllegalArgumentException("message");
        ScheduledMessage.Status status = message.getStatus();
        if (status.isTerminal() || status == ScheduledMessage.Status.IN_FLIGHT) {
            throw new IllegalArgumentException("cannot send anyway a message in status " + status);
        }
        if (events != null) {
            for (ThreadEvent e : events) {
                if (e == null || e.isInbound() || e.isCallLike()) continue;
                if (ThreadEventClassifier.counts(selfPreemptionBaseline, message.getCreatedAt(), e)
                        && ThreadEventClassifier.similar(message, e)) {
                    return Decision.cancel(Classification.SELF_PREEMPTED);
                }
            }
        }
        return Decision.send();
    }
}
