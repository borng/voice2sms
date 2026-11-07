package com.voice2sms.sendlater.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * D39: a recipient without a name is shown as ChipLabel.formatNumber(e164) everywhere
 * (notifications, Upcoming, the sheet), never as the raw "+1" number. Only the chip truncates.
 * Numbers are fictional (555-01xx).
 */
public class FormattedRecipientTest {

    private static final String US = "+15555550123";
    private static final String US_SHOWN = "(555) 555-0123";
    private static final String INTL = "+445555550100";

    private static ScheduledMessage nameless(String e164) {
        return EngineFakes.message(1L).recipientDisplayName(null).recipientE164(e164).build();
    }

    private static String read(String rel) throws IOException {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater").resolve(rel);
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test public void recipientHelper() {
        assertEquals("Test Contact", ChipLabel.recipient("  Test Contact ", US, "x"));
        assertEquals(US_SHOWN, ChipLabel.recipient(null, US, "x"));
        assertEquals(US_SHOWN, ChipLabel.recipient("   ", US, "x"));
        assertEquals("non-NANP stays as E.164", INTL, ChipLabel.recipient(null, INTL, "x"));
        assertEquals("x", ChipLabel.recipient(null, null, "x"));
        assertEquals("x", ChipLabel.recipient("", "  ", "x"));
        String longName = "A Very Long Contact Name Indeed";
        assertEquals("never truncated outside the chip", longName, ChipLabel.recipient(longName, US, "x"));
    }

    @Test public void chipKeepsItsTruncation() {
        assertEquals(US_SHOWN, ChipLabel.heldDisplayName(null, US));
        String chip = ChipLabel.heldDisplayName("A Very Long Contact Name Indeed", US);
        assertEquals(20, chip.length());
        assertTrue(chip.endsWith("…"));
        assertEquals("", ChipLabel.heldDisplayName(null, null));
    }

    @Test public void notificationsShowTheFormattedNumber() {
        ScheduledMessage m = nameless(US);
        assertEquals(US_SHOWN, SendLaterNotifications.who(m));
        assertEquals("Couldn't send your message to " + US_SHOWN, SendLaterNotifications.sendFailedTitle(m));
        for (RemindCause c : RemindCause.values()) {
            String t = SendLaterNotifications.cannotSendTitle(m, c);
            assertFalse(c + ": " + t, t.contains(US));
        }
        assertEquals("your contact", SendLaterNotifications.who(nameless(null)));
        assertEquals(INTL, SendLaterNotifications.who(nameless(INTL)));
    }

    @Test public void draftNoticeAgrees() {
        assertEquals(SendLaterNotifications.who(nameless(US)), DraftNotice.who(nameless(US)));
        assertEquals("your contact", DraftNotice.who(null));
        assertEquals("your contact", DraftNotice.who(nameless(null)));
    }

    @Test public void upcomingAndSheetFormatTheNumber() throws IOException {
        String up = read("ui/UpcomingActivity.java");
        int at = up.indexOf("private String nameOf(ScheduledMessage m)");
        assertTrue(at > 0);
        String body = up.substring(at, up.indexOf("\n    }\n", at));
        assertTrue(body, body.contains("ChipLabel.recipient(m.getRecipientDisplayName(), m.getRecipientE164(),"));
        assertFalse("no raw number fallback", body.contains("return e;"));

        String sheet = read("ui/SendLaterSheetActivity.java");
        assertTrue(sheet.contains("ChipLabel.formatNumber(logic.getE164())"));
        assertTrue(sheet.contains("rb.setText(ChipLabel.formatNumber(number));"));
        assertFalse(sheet.contains("rb.setText(number);"));
        assertTrue("the raw number is still what a choice selects", sheet.contains("rb.setTag(number);"));

        String n = read("engine/SendLaterNotifications.java");
        at = n.indexOf("static String who(ScheduledMessage m)");
        body = n.substring(at, n.indexOf("\n    }\n", at));
        assertTrue(body.contains("ChipLabel.recipient("));
    }
}
