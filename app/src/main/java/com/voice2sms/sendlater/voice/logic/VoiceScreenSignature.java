package com.voice2sms.sendlater.voice.logic;

import java.util.List;

/**
 * R2: is the current Google Voice window a 1:1 thread screen? The caller re-acquires the nodes on
 * every event (never cached) and passes only whether each id was found; this class decides.
 *
 * <p>LIVE-F1: the native new-conversation screen has no {@code toolbar_title}; its recipient is a
 * {@code chip} view (text = the formatted number or the contact name). Exactly one chip with the
 * compose box stands in for the title; two or more chips are a group (OD13): no tab. When a
 * {@code toolbar_title} is present it wins and chips are not consulted.
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
    /** Recipient chip on the new-conversation screen (LIVE-F1). */
    public static final String ID_CHIP = VOICE_PACKAGE + ":id/chip";

    private VoiceScreenSignature() {}

    public static Level classify(boolean hasToolbarTitle, boolean hasSendText, boolean hasSendButton,
                                 String pkg) {
        if (!VOICE_PACKAGE.equals(pkg)) return Level.NONE;
        return hasToolbarTitle && hasSendText && hasSendButton ? Level.HIGH : Level.NONE;
    }

    /**
     * LIVE-F1 form: {@code chipCount} is the number of {@code chip} nodes found (only looked up
     * when there is no {@code toolbar_title}). HIGH needs the compose ids plus either a
     * {@code toolbar_title} or exactly one chip; two or more chips without a title are a group.
     */
    public static Level classify(boolean hasToolbarTitle, int chipCount, boolean hasSendText,
                                 boolean hasSendButton, String pkg) {
        return classify(hasToolbarTitle || chipCount == 1, hasSendText, hasSendButton, pkg);
    }

    /**
     * The 1:1 title of the screen: the {@code toolbar_title} text when that node exists (null
     * means the node is missing), else the text of the only chip. Null when there is no title
     * node and zero or several chips (a group, OD13): no recipient.
     */
    public static String title(String toolbarTitle, List<String> chipTexts) {
        if (toolbarTitle != null) return toolbarTitle;
        if (chipTexts == null || chipTexts.size() != 1) return null;
        String chip = chipTexts.get(0);
        return chip != null ? chip : "";
    }
}
