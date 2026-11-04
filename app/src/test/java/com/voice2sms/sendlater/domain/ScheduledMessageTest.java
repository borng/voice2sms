package com.voice2sms.sendlater.domain;

import static com.voice2sms.sendlater.domain.Fixtures.E164;
import static com.voice2sms.sendlater.domain.Fixtures.HOUR;
import static com.voice2sms.sendlater.domain.Fixtures.NAME;
import static com.voice2sms.sendlater.domain.Fixtures.T0;
import static com.voice2sms.sendlater.domain.Fixtures.TEXT;
import static com.voice2sms.sendlater.domain.Fixtures.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;

import org.junit.Test;

/** R20: the immutable domain record, its enums, the watermark, and ConversationRef. */
public class ScheduledMessageTest {

    @Test
    public void builder_carriesEveryR20Field() {
        Watermark wm = new Watermark(Watermark.Quality.EXACT, "srv-1", T0 - 1000L, null, T0, 250L);
        ScheduledMessage m = message(3 * HOUR)
                .id(42L)
                .threadItemId("t.+15555550100")
                .source(ScheduledMessage.Source.WEBVIEW)
                .latenessPolicy(LatenessPolicy.WITHIN_30_MIN)
                .holdIfReplied(false)
                .status(ScheduledMessage.Status.HELD)
                .lastError("e")
                .attemptCount(2)
                .watermark(wm)
                .build();
        assertEquals(42L, m.getId());
        assertEquals(T0, m.getCreatedAt());
        assertEquals(T0 + 3 * HOUR, m.getScheduledFor());
        assertEquals(TEXT, m.getText());
        assertEquals(E164, m.getRecipientE164());
        assertEquals(NAME, m.getRecipientDisplayName());
        assertEquals("t.+15555550100", m.getThreadItemId());
        assertEquals(ScheduledMessage.Source.WEBVIEW, m.getSource());
        assertEquals(Rung.R1, m.getRung());
        assertFalse(m.isHoldIfReplied());
        assertEquals(LatenessPolicy.WITHIN_30_MIN, m.getLatenessPolicy());
        assertEquals(ScheduledMessage.Status.HELD, m.getStatus());
        assertNull(m.getHoldReason());
        assertEquals("e", m.getLastError());
        assertEquals(2, m.getAttemptCount());
        assertEquals(wm, m.getWatermark());

        assertEquals(Watermark.Quality.EXACT, wm.getQuality());
        assertEquals("srv-1", wm.getNewestServerId());
        assertEquals(Long.valueOf(T0 - 1000L), wm.getNewestTs());
        assertNull(wm.getBubbleHash());
        assertEquals(T0, wm.getCapturedAtDevice());
        assertEquals(Long.valueOf(250L), wm.getSkewEstimateMs());
    }

    @Test
    public void builtInstanceIsUnaffectedByLaterBuilderChanges() {
        ScheduledMessage.Builder b = message(3 * HOUR);
        ScheduledMessage first = b.build();
        b.text("changed").scheduledFor(T0 + 9 * HOUR).status(ScheduledMessage.Status.CANCELED);
        assertEquals(TEXT, first.getText());
        assertEquals(T0 + 3 * HOUR, first.getScheduledFor());
        assertEquals(ScheduledMessage.Status.SCHEDULED, first.getStatus());
    }

    @Test
    public void valueTypesAreImmutable() {
        for (Class<?> c : new Class<?>[] {ScheduledMessage.class, Watermark.class, ThreadEvent.class, ConversationRef.class}) {
            assertTrue(c.getSimpleName() + " should be final", Modifier.isFinal(c.getModifiers()));
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                assertTrue(c.getSimpleName() + "." + f.getName() + " should be final", Modifier.isFinal(f.getModifiers()));
            }
        }
    }

    @Test
    public void enumsHaveTheSpecValues() {
        assertEquals(new HashSet<>(Arrays.asList("SCHEDULED", "HELD", "IN_FLIGHT", "SENT_VERIFIED",
                        "CANCELED", "SELF_PREEMPTED", "EXPIRED", "FAILED")),
                names(ScheduledMessage.Status.values()));
        assertEquals(new HashSet<>(Arrays.asList("NATIVE_GV", "WEBVIEW")), names(ScheduledMessage.Source.values()));
        assertEquals(new HashSet<>(Arrays.asList("EXACT", "TIMESTAMP", "DEVICE_ONLY")), names(Watermark.Quality.values()));
        assertEquals(new HashSet<>(Arrays.asList("IN", "OUT")), names(ThreadEvent.Direction.values()));
        assertEquals(new HashSet<>(Arrays.asList("SMS", "MMS", "CALL", "VOICEMAIL")), names(ThreadEvent.Kind.values()));
        assertEquals(new HashSet<>(Arrays.asList("R0", "R1", "R2", "R3")), names(Rung.values()));
        assertEquals(new HashSet<>(Arrays.asList("REPLIED", "REACHED_OUT", "SELF_PREEMPTED", "OUTBOUND_ONLY",
                "UNCHANGED", "UNVERIFIED")), names(Classification.values()));
        assertTrue(names(Decision.Kind.values()).containsAll(
                Arrays.asList("SEND", "HOLD", "REMIND", "CANCEL", "WAIT", "EXPIRED")));
        assertTrue(HoldReason.values().length > 0);
        assertTrue(RemindReason.values().length > 0);
        assertTrue(SendOutcome.values().length > 0);
    }

    @Test
    public void threadEvent_exposesFields() {
        ThreadEvent e = new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.CALL, T0, "h", null);
        assertEquals(ThreadEvent.Direction.IN, e.getDirection());
        assertEquals(ThreadEvent.Kind.CALL, e.getKind());
        assertEquals(T0, e.getTimeMs());
        assertEquals("h", e.getTextHash());
        assertNull(e.getText());
    }

    @Test
    public void conversationRef_keyFollowsTheNumber() {
        ConversationRef a = new ConversationRef("+15555550100", "Test Contact");
        ConversationRef b = new ConversationRef("+15555550100", "Other Label");
        ConversationRef c = new ConversationRef("+15555550101", "Test Contact");
        assertEquals(a.key(), b.key());
        assertNotEquals(a.key(), c.key());
        assertEquals("+15555550100", a.getE164());
        assertEquals("Test Contact", a.getDisplayName());
    }

    @Test
    public void conversationRef_withoutNumber_stillHasAKey() {
        ConversationRef n = new ConversationRef(null, "Test Contact");
        assertTrue(n.key() != null && !n.key().isEmpty());
        assertNotEquals(n.key(), new ConversationRef("+15555550100", null).key());
    }

    private static HashSet<String> names(Enum<?>[] values) {
        HashSet<String> s = new HashSet<>();
        for (Enum<?> v : values) s.add(v.name());
        return s;
    }
}
