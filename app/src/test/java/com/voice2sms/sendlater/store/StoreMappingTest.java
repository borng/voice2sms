package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import org.junit.Test;

/** Placeholder data only: 555-01xx fictional range, generic names. */
public class StoreMappingTest {

    private static final long T0 = 1_781_092_800_000L;
    private static final String E164 = "+15555550100";

    private static ScheduledMessage full(Watermark wm) {
        return new ScheduledMessage.Builder()
                .id(42).createdAt(T0).scheduledFor(T0 + 3_600_000L).text("See you at nine")
                .recipientE164(E164).recipientDisplayName("Test Contact").threadItemId("t.+15555550100")
                .source(ScheduledMessage.Source.WEBVIEW).rung(Rung.R2).holdIfReplied(false)
                .latenessPolicy(LatenessPolicy.WITHIN_30_MIN).status(ScheduledMessage.Status.HELD)
                .holdReason(HoldReason.REACHED_OUT).lastError("timeout").attemptCount(3)
                .watermark(wm).build();
    }

    private static ScheduledMessage roundTrip(ScheduledMessage m) {
        ScheduledMessageEntity e = StoreMapping.toEntity(m, T0 + 5);
        WatermarkEntity w = StoreMapping.toEntity(m.getId(), m.getWatermark());
        return StoreMapping.toDomain(e, w);
    }

    private static void assertSameMessage(ScheduledMessage a, ScheduledMessage b) {
        assertEquals(a.getId(), b.getId());
        assertEquals(a.getCreatedAt(), b.getCreatedAt());
        assertEquals(a.getScheduledFor(), b.getScheduledFor());
        assertEquals(a.getText(), b.getText());
        assertEquals(a.getRecipientE164(), b.getRecipientE164());
        assertEquals(a.getRecipientDisplayName(), b.getRecipientDisplayName());
        assertEquals(a.getThreadItemId(), b.getThreadItemId());
        assertEquals(a.getSource(), b.getSource());
        assertEquals(a.getRung(), b.getRung());
        assertEquals(a.isHoldIfReplied(), b.isHoldIfReplied());
        assertEquals(a.getLatenessPolicy(), b.getLatenessPolicy());
        assertEquals(a.getStatus(), b.getStatus());
        assertEquals(a.getHoldReason(), b.getHoldReason());
        assertEquals(a.getLastError(), b.getLastError());
        assertEquals(a.getAttemptCount(), b.getAttemptCount());
        assertEquals(a.getWatermark(), b.getWatermark());
        assertTrue(SendLaterRepository.sameContent(a, b));
    }

    @Test public void everyFieldRoundTripsWithAFullWatermark() {
        Watermark wm = new Watermark(Watermark.Quality.EXACT, "srv-1", T0 - 60_000L, "bh",
                T0 - 1, 1234L);
        assertSameMessage(full(wm), roundTrip(full(wm)));
    }

    @Test public void nullableWatermarkFieldsRoundTripAsNull() {
        Watermark wm = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, T0, null);
        ScheduledMessage back = roundTrip(full(wm));
        assertSameMessage(full(wm), back);
        assertNull(back.getWatermark().getNewestServerId());
        assertNull(back.getWatermark().getNewestTs());
        assertNull(back.getWatermark().getBubbleHash());
        assertNull(back.getWatermark().getSkewEstimateMs());
    }

    @Test public void absentWatermarkAndNullableMessageFieldsRoundTrip() {
        ScheduledMessage m = new ScheduledMessage.Builder().id(7).createdAt(T0).scheduledFor(T0 + 1)
                .recipientE164(E164).build();
        assertNull(StoreMapping.toEntity(7, null));
        ScheduledMessage back = roundTrip(m);
        assertSameMessage(m, back);
        assertNull(back.getText());
        assertNull(back.getRecipientDisplayName());
        assertNull(back.getThreadItemId());
        assertNull(back.getHoldReason());
        assertNull(back.getLastError());
        assertNull(back.getWatermark());
    }

    @Test public void everyEnumValueRoundTrips() {
        for (ScheduledMessage.Status s : ScheduledMessage.Status.values()) {
            assertEquals(s, roundTrip(full(null).toBuilder().status(s).build()).getStatus());
        }
        for (ScheduledMessage.Source s : ScheduledMessage.Source.values()) {
            assertEquals(s, roundTrip(full(null).toBuilder().source(s).build()).getSource());
        }
        for (Rung r : Rung.values()) {
            assertEquals(r, roundTrip(full(null).toBuilder().rung(r).build()).getRung());
        }
        for (LatenessPolicy p : LatenessPolicy.values()) {
            assertEquals(p, roundTrip(full(null).toBuilder().latenessPolicy(p).build()).getLatenessPolicy());
        }
        for (HoldReason h : HoldReason.values()) {
            assertEquals(h, roundTrip(full(null).toBuilder().holdReason(h).build()).getHoldReason());
        }
        for (Watermark.Quality q : Watermark.Quality.values()) {
            Watermark wm = new Watermark(q, null, null, null, T0, null);
            assertEquals(q, StoreMapping.toDomain(StoreMapping.toEntity(1, wm)).getQuality());
        }
    }

    @Test public void entityCarriesConversationKeyAndUpdatedAt() {
        ScheduledMessageEntity e = StoreMapping.toEntity(full(null), 99L);
        assertEquals(new ConversationRef(E164, "Test Contact").key(), e.conversationKey);
        assertEquals(99L, e.updatedAt);
        WatermarkEntity w = StoreMapping.toEntity(42, new Watermark(Watermark.Quality.TIMESTAMP,
                null, T0, null, T0, null));
        assertEquals(42, w.messageId);
    }

    @Test public void sameContentDetectsADifference() {
        ScheduledMessage a = full(null);
        assertFalse(SendLaterRepository.sameContent(a, a.toBuilder().text("other").build()));
        assertFalse(SendLaterRepository.sameContent(a, a.toBuilder().watermark(
                new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, T0, null)).build()));
        assertTrue(SendLaterRepository.sameContent(a, a.toBuilder().id(999).build()));
    }

    @Test public void threadEventRoundTripsWithBothKeys() {
        ConversationRef ref = new ConversationRef(E164, "Test Contact");
        ThreadEvent ev = new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.VOICEMAIL,
                T0, ThreadEvent.hashOf("call me"), "call me");
        ThreadEventEntity e = StoreMapping.toEntity(ref, ev, ThreadEventEntity.SOURCE_ON_SCREEN, T0 + 9);
        assertEquals(ref.key(), e.conversationKey);
        assertEquals(E164, e.recipientE164);
        assertEquals(ThreadEventEntity.SOURCE_ON_SCREEN, e.source);
        assertEquals(T0 + 9, e.recordedAt);
        assertEquals(ev, StoreMapping.toDomain(e));
    }

    @Test public void threadEventWithoutTextOrNumberRoundTrips() {
        ConversationRef ref = new ConversationRef(null, "  Zoë   Example ");
        for (ThreadEvent.Direction d : ThreadEvent.Direction.values()) {
            for (ThreadEvent.Kind k : ThreadEvent.Kind.values()) {
                ThreadEvent ev = new ThreadEvent(d, k, T0, "abc", null);
                ThreadEventEntity e = StoreMapping.toEntity(ref, ev, null, T0);
                assertEquals("name:zoe example", e.conversationKey);
                assertNull(e.recipientE164);
                assertEquals(ThreadEventEntity.SOURCE_LISTENER, e.source);
                assertEquals(ev, StoreMapping.toDomain(e));
            }
        }
    }
}
