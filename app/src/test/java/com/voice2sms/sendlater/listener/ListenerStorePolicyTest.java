package com.voice2sms.sendlater.listener;

import static com.voice2sms.sendlater.listener.ListenerFixtures.DAY;
import static com.voice2sms.sendlater.listener.ListenerFixtures.E164;
import static com.voice2sms.sendlater.listener.ListenerFixtures.MIN;
import static com.voice2sms.sendlater.listener.ListenerFixtures.NAME;
import static com.voice2sms.sendlater.listener.ListenerFixtures.OTHER_E164;
import static com.voice2sms.sendlater.listener.ListenerFixtures.OTHER_NAME;
import static com.voice2sms.sendlater.listener.ListenerFixtures.REPLY_TEXT;
import static com.voice2sms.sendlater.listener.ListenerFixtures.T0;
import static com.voice2sms.sendlater.listener.ListenerFixtures.gvReply;
import static com.voice2sms.sendlater.listener.ListenerFixtures.message;
import static com.voice2sms.sendlater.listener.ListenerFixtures.v2sSms;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.store.SendLaterRepository;
import com.voice2sms.sendlater.store.ThreadEventEntity;

import org.junit.Test;

import java.util.List;

/**
 * Store policy of the notification listener (R33, R34): an event is stored only for a conversation
 * with a pending or in-flight message, matched by E.164 when the notification carries a number and
 * by display name otherwise; a stored event triggers the early-hold hook; purge drops events older
 * than 7 days and orphans.
 */
public class ListenerStorePolicyTest {

    private final ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore();
    private final ListenerFixtures.FakeSink sink = new ListenerFixtures.FakeSink();
    private final ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink);

    // ------------------------------------------------------------------ R34: nothing pending -> nothing stored

    @Test public void noPendingMessage_eventNotStored_noEarlyHold() {
        boolean stored = recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN);
        assertFalse(stored);
        assertTrue("R34: no pending message, nothing stored", store.recorded.isEmpty());
        assertTrue("no early hold without a stored event", sink.keys.isEmpty());
    }

    @Test public void pendingMessageForAnotherConversation_eventNotStored() {
        store.seed(message(1L, OTHER_E164, OTHER_NAME).build());
        boolean stored = recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN);
        assertFalse(stored);
        assertTrue(store.recorded.isEmpty());
        assertTrue(sink.keys.isEmpty());
    }

    @Test public void numberCarryingNotification_matchesByE164NotByName() {
        // The pending message's name equals the notification's title text only by coincidence of a
        // different conversation: the number decides.
        store.seed(message(1L, OTHER_E164, E164).build());
        boolean stored = recorder.onNotification(v2sSms(E164, REPLY_TEXT, T0 + 10 * MIN), T0 + 10 * MIN);
        assertFalse("a notification with a number matches by E.164 only", stored);
        assertTrue(store.recorded.isEmpty());
        assertTrue(sink.keys.isEmpty());
    }

    @Test public void unrelatedPackage_neverStored() {
        store.seed(message(1L, E164, NAME).build());
        NotificationFields other = new NotificationFields("com.example.chat", null, "default", "msg",
                T0 + 10 * MIN, NAME, NAME, REPLY_TEXT,
                new NotificationFields.Message[] {ListenerFixtures.msg(REPLY_TEXT, T0 + 10 * MIN, NAME)});
        assertFalse(recorder.onNotification(other, T0 + 10 * MIN));
        assertTrue(store.recorded.isEmpty());
        assertTrue(sink.keys.isEmpty());
    }

    // ------------------------------------------------------------------ pending conversation -> stored + early hold

    @Test public void voiceTextForPendingConversation_matchedByName_isStoredAndTriggersEarlyHold() {
        ScheduledMessage m = message(1L, E164, NAME).build();
        store.seed(m);
        long t = T0 + 10 * MIN;

        assertTrue(recorder.onNotification(gvReply(t), t + 1000));

        assertEquals(1, store.recorded.size());
        ListenerFixtures.FakeStore.Recorded r = store.recorded.get(0);
        assertEquals(ThreadEvent.Direction.IN, r.event.getDirection());
        assertEquals(ThreadEvent.Kind.SMS, r.event.getKind());
        assertEquals(t, r.event.getTimeMs());
        assertEquals("every ThreadEvent producer sets textHash with hashOf",
                ThreadEvent.hashOf(REPLY_TEXT), r.event.getTextHash());
        assertEquals(ThreadEventEntity.SOURCE_LISTENER, r.source);

        assertEquals("R33: onInboundEvent is invoked once", 1, sink.keys.size());
        assertEquals("the engine looks messages up by ConversationRef.of(message).key()",
                ConversationRef.of(m).key(), sink.keys.get(0));
        assertEquals(r.event, sink.events.get(0));
    }

    @Test public void storedNameMatchedEvent_isReadableUnderTheMessagesConversationKey() {
        ScheduledMessage m = message(1L, E164, NAME).build();
        store.seed(m);
        long t = T0 + 10 * MIN;
        assertTrue(recorder.onNotification(gvReply(t), t));

        NotificationThreadEventSource src = new NotificationThreadEventSource(store, () -> true);
        List<ThreadEvent> events = src.readSince(ConversationRef.of(m), m.getWatermark());
        assertEquals("R32: the thread check must find the listener's event for this message", 1, events.size());
        assertEquals(ThreadEvent.hashOf(REPLY_TEXT), events.get(0).getTextHash());
    }

    @Test public void voice2smsSmsForPendingConversation_matchedByE164_isStoredAndTriggersEarlyHold() {
        ScheduledMessage m = message(1L, E164, OTHER_NAME).build();
        store.seed(m);
        long t = T0 + 20 * MIN;

        assertTrue(recorder.onNotification(v2sSms(E164, REPLY_TEXT, t), t));

        assertEquals(1, store.recorded.size());
        assertEquals(E164, store.recorded.get(0).ref.key());
        assertEquals(ThreadEvent.Direction.IN, store.recorded.get(0).event.getDirection());
        assertEquals(1, sink.keys.size());
        assertEquals(E164, sink.keys.get(0));
    }

    @Test public void inFlightMessage_countsAsPending() {
        ScheduledMessage m = message(1L, E164, NAME).status(ScheduledMessage.Status.IN_FLIGHT).build();
        store.seed(m);
        long t = T0 + 10 * MIN;
        assertTrue("R34: in-flight messages keep their conversation's events", recorder.onNotification(gvReply(t), t));
        assertEquals(1, store.recorded.size());
        assertEquals(1, sink.keys.size());
    }

    // ------------------------------------------------------------------ purge (R34, A2)

    @Test public void purge_removesEventsOlderThanSevenDaysAndOrphans() {
        long now = T0 + 30 * DAY;
        recorder.purge(now);
        assertEquals(1, store.purgeCalls.size());
        assertEquals("A2: listener event retention is 7 days", 7 * DAY, SendLaterRepository.EVENT_RETENTION_MS);
        assertEquals(now - 7 * DAY, store.purgeCalls.get(0)[0]);
        assertTrue("orphans (no pending message) are purged too", store.purgeOrphans.get(0));
    }
}
