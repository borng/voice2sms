package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Deliver fixes in the Upcoming screen and the settings copy: UI M5 (a reminded SCHEDULED row
 * has a status line), UI M6 (chip and sheet share one time rule), UI M7 (accessibility copy).
 */
public class DeliverLabelsUiTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    private Locale saved;

    @Before public void pinLocale() { saved = Locale.getDefault(); Locale.setDefault(Locale.US); }
    @After public void restoreLocale() { Locale.setDefault(saved); }

    private static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    private static ScheduledMessage msg(long id, ScheduledMessage.Status st) {
        return new ScheduledMessage.Builder().id(id).createdAt(1L).scheduledFor(1000L * id).text("t")
                .recipientE164("+14355550101").status(st)
                .holdReason(st == ScheduledMessage.Status.HELD ? HoldReason.REPLIED : null).build();
    }

    @Test public void m6_chipTimeLabelEqualsSheetTimeLabel() {
        long now = 1_793_800_000_000L; // early Nov 2026
        for (int days = 0; days <= 30; days++) {
            for (int h : new int[]{0, 9, 23}) {
                long t = now + days * 86_400_000L + h * 3_600_000L;
                assertEquals("day +" + days + " h" + h, SheetViewModelLogic.timeLabel(t, now, ZONE),
                        ChipLabel.timeLabel(t, now, ZONE));
            }
        }
    }

    @Test public void m5_waitingForUser_onlyMarkedScheduledRows() {
        ScheduledMessage a = msg(1, ScheduledMessage.Status.SCHEDULED);
        ScheduledMessage b = msg(2, ScheduledMessage.Status.SCHEDULED);
        ScheduledMessage h = msg(3, ScheduledMessage.Status.HELD);
        HashSet<Long> marks = new HashSet<>(Arrays.asList(1L, 3L));
        assertTrue(UpcomingSections.isWaitingForUser(a, marks));
        assertFalse(UpcomingSections.isWaitingForUser(b, marks));
        assertFalse("held rows keep their hold reason", UpcomingSections.isWaitingForUser(h, marks));
        assertFalse(UpcomingSections.isWaitingForUser(a, null));
        assertFalse(UpcomingSections.isWaitingForUser(a, Collections.<Long>emptySet()));
    }

    @Test public void m5_upcomingShowsTheReminderStatusLine() throws IOException {
        String src = read("src/main/java/com/voice2sms/sendlater/ui/UpcomingActivity.java");
        int at = src.indexOf("private String statusText(ScheduledMessage m)");
        assertTrue(at >= 0);
        String body = src.substring(at, src.indexOf("\n    }\n", at));
        assertTrue("statusText asks UpcomingSections.isWaitingForUser first",
                body.indexOf("UpcomingSections.isWaitingForUser(") >= 0
                        && body.indexOf("UpcomingSections.isWaitingForUser(") < body.indexOf("Status.HELD"));
        assertTrue(body.contains("R.string.sendlater_upcoming_status_reminded"));
        assertTrue("marks come from the engine on the worker",
                src.contains("SendLaterEngine.remindedIds(app, sections.getPending())"));
        String strings = read("src/main/res/values/sendlater_settings.xml");
        assertTrue(strings.contains("name=\"sendlater_upcoming_status_reminded\">Reminder sent: waiting for you to send it<"));
    }

    @Test public void m7_accessibilityCopyIsNotOptional() throws IOException {
        String strings = read("src/main/res/values/sendlater_settings.xml");
        String why = value(strings, "sendlater_settings_accessibility_why");
        assertFalse("accessibility is required in the Google Voice app", why.startsWith("Optional"));
        assertTrue(why.startsWith("Needed to add the Send Later button to the Google Voice app."));
        assertTrue(why.contains("Without it you can still schedule from the voice2sms Google Voice screen."));
        String body = value(strings, "sendlater_settings_onboarding_body");
        assertFalse("onboarding no longer calls every permission optional",
                body.contains("each permission below is optional"));
        assertTrue(body.contains("Accessibility is needed to add the Send Later button to the Google Voice app"));
        assertTrue(body.contains("you can still schedule from the voice2sms Google Voice screen"));
    }

    private static String value(String xml, String name) {
        String open = "name=\"" + name + "\">";
        int a = xml.indexOf(open);
        assertTrue(name, a >= 0);
        a += open.length();
        return xml.substring(a, xml.indexOf("</string>", a));
    }
}
