package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.web.ThreadBubble;

import java.util.List;

/**
 * Deliver M4: the watch over a scheduled send's bubble after it was reported SENT_VERIFIED. Pure
 * JVM.
 *
 * <p>GV web can still turn a bubble that looked settled into "Not sent" a little later. For
 * {@link #WATCH_MS} after the verdict, while the page stays on the job's thread, each read finds
 * the same bubble again (the first new outbound bubble with the body hash beyond the pre-send
 * baseline; with no baseline, the newest outbound bubble when it carries the body hash) and
 * answers {@link Step#FAILED} once the page marks it failed. The caller then downgrades the row
 * (SENT_VERIFIED -> HELD(UNVERIFIED), "may have already sent").
 *
 * <p>Never decides more than that: a bubble that disappears or a read that cannot be aligned keeps
 * watching (fail safe: the verdict stands); a page that left the thread, or the end of the window,
 * ends the watch ({@link Step#DONE}). Once FAILED or DONE, every later read answers DONE.
 */
public final class SentFollowUp {

    /** How long after the verdict the bubble is still watched. */
    public static final long WATCH_MS = 30_000L;

    /** What one read means for the watch. */
    public enum Step {
        /** Nothing new: read again. */
        KEEP,
        /** The verified bubble is now marked failed / not sent: downgrade, then stop. */
        FAILED,
        /** Stop watching (window over, page left the thread, or already decided). */
        DONE
    }

    private final String bodyHash;
    private final ThreadBaseline baseline;
    private final long startMs;
    private boolean over;

    /** {@code baseline} null: the send went out before the thread could be read (no baseline). */
    public SentFollowUp(String bodyHash, ThreadBaseline baseline, long startMs) {
        this.bodyHash = bodyHash;
        this.baseline = baseline;
        this.startMs = startMs;
    }

    /**
     * One read of the job's thread.
     *
     * @param bubbles  the read (null: not readable)
     * @param onThread the page still shows the job's thread
     */
    public Step onRead(List<ThreadBubble> bubbles, boolean onThread, long nowMs) {
        if (over) return Step.DONE;
        if (bodyHash == null || !onThread || nowMs - startMs >= WATCH_MS || nowMs < startMs) {
            over = true;
            return Step.DONE;
        }
        if (bubbles == null) return Step.KEEP;
        ThreadBubble ours = ours(bubbles);
        if (ours != null && ours.isFailed()) {
            over = true;
            return Step.FAILED;
        }
        return Step.KEEP;
    }

    /** True once the watch has ended (FAILED or DONE). */
    public boolean isOver() {
        return over;
    }

    /** The verified bubble in {@code bubbles}, failed or not; null when it cannot be found. */
    private ThreadBubble ours(List<ThreadBubble> bubbles) {
        if (baseline != null) {
            int start = baseline.newStart(bubbles);
            if (start < 0) return null;   // cannot be aligned: decide nothing
            for (int i = start; i < bubbles.size(); i++) {
                ThreadBubble b = bubbles.get(i);
                if (isOwn(b)) return b;
            }
            return null;
        }
        for (int i = bubbles.size() - 1; i >= 0; i--) {
            ThreadBubble b = bubbles.get(i);
            if (b == null || b.getDirection() != ThreadEvent.Direction.OUT) continue;
            return isOwn(b) ? b : null;
        }
        return null;
    }

    private boolean isOwn(ThreadBubble b) {
        return b != null && b.getDirection() == ThreadEvent.Direction.OUT
                && bodyHash.equals(ThreadEvent.hashOf(b.getText()));
    }
}
