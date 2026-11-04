package com.voice2sms.sendlater.engine;

/**
 * Sends the package-scoped {@code SendLaterIntents.ACTION_STATE_CHANGED} broadcast
 * ({@code EXTRA_MESSAGE_ID}, {@code EXTRA_E164}) after a status change; chips listen.
 */
public interface StateBroadcaster {
    void stateChanged(long id, String e164);
}
