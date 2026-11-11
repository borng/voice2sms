package com.voice2sms.sendlater.domain;

import java.util.Objects;

/**
 * D30 / LIVE-F3: the check of the open thread's newest messages that the WebView sender makes
 * right before it taps Send for a scheduled message (R26, R28, R31, OD22(5), OD27). Pure JVM,
 * immutable.
 *
 * <p>The engine decides, at due time, how strict the check is ({@link Mode}) and passes it with
 * the dispatch; the sender reads the thread, classifies it against the record's watermark with
 * {@link ThreadEventClassifier} and asks {@link #decide} what to do. When the answer is not
 * {@link Verdict#SEND} the sender reports {@code SendOutcome.CANNOT_SHOW} with
 * {@link Verdict#detail()} (nothing was sent: the Send tap was never granted) and the engine, the
 * only status writer, applies {@link Verdict#fromDetail}. The check can only add holds; it never
 * overrides one the engine already made from listener evidence.
 */
public final class PreSendCheck {

    /** How strict the pre-send check is for one dispatch. */
    public enum Mode {
        /** No check: the user's own Send now / Send anyway tap (R28 skips the thread check). */
        OFF,
        /**
         * Listener evidence allowed the send; the check adds holds. A thread that cannot be read
         * ends the attempt with a reminder (nothing sent).
         */
        CHECK,
        /**
         * OD22(5) / D30: no listener evidence exists for this number, so this check is the
         * evidence. A thread that cannot be read holds the message as UNVERIFIED.
         */
        REQUIRED
    }

    /** What the check concluded. Every value but SEND means nothing is sent by this attempt. */
    public enum Verdict {
        SEND(null, null),
        HOLD_REPLIED(HoldReason.REPLIED, "presend_replied"),
        HOLD_REACHED_OUT(HoldReason.REACHED_OUT, "presend_reached_out"),
        HOLD_OUTBOUND_ONLY(HoldReason.OUTBOUND_ONLY, "presend_outbound_only"),
        /** The user already sent a similar text since the watermark: the message is SELF_PREEMPTED. */
        SELF_PREEMPTED(null, "presend_self_preempted"),
        /** REQUIRED and the thread could not be read: HELD(UNVERIFIED), no "may have already sent". */
        HOLD_UNVERIFIED(HoldReason.UNVERIFIED, "presend_unverified"),
        /** CHECK and the thread could not be read: back to SCHEDULED with a reminder. */
        CANNOT_CHECK(null, "presend_unreadable");

        private final HoldReason holdReason;
        private final String detail;

        Verdict(HoldReason holdReason, String detail) {
            this.holdReason = holdReason;
            this.detail = detail;
        }

        /** The hold this verdict asks the engine for, or null (SEND, SELF_PREEMPTED, CANNOT_CHECK). */
        public HoldReason holdReason() { return holdReason; }

        /** The CANNOT_SHOW detail the sender reports for this verdict; null for SEND. */
        public String detail() { return detail; }

        /** The verdict a CANNOT_SHOW detail names, or null when it is not a pre-send detail. */
        public static Verdict fromDetail(String detail) {
            if (detail == null) return null;
            String d = detail.trim();
            for (Verdict v : values()) {
                if (v.detail != null && v.detail.equals(d)) return v;
            }
            return null;
        }
    }

    private static final PreSendCheck OFF = new PreSendCheck(Mode.OFF, OutboundPolicy.STILL_SEND, false, false, false);

    private final Mode mode;
    private final OutboundPolicy outboundPolicy;
    /** live-presend-failclosed: the engine stated the listener facts below at dispatch. */
    private final boolean listenerFactsKnown;
    /** The notification listener was enabled (and bound) at dispatch. */
    private final boolean listenerEnabled;
    /** The listener store ever matched a notification to this conversation (number match or stored event). */
    private final boolean listenerSawConversation;

    private PreSendCheck(Mode mode, OutboundPolicy outboundPolicy, boolean listenerFactsKnown,
                         boolean listenerEnabled, boolean listenerSawConversation) {
        if (mode == null) throw new IllegalArgumentException("mode");
        this.mode = mode;
        this.outboundPolicy = outboundPolicy != null ? outboundPolicy : OutboundPolicy.STILL_SEND;
        this.listenerFactsKnown = listenerFactsKnown;
        this.listenerEnabled = listenerEnabled;
        this.listenerSawConversation = listenerSawConversation;
    }

    /** The user's tap: no check. */
    public static PreSendCheck off() { return OFF; }

    /** Due time with listener evidence: the check adds holds. */
    public static PreSendCheck check(OutboundPolicy policy) {
        return new PreSendCheck(Mode.CHECK, policy, false, false, false);
    }

    /** Due time without listener evidence (OD22(5)): the check is the evidence. */
    public static PreSendCheck required(OutboundPolicy policy) {
        return new PreSendCheck(Mode.REQUIRED, policy, false, false, false);
    }

    /**
     * live-presend-failclosed (D30, R31): this check with the listener facts the engine knows at
     * dispatch, which the empty-conversation rule ({@link #mayClearEmptyConversation}) needs:
     * {@code listenerEnabled} (the notification listener is enabled and bound now) and
     * {@code listenerSawConversation} (its store ever matched a notification to this
     * conversation). A check without them never clears an empty conversation (fail closed).
     */
    public PreSendCheck withListenerFacts(boolean listenerEnabled, boolean listenerSawConversation) {
        if (mode == Mode.OFF) return this;
        return new PreSendCheck(mode, outboundPolicy, true, listenerEnabled, listenerSawConversation);
    }

    /** The engine stated the listener facts ({@link #withListenerFacts}). */
    public boolean hasListenerFacts() { return listenerFactsKnown; }

    public boolean isListenerEnabled() { return listenerFactsKnown && listenerEnabled; }

    /** True when unknown (fail closed). */
    public boolean listenerSawConversation() { return !listenerFactsKnown || listenerSawConversation; }

    /**
     * live-presend-failclosed: whether a read that sees NO conversation for the number (GV's
     * new-conversation draft never shows one, a never-texted thread is indistinguishable from a
     * slow render, the inbox list is lazy) may open the Send gate. Only when ALL hold:
     * <ol>
     *   <li>nothing was seen when the message was scheduled: neither the reply watermark nor the
     *       self-preemption baseline has a newest message (newestTs, bubbleHash and server id all null; a
     *       missing watermark is unknown, so false);</li>
     *   <li>the notification listener is enabled now (dispatch time);</li>
     *   <li>the listener store never matched a notification to this conversation (a reply after
     *       scheduling would have posted one).</li>
     * </ol>
     * Otherwise the empty read proves nothing and the verdict is {@link Verdict#HOLD_UNVERIFIED}.
     */
    public boolean mayClearEmptyConversation(ScheduledMessage message) {
        if (message == null || !listenerFactsKnown) return false;
        if (!nothingSeen(message.getWatermark()) || !nothingSeen(message.getSelfPreemptionBaseline())) return false;
        return listenerEnabled && !listenerSawConversation;
    }

    private static boolean nothingSeen(Watermark w) {
        return w != null && w.getNewestTs() == null && w.getBubbleHash() == null && w.getNewestServerId() == null;
    }

    public Mode getMode() { return mode; }

    public OutboundPolicy getOutboundPolicy() { return outboundPolicy; }

    public boolean isOff() { return mode == Mode.OFF; }

    /**
     * D30 dispatch rule: whether the only reason the ladder would hold is that no listener
     * evidence exists ({@code listenerClassification == UNVERIFIED}, OD22(5)) while the message
     * goes to a sender that can read the thread before it sends, at an autonomous rung (R0/R1).
     * Then the engine evaluates the rest of the ladder as if the thread were unchanged and
     * dispatches with {@link #required}; any other classification keeps its usual result.
     */
    public static boolean checkReplacesMissingEvidence(Classification listenerClassification, Rung rung,
                                                       boolean senderChecksThread) {
        return senderChecksThread && listenerClassification == Classification.UNVERIFIED
                && rung != Rung.R2 && rung != Rung.R3;
    }

    /**
     * The verdict for {@code message} given the classification of the thread just read
     * ({@link Classification#UNVERIFIED} when it could not be read or aligned). Follows the
     * ladder's own rules (R26, D8): SELF_PREEMPTED cancels; REPLIED / REACHED_OUT hold when
     * holdIfReplied is on; OUTBOUND_ONLY holds under HOLD_AND_ASK; anything else sends.
     */
    public Verdict decide(ScheduledMessage message, Classification read) {
        if (mode == Mode.OFF) return Verdict.SEND;
        if (message == null) throw new IllegalArgumentException("message");
        if (read == null || read == Classification.UNVERIFIED) {
            return mode == Mode.REQUIRED ? Verdict.HOLD_UNVERIFIED : Verdict.CANNOT_CHECK;
        }
        switch (read) {
            case SELF_PREEMPTED:
                return Verdict.SELF_PREEMPTED;
            case REPLIED:
                return message.isHoldIfReplied() ? Verdict.HOLD_REPLIED : Verdict.SEND;
            case REACHED_OUT:
                return message.isHoldIfReplied() ? Verdict.HOLD_REACHED_OUT : Verdict.SEND;
            case OUTBOUND_ONLY:
                return outboundPolicy == OutboundPolicy.HOLD_AND_ASK ? Verdict.HOLD_OUTBOUND_ONLY : Verdict.SEND;
            case UNCHANGED:
            default:
                return Verdict.SEND;
        }
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof PreSendCheck)) return false;
        PreSendCheck c = (PreSendCheck) o;
        return mode == c.mode && outboundPolicy == c.outboundPolicy && listenerFactsKnown == c.listenerFactsKnown
                && listenerEnabled == c.listenerEnabled && listenerSawConversation == c.listenerSawConversation;
    }

    @Override public int hashCode() {
        return Objects.hash(mode, outboundPolicy, listenerFactsKnown, listenerEnabled, listenerSawConversation);
    }

    @Override public String toString() {
        return "PreSendCheck{" + mode + ", " + outboundPolicy
                + (listenerFactsKnown ? ", listener=" + listenerEnabled + ", saw=" + listenerSawConversation : "")
                + "}";
    }
}
