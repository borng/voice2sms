package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.domain.Classification;
import com.voice2sms.sendlater.domain.EchoFilter;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEventClassifier;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.web.ThreadBubble;

import java.util.ArrayList;
import java.util.List;

/**
 * D30 / LIVE-F3: the open GV web thread as read right before a scheduled send, compared with what
 * was there when the message was scheduled. Pure JVM; one instance per job (the agreement state).
 *
 * <p><b>Stable read.</b> A read counts only when a second read at least {@link #MIN_GAP_MS} later
 * agrees with it (same bubbles, directions, texts and times), so a half-rendered page is never
 * taken as "nothing new". A null read (the thread is not on screen) restarts the pairing.
 *
 * <p><b>Alignment.</b> The real page has no ids or data times; a bubble's time comes from its
 * screen-reader label at minute precision. When a baseline (reply watermark or self-preemption
 * baseline) has a newest-message time, the read is anchored at the bubble with exactly that
 * time (and the baseline's bubble hash, when it has one; see {@link #anchorIndex} for several in
 * one minute): it and everything before it were there at scheduling; the bubbles after it are
 * new. An empty read is then unaligned. Without such a bubble the whole read is judged by
 * time, but only when every bubble has a time and the read reaches back to the baseline (a bubble
 * at or before it, or fewer bubbles than the read limit); otherwise it cannot be aligned. With no
 * baseline time (DEVICE_ONLY, e.g. a number never texted) an empty read is "nothing new" and a
 * non-empty one is judged by time, again only when every bubble has a time.
 *
 * <p><b>Classification.</b> The new events go to {@link ThreadEventClassifier} with the record's
 * two baselines and its usual windows (an inbound from {@code DEVICE_ONLY_MARGIN_MS} before the
 * baseline counts; an outbound must be newer). Inbound echoes of an outbound text
 * ({@link EchoFilter}, judged over the whole read; the D17 test number) are never replies, and a
 * bubble the page marks failed is ignored. An unreadable or unaligned read is
 * {@link Classification#UNVERIFIED}. Never logs bubble text.
 */
public final class PreSendRead {

    /** Two agreeing reads must be at least this far apart. */
    public static final long MIN_GAP_MS = 1_500L;
    /** Precision of a bubble's label time. */
    static final long MINUTE_MS = 60_000L;

    private List<ThreadBubble> first;
    private long firstAtMs;

    /**
     * Feeds one read (oldest first; null when the thread is not shown). Returns the agreed read
     * once two reads {@link #MIN_GAP_MS} apart agree, else null (keep polling).
     */
    public List<ThreadBubble> onRead(List<ThreadBubble> bubbles, long nowMs) {
        if (bubbles == null) {
            first = null;
            return null;
        }
        if (first == null || !agree(first, bubbles)) {
            first = new ArrayList<>(bubbles);
            firstAtMs = nowMs;
            return null;
        }
        return nowMs - firstAtMs >= MIN_GAP_MS ? bubbles : null;
    }

    /** Same length and, bubble by bubble, the same direction, text hash, time and failed mark. */
    static boolean agree(List<ThreadBubble> a, List<ThreadBubble> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            ThreadBubble x = a.get(i), y = b.get(i);
            if (x == null || y == null) {
                if (x != y) return false;
                continue;
            }
            if (x.getDirection() != y.getDirection() || x.isFailed() != y.isFailed()) return false;
            if (!ThreadBaseline.keyOf(x).equals(ThreadBaseline.keyOf(y))) return false;
            Long tx = x.getTimeMs(), ty = y.getTimeMs();
            if (tx == null ? ty != null : !tx.equals(ty)) return false;
        }
        return true;
    }

    /**
     * The thread check's classification of an agreed read (see class doc); UNVERIFIED when
     * {@code read} is null or cannot be aligned. {@code readLimit} is the number of bubbles asked
     * for.
     */
    public static Classification classify(ScheduledMessage m, List<ThreadBubble> read, int readLimit, long nowMs) {
        List<ThreadEvent> events = eventsSinceBaseline(m, read, readLimit, nowMs);
        if (events == null) return Classification.UNVERIFIED;
        // A read thread is evidence of its own: nothing new is UNCHANGED, not UNVERIFIED.
        return ThreadEventClassifier.classify(m, events, true, m.getWatermark(), m.getSelfPreemptionBaseline());
    }

    /**
     * The events of {@code read} that may be new since the baselines, echoes and failed bubbles
     * left out, for the classifier; null when the read is null or cannot be aligned.
     */
    static List<ThreadEvent> eventsSinceBaseline(ScheduledMessage m, List<ThreadBubble> read, int readLimit,
                                                 long nowMs) {
        if (m == null || read == null) return null;
        List<ThreadBubble> bubbles = new ArrayList<>();
        for (ThreadBubble b : read) {
            if (b != null && b.getDirection() != null) bubbles.add(b);
        }
        Watermark anchorWm = anchorBaseline(m);
        Long anchorTs = anchorWm != null ? anchorWm.getNewestTs() : null;
        String anchorHash = anchorWm != null ? anchorWm.getBubbleHash() : null;

        int newStart;
        if (anchorTs != null) {
            // The thread had messages when the message was scheduled: an empty read is a page
            // that has not rendered them (or another thread), never "nothing new".
            if (bubbles.isEmpty()) return null;
            int anchor = anchorIndex(bubbles, anchorTs, anchorHash, anchorWm.getCapturedAtDevice());
            if (anchor >= 0) {
                newStart = anchor + 1;
            } else {
                if (!allTimed(bubbles)) return null;
                boolean reachesBack = bubbles.size() < readLimit;
                for (ThreadBubble b : bubbles) {
                    if (b.getTimeMs() <= anchorTs) reachesBack = true;
                }
                if (!reachesBack) return null;
                newStart = 0;
            }
        } else {
            if (bubbles.isEmpty()) return new ArrayList<>();
            if (!allTimed(bubbles)) return null;
            newStart = 0;
        }

        List<ThreadEvent> stamped = ThreadBaseline.stamp(bubbles, newStart, nowMs);
        List<ThreadEvent> forEcho = new ArrayList<>();
        for (int i = 0; i < bubbles.size(); i++) {
            forEcho.add(bubbles.get(i).isFailed() ? null : stamped.get(i));
        }
        boolean[] echo = EchoFilter.echoFlags(forEcho);
        List<ThreadEvent> out = new ArrayList<>();
        for (int i = newStart; i < bubbles.size(); i++) {
            if (bubbles.get(i).isFailed() || echo[i]) continue;
            out.add(stamped.get(i));
        }
        return out;
    }

    /**
     * Index of the bubble that was the newest one at scheduling, or -1. Label times are minute
     * precision and the page has no ids, so several bubbles can share the anchor's minute. When
     * that minute was over at capture ({@code capturedAt >= anchorTs + }{@link #MINUTE_MS}) they
     * were all there: the LAST one is the anchor. When the capture fell inside that minute, a
     * later bubble of the same minute may be new: the FIRST one is the anchor, so those count per
     * the classifier windows (an inbound holds; an outbound in the anchor's minute does not
     * count). Fail safe: a same-minute companion is at worst taken as a reply. A baseline bubble
     * hash, when known, must match too.
     */
    static int anchorIndex(List<ThreadBubble> bubbles, long anchorTs, String anchorHash, long capturedAt) {
        boolean minuteOver = capturedAt >= anchorTs + MINUTE_MS;
        int found = -1;
        for (int i = bubbles.size() - 1; i >= 0; i--) {
            ThreadBubble b = bubbles.get(i);
            if (b.getTimeMs() == null || b.getTimeMs().longValue() != anchorTs) continue;
            if (anchorHash != null && !anchorHash.equals(ThreadEvent.hashOf(b.getText()))) continue;
            found = i;
            if (minuteOver) break;
        }
        return found;
    }

    /**
     * The baseline the read is anchored at: of the reply watermark and the self-preemption
     * baseline, the one with the earlier newest-message time (so everything either may count is
     * in the new part); null when neither has one.
     */
    static Watermark anchorBaseline(ScheduledMessage m) {
        Watermark a = timed(m.getWatermark());
        Watermark b = timed(m.getSelfPreemptionBaseline());
        if (a == null) return b;
        if (b == null) return a;
        return b.getNewestTs() < a.getNewestTs() ? b : a;
    }

    private static Watermark timed(Watermark w) {
        return w != null && w.getQuality() != Watermark.Quality.DEVICE_ONLY && w.getNewestTs() != null ? w : null;
    }

    private static boolean allTimed(List<ThreadBubble> bubbles) {
        for (ThreadBubble b : bubbles) {
            if (b.getTimeMs() == null) return false;
        }
        return true;
    }
}
