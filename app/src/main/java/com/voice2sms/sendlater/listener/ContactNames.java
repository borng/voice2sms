package com.voice2sms.sendlater.listener;

import java.util.Collections;
import java.util.Set;

/**
 * LIVE-F12: the device-contact display names saved for a number. A Google Voice notification from
 * a saved contact carries the contact's name as its title and no number, so a live message
 * scheduled with only an E.164 can be matched to it only through these names.
 *
 * <p>Implementations are synchronous (call off the main thread), return an empty set when the
 * names are not known (no permission, no contact, failure) and never log names or numbers. Pure
 * JVM seam; the Android implementation is {@link AndroidContactNames}.
 *
 * <p>A name shared by several contacts may match several live messages. That only causes holds
 * (the safe direction), never a send.
 */
public interface ContactNames {

    /** Names saved for {@code e164}; never null. */
    Set<String> namesFor(String e164);

    /** Knows no names: the behaviour before LIVE-F12. */
    ContactNames NONE = e164 -> Collections.<String>emptySet();

    /** {@code names.namesFor(e164)}, or an empty set when {@code names} is null, fails or returns null. */
    static Set<String> safeNamesFor(ContactNames names, String e164) {
        if (names == null || e164 == null) return Collections.emptySet();
        try {
            Set<String> s = names.namesFor(e164);
            return s != null ? s : Collections.<String>emptySet();
        } catch (RuntimeException e) {
            return Collections.emptySet();
        }
    }
}
