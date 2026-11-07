package com.voice2sms.sendlater.store;

import com.voice2sms.sendlater.domain.ScheduledMessage;

/**
 * D33: the display name a record is saved with when its capture surface gave none. A Google Voice
 * reply notification from a saved contact is titled with the contact's name and carries no number;
 * with the name on the record the listener gate and the event source match it and count the
 * listener as available for it (OD22(5), LIVE-F12).
 *
 * <p>Implementations are synchronous (call off the main thread), return null when no name is
 * known (no permission, no contact, failure) and never log names or numbers. Pure JVM seam; the
 * Android implementation is {@code listener.AndroidContactNames}.
 */
public interface RecipientNames {

    /** The first display name saved for {@code e164}, or null. */
    String displayNameFor(String e164);

    /**
     * {@code message} with its display name filled from {@code names} when it has none (null or
     * blank) and has an E.164; otherwise {@code message} itself. A name the capture surface gave
     * is never replaced. Never throws: a failed or empty lookup leaves the message unchanged.
     */
    static ScheduledMessage fill(ScheduledMessage message, RecipientNames names) {
        if (message == null || names == null) return message;
        String current = message.getRecipientDisplayName();
        if (current != null && !current.trim().isEmpty()) return message;
        String e164 = message.getRecipientE164();
        if (e164 == null || e164.trim().isEmpty()) return message;
        String name;
        try {
            name = names.displayNameFor(e164);
        } catch (RuntimeException e) {
            return message;
        }
        if (name == null || name.trim().isEmpty()) return message;
        return message.toBuilder().recipientDisplayName(name).build();
    }
}
