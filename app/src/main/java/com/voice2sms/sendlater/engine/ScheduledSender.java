package com.voice2sms.sendlater.engine;

import android.content.Context;

import com.voice2sms.sendlater.domain.PreSendCheck;
import com.voice2sms.sendlater.domain.ScheduledMessage;

/**
 * The send path (implemented by the send-path node, registered with
 * {@link SendLaterEngine#setSender}). The engine has already marked the row IN_FLIGHT with a body
 * hash when it calls {@link #start}. The sender never changes status itself: it reports through
 * {@link SendLaterEngine#onSendOutcome}, the only path from a send to a status change (R31).
 */
public interface ScheduledSender {
    /**
     * Starts sending {@code message}. Returning true only means the attempt started; false means
     * nothing was attempted (treated like {@code SendOutcome.CANNOT_SHOW}).
     */
    boolean start(Context context, ScheduledMessage message);

    /**
     * D30: {@link #start(Context, ScheduledMessage)} with the pre-send thread check the engine
     * asks for ({@link PreSendCheck#off()} for the user's own tap). A sender that cannot read the
     * thread ignores it; such a sender must keep {@link #checksThreadBeforeSend()} false.
     */
    default boolean start(Context context, ScheduledMessage message, PreSendCheck check) {
        return start(context, message);
    }

    /**
     * D30 / OD22(5): this sender reads the conversation right before it sends and stops the send
     * on what it finds ({@link PreSendCheck}). Only then may the engine dispatch a message that
     * has no listener evidence with {@link PreSendCheck#required} instead of holding it.
     */
    default boolean checksThreadBeforeSend() {
        return false;
    }
}
