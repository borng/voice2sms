package com.voice2sms.sendlater.voice.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R4 / I3: who is the recipient of a native Voice thread?
 * <ol>
 *   <li>A {@code toolbar_title} that parses as a phone number wins, silently.</li>
 *   <li>Otherwise the candidates are the device-contact numbers for that name intersected with the
 *       GV web directory numbers for that name. When one side is empty (contacts not granted, or no
 *       web session) the other side alone is the candidate set.</li>
 *   <li>Exactly one candidate: RESOLVED. Several: NEEDS_CHOICE. None: NEEDS_ENTRY.</li>
 * </ol>
 * Every returned number is E.164; anything that does not normalize is dropped, so a display name
 * is never a recipient. Callers supply already-filtered lists: {@code directory} must hold only the
 * web threads whose display name matches the title, never the whole thread list.
 */
public final class RecipientResolver {

    public static final String DEFAULT_REGION = "US";

    public enum Kind { RESOLVED, NEEDS_CHOICE, NEEDS_ENTRY }

    public static final class Resolution {
        private final Kind kind;
        private final String e164;
        private final List<String> candidates;

        private Resolution(Kind kind, String e164, List<String> candidates) {
            this.kind = kind;
            this.e164 = e164;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        }

        public Kind getKind() { return kind; }
        /** The chosen E.164 when RESOLVED, else null. */
        public String getE164() { return e164; }
        /** E.164 candidates: the one chosen when RESOLVED, the choices when NEEDS_CHOICE, empty for NEEDS_ENTRY. */
        public List<String> getCandidates() { return candidates; }

        @Override public String toString() { return "Resolution{" + kind + ", n=" + candidates.size() + "}"; }
    }

    private RecipientResolver() {}

    public static Resolution resolve(String toolbarTitle, List<String> contacts, List<String> directory) {
        String fromTitle = PhoneNumbers.toE164(toolbarTitle, DEFAULT_REGION);
        if (fromTitle != null) return resolved(fromTitle);

        Set<String> c = normalize(contacts);
        Set<String> d = normalize(directory);
        Set<String> candidates;
        if (c.isEmpty()) {
            candidates = d;
        } else if (d.isEmpty()) {
            candidates = c;
        } else {
            candidates = new LinkedHashSet<>(c);
            candidates.retainAll(d);
            // Disjoint lists: neither source confirms the other, so offer everything as a choice.
            if (candidates.isEmpty()) {
                candidates = new LinkedHashSet<>(c);
                candidates.addAll(d);
            }
        }
        if (candidates.isEmpty()) return new Resolution(Kind.NEEDS_ENTRY, null, Collections.<String>emptyList());
        if (candidates.size() == 1) return resolved(candidates.iterator().next());
        return new Resolution(Kind.NEEDS_CHOICE, null, new ArrayList<>(candidates));
    }

    /** A number the user picked or typed in the sheet; null if it is not a number. */
    public static Resolution manual(String raw) {
        String e164 = PhoneNumbers.toE164(raw, DEFAULT_REGION);
        return e164 == null ? null : resolved(e164);
    }

    private static Resolution resolved(String e164) {
        return new Resolution(Kind.RESOLVED, e164, Collections.singletonList(e164));
    }

    private static Set<String> normalize(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String r : raw) {
            String e = PhoneNumbers.toE164(r, DEFAULT_REGION);
            if (e != null) out.add(e);
        }
        return out;
    }
}
