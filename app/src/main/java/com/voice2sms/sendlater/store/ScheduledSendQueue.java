package com.voice2sms.sendlater.store;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Serializes scheduled sends (R30): consecutive slots are at least {@link #SPACING_MS} apart and at
 * most {@link #DAILY_CAP} slots are granted per local calendar day. Independent of the interactive
 * auto-send limiter: the scheduled path never consults it. State (day, count, last slot) is
 * persisted, so a new instance over the same store continues the same day's count.
 */
public final class ScheduledSendQueue {

    public static final long SPACING_MS = 2_000L;
    public static final int DAILY_CAP = 50;
    /** Returned by {@link #acquireSlot(long)} when today's cap is used up. */
    public static final long REFUSED = -1L;

    static final String PREFS_NAME = "sendlater_queue";
    static final String KEY_DAY = "day";
    static final String KEY_COUNT = "count";
    static final String KEY_LAST_SLOT = "last_slot_ms";

    /** A last slot further ahead than this is treated as a clock change and ignored. */
    private static final long MAX_AHEAD_MS = 24L * 60 * 60 * 1000;

    /** Shared by all instances: they may share one persisted store. */
    private static final Object LOCK = new Object();

    /** Persisted counter state. */
    public interface Store {
        long getLong(String key, long def);
        /** Writes all pairs durably and atomically. */
        void putLongs(String[] keys, long[] values);
    }

    /** Injectable clock. */
    public interface Clock { long nowMs(); }

    private final Store store;
    private final TimeZone zone;
    private final Clock clock;
    private final int dailyCap;

    public ScheduledSendQueue(Store store, TimeZone zone, Clock clock, int dailyCap) {
        if (store == null) throw new IllegalArgumentException("store");
        if (dailyCap < 0) throw new IllegalArgumentException("dailyCap");
        this.store = store;
        this.zone = zone;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.dailyCap = dailyCap;
    }

    /** Production queue over SharedPreferences {@code sendlater_queue}, local time zone, cap 50. */
    public static ScheduledSendQueue get(Context context) {
        return new ScheduledSendQueue(new PrefsStore(
                context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
                null, System::currentTimeMillis, DAILY_CAP);
    }

    /** {@link #acquireSlot(long)} at the clock's now. */
    public long acquireSlot() { return acquireSlot(clock.nowMs()); }

    /**
     * Reserves the next send slot. Returns the delay in ms from {@code nowMs} until the send may
     * start (0 = now), or {@link #REFUSED} when today's cap is reached (nothing is reserved).
     */
    public long acquireSlot(long nowMs) {
        synchronized (LOCK) {
            long day = dayKey(nowMs);
            long count = store.getLong(KEY_DAY, -1) == day ? store.getLong(KEY_COUNT, 0) : 0;
            if (count >= dailyCap) return REFUSED;
            long last = store.getLong(KEY_LAST_SLOT, Long.MIN_VALUE);
            long slot = nowMs;
            if (last != Long.MIN_VALUE && last <= nowMs + MAX_AHEAD_MS && last + SPACING_MS > nowMs) {
                slot = last + SPACING_MS;
            }
            store.putLongs(new String[] {KEY_DAY, KEY_COUNT, KEY_LAST_SLOT},
                    new long[] {day, count + 1, slot});
            return slot - nowMs;
        }
    }

    /** Slots granted so far on {@code nowMs}'s local day. */
    public int usedToday(long nowMs) {
        synchronized (LOCK) {
            return store.getLong(KEY_DAY, -1) == dayKey(nowMs) ? (int) store.getLong(KEY_COUNT, 0) : 0;
        }
    }

    /** yyyymmdd of {@code ms} in the queue's zone (default zone when none was given). */
    long dayKey(long ms) {
        Calendar c = Calendar.getInstance(zone != null ? zone : TimeZone.getDefault());
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) * 10_000L + (c.get(Calendar.MONTH) + 1) * 100L
                + c.get(Calendar.DAY_OF_MONTH);
    }

    /** SharedPreferences-backed store; commit() so the count survives process death. */
    static final class PrefsStore implements Store {
        private final SharedPreferences prefs;
        PrefsStore(SharedPreferences prefs) { this.prefs = prefs; }

        @Override public long getLong(String key, long def) { return prefs.getLong(key, def); }

        @Override public void putLongs(String[] keys, long[] values) {
            SharedPreferences.Editor ed = prefs.edit();
            for (int i = 0; i < keys.length; i++) ed.putLong(keys[i], values[i]);
            ed.commit();
        }
    }
}
