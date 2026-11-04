package com.voice2sms.sendlater.domain;

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

/** The domain layer is pure JVM: nothing under sendlater/domain may touch android.* or androidx.*. */
public class DomainPurityTest {

    // Gradle runs :app:test with cwd=app/.
    private static final Path DOMAIN = Paths.get("src/main/java/com/voice2sms/sendlater/domain");
    private static final Pattern ANDROID_REF = Pattern.compile("\\bandroidx?\\.[a-zA-Z_]");
    private static final String[] EXPECTED = {
            "ScheduledMessage.java", "Watermark.java", "ThreadEvent.java", "ThreadEventSource.java",
            "ThreadEventClassifier.java", "LadderEvaluator.java", "LatenessBudget.java",
            "PresetSlots.java", "ChipLabel.java", "ConversationRef.java",
    };

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    @Test
    public void domainSourcesExist() {
        assertTrue("missing " + DOMAIN, Files.isDirectory(DOMAIN));
        for (String f : EXPECTED) {
            assertTrue("missing " + DOMAIN.resolve(f), Files.exists(DOMAIN.resolve(f)));
        }
    }

    @Test
    public void noDomainClassReferencesAndroid() throws IOException {
        assertTrue("missing " + DOMAIN, Files.isDirectory(DOMAIN));
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DOMAIN, "*.java")) {
            for (Path p : ds) files.add(p);
        }
        assertFalse("no sources under " + DOMAIN, files.isEmpty());
        for (Path p : files) {
            String src = stripComments(new String(Files.readAllBytes(p), "UTF-8"));
            assertFalse(p.getFileName() + " references android.*/androidx.*", ANDROID_REF.matcher(src).find());
        }
    }
}
