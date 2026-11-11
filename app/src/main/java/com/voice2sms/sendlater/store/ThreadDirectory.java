package com.voice2sms.sendlater.store;

import android.content.Context;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.voice.logic.PhoneNumbers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Display name -&gt; numbers seen in the GV web thread list (R4b). Names are normalized exactly as
 * {@link ConversationRef#key()} does (case-fold, collapse whitespace, strip diacritics). A display
 * name alone is never a recipient (I3): callers resolve a single candidate or ask. Synchronous;
 * background threads only. Personal data: never log names or numbers.
 */
public final class ThreadDirectory {

    private static final String NAME_PREFIX = "name:";

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

    /**
     * {@link #upsert(String, String, long)} for every {@code {name, e164}} pair, in one
     * transaction (same rows as one call per pair). Invalid pairs are skipped the same way.
     * Returns how many rows were stored.
     */
    public int upsert(List<String[]> pairs, long nowMs) {
        List<ThreadDirectoryEntity> rows = entries(pairs, nowMs);
        if (!rows.isEmpty()) dao.upsertDirectoryAll(rows);
        return rows.size();
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
        String key = ConversationRef.nameKey(name);
        return key == null ? "" : key.substring(NAME_PREFIX.length());
    }

    /** The one E.164 rule (D38); delegates to {@link PhoneNumbers#isE164}. */
    public static boolean isE164(String s) {
        return PhoneNumbers.isE164(s);
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

    /** Rows for the valid pairs, in order; null pairs and invalid pairs are skipped. */
    static List<ThreadDirectoryEntity> entries(List<String[]> pairs, long nowMs) {
        List<ThreadDirectoryEntity> out = new ArrayList<>();
        if (pairs == null) return out;
        for (String[] p : pairs) {
            if (p == null || p.length < 2) continue;
            ThreadDirectoryEntity e = entry(p[0], p[1], nowMs);
            if (e != null) out.add(e);
        }
        return out;
    }

    static List<String> distinct(List<String> numbers) {
        List<String> out = new ArrayList<>();
        if (numbers == null) return out;
        out.addAll(new LinkedHashSet<>(numbers));
        out.remove(null);
        return out;
    }
}
