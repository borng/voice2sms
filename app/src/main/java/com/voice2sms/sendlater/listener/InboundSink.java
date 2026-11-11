package com.voice2sms.sendlater.listener;

import com.voice2sms.sendlater.domain.ThreadEvent;

/**
 * The early-hold hook (R33). Production forwards to
 * {@code SendLaterEngine.onInboundEvent(context, key, event)}.
 */
public interface InboundSink {
    void onInboundEvent(String e164OrConversationKey, ThreadEvent event);
}
