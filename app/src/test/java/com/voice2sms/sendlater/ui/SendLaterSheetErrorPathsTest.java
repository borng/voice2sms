package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OD22(8) source checks of SendLaterSheetActivity: onUpdate wraps its store and notification calls
 * and shows the persist error; a missing or unknown EXTRA_SOURCE rejects the capture; an invalid
 * manual number shows sendlater_sheet_number_invalid inline. Also OD22(4): a no-session save
 * records rungReason NO_SESSION.
 */
public class SendLaterSheetErrorPathsTest {

    private static String code() throws IOException {
        String s = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/ui/SendLaterSheetActivity.java")), StandardCharsets.UTF_8);
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static String block(String s, String startRegex) {
        Matcher m = Pattern.compile(startRegex).matcher(s);
        assertTrue("not found: " + startRegex, m.find());
        int open = s.indexOf('{', m.end() - 1);
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced");
    }

    @Test public void parseSourceRejectsMissingOrUnknownSource() throws IOException {
        String c = code();
        String parse = block(c, "ScheduledMessage\\.Source\\s+parseSource\\s*\\(\\s*String\\s+\\w+\\s*\\)\\s*\\{");
        assertTrue("no NATIVE_GV fallback: " + parse, !parse.contains("NATIVE_GV"));
        assertTrue(parse.contains("return null"));
        String capture = block(c, "void\\s+setupCapture\\s*\\(\\s*Intent\\s+\\w+\\s*\\)\\s*\\{");
        int nullCheck = capture.indexOf("== null");
        int fin = capture.indexOf("finish()");
        int logic = capture.indexOf("SheetViewModelLogic.forCapture");
        assertTrue("null source finishes before the sheet is built: " + capture,
                nullCheck >= 0 && fin > nullCheck && logic > fin);
        assertTrue(capture.contains("R.string.sendlater_sheet_bad_source"));
    }

    @Test public void badSourceStringLivesInTheSheetStrings() throws IOException {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/values/sendlater_sheet.xml")),
                StandardCharsets.UTF_8);
        assertTrue(xml.contains("name=\"sendlater_sheet_bad_source\""));
        String strings = new String(Files.readAllBytes(Paths.get("src/main/res/values/strings.xml")),
                StandardCharsets.UTF_8);
        assertTrue(!strings.contains("sendlater_sheet_bad_source"));
    }

    @Test public void onUpdateCatchesStoreAndNotificationErrorsAndShowsThePersistError() throws IOException {
        String c = code();
        String onUpdate = block(c, "void\\s+onUpdate\\s*\\(\\s*\\)\\s*\\{");
        int exec = onUpdate.indexOf("io.execute(");
        assertTrue(exec >= 0);
        String worker = onUpdate.substring(exec);
        int tryAt = worker.indexOf("try {");
        int call = worker.indexOf("updateArmAndAnnounce(");
        int catchAt = worker.indexOf("catch (RuntimeException");
        int failed = worker.indexOf("onPersistFailed");
        assertTrue(worker, tryAt >= 0 && call > tryAt && catchAt > call && failed > catchAt);
        String body = block(c, "void\\s+updateArmAndAnnounce\\s*\\(");
        // Deliver M2: the reschedule, notification dismiss and re-read moved into the engine's
        // locked SendLaterEngine.reschedule (EditUnderEngineLockTest pins them there).
        assertTrue(body.contains("SendLaterEngine.reschedule("));
        String failedBody = block(c, "void\\s+onPersistFailed\\s*\\(\\s*\\)\\s*\\{");
        assertTrue(failedBody.contains("R.string.sendlater_sheet_persist_failed"));
    }

    @Test public void invalidManualNumberShowsTheInlineMessage() throws IOException {
        String c = code();
        String watcher = block(c, "void\\s+afterTextChanged\\s*\\(\\s*Editable\\s+\\w+\\s*\\)\\s*\\{");
        assertTrue(watcher, watcher.contains("logic.enterNumber(") && watcher.contains("showNumberError("));
        String show = block(c, "void\\s+showNumberError\\s*\\(\\s*boolean\\s+\\w+\\s*\\)\\s*\\{");
        assertTrue(show.contains("R.string.sendlater_sheet_number_invalid"));
        assertTrue(show.contains("setError("));
    }

    @Test public void noSessionSaveRecordsTheRungReason() throws IOException {
        String c = code();
        String schedule = block(c, "void\\s+onSchedule\\s*\\(\\s*\\)\\s*\\{");
        assertTrue(schedule.contains(".rung(hasWebSession ? Rung.R1 : Rung.R3)"));
        assertTrue(schedule.contains(".rungReason(hasWebSession ? null : RemindReason.NO_SESSION)"));
    }
}
