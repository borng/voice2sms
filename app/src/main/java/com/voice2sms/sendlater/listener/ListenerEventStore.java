package com.voice2sms.sendlater.listener;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;

import java.util.List;

/**
 * The slice of {@code SendLaterRepository} the reply listener uses, with the repository's
 * signatures. Calls are synchronous: run them off the main thread.
 */
public interface ListenerEventStore {

    /** Messages that keep their conversation's events: SCHEDULED, HELD and IN_FLIGHT (R34). */
    List<ScheduledMessage> liveMessages();

    long recordEvent(ConversationRef ref, ThreadEvent event, String source, long recordedAt);

    /** Events of {@code conversationKey} at or after {@code sinceMs}, oldest first. */
    List<ThreadEvent> eventsSince(String conversationKey, long sinceMs);

    int purgeEvents(long olderThanMs, boolean orphans);
}
