package com.voice2sms.sendlater.a11y;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** LIVE-F5 wiring: schedule time, engine wakes, Settings row and Upcoming banner. */
public class A11yWarningSourceTest {

    private static String read(String p) throws IOException {
        Path path = Paths.get(p);
        assertTrue("missing: " + p, Files.exists(path));
        return new String(Files.readAllBytes(path), "UTF-8");
    }

    @Test public void receiverIsDeclaredNotExportedOnScheduled() throws IOException {
        String m = read("src/main/AndroidManifest.xml");
        int start = m.indexOf("<!-- sendlater:live-a11y-warn start -->");
        int end = m.indexOf("<!-- sendlater:live-a11y-warn end -->");
        assertTrue(start > 0 && end > start);
        String block = m.substring(start, end);
        assertTrue(block.contains(".sendlater.a11y.A11yScheduledReceiver"));
        assertTrue(block.contains("android:exported=\"false\""));
        assertTrue(block.contains("com.voice2sms.sendlater.action.SCHEDULED"));
    }

    @Test public void receiverDoesTheDatabaseReadOffTheMainThread() throws IOException {
        String r = read("src/main/java/com/voice2sms/sendlater/a11y/A11yScheduledReceiver.java");
        assertTrue(r.contains("goAsync()"));
        assertTrue(r.contains("result.finish()"));
        assertTrue(r.contains("A11yWarning.check("));
    }

    @Test public void engineChecksOnProcessStartLastCallAndDue() throws IOException {
        String e = read("src/main/java/com/voice2sms/sendlater/engine/SendLaterEngine.java");
        int count = 0;
        for (int i = e.indexOf("warnIfAccessibilityOff(context, d, "); i >= 0;
                i = e.indexOf("warnIfAccessibilityOff(context, d, ", i + 1)) count++;
        assertTrue("process start (both branches), T-5 and due alarm", count >= 4);
        assertTrue(e.contains("A11yWarning.onEngineWake(context, sendLaterOn, pending, d.conditions.nowMs())"));
    }

    @Test public void notificationOpensAccessibilitySettingsImmutablyAndNamesNoContact() throws IOException {
        String w = read("src/main/java/com/voice2sms/sendlater/a11y/A11yWarning.java");
        assertTrue(w.contains("Settings.ACTION_ACCESSIBILITY_SETTINGS"));
        assertTrue(w.contains("PendingIntent.FLAG_IMMUTABLE"));
        assertTrue(w.contains("Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES"));
        for (String forbidden : new String[] {"getRecipientE164", "getText()", "getRecipientDisplayName", "getThreadItemId"}) {
            assertFalse("warning must not read " + forbidden, w.contains(forbidden));
        }
        assertFalse("never turns the service on itself", w.contains("putString("));
    }

    @Test public void settingsRowAndUpcomingBannerShowTheWarning() throws IOException {
        String c = read("src/main/java/com/voice2sms/sendlater/ui/SendLaterSettingsController.java");
        assertTrue(c.contains("R.drawable.sendlater_ic_a11y_warn"));
        assertTrue(c.contains("A11yWarning.clearIfEnabled("));
        String u = read("src/main/java/com/voice2sms/sendlater/ui/UpcomingActivity.java");
        assertTrue(u.contains("R.id.sendlater_upcoming_a11y_off"));
        assertTrue(u.contains("A11yHealth.decide("));
        String layout = read("src/main/res/layout/activity_upcoming.xml");
        assertTrue(layout.contains("@+id/sendlater_upcoming_a11y_off"));
    }
}
