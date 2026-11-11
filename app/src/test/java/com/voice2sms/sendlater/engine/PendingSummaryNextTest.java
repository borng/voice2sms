package com.voice2sms.sendlater.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.Watermark;

import org.junit.Test;

import java.util.Arrays;
import java.util.TimeZone;

/**
 * Implementer's own checks of PendingSummary (R45): "next" is the soonest SCHEDULED message even
 * when a HELD one is earlier (a held message does not send on its own); with only HELD messages
 * the soonest HELD one is shown. Placeholder names, fictional 555-01xx numbers.
 */
public class PendingSummaryNextTest {

    private static final long HOUR = 3_600_000L;
    private static final long NOW = 1_781_092_800_000L;
    private static final TimeZone ZONE = TimeZone.getTimeZone("UTC");

    private static ScheduledMessage msg(long id, Status status, long when, String name) {
        ScheduledMessage.Builder b = new ScheduledMessage.Builder()
                .id(id).createdAt(when - 2 * HOUR).scheduledFor(when).text("Placeholder")
                .recipientE164("+1555555010" + id).recipientDisplayName(name)
                .source(ScheduledMessage.Source.NATIVE_GV).rung(Rung.R1).holdIfReplied(true)
                .latenessPolicy(LatenessPolicy.DEFAULT).status(status).attemptCount(0)
                .watermark(new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null,
                        when - 2 * HOUR, null));
        if (status == Status.HELD) b.holdReason(HoldReason.REPLIED);
        return b.build();
    }

    @Test
    public void nextPrefersTheSoonestScheduledOverAnEarlierHeld() {
        PendingSummary s = PendingSummary.build(Arrays.asList(
                msg(1, Status.HELD, NOW - HOUR, "Held One"),
                msg(2, Status.SCHEDULED, NOW + 3 * HOUR, "Later Two"),
                msg(3, Status.SCHEDULED, NOW + HOUR, "Soon Three")), true, NOW, ZONE);
        assertEquals(3, s.getCount());
        assertTrue(s.getText(), s.getText().startsWith("3 scheduled · next Soon Three "));
    }

    @Test
    public void onlyHeldShowsTheSoonestHeld() {
        PendingSummary s = PendingSummary.build(Arrays.asList(
                msg(1, Status.HELD, NOW + 2 * HOUR, "Held Late"),
                msg(2, Status.HELD, NOW + HOUR, "Held Early")), true, NOW, ZONE);
        assertTrue(s.getText(), s.getText().startsWith("2 scheduled · next Held Early "));
    }

    @Test
    public void publicTextHasNoDigits() {
        PendingSummary s = PendingSummary.build(Arrays.asList(
                msg(1, Status.SCHEDULED, NOW + HOUR, "Some Name")), true, NOW, ZONE);
        assertTrue(s.getPublicText(), !s.getPublicText().matches(".*\\d.*"));
    }
}
