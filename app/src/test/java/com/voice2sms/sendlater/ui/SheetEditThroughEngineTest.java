package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Deliver M2: the sheet changes a stored message only through the engine (its lock): Update via
 * {@code SendLaterEngine.reschedule}, Cancel message via {@code SendLaterEngine.cancel}, a new
 * capture's alarms via {@code SendLaterEngine.registerAlarms}. A refused edit says why inline.
 */
public class SheetEditThroughEngineTest {

    private static String code() throws IOException {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater/ui/SendLaterSheetActivity.java");
        if (!Files.exists(p)) p = Paths.get("app").resolve(p);
        String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    @Test public void sheetNeverWritesScheduleStateOrAlarmsDirectly() throws IOException {
        String c = code();
        assertFalse(c.contains(".reschedule(id"));
        assertFalse(c.contains("SendLaterScheduler"));
        assertFalse(c.contains("cancelAll("));
        assertFalse(c.contains("SendLaterNotifications.dismiss("));
        assertTrue(c.contains("SendLaterEngine.reschedule(app, id, when, hold, policy, baseline)"));
        assertTrue(c.contains("SendLaterEngine.cancel(app, id)"));
        assertTrue(c.contains("SendLaterEngine.registerAlarms(app, stored)"));
    }

    @Test public void refusedEditShowsWhyAndKeepsTheSheetOpen() throws IOException {
        String c = code();
        int at = c.indexOf("private void updateArmAndAnnounce(");
        assertTrue(at > 0);
        String body = c.substring(at);
        body = body.substring(0, body.indexOf("\n    }\n"));
        int done = body.indexOf("TapResult.DONE");
        int finish = body.indexOf("finish();");
        int notEditable = body.indexOf("showNotEditable()");
        int why = body.indexOf("TapResult.feedback(result, false)");
        assertTrue(body, done > 0 && finish > done && notEditable > finish && why > notEditable);
        assertTrue(body.contains("setBusy(false)"));
        assertTrue(body.contains("isFinishing() || isDestroyed()"));
    }
}
