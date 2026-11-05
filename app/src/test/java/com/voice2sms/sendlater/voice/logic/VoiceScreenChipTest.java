package com.voice2sms.sendlater.voice.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * LIVE-F1 / OD13: the native new-conversation screen has no toolbar_title; exactly one recipient
 * chip with the compose box stands in for it, two or more chips are a group (no tab), and a
 * toolbar_title always wins over chips. Numbers are fictional 555-01xx; names are placeholders.
 */
public class VoiceScreenChipTest {

    private static final String VOICE = VoiceScreenSignature.VOICE_PACKAGE;
    private static final List<String> NO_CHIPS = Collections.<String>emptyList();
    private static final List<String> NONE = Collections.<String>emptyList();

    private static String classify(boolean title, int chips, boolean text, boolean button) {
        return VoiceScreenSignature.classify(title, chips, text, button, VOICE).name();
    }

    // ---------------------------------------------------------------- VoiceScreenSignature

    @Test public void chipIdIsAVoiceResourceId() {
        assertEquals(VOICE + ":id/chip", VoiceScreenSignature.ID_CHIP);
    }

    @Test public void oneChipWithComposeBoxIsHigh() {
        assertEquals("HIGH", classify(false, 1, true, true));
    }

    @Test public void oneChipWithoutComposeBoxIsNone() {
        assertEquals("NONE", classify(false, 1, false, true));
        assertEquals("NONE", classify(false, 1, true, false));
    }

    @Test public void oneChipInAnotherPackageIsNone() {
        assertEquals("NONE", VoiceScreenSignature.classify(false, 1, true, true, "com.voice2sms").name());
        assertEquals("NONE", VoiceScreenSignature.classify(false, 1, true, true, null).name());
    }

    @Test public void twoChipsAreAGroupNoTab() {
        assertEquals("NONE", classify(false, 2, true, true));
        assertEquals("NONE", classify(false, 3, true, true));
        assertNull(VoiceScreenSignature.title(null, Arrays.asList("(435) 555-0142", "(435) 555-0143")));
    }

    @Test public void noTitleNoChipIsNone() {
        assertEquals("NONE", classify(false, 0, true, true));
        assertNull(VoiceScreenSignature.title(null, NO_CHIPS));
        assertNull(VoiceScreenSignature.title(null, null));
    }

    @Test public void toolbarTitleStillHighWithoutChips() {
        assertEquals("HIGH", classify(true, 0, true, true));
    }

    @Test public void oneChipTextIsTheTitle() {
        assertEquals("(435) 555-0142",
                VoiceScreenSignature.title(null, Collections.singletonList("(435) 555-0142")));
        assertEquals("Test Contact", VoiceScreenSignature.title(null, Collections.singletonList("Test Contact")));
    }

    @Test public void nullChipTextIsBlankTitle() {
        assertEquals("", VoiceScreenSignature.title(null, Collections.<String>singletonList(null)));
    }

    @Test public void chipPlusToolbarTitleToolbarWins() {
        assertEquals("HIGH", classify(true, 1, true, true));
        assertEquals("Test Contact",
                VoiceScreenSignature.title("Test Contact", Collections.singletonList("(435) 555-0142")));
        assertEquals("Test Contact",
                VoiceScreenSignature.title("Test Contact", Arrays.asList("A", "B")));
    }

    // ---------------------------------------------------------------- RecipientResolver

    @Test public void oneNumberChipResolvesToE164() {
        RecipientResolver.Resolution r = RecipientResolver.resolveScreen(null,
                Collections.singletonList("(435) 555-0142"), NONE, NONE);
        assertEquals("RESOLVED", r.getKind().name());
        assertEquals("+14355550142", r.getE164());
    }

    @Test public void oneNumberChipResolvesLikeToolbarTitle() {
        List<String> contacts = Arrays.asList("+14355550101", "+14355550102");
        assertEquals(RecipientResolver.resolve("(435) 555-0142", contacts, NONE).getE164(),
                RecipientResolver.resolveScreen(null, Collections.singletonList("(435) 555-0142"),
                        contacts, NONE).getE164());
    }

    @Test public void oneNameChipTakesTheNamePath() {
        List<String> contacts = Arrays.asList("+14355550101", "+14355550102");
        List<String> directory = Collections.singletonList("+14355550102");
        RecipientResolver.Resolution r = RecipientResolver.resolveScreen(null,
                Collections.singletonList("Test Contact"), contacts, directory);
        assertEquals("RESOLVED", r.getKind().name());
        assertEquals("+14355550102", r.getE164());
        assertEquals("NEEDS_CHOICE", RecipientResolver.resolveScreen(null,
                Collections.singletonList("Test Contact"), contacts, NONE).getKind().name());
        assertEquals("NEEDS_ENTRY", RecipientResolver.resolveScreen(null,
                Collections.singletonList("Test Contact"), NONE, NONE).getKind().name());
    }

    @Test public void twoChipsHaveNoRecipient() {
        assertNull(RecipientResolver.resolveScreen(null,
                Arrays.asList("(435) 555-0142", "(435) 555-0143"), NONE, NONE));
    }

    @Test public void chipPlusToolbarTitleResolvesTheToolbarTitle() {
        RecipientResolver.Resolution r = RecipientResolver.resolveScreen("(435) 555-0150",
                Collections.singletonList("(435) 555-0142"), NONE, NONE);
        assertEquals("+14355550150", r.getE164());
    }
}
