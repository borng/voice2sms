package com.voice2sms.sendlater.voice.logic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.Test;

/** sendlater/voice/logic is pure JVM: no android.* / androidx.* anywhere in it. */
public class VoiceLogicPurityTest {

    // Gradle runs :app:test with cwd=app/.
    private static final Path LOGIC = Paths.get("src/main/java/com/voice2sms/sendlater/voice/logic");
    private static final Pattern ANDROID_REF = Pattern.compile("\\bandroidx?\\.[a-zA-Z_]");
    private static final String[] EXPECTED = {
            "VoiceScreenSignature.java", "VoiceBubbleParser.java", "RecipientResolver.java",
            "PhoneNumbers.java", "UndoGuard.java", "ConversationChanged.java",
    };

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @Test
    public void logicSourcesExist() {
        assertTrue("missing " + LOGIC, Files.isDirectory(LOGIC));
        for (String f : EXPECTED) {
            assertTrue("missing " + LOGIC.resolve(f), Files.exists(LOGIC.resolve(f)));
        }
    }

    @Test
    public void noLogicClassReferencesAndroid() throws IOException {
        assertTrue("missing " + LOGIC, Files.isDirectory(LOGIC));
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(LOGIC, "*.java")) {
            for (Path p : ds) files.add(p);
        }
        assertFalse("no sources under " + LOGIC, files.isEmpty());
        for (Path p : files) {
            String src = stripComments(new String(Files.readAllBytes(p), "UTF-8"));
            assertFalse(p.getFileName() + " references android.*/androidx.*", ANDROID_REF.matcher(src).find());
        }
    }
}
