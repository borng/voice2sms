package com.voice2sms.sendlater.voice.overlay;

import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.voice.logic.RecipientResolver;
import com.voice2sms.sendlater.voice.logic.VoiceBubbleParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * What the edge-tab tap captured from the native Voice thread (R3, R4): the title as shown, the
 * draft as exact Unicode, the capture time, the bubble watermark and the E.164 candidates
 * (RecipientResolver; one entry when resolved, empty when the sheet must ask for a number, I3;
 * deliver sec-M1: a number from the thread directory alone is a candidate the user must confirm).
 * Immutable. {@link #toString()} never shows the draft, the name or a number (I7).
 */
public final class VoiceDraftSnapshot {

    private final String displayName;
    private final String draftText;
    private final long capturedAt;
    private final Watermark watermark;
    private final List<String> candidates;
    /** RecipientResolver answered RESOLVED (deliver sec-M1: not for directory-only numbers). */
    private final boolean resolved;

    private VoiceDraftSnapshot(String displayName, String draftText, long capturedAt, Watermark watermark,
                               List<String> candidates, boolean resolved) {
        this.displayName = displayName;
        this.draftText = draftText;
        this.capturedAt = capturedAt;
        this.watermark = watermark;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.resolved = resolved && this.candidates.size() == 1;
    }

    /**
     * Reads the live screen through {@code nodes} and resolves the recipient.
     *
     * @param contacts  numbers of device contacts whose name equals the title (empty without
     *                  READ_CONTACTS)
     * @param directory numbers the GV web thread list showed under that name
     *                  ({@code ThreadDirectory.candidatesFor(title)})
     * @return the snapshot, or null when the title or the draft field is missing, or the draft is empty
     */
    public static VoiceDraftSnapshot capture(VoiceNodeAccessor nodes, long nowMs, TimeZone zone,
                                             List<String> contacts, List<String> directory) {
        if (nodes == null) return null;
        String title = nodes.readTitle();
        if (title == null || title.trim().isEmpty()) return null;
        String draft = nodes.readDraft();
        if (draft == null || draft.isEmpty()) return null;
        List<String> bubbles = nodes.readBubbleDescriptions();
        if (bubbles == null) bubbles = Collections.emptyList();
        Watermark wm = VoiceBubbleParser.bubbleWatermark(bubbles, nowMs, zone);
        RecipientResolver.Resolution r = RecipientResolver.resolve(title, contacts, directory);
        return new VoiceDraftSnapshot(title, draft, nowMs, wm, r.getCandidates(),
                r.getKind() == RecipientResolver.Kind.RESOLVED);
    }

    /** {@code toolbar_title} as shown at capture. */
    public String getDisplayName() { return displayName; }

    /** The draft, exact and untrimmed. */
    public String getDraftText() { return draftText; }

    public long getCapturedAt() { return capturedAt; }

    public Watermark getWatermark() { return watermark; }

    /** E.164 candidates, never null; one entry when the recipient resolved (or one to confirm). */
    public List<String> getCandidates() { return candidates; }

    /** The resolved number, else null (several candidates, none, or one the user must confirm). */
    public String getE164() {
        return resolved ? candidates.get(0) : null;
    }

    /**
     * deliver sec-M1: true when there are candidates but none was resolved, so the sheet must ask
     * "Which number?" even for a single candidate.
     */
    public boolean needsRecipientConfirm() {
        return !resolved && !candidates.isEmpty();
    }

    @Override public String toString() {
        return "VoiceDraftSnapshot{len=" + draftText.length() + ", candidates=" + candidates.size()
                + ", wm=" + (watermark != null ? watermark.getQuality() : null) + "}";
    }
}
