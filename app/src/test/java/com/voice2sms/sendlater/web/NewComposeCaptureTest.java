package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.Watermark;

import org.junit.Test;

/**
 * Implementer's own checks for LIVE-F14 / R10a beyond the acceptance suite: group threads are no
 * new-message page, an intent recipient that does not normalize is not "none", and a new-message
 * capture carries no watermark (DEVICE_ONLY). Fictional numbers (555-01xx).
 */
public class NewComposeCaptureTest {

    private static final String NEW_MESSAGE = "https://voice.google.com/u/0/messages";
    private static final String CHIP = "+12025550123";

    @Test
    public void groupThreadPage_isNotANewMessagePage() {
        String group = "https://voice.google.com/u/0/messages?itemId=t.g.abc123";
        assertTrue(NewComposeRecipient.isThreadPage(group));
        assertNull(NewComposeRecipient.confirm(group, null, 1, CHIP));
        assertFalse(NewComposeRecipient.isThreadPage(NEW_MESSAGE));
    }

    @Test
    public void intentRecipientThatDoesNotNormalize_rejectsInsteadOfAcceptingAnyChip() {
        assertNull(NewComposeRecipient.confirm(NEW_MESSAGE, "Alex", 1, CHIP));
        assertNull(NewComposeRecipient.confirm(NEW_MESSAGE, "2025550123;2025550124", 1, CHIP));
        assertEquals(CHIP, NewComposeRecipient.confirm(NEW_MESSAGE, "  ", 1, CHIP));
    }

    @Test
    public void newMessageCapture_dropsTheWatermark() {
        CapturePayload p = CapturePayload.parse("{\"e164\":\"" + CHIP + "\",\"draftText\":\"hi\","
                + "\"newestMessageId\":\"m1\",\"newestMessageTimeMs\":1700000000000}");
        assertEquals(Watermark.Quality.EXACT, p.getWatermarkQuality());
        CapturePayload d = p.withoutWatermark();
        assertEquals(Watermark.Quality.DEVICE_ONLY, d.getWatermarkQuality());
        assertNull(d.getNewestMessageId());
        assertNull(d.getNewestMessageTimeMs());
        assertEquals(CHIP, d.getE164());
        assertEquals("hi", d.getDraftText());
    }
}
