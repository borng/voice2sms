package com.voice2sms.sendlater.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The thread check's classification step (R26, R32).
 *
 * <p>Inputs: the message (its text and watermark), the events read since the watermark
 * ({@code null} when no source could be read), and whether the notification listener is enabled.
 *
 * <p>Event window: for EXACT/TIMESTAMP watermarks an outbound event counts when it is newer than
 * the watermark's newest timestamp (or its capture time when the timestamp is unknown); an inbound
 * event counts from {@link #DEVICE_ONLY_MARGIN_MS} before that time, shifted by the watermark's
 * {@code skewEstimateMs} when present (OD20(6): listener times use the device clock, the
 * watermark may use server time, so a reply right at the watermark is not dropped). For
 * DEVICE_ONLY (and a missing watermark, using {@code createdAt}) the capture clock is fuzzy by
 * {@link #DEVICE_ONLY_MARGIN_MS}: inbound events from 90 s before capture onward count (better to
 * hold than to talk over a reply), outbound events only from 90 s after capture (the message the
 * user was typing around capture time is not "his own newer message").
 *
 * <p>Precedence among counted events: SELF_PREEMPTED, then REPLIED, then REACHED_OUT, then
 * OUTBOUND_ONLY. Nothing counted: UNCHANGED when the listener is enabled, else UNVERIFIED.
 */
public final class ThreadEventClassifier {

    public static final long DEVICE_ONLY_MARGIN_MS = 90_000L;
    public static final double JACCARD_THRESHOLD = 0.6;
    /** OD20(1): containment counts only when the shorter normalized text has this many tokens. */
    public static final int MIN_CONTAINMENT_TOKENS = 3;

    private ThreadEventClassifier() {}

    /**
     * {@link #classify(ScheduledMessage, List, boolean, Watermark, Watermark)} with the message's
     * own baselines: replies from {@link ScheduledMessage#getWatermark()}, self-preemption from
     * {@link ScheduledMessage#getSelfPreemptionBaseline()} (OD22(1)).
     */
    public static Classification classify(ScheduledMessage message, List<ThreadEvent> events,
                                          boolean listenerEnabled) {
        return classify(message, events, listenerEnabled, message.getWatermark(),
                message.getSelfPreemptionBaseline());
    }

    /**
     * Classifies with two explicit baselines (OD22(1)). {@code replyBaseline} bounds inbound
     * events and outbound texts that are not similar to the message; {@code selfPreemptionBaseline}
     * bounds outbound texts similar to the message, so after a snooze (fresh reply baseline) a text
     * the user sent by hand since the ORIGINAL watermark still cancels. Either baseline may be null
     * (DEVICE_ONLY at {@code createdAt}).
     */
    public static Classification classify(ScheduledMessage message, List<ThreadEvent> events,
                                          boolean listenerEnabled, Watermark replyBaseline,
                                          Watermark selfPreemptionBaseline) {
        if (events == null) return Classification.UNVERIFIED;

        long createdAt = message.getCreatedAt();
        boolean selfPreempted = false, replied = false, reachedOut = false, outbound = false;
        for (ThreadEvent e : events) {
            if (e == null) continue;
            if (e.isInbound()) {
                if (!counts(replyBaseline, createdAt, e)) continue;
                if (e.isCallLike()) reachedOut = true; else replied = true;
            } else if (e.getKind() == ThreadEvent.Kind.SMS || e.getKind() == ThreadEvent.Kind.MMS) {
                if (similar(message, e)) {
                    if (counts(selfPreemptionBaseline, createdAt, e)) selfPreempted = true;
                } else if (counts(replyBaseline, createdAt, e)) {
                    outbound = true;
                }
            } else if (counts(replyBaseline, createdAt, e)) {
                outbound = true; // The user called them: acted in the thread, but not with this text.
            }
        }
        if (selfPreempted) return Classification.SELF_PREEMPTED;
        if (replied) return Classification.REPLIED;
        if (reachedOut) return Classification.REACHED_OUT;
        if (outbound) return Classification.OUTBOUND_ONLY;
        return listenerEnabled ? Classification.UNCHANGED : Classification.UNVERIFIED;
    }

    /** Whether {@code e} is newer than the message's (reply) watermark (see class doc for margins). */
    static boolean counts(ScheduledMessage m, ThreadEvent e) {
        return counts(m.getWatermark(), m.getCreatedAt(), e);
    }

    /**
     * Whether {@code e} is newer than baseline {@code wm} (see class doc for margins); a null
     * baseline is DEVICE_ONLY at {@code createdAt}.
     */
    static boolean counts(Watermark wm, long createdAt, ThreadEvent e) {
        long t = e.getTimeMs();
        if (wm == null || wm.getQuality() == Watermark.Quality.DEVICE_ONLY) {
            long captured = wm == null ? createdAt : wm.getCapturedAtDevice();
            return e.isInbound()
                    ? t >= captured - DEVICE_ONLY_MARGIN_MS
                    : t > captured + DEVICE_ONLY_MARGIN_MS;
        }
        long floor = wm.getNewestTs() != null ? wm.getNewestTs() : wm.getCapturedAtDevice();
        if (e.isInbound()) {
            long skew = wm.getSkewEstimateMs() != null ? wm.getSkewEstimateMs() : 0L;
            return t >= floor - DEVICE_ONLY_MARGIN_MS + skew;
        }
        return t > floor;
    }

    /**
     * Whether event {@code e} carries the message's text: {@link #similar(String, String)} on the
     * texts, or, when either side has no text, equality of {@link ThreadEvent#hashOf} of the
     * message text with the event's {@code textHash} (OD20(5)).
     */
    static boolean similar(ScheduledMessage m, ThreadEvent e) {
        String mt = m.getText();
        String et = e.getText();
        if (mt != null && et != null) return similar(mt, et);
        if (mt == null || TextNorm.tokens(mt).isEmpty() || e.getTextHash() == null) return false;
        return ThreadEvent.hashOf(mt).equals(e.getTextHash());
    }

    /**
     * R26 similarity: after normalization (case, diacritics, punctuation, whitespace) one text's
     * token sequence contains the other's and the shorter has at least
     * {@link #MIN_CONTAINMENT_TOKENS} tokens (OD20(1)), or the token-set Jaccard index is at least
     * 0.6 (any length). Null or token-less texts are never similar.
     */
    public static boolean similar(String a, String b) {
        List<String> ta = TextNorm.tokens(a);
        List<String> tb = TextNorm.tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;
        if (Math.min(ta.size(), tb.size()) >= MIN_CONTAINMENT_TOKENS
                && (containsSequence(ta, tb) || containsSequence(tb, ta))) return true;
        return jaccard(ta, tb) >= JACCARD_THRESHOLD;
    }

    static double jaccard(List<String> a, List<String> b) {
        Set<String> sa = new HashSet<>(a);
        Set<String> sb = new HashSet<>(b);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        sa.retainAll(sb);
        return union.isEmpty() ? 0.0 : (double) sa.size() / union.size();
    }

    private static boolean containsSequence(List<String> hay, List<String> needle) {
        int n = needle.size();
        for (int i = 0; i + n <= hay.size(); i++) {
            if (hay.subList(i, i + n).equals(needle)) return true;
        }
        return false;
    }
}
