package com.voice2sms.sendlater.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared fixtures for the Send Later domain tests. No real numbers or names:
 * 555-01xx is the reserved fictional range, the display name is a placeholder.
 */
final class Fixtures {

    static final long MIN = 60_000L;
    static final long HOUR = 60 * MIN;
    static final long DAY = 24 * HOUR;

    /** Arbitrary fixed epoch (2026-06-10T12:00Z); only differences matter. */
    static final long T0 = 1_781_092_800_000L;

    static final String E164 = "+15555550100";
    static final String NAME = "Test Contact";
    static final String TEXT = "Running late, be there at 9";

    private Fixtures() {}

    static Watermark timestampWatermark(long capturedAt) {
        return new Watermark(Watermark.Quality.TIMESTAMP, null, capturedAt - 10 * MIN,
                "bubble-hash", capturedAt, null);
    }

    static Watermark deviceOnlyWatermark(long capturedAt) {
        return new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, capturedAt, null);
    }

    /** A SCHEDULED message created at T0, due {@code aheadMs} later, default AUTO policy. */
    static ScheduledMessage.Builder message(long aheadMs) {
        return new ScheduledMessage.Builder()
                .id(1L)
                .createdAt(T0)
                .scheduledFor(T0 + aheadMs)
                .text(TEXT)
                .recipientE164(E164)
                .recipientDisplayName(NAME)
                .threadItemId(null)
                .source(ScheduledMessage.Source.NATIVE_GV)
                .rung(Rung.R1)
                .holdIfReplied(true)
                .latenessPolicy(LatenessPolicy.AUTO)
                .status(ScheduledMessage.Status.SCHEDULED)
                .lastError(null)
                .attemptCount(0)
                .watermark(timestampWatermark(T0));
    }

    static ThreadEvent inbound(ThreadEvent.Kind kind, long timeMs, String text) {
        return new ThreadEvent(ThreadEvent.Direction.IN, kind, timeMs, hash(text), text);
    }

    static ThreadEvent inboundSms(long timeMs, String text) {
        return inbound(ThreadEvent.Kind.SMS, timeMs, text);
    }

    static ThreadEvent outboundSms(long timeMs, String text) {
        return new ThreadEvent(ThreadEvent.Direction.OUT, ThreadEvent.Kind.SMS, timeMs, hash(text), text);
    }

    static List<ThreadEvent> events(ThreadEvent... e) {
        return new ArrayList<>(Arrays.asList(e));
    }

    static List<ThreadEvent> none() {
        return new ArrayList<>();
    }

    static String hash(String text) {
        return text == null ? null : "h" + Integer.toHexString(text.hashCode());
    }
}
