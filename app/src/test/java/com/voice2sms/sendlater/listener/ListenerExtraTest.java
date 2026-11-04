package com.voice2sms.sendlater.listener;

import static com.voice2sms.sendlater.listener.ListenerFixtures.E164;
import static com.voice2sms.sendlater.listener.ListenerFixtures.GV_PKG;
import static com.voice2sms.sendlater.listener.ListenerFixtures.MIN;
import static com.voice2sms.sendlater.listener.ListenerFixtures.NAME;
import static com.voice2sms.sendlater.listener.ListenerFixtures.REPLY_TEXT;
import static com.voice2sms.sendlater.listener.ListenerFixtures.T0;
import static com.voice2sms.sendlater.listener.ListenerFixtures.gvReply;
import static com.voice2sms.sendlater.listener.ListenerFixtures.gvText;
import static com.voice2sms.sendlater.listener.ListenerFixtures.message;
import static com.voice2sms.sendlater.listener.ListenerFixtures.msg;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;

import org.junit.Test;

/** Implementer's extra cases for the reply listener (beyond the acceptance suite). */
public class ListenerExtraTest {

    private static final NotificationFields.Message[] NONE = new NotificationFields.Message[0];

    @Test public void missedCallTitle_stripsThePrefixForTheConversationTitle() {
        ParsedEvent e = NotificationParser.parse(new NotificationFields(GV_PKG, null, "x", null, T0,
                null, "Missed call from " + NAME, null, NONE));
        assertEquals(NAME, e.getConversationTitle());
    }

    @Test public void voicemailFromNumber_carriesTheE164() {
        ParsedEvent e = NotificationParser.parse(new NotificationFields(GV_PKG, null, "x", null, T0,
                null, "New voicemail from (555) 555-0100", null, NONE));
        assertEquals(ParsedEvent.Kind.VOICEMAIL, e.getKind());
        assertEquals(E164, e.getNumber());
    }

    @Test public void onlyOwnMessages_isNull() {
        assertNull(NotificationParser.parse(gvText(NAME, T0, REPLY_TEXT, msg(REPLY_TEXT, T0, null))));
    }

    @Test public void ownMessageNewest_usesTheNewestInboundMessage() {
        ParsedEvent e = NotificationParser.parse(gvText(NAME, T0 + 2 * MIN, "mine",
                msg(REPLY_TEXT, T0 + MIN, NAME), msg("mine", T0 + 2 * MIN, null)));
        assertNotNull(e);
        assertEquals(REPLY_TEXT, e.getText());
        assertEquals(T0 + MIN, e.getTimeMs());
    }

    @Test public void sendLatersOwnNotifications_areNull() {
        assertNull(NotificationParser.parse(new NotificationFields("com.voice2sms", "sendlater",
                "sendlater_hold", "msg", T0, null, NAME, REPLY_TEXT, NONE)));
    }

    @Test public void unknownKind_isNotStored() {
        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore();
        ListenerFixtures.FakeSink sink = new ListenerFixtures.FakeSink();
        store.seed(message(1L, E164, NAME).build());
        NotificationFields f = new NotificationFields(GV_PKG, null, "3OTHER", "status", T0, null, NAME,
                "Ongoing", NONE);
        assertFalse(new ListenerEventRecorder(store, sink).onNotification(f, T0));
        assertTrue(store.recorded.isEmpty());
        assertTrue(sink.keys.isEmpty());
    }

    @Test public void repostedNotification_isStoredOnce() {
        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore();
        ListenerFixtures.FakeSink sink = new ListenerFixtures.FakeSink();
        store.seed(message(1L, E164, NAME).build());
        ListenerEventRecorder r = new ListenerEventRecorder(store, sink);
        assertTrue(r.onNotification(gvReply(T0 + MIN), T0 + MIN));
        assertFalse(r.onNotification(gvReply(T0 + MIN), T0 + 2 * MIN));
        assertEquals(1, store.recorded.size());
        assertEquals(1, sink.keys.size());
    }

    @Test public void heldMessage_countsAsLive() {
        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore();
        store.seed(message(1L, E164, NAME).status(ScheduledMessage.Status.HELD).build());
        assertTrue(new ListenerEventRecorder(store, null).onNotification(gvReply(T0 + MIN), T0 + MIN));
    }

    @Test public void storedEvent_keepsNoText() {
        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore();
        store.seed(message(1L, E164, NAME).build());
        new ListenerEventRecorder(store, null).onNotification(gvReply(T0 + MIN), T0 + MIN);
        ThreadEvent ev = store.recorded.get(0).event;
        assertNull("R34: only what R32 needs", ev.getText());
        assertEquals(ThreadEvent.hashOf(REPLY_TEXT), ev.getTextHash());
    }

    @Test public void caseAndSpacingOfTheName_doNotMatter() {
        ListenerFixtures.FakeStore store = new ListenerFixtures.FakeStore();
        store.seed(message(1L, E164, NAME).build());
        assertTrue(new ListenerEventRecorder(store, null).onNotification(
                gvText("  test   CONTACT ", T0, REPLY_TEXT, msg(REPLY_TEXT, T0, NAME)), T0));
    }

    @Test public void boundRecently_rule() {
        assertFalse(SendLaterListenerStatus.boundRecently(0, 0, T0));
        assertTrue(SendLaterListenerStatus.boundRecently(T0 - 1000, 0, T0));
        assertTrue(SendLaterListenerStatus.boundRecently(T0 - 2 * MIN, T0 - MIN, T0));
        assertFalse(SendLaterListenerStatus.boundRecently(T0 - 60 * MIN, T0 - 30 * MIN, T0));
    }
}
