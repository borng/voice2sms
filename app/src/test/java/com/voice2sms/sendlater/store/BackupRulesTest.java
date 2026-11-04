package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * dl-privacy (OD31, whole-branch review M1): sendlater.db (+ -wal/-shm/-journal) and every
 * sendlater_* SharedPreferences file are excluded from cloud backup and device transfer
 * (API 31+, dataExtractionRules) and from full backup (API 30 and older, fullBackupContent).
 * allowBackup stays on for the rest of the app.
 */
public class BackupRulesTest {

    private static final Path MANIFEST = Paths.get("src/main/AndroidManifest.xml");
    private static final Path FULL = Paths.get("src/main/res/xml/sendlater_backup_rules.xml");
    private static final Path EXTRACT = Paths.get("src/main/res/xml/sendlater_data_extraction_rules.xml");
    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    private static final String[] DB_FILES = {"sendlater.db", "sendlater.db-wal", "sendlater.db-shm"};

    private static String read(Path p) throws IOException {
        assertTrue("missing: " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), "UTF-8");
    }

    private static String applicationTag() throws IOException {
        String m = read(MANIFEST);
        Matcher tag = Pattern.compile("<application\\s").matcher(m);
        assertTrue(tag.find());
        int at = tag.start();
        return m.substring(at, m.indexOf('>', at));
    }

    /** Every SharedPreferences file name the app's sources declare under the sendlater_ prefix. */
    static TreeSet<String> sendLaterPrefsFiles() throws IOException {
        TreeSet<String> out = new TreeSet<>();
        Pattern constant = Pattern.compile("static final String [A-Z_]*PREFS[A-Z_]*\\s*=\\s*\"(sendlater_[a-z0-9_]+)\"");
        Pattern inline = Pattern.compile("getSharedPreferences\\(\\s*\"(sendlater_[a-z0-9_]+)\"");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN_JAVA)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        for (Path p : files) {
            String src = read(p);
            for (Pattern pat : new Pattern[] {constant, inline}) {
                Matcher m = pat.matcher(src);
                while (m.find()) out.add(m.group(1));
            }
        }
        return out;
    }

    private static void assertExcludes(String section, String where) throws IOException {
        for (String db : DB_FILES) {
            assertTrue(where + " must exclude database " + db,
                    section.contains("<exclude domain=\"database\" path=\"" + db + "\" />"));
        }
        for (String prefs : sendLaterPrefsFiles()) {
            assertTrue(where + " must exclude prefs " + prefs,
                    section.contains("<exclude domain=\"sharedpref\" path=\"" + prefs + ".xml\" />"));
        }
        assertFalse(where + " must not use <include> (that would drop the rest of the app)",
                section.contains("<include"));
    }

    @Test public void manifestKeepsAllowBackupAndPointsAtBothRuleFiles() throws IOException {
        String app = applicationTag();
        assertTrue(app.contains("android:allowBackup=\"true\""));
        assertTrue(app.contains("android:dataExtractionRules=\"@xml/sendlater_data_extraction_rules\""));
        assertTrue(app.contains("android:fullBackupContent=\"@xml/sendlater_backup_rules\""));
    }

    @Test public void databaseNameMatchesTheRules() {
        assertEquals("sendlater.db", SendLaterDatabase.NAME);
    }

    @Test public void prefsScanFindsTheKnownFiles() throws IOException {
        TreeSet<String> found = sendLaterPrefsFiles();
        for (String known : new String[] {"sendlater_engine", "sendlater_queue", "sendlater_listener",
                "sendlater_battery", "sendlater_settings_asked", "sendlater_retention"}) {
            assertTrue("scan should find " + known + " (found " + found + ")", found.contains(known));
        }
    }

    @Test public void fullBackupContentExcludesSendLaterData() throws IOException {
        String x = read(FULL);
        assertTrue(x.contains("<full-backup-content>"));
        assertExcludes(x, "fullBackupContent");
    }

    @Test public void dataExtractionRulesExcludeFromCloudAndDeviceTransfer() throws IOException {
        String x = read(EXTRACT);
        assertTrue(x.contains("<data-extraction-rules>"));
        int c0 = x.indexOf("<cloud-backup>"), c1 = x.indexOf("</cloud-backup>");
        int d0 = x.indexOf("<device-transfer>"), d1 = x.indexOf("</device-transfer>");
        assertTrue("both sections present", c0 > 0 && c1 > c0 && d0 > 0 && d1 > d0);
        assertExcludes(x.substring(c0, c1), "cloud-backup");
        assertExcludes(x.substring(d0, d1), "device-transfer");
    }
}
