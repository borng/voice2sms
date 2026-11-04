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
import static com.voice2sms.sendlater.listener.ListenerFixtures.v2sSms;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R40 / F3 (listener-key-gate): the listener matches a parsed notification against an in-memory
 * set of live conversation keys and calls the store only on a hit. No real numbers or names.
 */
public class ListenerKeyGateTest {

    /** Counts every store call, whatever the method. */
    static class CountingStore implements ListenerEventStore {
        final ListenerFixtures.FakeStore inner = new ListenerFixtures.FakeStore();
        int calls;

        CountingStore seed(ScheduledMessage... m) {
            inner.seed(m);
            return this;
        }

        @Override public List<ScheduledMessage> liveMessages() { calls++; return inner.liveMessages(); }

        @Override public long recordEvent(ConversationRef ref, ThreadEvent event, String source, long at) {
            calls++;
            return inner.recordEvent(ref, event, source, at);
        }

        @Override public List<ThreadEvent> eventsSince(String key, long sinceMs) {
            calls++;
            return inner.eventsSince(key, sinceMs);
        }

        @Override public int purgeEvents(long olderThanMs, boolean orphans) { calls++; return 0; }

        @Override public void noteNumberMatch(String key) { calls++; }

        @Override public boolean hasNumberMatch(String key) { calls++; return false; }
    }

    private final ListenerFixtures.FakeSink sink = new ListenerFixtures.FakeSink();

    @After public void resetProcessGate() {
        LiveConversationKeys.invalidate();
    }

    private static LiveConversationKeys gateOf(ScheduledMessage... live) {
        return LiveConversationKeys.of(new ArrayList<>(Arrays.asList(live)));
    }

    // ------------------------------------------------------------------ the acceptance test

    @Test public void nonMatchingVoiceNotification_makesZeroStoreCalls() {
        ScheduledMessage live = message(1L, OTHER_E164, OTHER_NAME).build();
        CountingStore store = new CountingStore().seed(live);
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, gateOf(live));

        // A Voice text from another conversation, by name (no number on the notification) ...
        assertFalse(recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
        // ... and a voice2sms SMS carrying a number nobody is scheduled to.
        assertFalse(recorder.onNotification(v2sSms(E164, REPLY_TEXT, T0 + 11 * MIN), T0 + 11 * MIN));

        assertEquals("R40: a miss must not touch the store", 0, store.calls);
        assertTrue(sink.keys.isEmpty());
    }

    @Test public void emptyGate_voiceNotification_makesZeroStoreCalls() {
        CountingStore store = new CountingStore();
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, gateOf());
        assertFalse(recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
        assertEquals(0, store.calls);
    }

    // ------------------------------------------------------------------ hits still record

    @Test public void nameHit_recordsAndFiresEarlyHold() {
        ScheduledMessage live = message(1L, E164, NAME).build();
        CountingStore store = new CountingStore().seed(live);
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, gateOf(live));
        assertTrue(recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
        assertEquals(1, store.inner.recorded.size());
        assertEquals(Arrays.asList(E164), sink.keys);
    }

    @Test public void numberHit_recordsUnderTheMessagesKey() {
        ScheduledMessage live = message(1L, E164, NAME).build();
        CountingStore store = new CountingStore().seed(live);
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, gateOf(live));
        assertTrue(recorder.onNotification(v2sSms(E164, REPLY_TEXT, T0 + 10 * MIN), T0 + 10 * MIN));
        assertEquals(1, store.inner.recorded.size());
        assertEquals(ConversationRef.of(live).key(), store.inner.recorded.get(0).ref.key());
    }

    @Test public void nameMatchIgnoresCaseAndSpacing_likeTheRecorder() {
        ScheduledMessage live = message(1L, E164, NAME).build();
        LiveConversationKeys gate = gateOf(live);
        ParsedEvent p = NotificationParser.parse(
                gvText("  " + NAME.toUpperCase() + " ", T0, REPLY_TEXT, msg(REPLY_TEXT, T0, NAME)));
        assertNotNull(p);
        assertTrue(gate.mayMatch(p));
    }

    @Test public void numberNotificationDoesNotMatchByName() {
        // The gate mirrors the recorder: a notification with a number is decided by the number.
        ScheduledMessage live = message(1L, OTHER_E164, NAME).build();
        CountingStore store = new CountingStore().seed(live);
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, gateOf(live));
        assertFalse(recorder.onNotification(v2sSms(E164, REPLY_TEXT, T0 + 10 * MIN), T0 + 10 * MIN));
        assertEquals(0, store.calls);
    }

    // ------------------------------------------------------------------ gate contents

    @Test public void gateHoldsOnlyNonTerminalMessages() {
        ScheduledMessage sent = message(1L, E164, NAME).status(ScheduledMessage.Status.SENT_VERIFIED).build();
        ScheduledMessage held = message(2L, OTHER_E164, null).status(ScheduledMessage.Status.HELD).build();
        ScheduledMessage inFlight = message(3L, null, OTHER_NAME).status(ScheduledMessage.Status.IN_FLIGHT).build();
        LiveConversationKeys gate = LiveConversationKeys.of(Arrays.asList(sent, held, inFlight, null));
        assertFalse("terminal row not gated in", gate.mayMatch(new ParsedEvent(null, E164, ParsedEvent.Kind.SMS, T0, null)));
        assertFalse(gate.mayMatch(new ParsedEvent(NAME, null, ParsedEvent.Kind.SMS, T0, null)));
        assertTrue(gate.mayMatch(new ParsedEvent(null, OTHER_E164, ParsedEvent.Kind.SMS, T0, null)));
        assertTrue(gate.mayMatch(new ParsedEvent(OTHER_NAME, null, ParsedEvent.Kind.SMS, T0, null)));
        assertFalse("blank title never matches", gate.mayMatch(new ParsedEvent("  ", null, ParsedEvent.Kind.SMS, T0, null)));
        assertFalse(gate.mayMatch(null));
    }

    @Test public void toStringCarriesNoKeys() {
        String s = gateOf(message(1L, E164, NAME).build()).toString();
        assertFalse(s.contains(E164));
        assertFalse(s.contains(NAME));
    }

    // ------------------------------------------------------------------ process-wide gate

    @Test public void rebuild_installsSnapshot_andFailureLeavesGateUnknown() {
        ScheduledMessage live = message(1L, E164, NAME).build();
        CountingStore store = new CountingStore().seed(live);
        assertNull(LiveConversationKeys.current());
        LiveConversationKeys built = LiveConversationKeys.rebuild(store);
        assertNotNull(built);
        assertTrue(LiveConversationKeys.current() == built);
        assertEquals("one store read per rebuild", 1, store.calls);

        ListenerEventStore broken = new CountingStore() {
            @Override public List<ScheduledMessage> liveMessages() { throw new IllegalStateException("db"); }
        };
        assertNull(LiveConversationKeys.rebuild(broken));
        assertNull("fail toward the store, not toward dropping replies", LiveConversationKeys.current());
    }

    @Test public void nullGate_fallsBackToTheStore() {
        ScheduledMessage live = message(1L, E164, NAME).build();
        CountingStore store = new CountingStore().seed(live);
        ListenerEventRecorder recorder = new ListenerEventRecorder(store, sink, null);
        assertTrue(recorder.onNotification(gvReply(T0 + 10 * MIN), T0 + 10 * MIN));
        assertTrue(store.calls > 0);
    }

    // ------------------------------------------------------------------ service wiring (source)

    private static final Path SERVICE =
            Paths.get("src/main/java/com/voice2sms/sendlater/listener/SendLaterNotificationListener.java");

    private static String body(String src, String method) {
        Matcher m = Pattern.compile("void\\s+" + method + "\\s*\\(\\s*\\)\\s*\\{").matcher(src);
        assertTrue("missing " + method, m.find());
        int depth = 1;
        int i = m.end();
        while (i < src.length() && depth > 0) {
            char c = src.charAt(i++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return src.substring(m.end(), i);
    }

    @Test public void serviceRegistersNotExportedReceiverForStateBroadcasts() throws IOException {
        String src = new String(Files.readAllBytes(SERVICE), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
        assertTrue(src.contains("ContextCompat.RECEIVER_NOT_EXPORTED"));
        assertTrue(src.contains("SendLaterIntents.ACTION_STATE_CHANGED"));
        assertTrue(src.contains("SendLaterIntents.ACTION_SCHEDULED"));
        assertFalse("no exported runtime receiver", src.contains("RECEIVER_EXPORTED)"));
        String connected = body(src, "onListenerConnected");
        assertTrue(connected.contains("registerStateReceiver()"));
        assertTrue("gate built on connect", connected.contains("scheduleRebuild("));
        assertTrue(body(src, "onListenerDisconnected").contains("unregisterStateReceiver()"));
        assertTrue("rebuild runs on the worker", body(src, "registerStateReceiver").contains("RECEIVER_NOT_EXPORTED"));
        assertTrue(src.contains("LiveConversationKeys.current()"));
    }
}
