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
     * (the listener can see this conversation without a display name). Default: nothing durable.
     */
    default void noteNumberMatch(String conversationKey) {}

    /**
     * OD22(5): whether a notification was ever matched to this conversation by its number,
     * decided only from {@link #noteNumberMatch} marks. Stored events never count: they are
     * keyed by the E.164 and may have been matched by name for another message to the same
     * number. Default: false (fail closed, so a nameless message stays UNVERIFIED).
     */
    default boolean hasNumberMatch(String conversationKey) {
        return false;
    }
}
