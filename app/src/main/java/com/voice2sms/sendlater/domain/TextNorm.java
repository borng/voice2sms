package com.voice2sms.sendlater.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Text normalization shared by the classifier and ConversationRef. */
final class TextNorm {
    private TextNorm() {}

    /** Case-fold, strip diacritics, collapse whitespace, trim. Keeps punctuation. */
    static String foldName(String s) {
        if (s == null) return "";
        String d = stripDiacritics(s).toLowerCase(Locale.ROOT);
        return d.replaceAll("\\s+", " ").trim();
    }

    /** Case-fold, strip diacritics, split on anything that is not a letter or digit. */
    static List<String> tokens(String s) {
        if (s == null) return Collections.emptyList();
        String d = stripDiacritics(s).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String t : d.split("[^\\p{L}\\p{N}]+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String stripDiacritics(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
