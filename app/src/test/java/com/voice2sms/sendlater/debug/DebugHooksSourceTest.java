package com.voice2sms.sendlater.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-level guards for the debug-only Send Later hooks (debug-smoke node, D5, I9).
 * Paths are relative to the app module (Gradle's unit-test working directory).
 */
public class DebugHooksSourceTest {

    private static final Path PKG = Paths.get("src/main/java/com/voice2sms/sendlater/debug");
    private static final Path RECEIVER = PKG.resolve("SendLaterDebugReceiver.java");
    private static final Path COMMANDS = PKG.resolve("SendLaterDebugCommands.java");
    private static final Path MAIN_MANIFEST = Paths.get("src/main/AndroidManifest.xml");
    private static final Path DEBUG_MANIFEST = Paths.get("src/debug/AndroidManifest.xml");
    private static final Path RELEASE_DIR = Paths.get("src/release");

    private static String read(Path p) throws IOException {
        assertTrue("missing: " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), "UTF-8");
    }

    @Test public void receiverDeclaredOnlyInDebugManifest() throws IOException {
        assertFalse("main manifest must not declare the debug receiver",
                read(MAIN_MANIFEST).contains("SendLaterDebugReceiver"));
        String debug = read(DEBUG_MANIFEST);
        assertTrue(debug.contains("com.voice2sms.sendlater.debug.SendLaterDebugReceiver"));
        assertTrue("debug receiver must require the signature-level DUMP permission",
                debug.contains("android:permission=\"android.permission.DUMP\""));
        Path releaseManifest = RELEASE_DIR.resolve("AndroidManifest.xml");
        if (Files.exists(releaseManifest)) {
            assertFalse(read(releaseManifest).contains("SendLaterDebugReceiver"));
        }
    }

    @Test public void onReceive_returnsFirstWhenNotDebug() throws IOException {
        String src = read(RECEIVER);
        Matcher m = Pattern.compile("public void onReceive\\([^)]*\\)\\s*\\{\\s*([^\\n]*)").matcher(src);
        assertTrue("onReceive not found", m.find());
        assertEquals("first statement of onReceive", "if (!BuildConfig.DEBUG) return;", m.group(1).trim());
    }

    @Test public void onlyTheBackendCallsEngineOnDue() throws IOException {
        String receiver = read(RECEIVER);
        assertEquals("SendLaterEngine.onDue must appear exactly once (AndroidBackend.engineOnDue)",
                1, count(receiver, "SendLaterEngine.onDue("));
        assertTrue(receiver.contains("public void engineOnDue(long id) { SendLaterEngine.onDue(app, id); }"));
        String commands = read(COMMANDS);
        assertFalse(commands.contains("SendLaterEngine"));
        assertEquals("engineOnDue is called from fire() only", 1, count(commands, "b.engineOnDue("));
        int fire = commands.indexOf("String fire(");
        int guard = commands.indexOf("if (!sendAllowed(allowSend, allowedRecipient, m.getRecipientE164()))", fire);
        int call = commands.indexOf("b.engineOnDue(", fire);
        assertTrue("guard must come before the engine call inside fire()", fire > 0 && guard > fire && call > guard);
    }

    @Test public void evaluateCallsNoMutator() throws IOException {
        String commands = read(COMMANDS);
        int start = commands.indexOf("String evaluate(long id)");
        int end = commands.indexOf("String fire(", start);
        assertTrue(start > 0 && end > start);
        String body = commands.substring(start, end);
        for (String mutator : new String[] {"b.hold(", "b.cancelAlarms(", "b.engineOn", "b.makeDueNow(",
                "b.schedule(", "b.registerAlarms(", "b.recordInbound("}) {
            assertFalse("evaluate() must not call " + mutator, body.contains(mutator));
        }
    }

    @Test public void debugPackageNeverUsesAutoSendShortcuts() throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(PKG)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String src = read(p);
                for (String banned : new String[] {"force_auto_send", "EXTRA_FORCE_AUTO_SEND",
                        "AutoSendRateLimiter", "setSender(", "onSendAnyway(", "onSendOutcome("}) {
                    assertFalse(p + " must not use " + banned, src.contains(banned));
                }
                assertFalse(p + " must not log intent text extras",
                        Pattern.compile("Log\\.[a-z]\\([^;]*EXTRA_TEXT").matcher(src).find());
            }
        }
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }
}
