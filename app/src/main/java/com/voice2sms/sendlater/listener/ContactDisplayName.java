package com.voice2sms.sendlater.listener;

import java.util.List;

/**
 * D33 (pure JVM): the first non-blank DISPLAY_NAME a contacts lookup gives for a number, only
 * while READ_CONTACTS is granted. Denial, no match or any failure gives null; never throws, never
 * logs. {@link AndroidContactNames} supplies the Android resolver.
 */
public final class ContactDisplayName {

    /** The contacts provider as the lookup sees it. */
    public interface Resolver {
        boolean readContactsGranted();

        /** DISPLAY_NAME of each PhoneLookup row for {@code e164}, in row order; null when the query failed. */
        List<String> displayNames(String e164);
    }

    private ContactDisplayName() {}

    public static String lookup(Resolver resolver, String e164) {
        if (resolver == null || e164 == null || e164.trim().isEmpty()) return null;
        try {
            if (!resolver.readContactsGranted()) return null;
            List<String> names = resolver.displayNames(e164);
            if (names == null) return null;
            for (String n : names) {
                if (n != null && !n.trim().isEmpty()) return n;
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
