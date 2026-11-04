package com.voice2sms.sendlater.store;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

/**
 * Pure entity &lt;-&gt; domain mapping (no Room runtime, no android.*), tested on the JVM. Enums are
 * persisted by {@code name()}.
 */
public final class StoreMapping {
    private StoreMapping() {}

    /** Entity for {@code m}; {@code updatedAt} is the persist time. The watermark is mapped apart. */
    public static ScheduledMessageEntity toEntity(ScheduledMessage m, long updatedAt) {
        ScheduledMessageEntity e = new ScheduledMessageEntity();
        e.id = m.getId();
        e.createdAt = m.getCreatedAt();
        e.scheduledFor = m.getScheduledFor();
        e.text = m.getText();
        e.recipientE164 = m.getRecipientE164();
        e.recipientDisplayName = m.getRecipientDisplayName();
        e.threadItemId = m.getThreadItemId();
        e.source = m.getSource().name();
        e.rung = m.getRung().name();
        e.holdIfReplied = m.isHoldIfReplied();
        e.latenessPolicy = m.getLatenessPolicy().name();
        e.status = m.getStatus().name();
        e.holdReason = m.getHoldReason() == null ? null : m.getHoldReason().name();
        e.lastError = m.getLastError();
        e.attemptCount = m.getAttemptCount();
        e.conversationKey = ConversationRef.of(m).key();
        e.updatedAt = updatedAt;
        return e;
    }

    /** Null in, null out. */
    public static WatermarkEntity toEntity(long messageId, Watermark w) {
        if (w == null) return null;
        WatermarkEntity e = new WatermarkEntity();
        e.messageId = messageId;
        e.quality = w.getQuality().name();
        e.newestServerId = w.getNewestServerId();
        e.newestTs = w.getNewestTs();
        e.bubbleHash = w.getBubbleHash();
        e.capturedAtDevice = w.getCapturedAtDevice();
        e.skewEstimateMs = w.getSkewEstimateMs();
        return e;
    }

    /** Null in, null out. */
    public static Watermark toDomain(WatermarkEntity e) {
        if (e == null) return null;
        return new Watermark(Watermark.Quality.valueOf(e.quality), e.newestServerId, e.newestTs,
                e.bubbleHash, e.capturedAtDevice, e.skewEstimateMs);
    }

    /** {@code wm} may be null (no watermark row). */
    public static ScheduledMessage toDomain(ScheduledMessageEntity e, WatermarkEntity wm) {
        return new ScheduledMessage.Builder()
                .id(e.id)
                .createdAt(e.createdAt)
                .scheduledFor(e.scheduledFor)
                .text(e.text)
                .recipientE164(e.recipientE164)
                .recipientDisplayName(e.recipientDisplayName)
                .threadItemId(e.threadItemId)
                .source(ScheduledMessage.Source.valueOf(e.source))
                .rung(Rung.valueOf(e.rung))
                .holdIfReplied(e.holdIfReplied)
                .latenessPolicy(LatenessPolicy.valueOf(e.latenessPolicy))
                .status(ScheduledMessage.Status.valueOf(e.status))
                .holdReason(e.holdReason == null ? null : HoldReason.valueOf(e.holdReason))
                .lastError(e.lastError)
                .attemptCount(e.attemptCount)
                .watermark(toDomain(wm))
                .build();
    }

    /** {@code source} is {@link ThreadEventEntity#SOURCE_LISTENER} or {@code SOURCE_ON_SCREEN}. */
    public static ThreadEventEntity toEntity(ConversationRef ref, ThreadEvent ev, String source,
                                             long recordedAt) {
        ThreadEventEntity e = new ThreadEventEntity();
        e.conversationKey = ref.key();
        e.recipientE164 = ref.getE164();
        e.direction = ev.getDirection().name();
        e.kind = ev.getKind().name();
        e.timeMs = ev.getTimeMs();
        e.textHash = ev.getTextHash();
        e.text = ev.getText();
        e.source = source == null ? ThreadEventEntity.SOURCE_LISTENER : source;
        e.recordedAt = recordedAt;
        return e;
    }

    public static ThreadEvent toDomain(ThreadEventEntity e) {
        return new ThreadEvent(ThreadEvent.Direction.valueOf(e.direction),
                ThreadEvent.Kind.valueOf(e.kind), e.timeMs, e.textHash, e.text);
    }
}
