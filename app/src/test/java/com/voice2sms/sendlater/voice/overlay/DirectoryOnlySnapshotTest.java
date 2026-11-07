package com.voice2sms.sendlater.voice.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.TimeZone;

/**
 * deliver sec-M1: the native capture keeps a directory-only number unconfirmed (no EXTRA_E164;
 * EXTRA_RECIPIENT_UNCONFIRMED) so the sheet asks for it. Fictional numbers; placeholder name.
 */
public class DirectoryOnlySnapshotTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    private static final String NAME = "Alex Example";
    private static final String A = "+14355550101";

    private static FakeVoiceNodes nodes() {
        return new FakeVoiceNodes(new ArrayList<String>(), NAME, "hello");
    }

    @Test
    public void directoryOnlyNumberIsACandidateNotTheRecipient() {
        VoiceDraftSnapshot s = VoiceDraftSnapshot.capture(nodes(), 1000L, ZONE,
                Collections.<String>emptyList(), Collections.singletonList(A));
        assertNotNull(s);
        assertEquals(Collections.singletonList(A), s.getCandidates());
        assertNull(s.getE164());
        assertTrue(s.needsRecipientConfirm());
    }

    @Test
    public void contactConfirmedNumberIsResolved() {
        VoiceDraftSnapshot s = VoiceDraftSnapshot.capture(nodes(), 1000L, ZONE,
                Collections.singletonList(A), Collections.singletonList(A));
        assertEquals(A, s.getE164());
        assertFalse(s.needsRecipientConfirm());
        VoiceDraftSnapshot none = VoiceDraftSnapshot.capture(nodes(), 1000L, ZONE,
                Collections.<String>emptyList(), Collections.<String>emptyList());
        assertNull(none.getE164());
        assertFalse("no candidate: number entry, not a confirmation", none.needsRecipientConfirm());
    }

    @Test
    public void overlayFlagsUnconfirmedCandidatesForTheSheet() throws IOException {
        String c = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/voice/overlay/VoiceOverlayController.java")),
                StandardCharsets.UTF_8);
        assertTrue(c.contains("if (snap.needsRecipientConfirm()) i.putExtra(SendLaterIntents.EXTRA_RECIPIENT_UNCONFIRMED, true);"));
        String s = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/ui/SendLaterSheetActivity.java")), StandardCharsets.UTF_8);
        int read = s.indexOf("getBooleanExtra(SendLaterIntents.EXTRA_RECIPIENT_UNCONFIRMED, false)");
        assertTrue("the sheet reads the flag", read > 0);
        assertTrue("and passes it to forCapture",
                s.indexOf("SheetViewModelLogic.forCapture(givenNumber, candidates, unconfirmed,") > read);
    }
}
