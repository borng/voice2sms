package com.voice2sms.sendlater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.store.ThreadDirectory;
import com.voice2sms.sendlater.voice.overlay.PendingIndex;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * simplify-shared: the shared helpers return exactly what the per-class copies they replaced
 * returned. The "legacy" functions below are verbatim copies of the removed bodies.
 */
public class SimplifySharedHelpersTest {

    /** Fictional names only. */
    private static final List<String> NAMES = Arrays.asList(
            null, "", " ", "\t\n ", "\u0001",
            "Example Person", "  EXAMPLE   person ", "eXaMpLe\tPeRsOn",
            "Test-Contact (Work)", "O'Example, Jr.", "#1 Example!", "— dash —",
            "José Exemplár", "JOSÉ EXEMPLÁR", "Élodie Østlünd",
            "田中 太郎", "Иван Пример",
            "Example 😀 Person", " Example ", "́", "​Example");

    // ---- removed copies ----

    /** LiveConversationKeys.nameKey / ListenerEventRecorder.nameKey before simplify-shared. */
    private static String legacyListenerKey(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        return new ConversationRef(null, name).key();
    }

    /** PendingIndex.nameKey before simplify-shared (callers had already rejected blank names). */
    private static String legacyPendingKey(String name) {
        return new ConversationRef(null, name).key();
    }

    /** ThreadDirectory.normalizeName before simplify-shared. */
    private static String legacyDirectoryKey(String name) {
        return new ConversationRef(null, name).key().substring("name:".length());
    }

    /** SendLaterWebHooks.labelOf / VoiceOverlayController.labelOf core before simplify-shared. */
    private static String legacyLabel(String label, long at) {
        if (label != null && !label.trim().isEmpty()) return label;
        if (at <= 0) return "later";
        SimpleDateFormat f = new SimpleDateFormat("EEE h:mm a", Locale.getDefault());
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(at));
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ---- nameKey ----

    @Test public void nameKeyIsByteIdenticalToTheListenerCopies() {
        for (String n : NAMES) {
            assertEquals(String.valueOf(n), legacyListenerKey(n), ConversationRef.nameKey(n));
        }
    }

    @Test public void nameKeyIsByteIdenticalToPendingIndexForNonBlankNames() {
        for (String n : NAMES) {
            if (blank(n)) {
                assertNull(ConversationRef.nameKey(n));
                assertNull(PendingIndex.keyForTitle(n));
            } else {
                assertEquals(String.valueOf(n), legacyPendingKey(n), ConversationRef.nameKey(n));
                assertEquals(String.valueOf(n), legacyPendingKey(n), PendingIndex.keyForTitle(n));
            }
        }
    }

    @Test public void directoryKeysStayByteIdentical() {
        for (String n : NAMES) {
            assertEquals(String.valueOf(n), legacyDirectoryKey(n), ThreadDirectory.normalizeName(n));
        }
    }

    @Test public void nameKeyFoldsCaseWhitespaceAndDiacritics() {
        assertNull(ConversationRef.nameKey(null));
        assertNull(ConversationRef.nameKey(""));
        assertNull(ConversationRef.nameKey(" \t"));
        assertEquals("name:example person", ConversationRef.nameKey("  EXAMPLE \t person "));
        assertEquals("name:o'example, jr.", ConversationRef.nameKey("O'Example, Jr."));
        assertEquals("name:jose exemplar", ConversationRef.nameKey("José Exemplár"));
        assertEquals(ConversationRef.nameKey("José"), ConversationRef.nameKey("JOSÉ"));
        assertEquals("name:田中 太郎", ConversationRef.nameKey("田中  太郎"));
        assertEquals("", ThreadDirectory.normalizeName(" "));
        assertEquals("example person", ThreadDirectory.normalizeName("Example  PERSON"));
    }

    // ---- scheduled label ----

    @Test public void scheduledLabelMatchesTheRemovedLabelOfCopies() {
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        try {
            for (Locale l : Arrays.asList(Locale.US, Locale.GERMANY)) {
                Locale.setDefault(l);
                TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
                long at = 1794060000000L; // a Saturday morning in New York
                for (String label : Arrays.asList(null, "", "  ", "Sat 9:00 AM", " tomorrow ")) {
                    for (long t : new long[] {-1L, 0L, at}) {
                        assertEquals(label + "@" + t, legacyLabel(label, t),
                                ChipLabel.scheduledLabel(label, t, TimeZone.getDefault()));
                    }
                }
            }
            Locale.setDefault(Locale.US);
            assertEquals("later", ChipLabel.scheduledLabel(null, -1L, TimeZone.getDefault()));
            assertEquals(" tomorrow ", ChipLabel.scheduledLabel(" tomorrow ", 5L, TimeZone.getDefault()));
            String s = ChipLabel.scheduledLabel(" ", 1794060000000L, TimeZone.getTimeZone("America/New_York"));
            assertTrue(s, s.startsWith("Sat ") && s.endsWith("AM") && !s.contains(","));
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
        }
    }
}
