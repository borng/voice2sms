package com.voice2sms.sendlater;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * dl-privacy (whole-branch review L2): the adb-only Send Later debug hooks live in the debug
 * source set, so the release APK does not contain them (nor DEBUG_FIRE's send path). Runs for
 * both unit-test variants; the hooks' own tests live in src/testDebug.
 */
public class DebugHooksLocationTest {

    private static final Path MAIN_DEBUG_PKG = Paths.get("src/main/java/com/voice2sms/sendlater/debug");
    private static final Path DEBUG_PKG = Paths.get("src/debug/java/com/voice2sms/sendlater/debug");
    private static final String[] HOOKS = {"SendLaterDebugReceiver.java", "SendLaterDebugCommands.java",
            "DebugBackend.java", "DebugDump.java"};

    @Test public void hooksLiveOnlyInTheDebugSourceSet() {
        assertFalse("src/main must not contain sendlater/debug", Files.exists(MAIN_DEBUG_PKG));
        for (String f : HOOKS) assertTrue("missing debug hook " + f, Files.exists(DEBUG_PKG.resolve(f)));
        assertFalse("no release source set copy", Files.exists(Paths.get("src/release/java/com/voice2sms/sendlater/debug")));
    }

    @Test public void mainSourcesNeverReferenceTheDebugPackage() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Paths.get("src/main/java"))) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        for (Path p : files) {
            String src = new String(Files.readAllBytes(p), "UTF-8");
            assertFalse(p + " references com.voice2sms.sendlater.debug (release would not compile)",
                    src.contains("com.voice2sms.sendlater.debug"));
        }
        String manifest = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")), "UTF-8");
        assertFalse(manifest.contains("sendlater.debug"));
    }
}
