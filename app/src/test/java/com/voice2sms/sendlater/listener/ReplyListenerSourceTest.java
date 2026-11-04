package com.voice2sms.sendlater.listener;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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
 * Source-level guards for the reply-listener node (R32-R34, I7): files and contracts exist, the
 * manifest declares the listener in its marked block, and no Log.* call in the listener package
 * passes notification content. Paths are relative to the app module.
 */
public class ReplyListenerSourceTest {

    private static final Path LISTENER = Paths.get("src/main/java/com/voice2sms/sendlater/listener");
    private static final Path MANIFEST = Paths.get("src/main/AndroidManifest.xml");
    private static final Path VALUES = Paths.get("src/main/res/values");

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

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static String stripStrings(String src) {
        return src.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    @Test public void listenerFilesExist() {
        for (String f : new String[] {"SendLaterNotificationListener.java", "NotificationParser.java",
                "NotificationFields.java", "ParsedEvent.java", "NotificationThreadEventSource.java",
                "SendLaterListenerStatus.java"}) {
            assertTrue("missing listener/" + f, Files.exists(LISTENER.resolve(f)));
        }
    }

    @Test public void listenerServiceExtendsNotificationListenerService() throws IOException {
        String src = stripComments(read(LISTENER.resolve("SendLaterNotificationListener.java")));
        assertTrue(src.matches("(?s).*class\\s+SendLaterNotificationListener\\s+extends\\s+"
                + "(android\\.service\\.notification\\.)?NotificationListenerService\\b.*"));
        assertFalse("pure-JVM parser must not touch android.*",
                read(LISTENER.resolve("NotificationParser.java")).contains("import android."));
    }

    @Test public void statusHelperHasIsEnabledAndOpenSettingsIntent() throws IOException {
        String src = stripComments(read(LISTENER.resolve("SendLaterListenerStatus.java")));
        assertTrue("isEnabled(Context)",
                src.matches("(?s).*public\\s+static\\s+boolean\\s+isEnabled\\s*\\(\\s*(android\\.content\\.)?Context\\b.*"));
        assertTrue("openSettingsIntent()",
                src.matches("(?s).*public\\s+static\\s+(android\\.content\\.)?Intent\\s+openSettingsIntent\\s*\\(\\s*\\).*"));
        assertTrue("isEnabled reads enabled_notification_listeners or the compat helper",
                src.contains("enabled_notification_listeners") || src.contains("getEnabledListenerPackages")
                        || src.contains("ENABLED_NOTIFICATION_LISTENERS"));
    }

    @Test public void statusSummaryTextIsPinned() throws IOException {
        String needle = "Needs the Google Voice app with notifications on";
        StringBuilder all = new StringBuilder();
        for (Path p : javaUnder(LISTENER)) all.append(read(p));
        if (Files.isDirectory(VALUES)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(VALUES, "sendlater_*.xml")) {
                for (Path p : ds) all.append(read(p));
            }
        }
        assertTrue("status summary text missing", all.toString().contains(needle));
        assertFalse("never edit res/values/strings.xml", read(VALUES.resolve("strings.xml")).contains(needle));
    }

    @Test public void notificationSourceImplementsThreadEventSource() throws IOException {
        String src = stripComments(read(LISTENER.resolve("NotificationThreadEventSource.java")));
        assertTrue(src.matches("(?s).*class\\s+NotificationThreadEventSource\\s+implements\\s+"
                + "(com\\.voice2sms\\.sendlater\\.domain\\.)?ThreadEventSource\\b.*"));
    }

    @Test public void manifestDeclaresListenerInMarkedBlockAfterWearService() throws IOException {
        String mf = read(MANIFEST);
        int start = mf.indexOf("<!-- sendlater:reply-listener start -->");
        int end = mf.indexOf("<!-- sendlater:reply-listener end -->");
        assertTrue("manifest block markers", start >= 0 && end > start);
        int wear = mf.indexOf(".wear.WearSmsListenerService");
        assertTrue("block sits after the WearSmsListenerService element", wear >= 0 && start > wear);
        int wearClose = mf.indexOf("</service>", wear);
        assertTrue("block starts after the Wear service element closes", wearClose >= 0 && start > wearClose);

        String block = mf.substring(start, end);
        assertTrue(block.contains("sendlater.listener.SendLaterNotificationListener"));
        assertTrue(block.contains("android:permission=\"android.permission.BIND_NOTIFICATION_LISTENER_SERVICE\""));
        assertTrue(block.contains("android:exported=\"true\""));
        assertTrue(block.contains("android.service.notification.NotificationListenerService"));
    }

    private static final Pattern LOG_CALL =
            Pattern.compile("\\bLog\\s*\\.\\s*(v|d|i|w|e|wtf)\\s*\\(([^;]*)\\)\\s*;");
    private static final Pattern CONTENT = Pattern.compile(
            "(?i)\\b(text|title|body|conversationTitle|extras|getCharSequence|getString|messages?|sender|"
                    + "number|e164|name|displayName|getText|getTitle|getConversationTitle|getNumber|"
                    + "getSender|tickerText|EXTRA_[A-Z_]+)\\b");

    @Test public void noNotificationContentInLogCalls() throws IOException {
        List<Path> files = javaUnder(LISTENER);
        assertFalse("listener package has no sources yet", files.isEmpty());
        for (Path p : files) {
            String src = stripStrings(stripComments(read(p)));
            Matcher m = LOG_CALL.matcher(src);
            while (m.find()) {
                Matcher bad = CONTENT.matcher(m.group(2));
                assertFalse("I7: " + p.getFileName() + " logs notification content: " + m.group(0), bad.find());
            }
            assertFalse(p + ": no System.out / printStackTrace",
                    src.contains("System.out") || src.contains("System.err") || src.contains("printStackTrace"));
        }
    }

    @Test public void listenerNeverUsesForceAutoSendOrRateLimiter() throws IOException {
        List<Path> files = javaUnder(LISTENER);
        assertFalse("listener package has no sources yet", files.isEmpty());
        for (Path p : files) {
            String src = read(p);
            assertFalse(p + " uses force_auto_send", src.contains("force_auto_send") || src.contains("EXTRA_FORCE_AUTO_SEND"));
            assertFalse(p + " consults AutoSendRateLimiter", src.contains("AutoSendRateLimiter"));
        }
    }
}
