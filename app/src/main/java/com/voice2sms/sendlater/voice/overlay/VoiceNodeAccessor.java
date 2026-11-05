package com.voice2sms.sendlater.voice.overlay;

import java.util.List;

/**
 * The live native Voice thread screen, as the overlay flows see it. Every call re-acquires the
 * nodes (R2): nothing is cached between calls. Implementations read only {@link VoiceOverlayIds}.
 */
public interface VoiceNodeAccessor {

    /**
     * The 1:1 title: {@code toolbar_title} text, else the text of the single recipient {@code chip}
     * on the new-conversation screen (LIVE-F1); null when neither gives one (no title node and zero
     * or several chips, or Voice is not in front).
     */
    String readTitle();

    /**
     * {@code send_message_text} text with hint text stripped ("" when the field shows its hint),
     * or null when the field node is missing.
     */
    String readDraft();

    /** Content descriptions of the visible bubble-list rows, top to bottom; empty when none. */
    List<String> readBubbleDescriptions();

    /** ACTION_SET_TEXT on {@code send_message_text}; false when it fails or the node is missing. */
    boolean setDraft(String text);
}
