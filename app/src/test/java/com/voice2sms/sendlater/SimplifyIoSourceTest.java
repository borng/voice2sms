package com.voice2sms.sendlater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** simplify-io items 2-4: source guards for the I/O cleanups that need a device to run. */
public class SimplifyIoSourceTest {

    private static String read(String rel) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/com/voice2sms/sendlater/" + rel)),
                StandardCharsets.UTF_8);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    @Test public void settingsGettersDoNotCopyTheWholeMap() throws Exception {
        assertFalse("no prefs.getAll() call", read("SendLaterSettings.java").contains(").getAll()"));
    }

    @Test public void upcomingReadsPendingOncePerReload() throws Exception {
        String u = read("ui/UpcomingActivity.java");
        int start = u.indexOf("private void reload()");
        String body = u.substring(start, u.indexOf("private static void addAll(", start));
        assertEquals(1, count(body, "repo.allPending()"));
        assertTrue(body.contains("addAll(all, pending);"));
        assertTrue(body.contains("pending == null ? 0 : pending.size()"));
    }

    @Test public void contactLookupRemembersMissingAlternativeColumn() throws Exception {
        String c = read("listener/AndroidContactNames.java");
        assertTrue(c.contains("private static volatile boolean alternativeUnsupported;"));
        assertTrue(c.contains("alternativeUnsupported ? null"));
        assertTrue("set only after the primary-only retry worked",
                c.contains("if (out != null) alternativeUnsupported = true;"));
    }
}
