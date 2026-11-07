package com.voice2sms.sendlater.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * D32: hands the draft a scheduled compose found in the conversation's box from the send path to
 * the engine, in process memory only. The send path {@link #put}s it right before it reports the
 * job CANNOT_SHOW; the engine {@link #take}s it while handling that outcome and puts the text
 * into the "draft in the way" notification. The text is never written to Room, preferences or
 * logs; an entry not taken within {@link #MAX_AGE_MS} is dropped. Pure JVM.
 */
public final class DraftStash {

    /** An entry older than this is ignored (the outcome normally follows within milliseconds). */
    public static final long MAX_AGE_MS = 120_000L;

    private static final class Entry {
        final String draft;
        final long atMs;

        Entry(String draft, long atMs) {
            this.draft = draft;
            this.atMs = atMs;
        }
    }

    private static final Map<Long, Entry> DRAFTS = new HashMap<>();

    private DraftStash() {}

    /** Keeps {@code draft} for message {@code id}. */
    public static void put(long id, String draft) {
        put(id, draft, monotonicMs());
    }

    /** {@link #put(long, String)} at {@code nowMs} (monotonic; tests). */
    static synchronized void put(long id, String draft, long nowMs) {
        if (draft == null || draft.trim().isEmpty()) {
            DRAFTS.remove(id);
            return;
        }
        DRAFTS.put(id, new Entry(draft, nowMs));
    }

    /** The draft kept for {@code id}, removed; null when none (or too old). */
    public static String take(long id) {
        return take(id, monotonicMs());
    }

    /** {@link #take(long)} at {@code nowMs} (monotonic; tests). */
    static synchronized String take(long id, long nowMs) {
        Entry e = DRAFTS.remove(id);
        if (e == null) return null;
        long age = nowMs - e.atMs;
        return age < 0 || age > MAX_AGE_MS ? null : e.draft;
    }

    /** Drops whatever is kept for {@code id}. */
    public static synchronized void drop(long id) {
        DRAFTS.remove(id);
    }

    private static long monotonicMs() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Tests only. */
    static synchronized void clearAll() {
        DRAFTS.clear();
    }
}
