package com.voice2sms.sendlater.engine;

import com.voice2sms.sendlater.domain.NetworkState;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.SessionState;

/** The facts the ladder needs besides the message and its thread events (R25, R27). */
public interface EngineConditions extends EngineClock {
    /** GV web session (SID cookie) and whether the send activity can be shown (D15). */
    SessionState session();

    NetworkState network();

    /** Whether the notification listener (the store-backed thread source) is enabled. */
    boolean listenerEnabled();

    OutboundPolicy outboundPolicy();

    /**
     * The Send Later master switch ({@code SendLaterSettings.isEnabled}, OD24). Off: nothing is
     * sent automatically and no alarm is re-armed; IN_FLIGHT rows are still resolved.
     */
    default boolean sendLaterEnabled() { return true; }
}
