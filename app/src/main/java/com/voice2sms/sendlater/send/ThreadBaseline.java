package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.web.ThreadBubble;

import java.util.ArrayList;
import java.util.List;

/**
 * The bubbles of the open thread as read before a scheduled send (R31). Pure JVM.
 *
 * <p>A later read is aligned against it: bubbles after the baseline's tail are new, everything up
 * to it was already there. Only a NEW outbound bubble with the message's body hash can prove a
 * send; an identical old bubble never can, whatever time it shows or lacks. When the later read
 * cannot be aligned, nothing counts as new (fail safe: unverified, never a false "Sent").
 *
 * <p>Untimed bubbles are stamped by what they are: an old one takes the time of the timed bubble
 * before it (the earliest it can be, 0 when none), so an old untimed reply is never read as a
 * reply that landed during the send; a new one takes the next timed bubble's time or now (the
 * latest it can be).
 */
public final class ThreadBaseline {

    /** Baseline bubbles compared at the tail when aligning. */
    static final int TAIL = 5;

    private final List<String> keys;

    public ThreadBaseline(List<ThreadBubble> bubbles) {
        keys = keysOf(bubbles);
    }

    public int size() {
        return keys.size();
    }

    /**
     * Index in {@code later} (oldest first) of its first bubble that is not in this baseline:
     * {@code later.size()} when nothing is new, -1 when {@code later} cannot be aligned. The
     * LATEST position matching the baseline's tail is used, so a repeated text never makes an
     * old bubble look new.
     */
    public int newStart(List<ThreadBubble> later) {
        if (later == null) return -1;
        if (keys.isEmpty()) return 0;
        List<String> l = keysOf(later);
        int k = Math.min(TAIL, keys.size());
        for (int p = l.size() - 1; p >= k - 1; p--) {
            boolean match = true;
            for (int j = 0; j < k; j++) {
                if (!keys.get(keys.size() - 1 - j).equals(l.get(p - j))) {
                    match = false;
                    break;
                }
            }
            if (match) return p + 1;
        }
        return -1;
    }

    /**
     * Index of the newest outbound bubble when it carries {@code bodyHash}, else -1. For a send
     * that went out before the thread could be read at all (a new conversation: the thread page
     * appears only once the first message is sent), there is no baseline; only the newest
     * outbound can be this send.
     */
    public static int newestOutboundIfOwn(String bodyHash, List<ThreadBubble> later) {
        if (bodyHash == null || later == null) return -1;
        for (int i = later.size() - 1; i >= 0; i--) {
            ThreadBubble b = later.get(i);
            if (b == null || b.getDirection() != ThreadEvent.Direction.OUT) continue;
            return bodyHash.equals(ThreadEvent.hashOf(b.getText())) ? i : -1;
        }
        return -1;
    }

    /**
     * Events for {@code later}: bubbles before {@code newStart} are old, the rest new (a negative
     * {@code newStart} makes them all old). Timing as in the class comment.
     */
    public static List<ThreadEvent> stamp(List<ThreadBubble> later, int newStart, long nowMs) {
        List<ThreadEvent> out = new ArrayList<>();
        if (later == null) return out;
        int n = later.size();
        int firstNew = newStart < 0 ? n : Math.min(newStart, n);
        long[] times = new long[n];
        long lower = 0L;
        for (int i = 0; i < firstNew; i++) {
            Long t = timeOf(later.get(i));
            if (t != null) lower = t;
            times[i] = lower;
        }
        long upper = nowMs;
        for (int i = n - 1; i >= firstNew; i--) {
            Long t = timeOf(later.get(i));
            if (t != null) upper = t;
            times[i] = upper;
        }
        for (int i = 0; i < n; i++) {
            ThreadBubble b = later.get(i);
            if (b == null || b.getDirection() == null) continue;
            ThreadEvent e = b.toThreadEvent();
            if (e == null) {
                e = new ThreadEvent(b.getDirection(), ThreadEvent.Kind.SMS, times[i],
                        ThreadEvent.hashOf(b.getText()), b.getText());
            }
            out.add(e);
        }
        return out;
    }

    /**
     * What {@link SendVerifier#verify} may see: old inbound bubbles (a timed one after the thread
     * check is a late reply), then the new bubbles up to and including the first new outbound
     * with {@code bodyHash}. Old outbound bubbles are left out (they can never be this send), and
     * so are bubbles after our own (a reply to the sent message is not a late reply).
     */
    public static List<ThreadEvent> verificationEvents(String bodyHash, List<ThreadBubble> later, int newStart,
                                                       long nowMs) {
        List<ThreadEvent> out = new ArrayList<>();
        if (later == null) return out;
        int n = later.size();
        int firstNew = newStart < 0 ? n : Math.min(newStart, n);
        List<ThreadEvent> stamped = stamp(later, newStart, nowMs);
        int e = 0;
        for (int i = 0; i < n; i++) {
            ThreadBubble b = later.get(i);
            if (b == null || b.getDirection() == null) continue;
            ThreadEvent ev = stamped.get(e++);
            if (i < firstNew) {
                if (ev.getDirection() == ThreadEvent.Direction.IN) out.add(ev);
                continue;
            }
            out.add(ev);
            if (ev.getDirection() == ThreadEvent.Direction.OUT && bodyHash != null
                    && bodyHash.equals(ev.getTextHash())) {
                break;
            }
        }
        return out;
    }

    private static Long timeOf(ThreadBubble b) {
        return b != null ? b.getTimeMs() : null;
    }

    private static List<String> keysOf(List<ThreadBubble> bubbles) {
        List<String> out = new ArrayList<>();
        if (bubbles == null) return out;
        for (ThreadBubble b : bubbles) out.add(keyOf(b));
        return out;
    }

    /** The page's bubble id when it has one, else direction plus text hash (never the text). */
    static String keyOf(ThreadBubble b) {
        if (b == null) return "-";
        if (b.getId() != null) return "id:" + b.getId();
        return b.getDirection() + ":" + ThreadEvent.hashOf(b.getText());
    }
}
