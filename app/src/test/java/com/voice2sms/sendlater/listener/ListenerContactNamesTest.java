package com.voice2sms.sendlater.listener;

import static com.voice2sms.sendlater.listener.ListenerFixtures.E164;
import static com.voice2sms.sendlater.listener.ListenerFixtures.MIN;
import static com.voice2sms.sendlater.listener.ListenerFixtures.NAME;
import static com.voice2sms.sendlater.listener.ListenerFixtures.OTHER_E164;
import static com.voice2sms.sendlater.listener.ListenerFixtures.OTHER_NAME;
import static com.voice2sms.sendlater.listener.ListenerFixtures.REPLY_TEXT;
import static com.voice2sms.sendlater.listener.ListenerFixtures.T0;
import static com.voice2sms.sendlater.listener.ListenerFixtures.gvReply;
import static com.voice2sms.sendlater.listener.ListenerFixtures.gvText;
import static com.voice2sms.sendlater.listener.ListenerFixtures.message;
import static com.voice2sms.sendlater.listener.ListenerFixtures.msg;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LIVE-F12: a Voice reply from a saved contact is titled with the contact's name and carries no
 * number, so a message scheduled with only an E.164 matches it through {@link ContactNames}.
 * Placeholder names and the 555-01xx fictional range only.
 */
public class ListenerContactNamesTest {

    /** Fake resolver: fixed names per E.164, counting calls. */
    static final class FakeNames implements ContactNames {
        final Map<String, Set<String>> byNumber = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        FakeNames put(String e164, String... names) {
            byNumber.put(e164, new HashSet<>(Arrays.asList(names)));
            return this;
        }

        @Override public Set<String> namesFor(String e164) {
            calls.add(e164);
            Set<String> s = byNumber.get(e164);
            return s != null ? s : Collections.<String>emptySet();
        }
    }

    /** Counting store that also lists noteNumberMatch keys. */
    static final class NotingStore extends ListenerKeyGateTest.CountingStore {
        final List<String> numberMatches = new ArrayList<>();
        final List<ListenerFixtures.FakeStore.Recorded> recorded = inner.recorded;

        @Override public void noteNumberMatch(String key) {
            super.noteNumberMatch(key);
            numberMatches.add(key);
        }
    }

    private final ListenerFixtures.FakeSink sink = new ListenerFixtures.FakeSink();

    @After public void resetProcessGate() {
        LiveConversationKeys.invalidate();
    }

    private static ScheduledMessage numberOnly(long id, String e164) {
        return message(id, e164, null).build();
    }

    private static List<ScheduledMessage> list(ScheduledMessage... m) {
        return new ArrayList<>(Arrays.asList(m));
    }

    // ------------------------------------------------------------------ recorder

    @Test public void numberOnlyRecord_contactNameTitle_storedAndSinkFired() {
        ScheduledMessage live = numberOnly(1L, E164);
        NotingStore store = new NotingStore();
        store.seed(live);
        FakeNames names = new FakeNames().put(E164, NAME);
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, null, names);

        assertTrue(recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
        assertEquals(1, store.recorded.size());
        assertEquals(ConversationRef.of(live).key(), store.recorded.get(0).ref.key());
        assertEquals(Arrays.asList(ConversationRef.of(live).key()), sink.keys);
        assertEquals(ThreadEvent.Direction.IN, sink.events.get(0).getDirection());
    }

    @Test public void contactNameMatch_ignoresCaseAndSpacing() {
        ScheduledMessage live = numberOnly(1L, E164);
        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore().seed(live);
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, null,
                new FakeNames().put(E164, "  " + NAME.toUpperCase() + " "));
        assertTrue(recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
    }

    @Test public void gateBuiltThroughResolver_mayMatchAndRecords() {
        ScheduledMessage live = numberOnly(1L, E164);
        FakeNames names = new FakeNames().put(E164, NAME);
        LiveConversationKeys gate = LiveConversationKeys.of(list(live), names);
        ParsedEvent p = NotificationParser.parse(gvReply(T0 + 10 * MIN));
        assertNotNull(p);
        assertTrue(gate.mayMatch(p));

        ListenerKeyGateTest.CountingStore store = new ListenerKeyGateTest.CountingStore().seed(live);
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, gate, names);
        assertTrue(recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
        assertEquals(1, store.inner.recorded.size());
        assertEquals(1, sink.keys.size());
    }

    @Test public void rebuildThroughResolver_addsContactNameKeys() {
        ScheduledMessage live = numberOnly(1L, E164);
        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore().seed(live);
        LiveConversationKeys gate = LiveConversationKeys.rebuild(store, new FakeNames().put(E164, NAME));
        assertNotNull(gate);
        assertTrue(gate.mayMatch(NotificationParser.parse(gvReply(T0))));
        assertTrue(LiveConversationKeys.current() == gate);
        // Without the resolver the number-only record has no name key (the LIVE-F12 failure).
        assertFalse(LiveConversationKeys.rebuild(store).mayMatch(NotificationParser.parse(gvReply(T0))));
    }

    @Test public void gateResolvesEachDistinctNumberOnce() {
        FakeNames names = new FakeNames().put(E164, NAME);
        LiveConversationKeys.of(list(numberOnly(1L, E164), numberOnly(2L, E164),
                numberOnly(3L, OTHER_E164)), names);
        assertEquals(2, names.calls.size());
        assertEquals(new HashSet<>(Arrays.asList(E164, OTHER_E164)), new HashSet<>(names.calls));
    }

    @Test public void recorderResolvesEachDistinctNumberOncePerEvent() {
        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore().seed(
                numberOnly(1L, E164), numberOnly(2L, E164));
        FakeNames names = new FakeNames().put(E164, OTHER_NAME);
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, null, names);
        assertFalse(recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
        assertEquals(Arrays.asList(E164), names.calls);
    }

    @Test public void unrelatedName_noStoreCall() {
        ScheduledMessage live = numberOnly(1L, E164);
        FakeNames names = new FakeNames().put(E164, OTHER_NAME);
        LiveConversationKeys gate = LiveConversationKeys.of(list(live), names);
        ListenerKeyGateTest.CountingStore store = new ListenerKeyGateTest.CountingStore().seed(live);
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, gate, names);

        assertFalse(recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
        assertEquals("R40: a miss must not touch the store", 0, store.calls);
        assertTrue(sink.keys.isEmpty());
    }

    @Test public void unrelatedName_ungated_storesNothing() {
        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore().seed(numberOnly(1L, E164));
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, null,
                new FakeNames().put(E164, OTHER_NAME));
        assertFalse(recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
        assertTrue(store.recorded.isEmpty());
        assertTrue(sink.keys.isEmpty());
    }

    @Test public void resolverEmpty_previousBehaviour() {
        ScheduledMessage live = numberOnly(1L, E164);
        ContactNames empty = ContactNames.NONE;
        assertFalse(LiveConversationKeys.of(list(live), empty).mayMatch(NotificationParser.parse(gvReply(T0))));
        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore().seed(live);
        assertFalse(new ListenerEventRecorder(store, sink, null, empty)
                .onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
        assertTrue(store.recorded.isEmpty());

        // A named record still matches by its display name, as before.
        ListenerFixtures.FakeStore named = new ListenerFixtures.FakeStore().seed(message(2L, OTHER_E164, NAME).build());
        assertTrue(new ListenerEventRecorder(named, sink, null, empty)
                .onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
    }

    @Test public void resolverThrowing_previousBehaviour() {
        ContactNames broken = e164 -> { throw new IllegalStateException("provider gone"); };
        ScheduledMessage nameless = numberOnly(1L, E164);
        ScheduledMessage named = message(2L, OTHER_E164, NAME).build();

        LiveConversationKeys gate = LiveConversationKeys.rebuild(
                new ListenerFixtures.FakeStore().seed(nameless, named), broken);
        assertNotNull("a failing resolver must not make the gate unknown", gate);
        assertTrue(gate.mayMatch(NotificationParser.parse(gvReply(T0))));

        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore().seed(nameless, named);
        assertTrue(new ListenerEventRecorder(store, sink, gate, broken)
                .onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
        assertEquals(1, store.recorded.size());
        assertEquals(ConversationRef.of(named).key(), store.recorded.get(0).ref.key());
    }

    @Test public void resolverReturningNull_treatedAsEmpty() {
        ContactNames nulls = e164 -> null;
        ScheduledMessage live = numberOnly(1L, E164);
        assertFalse(LiveConversationKeys.of(list(live), nulls).mayMatch(NotificationParser.parse(gvReply(T0))));
        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore().seed(live);
        assertFalse(new ListenerEventRecorder(store, sink, null, nulls)
                .onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
    }

    @Test public void contactNameMatch_doesNotNoteNumberMatch() {
        NotingStore store = new NotingStore();
        store.seed(numberOnly(1L, E164));
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, null,
                new FakeNames().put(E164, NAME));
        assertTrue(recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
        assertTrue("OD22(5) is about number evidence only", store.numberMatches.isEmpty());
    }

    @Test public void numberNotification_notResolvedAndUnchanged() {
        // A notification that carries a number is decided by the number; the resolver is not asked.
        NotingStore store = new NotingStore();
        ScheduledMessage live = numberOnly(1L, E164);
        store.seed(live);
        FakeNames names = new FakeNames().put(OTHER_E164, NAME);
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, null, names);
        assertTrue(recorder.onNotification(
                ListenerFixtures.v2sSms(E164, REPLY_TEXT, T0 + 10 * MIN), T0 + 10 * MIN));
        assertTrue(names.calls.isEmpty());
        assertEquals(Arrays.asList(ConversationRef.of(live).key()), store.numberMatches);
    }

    @Test public void sharedContactName_holdsEveryMatchingRecord() {
        // Two contacts saved under the same name: both records match (holds only, the safe direction).
        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore().seed(
                numberOnly(1L, E164), numberOnly(2L, OTHER_E164));
        FakeNames names = new FakeNames().put(E164, NAME).put(OTHER_E164, NAME);
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, null, names);
        assertTrue(recorder.onNotification(
                gvText(NAME, T0 + 10 * MIN, REPLY_TEXT, msg(REPLY_TEXT, T0 + 10 * MIN, NAME)), T0 + 10 * MIN));
        assertEquals(2, sink.keys.size());
    }
}
