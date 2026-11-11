package com.voice2sms.sendlater.listener;

import static com.voice2sms.sendlater.listener.ListenerFixtures.DAY;
import static com.voice2sms.sendlater.listener.ListenerFixtures.E164;
import static com.voice2sms.sendlater.listener.ListenerFixtures.MIN;
import static com.voice2sms.sendlater.listener.ListenerFixtures.NAME;
import static com.voice2sms.sendlater.listener.ListenerFixtures.REPLY_TEXT;
import static com.voice2sms.sendlater.listener.ListenerFixtures.T0;
import static com.voice2sms.sendlater.listener.ListenerFixtures.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.Classification;
import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEventClassifier;
import com.voice2sms.sendlater.domain.ThreadEventSource;
import com.voice2sms.sendlater.store.ThreadEventEntity;

import org.junit.Test;

import java.util.List;

/**
 * NotificationThreadEventSource (R32): unavailable while the listener is off (thread check yields
 * UNVERIFIED); available with nothing recorded since the watermark yields UNCHANGED.
 */
public class NotificationThreadEventSourceTest {

    private final ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore();
    private final ScheduledMessage m = message(1L, E164, NAME).build();
    private final ConversationRef ref = ConversationRef.of(m);

    /** What the engine does with one source: no available source means events == null. */
    private static Classification check(ThreadEventSource src, ScheduledMessage m) {
        boolean available = src.isAvailable();
        List<ThreadEvent> events = available ? src.readSince(ConversationRef.of(m), m.getWatermark()) : null;
        return ThreadEventClassifier.classify(m, events, available);
    }

    private void storeInbound(long timeMs) {
        store.recordEvent(ref, new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.SMS, timeMs,
                ThreadEvent.hashOf(REPLY_TEXT), REPLY_TEXT), ThreadEventEntity.SOURCE_LISTENER, timeMs);
    }

    @Test public void implementsThreadEventSource() {
        Object src = new NotificationThreadEventSource(store, () -> true);
        assertTrue(src instanceof ThreadEventSource);
    }

    @Test public void listenerNotEnabled_isUnavailable_classifiesUnverified() {
        storeInbound(T0 + 10 * MIN); // even rows on disk do not count while the listener is off
        NotificationThreadEventSource src = new NotificationThreadEventSource(store, () -> false);
        assertFalse(src.isAvailable());
        assertEquals(Classification.UNVERIFIED, check(src, m));
    }

    @Test public void listenerEnabled_noRows_readsEmpty_classifiesUnchanged() {
        NotificationThreadEventSource src = new NotificationThreadEventSource(store, () -> true);
        assertTrue(src.isAvailable());
        List<ThreadEvent> events = src.readSince(ref, m.getWatermark());
        assertNotNull("readSince never returns null", events);
        assertTrue(events.isEmpty());
        assertEquals(Classification.UNCHANGED, check(src, m));
    }

    @Test public void listenerEnabled_replyAfterWatermark_classifiesReplied() {
        storeInbound(T0 + 30 * MIN);
        NotificationThreadEventSource src = new NotificationThreadEventSource(store, () -> true);
        List<ThreadEvent> events = src.readSince(ref, m.getWatermark());
        assertEquals(1, events.size());
        assertEquals(Classification.REPLIED, check(src, m));
    }

    @Test public void listenerEnabled_onlyRowsLongBeforeWatermark_classifiesUnchanged() {
        storeInbound(T0 - 2 * DAY);
        NotificationThreadEventSource src = new NotificationThreadEventSource(store, () -> true);
        assertEquals(Classification.UNCHANGED, check(src, m));
    }

    @Test public void listenerEnabled_rowsOfAnotherConversationDoNotCount() {
        store.recordEvent(new ConversationRef("+15555550199", "Another Placeholder"),
                new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.SMS, T0 + 30 * MIN,
                        ThreadEvent.hashOf(REPLY_TEXT), REPLY_TEXT), ThreadEventEntity.SOURCE_LISTENER, T0 + 30 * MIN);
        NotificationThreadEventSource src = new NotificationThreadEventSource(store, () -> true);
        assertTrue(src.readSince(ref, m.getWatermark()).isEmpty());
        assertEquals(Classification.UNCHANGED, check(src, m));
    }
}
