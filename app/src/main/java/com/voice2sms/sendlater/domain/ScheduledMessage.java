package com.voice2sms.sendlater.domain;

/**
 * A scheduled message (R20). Immutable; build or change one with {@link Builder} /
 * {@link #toBuilder()}. {@code text} is message content: never log it.
 */
public final class ScheduledMessage {

    public enum Status {
        SCHEDULED, HELD, IN_FLIGHT, SENT_VERIFIED, CANCELED, SELF_PREEMPTED, EXPIRED, FAILED;

        /** True once nothing more will happen to the message automatically. */
        public boolean isTerminal() {
            return this == SENT_VERIFIED || this == CANCELED || this == SELF_PREEMPTED
                    || this == EXPIRED || this == FAILED;
        }
    }

    /** Where the draft was captured. Names match SendLaterIntents.EXTRA_SOURCE values. */
    public enum Source { NATIVE_GV, WEBVIEW }

    private final long id;
    private final long createdAt;
    private final long scheduledFor;
    private final String text;
    private final String recipientE164;
    private final String recipientDisplayName;
    private final String threadItemId;
    private final Source source;
    private final Rung rung;
    private final boolean holdIfReplied;
    private final LatenessPolicy latenessPolicy;
    private final Status status;
    private final HoldReason holdReason;
    private final String lastError;
    private final int attemptCount;
    private final Watermark watermark;

    private ScheduledMessage(Builder b) {
        this.id = b.id;
        this.createdAt = b.createdAt;
        this.scheduledFor = b.scheduledFor;
        this.text = b.text;
        this.recipientE164 = b.recipientE164;
        this.recipientDisplayName = b.recipientDisplayName;
        this.threadItemId = b.threadItemId;
        this.source = b.source;
        this.rung = b.rung;
        this.holdIfReplied = b.holdIfReplied;
        this.latenessPolicy = b.latenessPolicy;
        this.status = b.status;
        this.holdReason = b.holdReason;
        this.lastError = b.lastError;
        this.attemptCount = b.attemptCount;
        this.watermark = b.watermark;
    }

    public long getId() { return id; }
    public long getCreatedAt() { return createdAt; }
    public long getScheduledFor() { return scheduledFor; }
    public String getText() { return text; }
    public String getRecipientE164() { return recipientE164; }
    public String getRecipientDisplayName() { return recipientDisplayName; }
    public String getThreadItemId() { return threadItemId; }
    public Source getSource() { return source; }
    public Rung getRung() { return rung; }
    public boolean isHoldIfReplied() { return holdIfReplied; }
    public LatenessPolicy getLatenessPolicy() { return latenessPolicy; }
    public Status getStatus() { return status; }
    public HoldReason getHoldReason() { return holdReason; }
    public String getLastError() { return lastError; }
    public int getAttemptCount() { return attemptCount; }
    public Watermark getWatermark() { return watermark; }

    public Builder toBuilder() {
        return new Builder()
                .id(id).createdAt(createdAt).scheduledFor(scheduledFor).text(text)
                .recipientE164(recipientE164).recipientDisplayName(recipientDisplayName)
                .threadItemId(threadItemId).source(source).rung(rung).holdIfReplied(holdIfReplied)
                .latenessPolicy(latenessPolicy).status(status).holdReason(holdReason)
                .lastError(lastError).attemptCount(attemptCount).watermark(watermark);
    }

    /** Never includes text, name or number. */
    @Override public String toString() {
        return "ScheduledMessage{id=" + id + ", status=" + status + ", scheduledFor=" + scheduledFor
                + ", rung=" + rung + ", policy=" + latenessPolicy + "}";
    }

    /**
     * Mutable builder. Defaults: source NATIVE_GV, rung R1 (R24), holdIfReplied true (R12),
     * latenessPolicy DEFAULT, status SCHEDULED.
     */
    public static final class Builder {
        private long id;
        private long createdAt;
        private long scheduledFor;
        private String text;
        private String recipientE164;
        private String recipientDisplayName;
        private String threadItemId;
        private Source source = Source.NATIVE_GV;
        private Rung rung = Rung.R1;
        private boolean holdIfReplied = true;
        private LatenessPolicy latenessPolicy = LatenessPolicy.DEFAULT;
        private Status status = Status.SCHEDULED;
        private HoldReason holdReason;
        private String lastError;
        private int attemptCount;
        private Watermark watermark;

        public Builder id(long v) { id = v; return this; }
        public Builder createdAt(long v) { createdAt = v; return this; }
        public Builder scheduledFor(long v) { scheduledFor = v; return this; }
        public Builder text(String v) { text = v; return this; }
        public Builder recipientE164(String v) { recipientE164 = v; return this; }
        public Builder recipientDisplayName(String v) { recipientDisplayName = v; return this; }
        public Builder threadItemId(String v) { threadItemId = v; return this; }
        public Builder source(Source v) { source = v; return this; }
        public Builder rung(Rung v) { rung = v; return this; }
        public Builder holdIfReplied(boolean v) { holdIfReplied = v; return this; }
        public Builder latenessPolicy(LatenessPolicy v) { latenessPolicy = v; return this; }
        public Builder status(Status v) { status = v; return this; }
        public Builder holdReason(HoldReason v) { holdReason = v; return this; }
        public Builder lastError(String v) { lastError = v; return this; }
        public Builder attemptCount(int v) { attemptCount = v; return this; }
        public Builder watermark(Watermark v) { watermark = v; return this; }

        public ScheduledMessage build() {
            if (source == null) throw new IllegalStateException("source");
            if (rung == null) throw new IllegalStateException("rung");
            if (latenessPolicy == null) throw new IllegalStateException("latenessPolicy");
            if (status == null) throw new IllegalStateException("status");
            return new ScheduledMessage(this);
        }
    }
}
