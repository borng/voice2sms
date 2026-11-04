package com.voice2sms.sendlater.engine;

import com.voice2sms.sendlater.domain.ThreadEventSource;

/**
 * Marker for a {@link ThreadEventSource} that sees the user's own outbound messages in the thread
 * (the native on-screen bubbles).
 *
 * <p>OD27: no engine path verifies or retries a send from any thread read, this kind included.
 * Such a source still feeds the decision before a send (replies, self-preemption). The engine
 * leaves it out of the late-reply notice, which uses only the listener's inbound events.
 * {@code listener.NotificationThreadEventSource} deliberately does not implement this.
 */
public interface OutboundCapableSource extends ThreadEventSource {
}
