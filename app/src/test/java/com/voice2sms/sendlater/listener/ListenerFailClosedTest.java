package com.voice2sms.sendlater.listener;

import static com.voice2sms.sendlater.listener.ListenerFixtures.E164;
import static com.voice2sms.sendlater.listener.ListenerFixtures.MIN;
import static com.voice2sms.sendlater.listener.ListenerFixtures.NAME;
import static com.voice2sms.sendlater.listener.ListenerFixtures.T0;
import static com.voice2sms.sendlater.listener.ListenerFixtures.gvReply;
import static com.voice2sms.sendlater.listener.ListenerFixtures.message;
import static com.voice2sms.sendlater.listener.ListenerFixtures.v2sSms;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.Classification;
import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEventClassifier;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * OD22(5): the listener source is not available for a message with no display name until a
 * notification was matched to that conversation by number, so the due-time check gives
 * UNVERIFIED (hold), not UNCHANGED. Fictional numbers and placeholder names only.
 */
public class ListenerFailClosedTest {

    /** FakeStore plus a durable number-match record. */
    static final class MatchStore implements ListenerEventStore {
        final ListenerFixtures.FakeStore inner = new ListenerFixtures.FakeStore();
        final List<String> notes = new ArrayList<>();
        @Override public List<ScheduledMessage> liveMessages() { return inner.liveMessages(); }
        @Override public long recordEvent(ConversationRef r, ThreadEvent e, String s, long at) { return inner.recordEvent(r, e, s, at); }
        @Override public List<ThreadEvent> eventsSince(String k, long since) { return inner.eventsSince(k, since); }
        @Override public int purgeEvents(long older, boolean orphans) { return inner.purgeEvents(older, orphans); }
        @Override public void noteNumberMatch(String key) { notes.add(key); }
        @Override public boolean hasNumberMatch(String key) { return notes.contains(key); }
    }

    /** What the engine does: read only from a source available for this conversation. */
    private static Classification check(NotificationThreadEventSource src, ScheduledMessage m) {
        ConversationRef ref = ConversationRef.of(m);
        List<ThreadEvent> events = src.isAvailableFor(ref) ? src.readSince(ref, m.getWatermark()) : null;
        return ThreadEventClassifier.classify(m, events, true);
    }

    @Test public void namelessMessageWithNoNumberMatchIsUnverified() {
        MatchStore store = new MatchStore();
        NotificationThreadEventSource src = new NotificationThreadEventSource(store, () -> true);
        ScheduledMessage m = message(1, E164, null).build();
        assertTrue(src.isAvailable());
        assertFalse(src.isAvailableFor(ConversationRef.of(m)));
        assertEquals(Classification.UNVERIFIED, check(src, m));
    }

    @Test public void blankNameCountsAsNoName() {
        NotificationThreadEventSource src = new NotificationThreadEventSource(new MatchStore(), () -> true);
        assertFalse(src.isAvailableFor(new ConversationRef(E164, "  ")));
        assertFalse(src.isAvailableFor(new ConversationRef(null, null)));
        assertFalse(src.isAvailableFor(null));
    }

    @Test public void namedMessageWithNothingRecordedIsUnchanged() {
        NotificationThreadEventSource src = new NotificationThreadEventSource(new MatchStore(), () -> true);
        ScheduledMessage m = message(1, E164, NAME).build();
        assertTrue(src.isAvailableFor(ConversationRef.of(m)));
        assertEquals(Classification.UNCHANGED, check(src, m));
    }

    @Test public void listenerOffIsUnavailableForEveryConversation() {
        NotificationThreadEventSource src = new NotificationThreadEventSource(new MatchStore(), () -> false);
        assertFalse(src.isAvailableFor(new ConversationRef(E164, NAME)));
    }

    @Test public void aNumberMatchMakesTheNamelessMessageVerifiable() {
        MatchStore store = new MatchStore();
        ScheduledMessage m = message(1, E164, null).build();
        store.inner.seed(m);
        ListenerEventRecorder rec = new ListenerEventRecorder(store, null);
        // A voice2sms SMS notification carries the sender number.
        assertTrue(rec.onNotification(v2sSms(E164, "ok", T0 + 5 * MIN), T0 + 5 * MIN));
        assertEquals(1, store.notes.size());
        assertEquals(E164, store.notes.get(0));
        NotificationThreadEventSource src = new NotificationThreadEventSource(store, () -> true);
        assertTrue(src.isAvailableFor(ConversationRef.of(m)));
        assertEquals(Classification.REPLIED, check(src, m));
    }

    @Test public void aNameMatchIsNotNotedAsANumberMatch() {
        MatchStore store = new MatchStore();
        store.inner.seed(message(1, E164, NAME).build());
        ListenerEventRecorder rec = new ListenerEventRecorder(store, null);
        assertTrue(rec.onNotification(gvReply(T0 + 5 * MIN), T0 + 5 * MIN));
        assertTrue(store.notes.isEmpty());
    }

    @Test public void defaultNumberMatchIgnoresStoredEvents() {
        ListenerFixtures.FakeStore plain = new ListenerFixtures.FakeStore();
        assertFalse(plain.hasNumberMatch(E164));
        plain.recordEvent(new ConversationRef(E164, null),
                new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.SMS, T0, null, null), "listener", T0);
        assertFalse(plain.hasNumberMatch(E164));
        plain.noteNumberMatch(E164); // default: no-op, no throw
        assertFalse(plain.hasNumberMatch(E164));
    }

    /**
     * Verifier F1: a name-only reply stored under the shared number for a named message must not
     * vouch for a nameless message to the same number.
     */
    @Test public void nameMatchedEventsForAnotherMessageDoNotVouchForANamelessOne() {
        MatchStore store = new MatchStore();
        ScheduledMessage named = message(1, E164, NAME).build();
        ScheduledMessage nameless = message(2, E164, null).build();
        store.inner.seed(named);
        store.inner.seed(nameless);
        ListenerEventRecorder rec = new ListenerEventRecorder(store, null);
        assertTrue(rec.onNotification(gvReply(T0 + 5 * MIN), T0 + 5 * MIN));
        assertFalse(store.inner.eventsSince(ConversationRef.of(nameless).key(), 0L).isEmpty());
        assertTrue(store.notes.isEmpty());
        NotificationThreadEventSource src = new NotificationThreadEventSource(store, () -> true);
        assertFalse(src.isAvailableFor(ConversationRef.of(nameless)));
        assertEquals(Classification.UNVERIFIED, check(src, nameless));
        // Same with the interface default (no durable marks at all).
        ListenerFixtures.FakeStore plain = new ListenerFixtures.FakeStore();
        plain.seed(named);
        plain.seed(nameless);
        new ListenerEventRecorder(plain, null).onNotification(gvReply(T0 + 5 * MIN), T0 + 5 * MIN);
        NotificationThreadEventSource src2 = new NotificationThreadEventSource(plain, () -> true);
        assertFalse(src2.isAvailableFor(ConversationRef.of(nameless)));
        assertEquals(Classification.UNVERIFIED, check(src2, nameless));
    }

    @Test public void repositoryStoreHashesKeysAndNeverStoresTheNumber() {
        String h = RepositoryListenerStore.hash(E164);
        assertEquals(64, h.length());
        assertFalse(h.contains(E164.substring(1)));
        assertEquals(h, RepositoryListenerStore.hash(E164));
        assertEquals(null, RepositoryListenerStore.hash(null));
    }
}
