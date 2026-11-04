package com.voice2sms.sendlater.listener;

import static com.voice2sms.sendlater.listener.ListenerFixtures.CATEGORY_MESSAGE;
import static com.voice2sms.sendlater.listener.ListenerFixtures.CATEGORY_MISSED_CALL;
import static com.voice2sms.sendlater.listener.ListenerFixtures.E164;
import static com.voice2sms.sendlater.listener.ListenerFixtures.GV_PKG;
import static com.voice2sms.sendlater.listener.ListenerFixtures.GV_TEXT_CHANNEL;
import static com.voice2sms.sendlater.listener.ListenerFixtures.GV_TEXT_TAG;
import static com.voice2sms.sendlater.listener.ListenerFixtures.MIN;
import static com.voice2sms.sendlater.listener.ListenerFixtures.NAME;
import static com.voice2sms.sendlater.listener.ListenerFixtures.OLDER_REPLY_TEXT;
import static com.voice2sms.sendlater.listener.ListenerFixtures.REPLY_TEXT;
import static com.voice2sms.sendlater.listener.ListenerFixtures.T0;
import static com.voice2sms.sendlater.listener.ListenerFixtures.gvText;
import static com.voice2sms.sendlater.listener.ListenerFixtures.msg;
import static com.voice2sms.sendlater.listener.ListenerFixtures.v2sSms;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * NotificationParser (R32, T4, I7): a pure-JVM mapping from a posted notification's fields to a
 * ParsedEvent. Kinds and directions are compared by name so the tests do not depend on which enum
 * type ParsedEvent uses (it needs UNKNOWN, which ThreadEvent.Kind does not have).
 */
public class NotificationParserTest {

    private static final NotificationFields.Message[] NO_MESSAGES = new NotificationFields.Message[0];

    private static String kind(ParsedEvent e) { return String.valueOf(e.getKind()); }

    private static String dir(ParsedEvent e) { return String.valueOf(e.getDirection()); }

    // ------------------------------------------------------------------ Google Voice text (primary)

    @Test public void voiceMessagingStyleText_isInboundSmsWithConversationTitle() {
        long t = T0 + 5 * MIN;
        ParsedEvent e = NotificationParser.parse(gvText(NAME, t, REPLY_TEXT, msg(REPLY_TEXT, t, NAME)));
        assertNotNull("a Voice MessagingStyle text notification must parse", e);
        assertEquals("SMS", kind(e));
        assertEquals("IN", dir(e));
        assertEquals(NAME, e.getConversationTitle());
        assertEquals(REPLY_TEXT, e.getText());
        assertEquals(t, e.getTimeMs());
        assertNull("a contact-name title carries no number", e.getNumber());
    }

    @Test public void voiceMessagingStyleText_usesTheNewestMessage() {
        long older = T0 + 2 * MIN, newer = T0 + 4 * MIN;
        ParsedEvent e = NotificationParser.parse(gvText(NAME, newer, REPLY_TEXT,
                msg(OLDER_REPLY_TEXT, older, NAME), msg(REPLY_TEXT, newer, NAME)));
        assertNotNull(e);
        assertEquals("SMS", kind(e));
        assertEquals(REPLY_TEXT, e.getText());
        assertEquals(newer, e.getTimeMs());
    }

    @Test public void voiceTextFromUnsavedNumber_carriesTheE164() {
        long t = T0 + MIN;
        String formatted = "(555) 555-0100";
        ParsedEvent e = NotificationParser.parse(gvText(formatted, t, REPLY_TEXT, msg(REPLY_TEXT, t, formatted)));
        assertNotNull(e);
        assertEquals("SMS", kind(e));
        assertEquals(E164, e.getNumber());
    }

    // ------------------------------------------------------------------ Google Voice call / voicemail (T4)

    @Test public void voiceMissedCallCategory_isCall() {
        ParsedEvent e = NotificationParser.parse(new NotificationFields(GV_PKG, null, "unknown-channel",
                CATEGORY_MISSED_CALL, T0, null, NAME, null, NO_MESSAGES));
        assertNotNull(e);
        assertEquals("CALL", kind(e));
        assertEquals("IN", dir(e));
        assertEquals(NAME, e.getConversationTitle());
        assertEquals(T0, e.getTimeMs());
    }

    @Test public void voiceMissedCallTitle_isCall() {
        ParsedEvent e = NotificationParser.parse(new NotificationFields(GV_PKG, null, "unknown-channel",
                null, T0, null, "Missed call from " + NAME, null, NO_MESSAGES));
        assertNotNull(e);
        assertEquals("CALL", kind(e));
        assertEquals("IN", dir(e));
    }

    @Test public void voiceVoicemailTitle_isVoicemail() {
        ParsedEvent e = NotificationParser.parse(new NotificationFields(GV_PKG, null, "unknown-channel",
                null, T0, null, "New voicemail from " + NAME, null, NO_MESSAGES));
        assertNotNull(e);
        assertEquals("VOICEMAIL", kind(e));
        assertEquals("IN", dir(e));
    }

    @Test public void voiceNotificationWithoutTextOrCallEvidence_isUnknown() {
        ParsedEvent e = NotificationParser.parse(new NotificationFields(GV_PKG, null, "3OTHER",
                "status", T0, null, NAME, "Ongoing", NO_MESSAGES));
        assertNotNull("Voice notifications that are not distinguishable parse as UNKNOWN, not null", e);
        assertEquals("UNKNOWN", kind(e));
    }

    // ------------------------------------------------------------------ voice2sms incoming SMS (secondary)

    @Test public void voice2smsIncomingSms_isSmsWithTheNumber() {
        long t = T0 + 3 * MIN;
        ParsedEvent e = NotificationParser.parse(v2sSms(E164, REPLY_TEXT, t));
        assertNotNull("SmsReceiver's incoming-SMS notification must parse", e);
        assertEquals("SMS", kind(e));
        assertEquals("IN", dir(e));
        assertEquals(E164, e.getNumber());
        assertEquals(REPLY_TEXT, e.getText());
        assertEquals(t, e.getTimeMs());
    }

    // ------------------------------------------------------------------ everything else

    @Test public void unrelatedPackage_isNull() {
        NotificationFields.Message[] m = {msg(REPLY_TEXT, T0, NAME)};
        assertNull(NotificationParser.parse(new NotificationFields("com.example.chat", GV_TEXT_TAG,
                GV_TEXT_CHANNEL, CATEGORY_MESSAGE, T0, NAME, NAME, REPLY_TEXT, m)));
        assertNull(NotificationParser.parse(new NotificationFields("com.google.android.apps.messaging", null,
                "default", CATEGORY_MESSAGE, T0, NAME, NAME, REPLY_TEXT, m)));
        assertNull(NotificationParser.parse(new NotificationFields("com.google.android.dialer", null,
                "missed_calls", CATEGORY_MISSED_CALL, T0, null, NAME, null, NO_MESSAGES)));
    }

    @Test public void nullPackage_isNull() {
        assertNull(NotificationParser.parse(new NotificationFields(null, null, null, null, T0,
                null, NAME, REPLY_TEXT, NO_MESSAGES)));
    }
}
