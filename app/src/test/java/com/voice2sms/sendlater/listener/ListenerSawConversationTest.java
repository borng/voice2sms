package com.voice2sms.sendlater.listener;

import static com.voice2sms.sendlater.listener.ListenerFixtures.E164;
import static com.voice2sms.sendlater.listener.ListenerFixtures.MIN;
import static com.voice2sms.sendlater.listener.ListenerFixtures.REPLY_TEXT;
import static com.voice2sms.sendlater.listener.ListenerFixtures.T0;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.store.ThreadEventEntity;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * live-presend-failclosed (D30, R31): the listener store's own view of whether it ever matched a
 * notification to a conversation, for the empty-conversation clearance rule. Fails closed.
 * Fictional data only (ListenerFixtures).
 */
public class ListenerSawConversationTest {

    /** The fixture store plus durable number-match marks. */
    private static final class MarkingStore implements ListenerEventStore {
        final ListenerFixtures.FakeStore inner = new ListenerFixtures.FakeStore();
        final Set<String> marks = new HashSet<>();
        boolean throwing;

        @Override public List<ScheduledMessage> liveMessages() { return inner.liveMessages(); }
        @Override public long recordEvent(ConversationRef ref, ThreadEvent event, String source, long at) {
            return inner.recordEvent(ref, event, source, at);
        }
        @Override public List<ThreadEvent> eventsSince(String key, long sinceMs) {
            if (throwing) throw new IllegalStateException("db");
            return inner.eventsSince(key, sinceMs);
        }
        @Override public int purgeEvents(long olderThanMs, boolean orphans) { return inner.purgeEvents(olderThanMs, orphans); }
        @Override public void noteNumberMatch(String key) { marks.add(key); }
        @Override public boolean hasNumberMatch(String key) { return marks.contains(key); }
    }

    private final MarkingStore store = new MarkingStore();
    private final ConversationRef ref = new ConversationRef(E164, null);

    @Test public void aNeverMatchedConversationIsNotSeen() {
        assertFalse(new NotificationThreadEventSource(store, () -> true).sawConversation(ref));
        assertFalse("independent of availability", new NotificationThreadEventSource(store, () -> false).sawConversation(ref));
    }

    @Test public void aNumberMatchIsSeen() {
        store.noteNumberMatch(ref.key());
        assertTrue(new NotificationThreadEventSource(store, () -> true).sawConversation(ref));
    }

    @Test public void aStoredEventIsSeen() {
        store.recordEvent(ref, new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.SMS, T0 + MIN,
                ThreadEvent.hashOf(REPLY_TEXT), REPLY_TEXT), ThreadEventEntity.SOURCE_LISTENER, T0 + MIN);
        assertTrue(new NotificationThreadEventSource(store, () -> true).sawConversation(ref));
    }

    @Test public void anotherNumbersMatchIsNotThisOne() {
        store.noteNumberMatch(new ConversationRef("+15555550199", null).key());
        assertFalse(new NotificationThreadEventSource(store, () -> true).sawConversation(ref));
    }

    @Test public void anUnreadableStoreOrNoRefCountsAsSeen() {
        store.throwing = true;
        assertTrue(new NotificationThreadEventSource(store, () -> true).sawConversation(ref));
        assertTrue(new NotificationThreadEventSource(store, () -> true).sawConversation(null));
    }
}
