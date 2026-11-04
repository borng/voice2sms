package com.voice2sms.sendlater.domain;

/**
 * Identifies a 1:1 conversation for {@link ThreadEventSource#readSince}. Either part may be null.
 * {@link #key()} prefers the number; without one it is {@code "name:" + normalized(displayName)}
 * (case-folded, whitespace collapsed, diacritics stripped).
 */
public final class ConversationRef {

    private final String e164;
    private final String displayName;

    public ConversationRef(String e164, String displayName) {
        this.e164 = (e164 == null || e164.trim().isEmpty()) ? null : e164.trim();
        this.displayName = displayName;
    }

    public static ConversationRef of(ScheduledMessage m) {
        return new ConversationRef(m.getRecipientE164(), m.getRecipientDisplayName());
    }

    public String getE164() { return e164; }
    public String getDisplayName() { return displayName; }

    public String key() {
        return e164 != null ? e164 : "name:" + TextNorm.foldName(displayName);
    }

    @Override public boolean equals(Object o) {
        return o instanceof ConversationRef && key().equals(((ConversationRef) o).key());
    }

    @Override public int hashCode() { return key().hashCode(); }

    /** Never includes the name or number. */
    @Override public String toString() {
        return "ConversationRef{" + (e164 != null ? "e164" : "name") + "}";
    }
}
