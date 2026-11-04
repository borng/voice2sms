package com.voice2sms.sendlater.store;

import android.content.Context;

import com.voice2sms.sendlater.domain.ConversationRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Display name -&gt; numbers seen in the GV web thread list (R4b). Names are normalized exactly as
 * {@link ConversationRef#key()} does (case-fold, collapse whitespace, strip diacritics). A display
 * name alone is never a recipient (I3): callers resolve a single candidate or ask. Synchronous;
 * background threads only. Personal data: never log names or numbers.
 */
public final class ThreadDirectory {

    private static final String NAME_PREFIX = "name:";
    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{6,14}");

    private static volatile ThreadDirectory instance;

    private final SendLaterDao dao;

    ThreadDirectory(SendLaterDao dao) { this.dao = dao; }

    public static ThreadDirectory get(Context context) {
        ThreadDirectory d = instance;
        if (d == null) {
            synchronized (ThreadDirectory.class) {
                d = instance;
                if (d == null) {
                    d = new ThreadDirectory(SendLaterDatabase.get(context).dao());
                    instance = d;
                }
            }
        }
        return d;
    }

    /** Records that {@code name} showed with {@code e164}; false (nothing stored) when either is invalid. */
    public boolean upsert(String name, String e164, long nowMs) {
        ThreadDirectoryEntity e = entry(name, e164, nowMs);
        if (e == null) return false;
        dao.upsertDirectory(e);
        return true;
    }

    /** Distinct E.164 numbers seen under {@code name}, most recently seen first. Empty when unknown. */
    public List<String> candidatesFor(String name) {
        String key = normalizeName(name);
        if (key.isEmpty()) return new ArrayList<>();
        return distinct(dao.directoryNumbers(key));
    }

    // ---- pure helpers (JVM-tested) ----

    /** Same normalization as {@code ConversationRef.key()} without the {@code name:} prefix. */
    public static String normalizeName(String name) {
        return new ConversationRef(null, name).key().substring(NAME_PREFIX.length());
    }

    public static boolean isE164(String s) {
        return s != null && E164.matcher(s).matches();
    }

    /** Row for the pair, or null when the name is blank or the number is not E.164. */
    static ThreadDirectoryEntity entry(String name, String e164, long nowMs) {
        String key = normalizeName(name);
        String num = e164 == null ? null : e164.trim();
        if (key.isEmpty() || !isE164(num)) return null;
        ThreadDirectoryEntity e = new ThreadDirectoryEntity();
        e.nameKey = key;
        e.e164 = num;
        e.displayName = name.trim();
        e.lastSeenMs = nowMs;
        return e;
    }

    static List<String> distinct(List<String> numbers) {
        List<String> out = new ArrayList<>();
        if (numbers == null) return out;
        out.addAll(new LinkedHashSet<>(numbers));
        out.remove(null);
        return out;
    }
}
