package com.voice2sms.sendlater.store;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.RemindReason;
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
        e.rungReason = m.getRungReason() == null ? null : m.getRungReason().name();
        return e;
    }

    /**
     * Entity holding {@code w} and, in the {@code orig*} columns, {@code original} (may be null).
     * Null {@code w} gives null.
     */
    public static WatermarkEntity toEntity(long messageId, Watermark w, Watermark original) {
        WatermarkEntity e = toEntity(messageId, w);
        if (e == null || original == null) return e;
        e.origQuality = original.getQuality().name();
        e.origNewestServerId = original.getNewestServerId();
        e.origNewestTs = original.getNewestTs();
        e.origBubbleHash = original.getBubbleHash();
        e.origCapturedAtDevice = original.getCapturedAtDevice();
        e.origSkewEstimateMs = original.getSkewEstimateMs();
        return e;
    }

    /** The original watermark kept in {@code e}'s {@code orig*} columns, or null. */
    public static Watermark originalOf(WatermarkEntity e) {
        if (e == null || e.origQuality == null) return null;
        return new Watermark(Watermark.Quality.valueOf(e.origQuality), e.origNewestServerId,
                e.origNewestTs, e.origBubbleHash,
                e.origCapturedAtDevice != null ? e.origCapturedAtDevice : 0L, e.origSkewEstimateMs);
    }

    /**
     * OD22(1), pure: the row to write when {@code existing} is replaced by {@code newWm}. The
     * self-preemption baseline is the message's FIRST watermark for its whole life: an existing
     * original is kept, else the replaced main watermark becomes the original. Without an existing
     * row the original is a DEVICE_ONLY watermark at {@code createdAt} (what the classifier uses
     * for a message with no watermark). Null {@code newWm} gives null (nothing to write).
     */
    public static WatermarkEntity replacing(long messageId, WatermarkEntity existing, Watermark newWm,
                                            long createdAt) {
        if (newWm == null) return null;
        Watermark original = originalOf(existing);
        if (original == null) original = toDomain(existing);
        if (original == null) {
            original = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, createdAt, null);
        }
        return toEntity(messageId, newWm, original);
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
                .originalWatermark(originalOf(wm))
                .rungReason(e.rungReason == null ? null : RemindReason.valueOf(e.rungReason))
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
