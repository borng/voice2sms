package com.voice2sms.sendlater.engine;

import com.voice2sms.sendlater.domain.ThreadEventSource;

/**
 * Marker for a {@link ThreadEventSource} that sees the user's own outbound messages in the thread
 * (the WebView open-thread read, the native on-screen bubbles), so an empty read from it is
 * evidence that an interrupted send did not go out (OD21, R31, I5).
 *
 * <p>The engine retries an IN_FLIGHT or FAILED message only when the thread read included at least
 * one available source of this kind. A read from other sources alone (the notification listener
 * sees inbound messages only) cannot prove the send did not happen, so the message goes to
 * HELD(UNVERIFIED) with the "may have already sent" notification instead.
 * {@link NotificationEventSource} deliberately does not implement this.
 */
public interface OutboundCapableSource extends ThreadEventSource {
}
