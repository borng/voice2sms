package com.voice2sms.sendlater.voice.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.voice.logic.VoiceBubbleParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * Tab-tap snapshot (R3, R4, I3) through a fake node accessor.
 *
 * <p>Pinned API: {@code VoiceDraftSnapshot.capture(VoiceNodeAccessor nodes, long nowMs,
 * TimeZone zone, List<String> contacts, List<String> directory)} returns a snapshot with
 * {@code getDisplayName()} (= toolbar_title), {@code getDraftText()} (exact), {@code getCapturedAt()},
 * {@code getWatermark()} (VoiceBubbleParser bubble watermark) and {@code getCandidates()} (E.164 list
 * from RecipientResolver; one entry when resolved), or null when there is nothing to schedule
 * (draft field missing or empty, title missing). {@code toString()} never shows the draft or name.
 * Numbers below are fictional 555-01xx numbers.
 */
public class VoiceDraftSnapshotTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    private static final String NAME = "Alex Example";
    private static final String A = "+14355550101";
    private static final String B = "+14355550102";
    private static final String C = "+14355550103";

    private static long now() {
        Calendar c = Calendar.getInstance(ZONE);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 4, 20, 0, 0);
        return c.getTimeInMillis();
    }

    private static FakeVoiceNodes nodes(String title, String draft) {
        return new FakeVoiceNodes(new ArrayList<String>(), title, draft);
    }

    @Test public void capturesTitleExactDraftAndTime() {
        String draft = "  See you at 9 😀\nbring the café card  ";
        FakeVoiceNodes n = nodes(NAME, draft);
        long now = now();
        VoiceDraftSnapshot s = VoiceDraftSnapshot.capture(n, now, ZONE,
                Collections.<String>emptyList(), Collections.singletonList(A));
        assertNotNull(s);
        assertEquals(NAME, s.getDisplayName());
        assertEquals("draft is kept as exact Unicode, untrimmed", draft, s.getDraftText());
        assertEquals(now, s.getCapturedAt());
    }

    @Test public void watermarkFromVisibleBubblesIsTimestampQuality() {
        FakeVoiceNodes n = nodes(NAME, "hello");
        n.bubbles = new ArrayList<>(Arrays.asList(
                "Running late from Alex Example, 6:45 PM",
                "No worries from me, 6:46 PM"));
        long now = now();
        VoiceDraftSnapshot s = VoiceDraftSnapshot.capture(n, now, ZONE,
                Collections.<String>emptyList(), Collections.singletonList(A));
        assertNotNull(s);
        Watermark w = s.getWatermark();
        assertNotNull(w);
        assertEquals(Watermark.Quality.TIMESTAMP, w.getQuality());
        assertEquals(VoiceBubbleParser.bubbleWatermark(n.bubbles, now, ZONE), w);
        assertEquals(now, w.getCapturedAtDevice());
    }

    @Test public void noVisibleBubblesGivesDeviceOnlyWatermark() {
        FakeVoiceNodes n = nodes(NAME, "hello");
        long now = now();
        VoiceDraftSnapshot s = VoiceDraftSnapshot.capture(n, now, ZONE,
                Collections.<String>emptyList(), Collections.singletonList(A));
        assertNotNull(s);
        assertEquals(Watermark.Quality.DEVICE_ONLY, s.getWatermark().getQuality());
        assertEquals(now, s.getWatermark().getCapturedAtDevice());
    }

    @Test public void phoneNumberTitleResolvesToThatNumber() {
        VoiceDraftSnapshot s = VoiceDraftSnapshot.capture(nodes("(435) 555-0123", "hi"), now(), ZONE,
                Arrays.asList(A, B), Arrays.asList(C));
        assertNotNull(s);
        assertEquals("R4a: a title that parses as a number wins",
                Collections.singletonList("+14355550123"), s.getCandidates());
    }

    @Test public void nameResolvesThroughContactsAndDirectory() {
        VoiceDraftSnapshot s = VoiceDraftSnapshot.capture(nodes(NAME, "hi"), now(), ZONE,
                Arrays.asList(A, B), Arrays.asList(B, C));
        assertNotNull(s);
        assertEquals("R4b/c: the one number in both lists", Collections.singletonList(B), s.getCandidates());
    }

    @Test public void severalNumbersAreOfferedAsCandidates() {
        VoiceDraftSnapshot s = VoiceDraftSnapshot.capture(nodes(NAME, "hi"), now(), ZONE,
                Collections.<String>emptyList(), Arrays.asList(A, B));
        assertNotNull(s);
        List<String> c = s.getCandidates();
        assertEquals(2, c.size());
        assertTrue(c.contains(A) && c.contains(B));
    }

    @Test public void nameWithoutAnyNumberHasNoCandidates() {
        VoiceDraftSnapshot s = VoiceDraftSnapshot.capture(nodes(NAME, "hi"), now(), ZONE,
                Collections.<String>emptyList(), Collections.<String>emptyList());
        assertNotNull("the sheet still opens and asks for a number (R4d)", s);
        assertNotNull(s.getCandidates());
        assertTrue("I3: a display name alone is never a recipient", s.getCandidates().isEmpty());
    }

    @Test public void emptyOrMissingDraftGivesNoSnapshot() {
        assertNull(VoiceDraftSnapshot.capture(nodes(NAME, ""), now(), ZONE,
                Collections.<String>emptyList(), Collections.singletonList(A)));
        assertNull(VoiceDraftSnapshot.capture(nodes(NAME, null), now(), ZONE,
                Collections.<String>emptyList(), Collections.singletonList(A)));
    }

    @Test public void missingTitleGivesNoSnapshot() {
        assertNull(VoiceDraftSnapshot.capture(nodes(null, "hello"), now(), ZONE,
                Collections.<String>emptyList(), Collections.singletonList(A)));
    }

    @Test public void toStringRedactsDraftAndName() {
        VoiceDraftSnapshot s = VoiceDraftSnapshot.capture(nodes(NAME, "secret plan text"), now(), ZONE,
                Collections.<String>emptyList(), Collections.singletonList(A));
        assertNotNull(s);
        String t = String.valueOf(s);
        assertFalse("I7: snapshot toString leaks the draft", t.contains("secret plan text"));
        assertFalse("I7: snapshot toString leaks the name", t.contains(NAME));
        assertFalse("I7: snapshot toString leaks the number", t.contains(A));
    }
}
