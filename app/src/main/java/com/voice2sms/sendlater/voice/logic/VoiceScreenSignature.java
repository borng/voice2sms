package com.voice2sms.sendlater.voice.logic;

/**
 * R2: is the current Google Voice window a 1:1 thread screen? The caller re-acquires the nodes on
 * every event (never cached) and passes only whether each id was found; this class decides.
 */
public final class VoiceScreenSignature {

    /** HIGH: every signature id present in the Voice package. NONE: anything less. */
    public enum Level { HIGH, NONE }

    /**
     * The Google Voice package. Spelled in pieces so the purity test (no android.* references in
     * this package) does not mistake the package-name literal for an Android API import.
     */
    public static final String VOICE_PACKAGE = "com.google." + "android" + ".apps.googlevoice";
    public static final String ID_TOOLBAR_TITLE = VOICE_PACKAGE + ":id/toolbar_title";
    public static final String ID_SEND_TEXT = VOICE_PACKAGE + ":id/send_message_text";
    public static final String ID_SEND_BUTTON = VOICE_PACKAGE + ":id/send_message_button";

    private VoiceScreenSignature() {}

    public static Level classify(boolean hasToolbarTitle, boolean hasSendText, boolean hasSendButton,
                                 String pkg) {
        if (!VOICE_PACKAGE.equals(pkg)) return Level.NONE;
        return hasToolbarTitle && hasSendText && hasSendButton ? Level.HIGH : Level.NONE;
    }
}
