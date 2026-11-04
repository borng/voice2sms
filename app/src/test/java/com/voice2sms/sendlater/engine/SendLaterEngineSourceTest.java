package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.HOUR;
import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
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
 * Source-level guards for the engine node (R22, R24, R28, R29, I5, I7) and the pure pieces of
 * SendLaterNotifications / GvSession that run on the JVM. Paths are relative to the app module.
 */
public class SendLaterEngineSourceTest {

    private static final Path SENDLATER = Paths.get("src/main/java/com/voice2sms/sendlater");
    private static final Path ENGINE = SENDLATER.resolve("engine");
    private static final Path MANIFEST = Paths.get("src/main/AndroidManifest.xml");
    private static final Path APPLICATION = Paths.get("src/main/java/com/voice2sms/Voice2SmsApplication.java");

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static List<Path> javaUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    private static String allEngineSource() throws IOException {
        List<Path> files = javaUnder(ENGINE);
        assertFalse("engine package has no sources yet", files.isEmpty());
        StringBuilder sb = new StringBuilder();
        for (Path p : files) sb.append(read(p)).append('\n');
        return sb.toString();
    }

    private static void assertEngineFilesExist() {
        for (String f : new String[] {"SendLaterEngine.java", "SendLaterScheduler.java",
                "SendLaterNotifications.java", "SendLaterAlarmReceiver.java", "SendLaterBootReceiver.java",
                "SendLaterActionReceiver.java", "ScheduledSender.java", "GvSession.java"}) {
            assertTrue("missing engine/" + f, Files.exists(ENGINE.resolve(f)));
        }
    }

    /** Removes string and char literals and comments so identifiers can be checked. */
    private static String stripLiteralsAndComments(String s) {
        String noBlock = s.replaceAll("(?s)/\\*.*?\\*/", " ");
        String noLine = noBlock.replaceAll("//[^\\n]*", " ");
        return noLine.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("'(?:\\\\.|[^'\\\\])'", "' '");
    }

    // ------------------------------------------------------------------ manifest (R22)

    @Test public void manifestDeclaresEngineReceiversPermissionsInsideMarkedBlockAfterGeminiService() throws IOException {
        String m = read(MANIFEST);
        int start = m.indexOf("<!-- sendlater:engine start -->");
        int end = m.indexOf("<!-- sendlater:engine end -->");
        assertTrue("sendlater:engine start/end markers", start >= 0 && end > start);
        int gemini = m.indexOf(".GeminiSmsInterceptService");
        assertTrue(gemini >= 0);
        int geminiEnd = m.indexOf("</service>", gemini);
        assertTrue("engine block sits after the GeminiSmsInterceptService service element", start > geminiEnd);

        String block = m.substring(start, end);
        for (String r : new String[] {"SendLaterAlarmReceiver", "SendLaterBootReceiver", "SendLaterActionReceiver"}) {
            assertTrue("receiver " + r + " in the engine block", block.contains(r));
        }
        String boot = elementOf(block, "SendLaterBootReceiver");
        for (String a : new String[] {"android.intent.action.BOOT_COMPLETED", "android.intent.action.TIME_SET",
                "android.intent.action.TIMEZONE_CHANGED",
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"}) {
            assertTrue("boot receiver listens for " + a, boot.contains(a));
        }
        assertFalse("USER_UNLOCKED cannot be a manifest receiver", m.contains("USER_UNLOCKED"));
        assertTrue(elementOf(block, "SendLaterAlarmReceiver").contains("android:exported=\"false\""));
        assertTrue(elementOf(block, "SendLaterActionReceiver").contains("android:exported=\"false\""));

        assertTrue(m.contains("<uses-permission android:name=\"android.permission.SCHEDULE_EXACT_ALARM\""));
        assertTrue(m.contains("<uses-permission android:name=\"android.permission.RECEIVE_BOOT_COMPLETED\""));
        assertFalse("never declare USE_EXACT_ALARM", m.contains("USE_EXACT_ALARM"));
    }

    /** The receiver element (open tag to its close) naming {@code name}. */
    private static String elementOf(String xml, String name) {
        int at = xml.indexOf(name);
        assertTrue("element " + name, at >= 0);
        int open = xml.lastIndexOf("<receiver", at);
        int close = xml.indexOf("</receiver>", at);
        int nextOpen = xml.indexOf("<receiver", at);
        if (close < 0 || (nextOpen >= 0 && nextOpen < close)) {
            close = xml.indexOf("/>", at);
        }
        assertTrue(open >= 0 && close > open);
        return xml.substring(open, close);
    }

    // ------------------------------------------------------------------ alarms (R22)

    @Test public void schedulerUsesExactAlarmsWhenPermittedAndInexactOtherwise() throws IOException {
        assertEngineFilesExist();
        String src = allEngineSource();
        assertTrue(src.contains("setExactAndAllowWhileIdle"));
        assertTrue(src.contains("canScheduleExactAlarms"));
        assertTrue("inexact fallback", src.contains("setAndAllowWhileIdle"));
        assertTrue("SecurityException from the exact call falls back", src.contains("SecurityException"));
        assertFalse("no WorkManager for WAIT", src.contains("WorkManager"));
        assertTrue("WAIT uses the default network callback", src.contains("registerDefaultNetworkCallback"));
    }

    @Test public void receiversRouteToTheEngine() throws IOException {
        assertEngineFilesExist();
        assertTrue(read(ENGINE.resolve("SendLaterAlarmReceiver.java")).contains("onDue("));
        String boot = read(ENGINE.resolve("SendLaterBootReceiver.java"));
        assertTrue(boot.contains("reregisterAll("));
        String action = read(ENGINE.resolve("SendLaterActionReceiver.java"));
        for (String a : new String[] {"ACTION_SEND_ANYWAY", "ACTION_SNOOZE", "ACTION_CANCEL", "ACTION_EDIT"}) {
            assertTrue("action receiver handles " + a, action.contains(a));
        }
    }

    @Test public void stateChangedBroadcastIsPackageScopedWithMessageId() throws IOException {
        assertEngineFilesExist();
        String src = allEngineSource();
        assertTrue(src.contains("ACTION_STATE_CHANGED"));
        assertTrue(src.contains("EXTRA_MESSAGE_ID"));
        assertTrue("app-internal broadcasts are package-scoped", src.contains("setPackage("));
    }

    @Test public void everyPendingIntentUnderSendLaterIsImmutable() throws IOException {
        assertEngineFilesExist();
        Pattern pi = Pattern.compile("PendingIntent\\.get(Broadcast|Activity|Service|ForegroundService)\\s*\\(");
        int seen = 0;
        for (Path p : javaUnder(SENDLATER)) {
            String src = stripLiteralsAndComments(read(p));
            Matcher mm = pi.matcher(src);
            while (mm.find()) {
                seen++;
                int semi = src.indexOf(';', mm.end());
                String call = src.substring(mm.start(), semi < 0 ? src.length() : semi);
                assertTrue(p + ": PendingIntent without FLAG_IMMUTABLE: " + call, call.contains("FLAG_IMMUTABLE"));
            }
        }
        assertTrue("alarms and notification actions use PendingIntents", seen > 0);
    }

    // ------------------------------------------------------------------ remind / review mode (R29)

    @Test public void noForceAutoSendAnywhereUnderSendLaterAndRemindOpensReviewMode() throws IOException {
        assertEngineFilesExist();
        for (Path p : javaUnder(SENDLATER)) {
            String src = read(p);
            assertFalse(p + " uses EXTRA_FORCE_AUTO_SEND", src.contains("EXTRA_FORCE_AUTO_SEND"));
            assertFalse(p + " uses force_auto_send", src.contains("force_auto_send"));
        }
        String n = read(ENGINE.resolve("SendLaterNotifications.java"));
        assertTrue("remind opens GVoiceWebViewActivity", n.contains("GVoiceWebViewActivity"));
        assertTrue("review mode gets the recipient extra", n.contains("\"recipient\""));
        assertTrue("review mode gets the body extra", n.contains("\"body\""));
    }

    @Test public void lastCallUsesChronometerCountdownAndChannelsAreLazy() throws IOException {
        assertEngineFilesExist();
        String n = read(ENGINE.resolve("SendLaterNotifications.java"));
        assertTrue(n.contains("setUsesChronometer"));
        assertTrue(n.contains("setChronometerCountDown"));
        assertTrue("own channels created inside SendLaterNotifications", n.contains("createNotificationChannel"));
        assertFalse("channels are not created in Application",
                read(APPLICATION).contains("SendLaterNotifications"));
    }

    @Test public void sendAnywayLabelShowsHowLatePastBudget() {
        assertEquals("Send anyway", SendLaterNotifications.sendAnywayLabel(message(1).build(), T + 5 * MIN));
        assertEquals("Send anyway (3 h late)", SendLaterNotifications.sendAnywayLabel(message(1).build(), T + 3 * HOUR));
    }

    // ------------------------------------------------------------------ logging (I7)

    @Test public void noMessageTextInLogCallsUnderSendLater() throws IOException {
        assertEngineFilesExist();
        Pattern log = Pattern.compile("\\bLog\\.(v|d|i|w|e|wtf)\\s*\\(");
        Pattern banned = Pattern.compile("\\b(getText|getRecipientDisplayName|getDisplayName|text|body|draft|draftText"
                + "|displayName|preview)\\b");
        for (Path p : javaUnder(SENDLATER)) {
            String src = stripLiteralsAndComments(read(p));
            Matcher m = log.matcher(src);
            while (m.find()) {
                int semi = src.indexOf(';', m.end());
                String call = src.substring(m.start(), semi < 0 ? src.length() : semi);
                Matcher b = banned.matcher(call);
                assertFalse(p + " logs message text / names: " + call, b.find());
            }
        }
    }

    // ------------------------------------------------------------------ sender seam (R31)

    @Test public void onlyTheEngineDrivesSendTransitions() throws IOException {
        assertEngineFilesExist();
        Pattern write = Pattern.compile("\\.(markInFlight|markSentVerified|releaseForRetry)\\s*\\(");
        for (Path p : javaUnder(SENDLATER)) {
            String rel = SENDLATER.relativize(p).toString().replace('\\', '/');
            if (rel.startsWith("store/") || rel.startsWith("engine/")) continue;
            String src = stripLiteralsAndComments(read(p));
            assertFalse(p + ": only SendLaterEngine (via onSendOutcome) changes send status",
                    write.matcher(src).find());
        }
        String sender = stripLiteralsAndComments(read(ENGINE.resolve("ScheduledSender.java")));
        assertTrue(Pattern.compile("boolean\\s+start\\s*\\(").matcher(sender).find());
    }

    // ------------------------------------------------------------------ process start (R23)

    @Test public void applicationCallsOnProcessStartExactlyOnce() throws IOException {
        String app = read(APPLICATION);
        int first = app.indexOf("SendLaterEngine.onProcessStart(");
        assertTrue("Voice2SmsApplication.onCreate calls SendLaterEngine.onProcessStart", first >= 0);
        assertEquals("exactly one call", first, app.lastIndexOf("SendLaterEngine.onProcessStart("));
    }

    // ------------------------------------------------------------------ GvSession

    @Test public void gvSessionChecksSidCookieAndIsFalseOnAnyException() throws IOException {
        assertEngineFilesExist();
        String src = read(ENGINE.resolve("GvSession.java"));
        assertTrue(src.contains("CookieManager"));
        assertTrue(src.contains("SID"));
        assertTrue(src.contains("https://voice.google.com"));
        // On the JVM CookieManager is an unmocked stub that throws: hasWebSession must swallow it.
        assertFalse(GvSession.hasWebSession(null));
    }
}
