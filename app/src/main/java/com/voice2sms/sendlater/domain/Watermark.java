package com.voice2sms.sendlater.domain;

/**
 * How far the conversation had got when the message was scheduled (R20). Immutable.
 * {@code messageId} (the Room FK) belongs to the store layer, not to this value.
 */
public final class Watermark {

    /** EXACT: server id known; TIMESTAMP: newest message time known; DEVICE_ONLY: capture clock only. */
    public enum Quality { EXACT, TIMESTAMP, DEVICE_ONLY }

    private final Quality quality;
    private final String newestServerId;
    private final Long newestTs;
    private final String bubbleHash;
    private final long capturedAtDevice;
    private final Long skewEstimateMs;

    public Watermark(Quality quality, String newestServerId, Long newestTs, String bubbleHash,
                     long capturedAtDevice, Long skewEstimateMs) {
        if (quality == null) throw new IllegalArgumentException("quality");
        this.quality = quality;
        this.newestServerId = newestServerId;
        this.newestTs = newestTs;
        this.bubbleHash = bubbleHash;
        this.capturedAtDevice = capturedAtDevice;
        this.skewEstimateMs = skewEstimateMs;
    }

    public Quality getQuality() { return quality; }
    public String getNewestServerId() { return newestServerId; }
    public Long getNewestTs() { return newestTs; }
    public String getBubbleHash() { return bubbleHash; }
    public long getCapturedAtDevice() { return capturedAtDevice; }
    public Long getSkewEstimateMs() { return skewEstimateMs; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Watermark)) return false;
        Watermark w = (Watermark) o;
        return quality == w.quality && capturedAtDevice == w.capturedAtDevice
                && Objects2.eq(newestServerId, w.newestServerId) && Objects2.eq(newestTs, w.newestTs)
                && Objects2.eq(bubbleHash, w.bubbleHash) && Objects2.eq(skewEstimateMs, w.skewEstimateMs);
    }

    @Override public int hashCode() {
        return Objects2.hash(quality, newestServerId, newestTs, bubbleHash, capturedAtDevice, skewEstimateMs);
    }

    @Override public String toString() {
        return "Watermark{" + quality + ", ts=" + newestTs + ", capturedAt=" + capturedAtDevice + "}";
    }
}
