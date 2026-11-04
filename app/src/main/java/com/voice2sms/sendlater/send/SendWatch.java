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
 */
public final class SendWatch {

    private final String bodyHash;
    private ThreadBaseline baseline;
    private boolean sawPending;

    public SendWatch(String bodyHash) {
        this.bodyHash = bodyHash;
    }

    /** @param bubbles the thread read (null: not readable); @param pending the probe after it. */
    public List<ThreadEvent> onPoll(List<ThreadBubble> bubbles, boolean pending, long nowMs) {
        if (pending) sawPending = true;
        if (baseline == null) {
            if (bubbles != null && pending) {
                baseline = new ThreadBaseline(bubbles);
                return null;
            }
            if (bubbles == null || pending || !sawPending) return null;
            // Sent before the thread could be read: no baseline exists.
            int own = ThreadBaseline.newestOutboundIfOwn(bodyHash, bubbles);
            return ThreadBaseline.verificationEvents(bodyHash, bubbles, own, nowMs);
        }
        if (bubbles == null) return null;
        return ThreadBaseline.verificationEvents(bodyHash, bubbles, baseline.newStart(bubbles), nowMs);
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
