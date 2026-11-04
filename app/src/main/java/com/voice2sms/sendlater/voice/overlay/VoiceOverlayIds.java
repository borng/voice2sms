package com.voice2sms.sendlater.voice.overlay;

import com.voice2sms.sendlater.voice.logic.VoiceScreenSignature;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The only Google Voice resource ids the accessibility service looks at (R8, I7). Every node lookup
 * in this package goes through {@link #R8_IDS}; nothing else in Voice is read.
 */
public final class VoiceOverlayIds {

    private static final String PREFIX = VoiceScreenSignature.VOICE_PACKAGE + ":id/";

    /** Thread title: the display name or number of the open conversation. */
    public static final String TOOLBAR_TITLE = VoiceScreenSignature.ID_TOOLBAR_TITLE;
    /** The composer EditText (draft read, ACTION_SET_TEXT clear and restore). */
    public static final String SEND_TEXT = VoiceScreenSignature.ID_SEND_TEXT;
    /** The Send button (presence only: screen signature). */
    public static final String SEND_BUTTON = VoiceScreenSignature.ID_SEND_BUTTON;
    /** The compose row: bounds only, the anchor of the tab, chip and confirmation. */
    public static final String INPUT_CONTAINER = PREFIX + "input_message_container";
    /** RecyclerView of bubbles: row content descriptions for the watermark and reply detection. */
    public static final String BUBBLE_LIST = PREFIX + "list";

    /** R8 allowlist: the full resource ids above, nothing else. */
    public static final List<String> R8_IDS = Collections.unmodifiableList(Arrays.asList(
            TOOLBAR_TITLE, SEND_TEXT, SEND_BUTTON, INPUT_CONTAINER, BUBBLE_LIST));

    private VoiceOverlayIds() {}

    /** True for an id the service may look up. */
    public static boolean isAllowed(String id) {
        return id != null && R8_IDS.contains(id);
    }
}
