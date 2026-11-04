package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Acceptance (sheet-ui; R4, R9, R12, R16, R21, I1, I3, A4): source-level checks of
 * SendLaterSheetActivity, its layout, resources, theme, manifest block and the Material
 * dependency. Paths are relative to the app module (Gradle runs unit tests there).
 */
public class SendLaterSheetSourceTest {

    private static final Path UI = Paths.get("src/main/java/com/voice2sms/sendlater/ui");
    private static final Path ACTIVITY = UI.resolve("SendLaterSheetActivity.java");
    private static final Path LOGIC = UI.resolve("SheetViewModelLogic.java");
    private static final Path RES = Paths.get("src/main/res");
    private static final Path LAYOUT_DIR = RES.resolve("layout");
    private static final Path SHEET_STRINGS = RES.resolve("values/sendlater_sheet.xml");
    private static final Path COLORS = RES.resolve("values/sendlater_colors.xml");
    private static final Path STRINGS = RES.resolve("values/strings.xml");
    private static final Path MANIFEST = Paths.get("src/main/AndroidManifest.xml");
    private static final Path GRADLE = Paths.get("build.gradle");

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** Removes comments and string literals so identifiers/call order can be checked. */
    private static String code(String s) {
        String noBlock = s.replaceAll("(?s)/\\*.*?\\*/", " ");
        String noLine = noBlock.replaceAll("//[^\\n]*", " ");
        return noLine.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    private static String activityCode() throws IOException {
        return code(read(ACTIVITY));
    }

    private static int indexOf(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.start() : -1;
    }

    /** Body of the block that starts at the first '{' after {@code from}. */
    private static String blockAfter(String s, int from) {
        int open = s.indexOf('{', from);
        assertTrue("no block after index " + from, open >= 0);
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced braces");
    }

    private static List<Path> xmlUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".xml")).sorted().collect(Collectors.toList());
        }
    }

    // ------------------------------------------------------------------ the activity exists and is wired

    @Test public void sheetActivityAndPureLogicExist() throws IOException {
        String a = activityCode();
        assertTrue("extends AppCompatActivity (F2)",
                Pattern.compile("class\\s+SendLaterSheetActivity\\s+extends\\s+(androidx\\.appcompat\\.app\\.)?AppCompatActivity")
                        .matcher(a).find());
        assertTrue("uses SheetViewModelLogic", a.contains("SheetViewModelLogic"));
        String logic = code(read(LOGIC));
        assertFalse("SheetViewModelLogic is pure JVM (no android.*)", logic.matches("(?s).*\\bimport\\s+android\\..*"));
        assertFalse("SheetViewModelLogic is pure JVM (no androidx.*)", logic.matches("(?s).*\\bimport\\s+androidx\\..*"));
        assertFalse("no java.time in production code (minSdk 24)", logic.contains("java.time"));
        assertFalse("no java.time in production code (minSdk 24)", a.contains("java.time"));
    }

    @Test public void handlesCaptureAndEditActionsAndReadsThePinnedExtras() throws IOException {
        String a = activityCode();
        assertTrue(a.contains("ACTION_CAPTURE"));
        assertTrue(a.contains("ACTION_EDIT"));
        for (String extra : new String[] {"EXTRA_SOURCE", "EXTRA_DISPLAY_NAME", "EXTRA_DRAFT", "EXTRA_E164",
                "EXTRA_CANDIDATES", "EXTRA_THREAD_ITEM_ID", "EXTRA_WATERMARK_QUALITY",
                "EXTRA_WATERMARK_CAPTURED_AT", "EXTRA_MESSAGE_ID"}) {
            assertTrue("reads " + extra, a.contains(extra));
        }
        assertTrue("candidates are a String[] extra", a.contains("getStringArrayExtra"));
        assertFalse("there is no EXTRA_RECIPIENT_E164", a.contains("EXTRA_RECIPIENT_E164"));
    }

    // ------------------------------------------------------------------ schedule path (R21, I1, I3)

    /**
     * The persist call appears in the source before the ACTION_SCHEDULED broadcast code, and alarm
     * registration follows it (I1, R21; engine contract: registerAlarms after schedule()).
     */
    @Test public void schedulePersistsThenRegistersAlarmsThenBroadcastsScheduled() throws IOException {
        String a = activityCode();
        int persist = indexOf(a, "\\.schedule\\s*\\(");
        int broadcast = a.indexOf("ACTION_SCHEDULED");
        assertTrue("calls repository.schedule(...)", persist >= 0);
        assertTrue("broadcasts ACTION_SCHEDULED", broadcast >= 0);
        assertTrue("SendLaterRepository used", a.contains("SendLaterRepository"));
        assertTrue("SendLaterScheduler used", a.contains("SendLaterScheduler"));
        assertTrue("persist before the ACTION_SCHEDULED broadcast (I1)", persist < broadcast);
        String after = a.substring(persist);
        assertTrue("registerAlarms after schedule()", indexOf(after, "registerAlarms\\s*\\(") >= 0);
        assertTrue("sendBroadcast after schedule()", indexOf(after, "sendBroadcast\\s*\\(") >= 0);
    }

    @Test public void scheduledBroadcastCarriesPinnedExtrasAndIsAppInternal() throws IOException {
        String a = activityCode();
        int b = a.indexOf("ACTION_SCHEDULED");
        assertTrue(b >= 0);
        String around = a.substring(Math.max(0, b - 1500), Math.min(a.length(), b + 1500));
        for (String extra : new String[] {"EXTRA_MESSAGE_ID", "EXTRA_SOURCE", "EXTRA_E164", "EXTRA_DRAFT",
                "EXTRA_SCHEDULED_FOR", "EXTRA_LABEL"}) {
            assertTrue("ACTION_SCHEDULED carries " + extra, around.contains(extra));
        }
        assertTrue("setPackage(getPackageName()) on the broadcast", around.contains("setPackage("));
        assertTrue(around.contains("getPackageName()"));
    }

    @Test public void broadcastOnlyAfterANonNullOrPositivePersistedId() throws IOException {
        String a = activityCode();
        int persist = indexOf(a, "\\.schedule\\s*\\(");
        int broadcast = a.indexOf("ACTION_SCHEDULED", Math.max(persist, 0));
        assertTrue(persist >= 0 && broadcast > persist);
        String between = a.substring(persist, broadcast);
        assertTrue("the id returned by schedule() is checked (null / <= 0) before broadcasting",
                Pattern.compile("(==|!=)\\s*null|null\\s*(==|!=)|<=\\s*0|>\\s*0|<\\s*1|>=\\s*1").matcher(between).find());
    }

    @Test public void persistFailureShowsInlineErrorAndTouchesNothingElse() throws IOException {
        String a = activityCode();
        int c = indexOf(a, "catch\\s*\\(\\s*(final\\s+)?[\\w.|\\s]*PersistException");
        assertTrue("catches store.PersistException (R21)", c >= 0);
        String handler = blockAfter(a, c);
        assertFalse("no broadcast on persist failure", handler.contains("sendBroadcast"));
        assertFalse("no ACTION_SCHEDULED on persist failure", handler.contains("ACTION_SCHEDULED"));
        assertFalse("no alarms on persist failure", handler.contains("registerAlarms"));
        assertFalse("sheet stays open on persist failure", Pattern.compile("\\bfinish\\s*\\(").matcher(handler).find());
    }

    @Test public void scheduleIsGatedOnAResolvedNumberNeverANameAlone() throws IOException {
        String a = activityCode();
        int persist = indexOf(a, "\\.schedule\\s*\\(");
        int gate = a.indexOf("isScheduleEnabled");
        assertTrue("checks SheetViewModelLogic.isScheduleEnabled()", gate >= 0);
        assertTrue("enable check precedes the persist call", gate < persist);
        assertTrue("recipient comes from the resolved E.164", a.contains("recipientE164("));
        Matcher m = Pattern.compile("recipientE164\\s*\\(([^;]*?)\\)\\s*[.;]").matcher(a);
        while (m.find()) {
            String arg = m.group(1).toLowerCase();
            assertFalse("recipientE164 must never be fed the display name: " + arg,
                    arg.contains("name") || arg.contains("display"));
        }
        int e164Read = a.indexOf("EXTRA_E164");
        assertTrue("EXTRA_E164 read before schedule", e164Read >= 0 && e164Read < persist);
    }

    @Test public void repositoryWorkRunsOffTheMainThread() throws IOException {
        String a = activityCode();
        assertTrue("Room calls are synchronous: use an Executor or a worker Thread",
                a.contains("Executor") || a.contains("new Thread("));
        assertTrue("UI results posted back with runOnUiThread / a main Handler",
                a.contains("runOnUiThread") || a.contains("Looper.getMainLooper()") || a.contains("ContextCompat.getMainExecutor"));
    }

    @Test public void scheduledPathNeverUsesForceAutoSendOrTheRateLimiter() throws IOException {
        String raw = read(ACTIVITY) + read(LOGIC);
        assertFalse(raw.contains("EXTRA_FORCE_AUTO_SEND"));
        assertFalse(raw.contains("force_auto_send"));
        assertFalse(raw.contains("AutoSendRateLimiter"));
    }

    @Test public void neverLogsDraftOrName() throws IOException {
        String a = activityCode();
        Matcher m = Pattern.compile("\\bLog\\.[vdiwe]\\s*\\(([^;]*)\\)\\s*;").matcher(a);
        while (m.find()) {
            String args = m.group(1).toLowerCase();
            for (String bad : new String[] {"draft", "displayname", "gettext(", "e164", "candidates"}) {
                assertFalse("Log call passes " + bad + ": " + m.group(), args.contains(bad));
            }
        }
    }

    // ------------------------------------------------------------------ edit mode, session, notifications (R9, R16)

    @Test public void editModeRechecksEditableReschedulesReArmsAlarmsAndDismissesNotification() throws IOException {
        String a = activityCode();
        assertTrue("re-checks SendLaterEngine.isEditable(id)", a.contains("isEditable("));
        assertTrue("SendLaterEngine used", a.contains("SendLaterEngine"));
        int resched = indexOf(a, "\\.reschedule\\s*\\(");
        assertTrue("Update calls repository.reschedule", resched >= 0);
        assertTrue("registerAlarms after reschedule", indexOf(a.substring(resched), "registerAlarms\\s*\\(") >= 0);
        assertTrue("SendLaterNotifications.dismiss(id) when rescheduling",
                Pattern.compile("SendLaterNotifications\\s*\\.\\s*dismiss\\s*\\(").matcher(a).find());
        assertTrue("Cancel message goes through the engine or repository cancel",
                Pattern.compile("\\.\\s*(onCancel|cancel)\\s*\\(").matcher(a).find());
    }

    @Test public void sessionHintAndNotificationPermissionHint() throws IOException {
        String a = activityCode();
        assertTrue("R9 hint from GvSession.hasWebSession(Context)",
                Pattern.compile("GvSession\\s*\\.\\s*hasWebSession\\s*\\(").matcher(a).find());
        assertTrue("POST_NOTIFICATIONS hint on API 33+", a.contains("POST_NOTIFICATIONS"));
        assertTrue("settings read from SendLaterSettings", a.contains("SendLaterSettings"));
        assertTrue("hold default from Settings", a.contains("holdDefault("));
        assertTrue("slot times from Settings (R13)", a.contains("slotMinutes("));
    }

    // ------------------------------------------------------------------ layout order (R12)

    @Test public void layoutHasR12IdsInOrder() throws IOException {
        String[] ids = {"title", "card", "presets", "pick_datetime", "hold_checkbox", "if_late",
                "schedule_button", "cancel_button"};
        List<Path> sheets = new ArrayList<>();
        for (Path p : xmlUnder(LAYOUT_DIR)) {
            if (read(p).contains("@+id/hold_checkbox")) sheets.add(p);
        }
        assertEquals("exactly one sheet layout declares @+id/hold_checkbox: " + sheets, 1, sheets.size());
        String xml = read(sheets.get(0)).replaceAll("(?s)<!--.*?-->", " ");
        int last = -1;
        for (String id : ids) {
            int at = xml.indexOf("\"@+id/" + id + "\"");
            assertTrue("layout declares @+id/" + id, at >= 0);
            assertTrue("@+id/" + id + " comes after the previous R12 element", at > last);
            last = at;
        }
        assertTrue("quoted draft limited to 3 lines", xml.contains("android:maxLines=\"3\""));
        assertTrue("hold checkbox is a CheckBox",
                Pattern.compile("<[\\w.]*CheckBox[^>]*@\\+id/hold_checkbox", Pattern.DOTALL).matcher(xml).find());
        String code = activityCode();
        for (String id : ids) assertTrue("activity binds R.id." + id, code.contains("R.id." + id));
    }

    // ------------------------------------------------------------------ strings, colours, theme (R9, R12, R16, A4, F2)

    @Test public void sheetStringsLiveInSendlaterSheetXmlNotStringsXml() throws IOException {
        String s = read(SHEET_STRINGS);
        for (String lit : new String[] {"Send later", "Pick date &amp; time ›", "Hold if they text first",
                "If late", "Which number?", "Sign in to Google Voice in voice2sms so this can send itself",
                ">Update<", ">Cancel message<", ">Cancel<", "This message can no longer be changed"}) {
            assertTrue("sendlater_sheet.xml has " + lit, s.contains(lit));
        }
        assertTrue("a 'Schedule for <time>' format string", s.contains(">Schedule for "));
        String strings = read(STRINGS);
        assertFalse("strings.xml untouched", strings.contains("Hold if they text first"));
        assertFalse("strings.xml untouched", strings.contains("Which number?"));
    }

    @Test public void accentColourTokenIsLightBlue() throws IOException {
        String c = read(COLORS);
        Matcher m = Pattern.compile("<color\\s+name=\"sendlater_accent\"\\s*>\\s*#(?:FF)?8AB4F8\\s*</color>",
                Pattern.CASE_INSENSITIVE).matcher(c);
        assertTrue("sendlater_accent = #8AB4F8", m.find());
        assertTrue("on-accent #0B1D3A defined", c.toUpperCase().contains("0B1D3A"));
    }

    @Test public void sheetThemeIsTranslucentMaterial3WithTheAccent() throws IOException {
        String style = null;
        for (Path p : xmlUnder(RES)) {
            if (!p.getParent().getFileName().toString().startsWith("values")) continue;
            String x = read(p);
            Matcher m = Pattern.compile("(?s)<style\\s+name=\"Theme\\.SendLater\\.Sheet\"[^>]*>.*?</style>").matcher(x);
            if (m.find()) { style = m.group(); break; }
        }
        assertTrue("style Theme.SendLater.Sheet declared", style != null);
        assertTrue("parent Theme.Material3.DayNight.NoActionBar",
                style.contains("parent=\"Theme.Material3.DayNight.NoActionBar\""));
        assertTrue("windowIsTranslucent true",
                Pattern.compile("android:windowIsTranslucent\"\\s*>\\s*true").matcher(style).find());
        assertTrue("transparent windowBackground",
                Pattern.compile("android:windowBackground\"\\s*>\\s*@android:color/transparent").matcher(style).find());
        assertTrue("colorPrimary is the accent",
                Pattern.compile("\"colorPrimary\"\\s*>\\s*@color/sendlater_accent").matcher(style).find());
    }

    @Test public void appThemeAndExistingActivitiesUntouched() throws IOException {
        String m = read(MANIFEST);
        assertTrue("SettingsActivity keeps its theme",
                Pattern.compile("(?s)\\.SettingsActivity\"\\s+android:exported=\"false\"\\s+android:theme=\"@style/Theme\\.AppCompat\\.Light\\.DarkActionBar\"")
                        .matcher(m).find());
    }

    // ------------------------------------------------------------------ manifest + dependency

    @Test public void manifestDeclaresTheSheetInsideItsMarkedBlockAfterSettingsActivity() throws IOException {
        String m = read(MANIFEST);
        int start = m.indexOf("<!-- sendlater:sheet-ui start -->");
        int end = m.indexOf("<!-- sendlater:sheet-ui end -->");
        assertTrue("sheet-ui start/end markers", start >= 0 && end > start);
        int settings = m.indexOf(".SettingsActivity\"");
        assertTrue(settings >= 0);
        int settingsEnd = m.indexOf("/>", settings);
        assertTrue("block sits right after the SettingsActivity element", settingsEnd < start
                && m.substring(settingsEnd + 2, start).trim().isEmpty());
        String block = m.substring(start, end);
        assertTrue(block.contains("sendlater.ui.SendLaterSheetActivity\""));
        assertTrue(block.contains("android:theme=\"@style/Theme.SendLater.Sheet\""));
        assertTrue(block.contains("adjustResize"));
        assertTrue("declares android:exported explicitly", block.contains("android:exported="));
    }

    @Test public void materialDependencyPinnedAfterRoom() throws IOException {
        String g = read(GRADLE);
        int room = g.indexOf("annotationProcessor 'androidx.room:room-compiler:2.6.1'");
        int material = g.indexOf("implementation 'com.google.android.material:material:1.12.0'");
        assertTrue("Room lines present", room >= 0);
        assertTrue("Material 1.12.0 pinned", material >= 0);
        assertTrue("Material line after the Room lines", material > room);
        Matcher other = Pattern.compile("com\\.google\\.android\\.material:material:(?!1\\.12\\.0')").matcher(g);
        assertFalse("only one Material version", other.find());
    }
}
