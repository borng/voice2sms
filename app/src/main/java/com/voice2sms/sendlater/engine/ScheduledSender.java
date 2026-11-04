package com.voice2sms.sendlater.engine;

import android.content.Context;

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
}
