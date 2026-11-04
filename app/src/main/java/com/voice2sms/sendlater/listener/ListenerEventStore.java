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

    /**
     * OD22(5): records that a notification carrying this conversation's number was matched to it
     * (the listener can see this conversation without a display name). Default: nothing durable;
     * {@link #hasNumberMatch} then falls back to the stored events.
     */
    default void noteNumberMatch(String conversationKey) {}

    /**
     * OD22(5): whether a notification was ever matched to this conversation by its number.
     * Default: any event is stored for the key (a message without a display name can only be
     * matched by number).
     */
    default boolean hasNumberMatch(String conversationKey) {
        List<ThreadEvent> e = eventsSince(conversationKey, 0L);
        return e != null && !e.isEmpty();
    }
}
