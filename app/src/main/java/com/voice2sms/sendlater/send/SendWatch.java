package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.web.ThreadBubble;

import java.util.List;

/**
 * What one scheduled send has seen of the page, poll by poll (R31). Pure JVM.
 *
 * <p>Each poll is a thread read followed by a composer probe ("our text is still in the message
 * box", i.e. Send has not been tapped yet). A read is a usable baseline only when the probe right
 * after it still found the text pending: then the read happened before the send. Afterwards only
 * bubbles beyond that baseline can verify. When the thread could not be read while the text was
 * pending (a new conversation has no thread page until its first message is sent) and the box is
 * later found empty, the newest outbound bubble alone may verify.
 *
 * <p>{@link #onPoll} returns the events {@link SendVerifier#verify} should judge, or null when
 * this poll cannot decide anything yet.
 *
 * <p>Fix pass 2 (G1, G3):
 * <ul>
 *   <li>GV web draws the outbound bubble as soon as Send is tapped, before the server takes it
 *       (and keeps it as "Not sent" when it fails). One sighting therefore decides nothing:
 *       {@link #settle} accepts a match only when it carries a server time, or when it is
 *       still there with the message box still empty at least {@link #SETTLE_MS} after it was
 *       first seen. An id attribute alone never confirms (send-hardening R4: GV's
 *       {@code data-message-id} / {@code data-id} may be a client id on an optimistic bubble).
 *       A match that never settles before the controller's deadline is SENT_UNVERIFIED.</li>
 *   <li>Without a baseline (the thread was never readable while our text was pending) an untimed
 *       newest outbound bubble may verify only when it is the ONLY outbound bubble of the read
 *       (a new conversation). In an existing thread it can be an older identical text, so it is
 *       treated as old; a timed one is judged by its time (SendVerifier's clock slack).</li>
 * </ul>
 *
 * <p>LIVE-F9: on the real GV web page a bubble's only time is its screen-reader label, which the
 * page draws on an optimistic bubble as well. A label time dates a bubble (G3) but never confirms
 * it at once: such a match must settle like an untimed one. A failed bubble never matches.
 */
public final class SendWatch {

    /** A matching bubble without a server time must persist this long (G1, R4). */
    public static final long SETTLE_MS = 3_000L;

    private final String bodyHash;
    private ThreadBaseline baseline;
    private boolean sawPending;
    /** The last onPoll's match carries what only the server gives (a time; never just an id). */
    private boolean lastMatchConfirmed;
    /** When the current unconfirmed match was first seen with the box empty; -1 when none. */
    private long firstSightingMs = -1L;

    public SendWatch(String bodyHash) {
        this.bodyHash = bodyHash;
    }

    /** @param bubbles the thread read (null: not readable); @param pending the probe after it. */
    public List<ThreadEvent> onPoll(List<ThreadBubble> bubbles, boolean pending, long nowMs) {
        lastMatchConfirmed = false;
        if (pending) sawPending = true;
        if (baseline == null) {
            if (bubbles != null && pending) {
                baseline = new ThreadBaseline(bubbles);
                return null;
            }
            if (bubbles == null || pending || !sawPending) return null;
            // Sent before the thread could be read: no baseline exists.
            int own = ThreadBaseline.newestOutboundIfOwn(bodyHash, bubbles);
            if (own >= 0 && bubbles.get(own).getTimeMs() == null && outboundCount(bubbles) != 1) {
                own = -1;   // G3: may be an older identical text; only a new conversation qualifies
            }
            // Without a baseline an id cannot date the bubble; only a time can.
            lastMatchConfirmed = own >= 0 && serverTimed(bubbles.get(own));
            return ThreadBaseline.verificationEvents(bodyHash, bubbles, own, nowMs);
        }
        if (bubbles == null) return null;
        int newStart = baseline.newStart(bubbles);
        ThreadBubble match = firstNewOwn(bubbles, newStart);
        // R4 (OD26): an id attribute proves nothing (an optimistic bubble may carry a client id);
        // only a time the page shows confirms at once, else the settle rule decides.
        lastMatchConfirmed = match != null && serverTimed(match);
        return ThreadBaseline.verificationEvents(bodyHash, bubbles, newStart, nowMs);
    }

    /**
     * G1 settle gate over one poll's verdict ({@code verdict} is SendVerifier's result for this
     * poll's events, null when onPoll returned null). Returns the verdict once the match may be
     * trusted, else null (keep polling). Never returns UNVERIFIED: the caller's deadline does.
     */
    public SendVerifier.Result settle(SendVerifier.Result verdict, boolean pending, long nowMs) {
        boolean match = verdict == SendVerifier.Result.VERIFIED
                || verdict == SendVerifier.Result.VERIFIED_WITH_LATE_REPLY;
        if (!match || pending) {
            firstSightingMs = -1L;   // gone, or text back in the box: start over
            return null;
        }
        if (lastMatchConfirmed) return verdict;
        if (firstSightingMs < 0) {
            firstSightingMs = nowMs;
            return null;
        }
        return nowMs - firstSightingMs >= SETTLE_MS ? verdict : null;
    }

    private ThreadBubble firstNewOwn(List<ThreadBubble> bubbles, int newStart) {
        if (bodyHash == null || newStart < 0) return null;
        for (int i = newStart; i < bubbles.size(); i++) {
            ThreadBubble b = bubbles.get(i);
            if (b != null && b.getDirection() == ThreadEvent.Direction.OUT && !b.isFailed()
                    && bodyHash.equals(ThreadEvent.hashOf(b.getText()))) {
                return b;
            }
        }
        return null;
    }

    /** A time the page itself gave (not a label minute, which an optimistic bubble shows too). */
    private static boolean serverTimed(ThreadBubble b) {
        return b.getTimeMs() != null && !b.isTimeFromLabel();
    }

    private static int outboundCount(List<ThreadBubble> bubbles) {
        int n = 0;
        for (ThreadBubble b : bubbles) {
            if (b != null && b.getDirection() == ThreadEvent.Direction.OUT) n++;
        }
        return n;
    }

    /** The pre-send read, or null when none was confirmed. */
    public ThreadBaseline baseline() {
        return baseline;
    }

    public boolean sawPending() {
        return sawPending;
    }

    public String bodyHash() {
        return bodyHash;
    }
}
