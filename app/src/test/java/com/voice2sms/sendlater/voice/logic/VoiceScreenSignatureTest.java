package com.voice2sms.sendlater.voice.logic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** R2: a Voice thread screen needs the Voice package plus all three ids; anything less is NONE. */
public class VoiceScreenSignatureTest {

    private static final String VOICE = "com.google.android.apps.googlevoice";

    private static String classify(boolean title, boolean text, boolean button, String pkg) {
        return VoiceScreenSignature.classify(title, text, button, pkg).name();
    }

    @Test
    public void allThreeIdsInVoicePackageIsHigh() {
        assertEquals("HIGH", classify(true, true, true, VOICE));
    }

    @Test
    public void missingToolbarTitleIsNone() {
        assertEquals("NONE", classify(false, true, true, VOICE));
    }

    @Test
    public void missingSendTextIsNone() {
        assertEquals("NONE", classify(true, false, true, VOICE));
    }

    @Test
    public void missingSendButtonIsNone() {
        assertEquals("NONE", classify(true, true, false, VOICE));
    }

    @Test
    public void nothingPresentIsNone() {
        assertEquals("NONE", classify(false, false, false, VOICE));
    }

    @Test
    public void otherPackageWithAllIdsIsNone() {
        assertEquals("NONE", classify(true, true, true, "com.google.android.apps.messaging"));
        assertEquals("NONE", classify(true, true, true, "com.voice2sms"));
    }

    @Test
    public void nullPackageIsNone() {
        assertEquals("NONE", classify(true, true, true, null));
    }
}
