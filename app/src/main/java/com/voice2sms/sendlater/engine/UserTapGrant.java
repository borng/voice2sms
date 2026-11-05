package com.voice2sms.sendlater.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * LIVE-F4: "this send was started by the user's own tap, from an activity that is in the
 * foreground right now". Pure JVM, process-wide.
 *
 * <p>{@link SendLaterUnlockActivity} (the Send now / Send anyway trampoline) grants it for one
 * message just before it hands the tap to the engine, and revokes it when it finishes. While the
 * grant is active the trampoline's window is showing, so voice2sms may start an activity itself
 * (a visible window lifts the background-activity-start limits); the send path then plans
 * {@code LaunchPlanner.Result.DIRECT} without needing the accessibility service. The send path
 * {@link #consume}s it once it has started the activity, which also lets the trampoline go.
 *
 * <p>A grant lasts at most {@link #MAX_AGE_MS} even if nobody revokes it, so a lost trampoline
 * can never make a later, unattended send look like a user tap.
 */
public final class UserTapGrant {

    /** Hard ceiling on a grant's life (the trampoline normally ends it much sooner). */
    public static final long MAX_AGE_MS = 10_000L;

    private static final class Entry {
        final long grantedAtMs;
        final Runnable onConsumed;

        Entry(long grantedAtMs, Runnable onConsumed) {
            this.grantedAtMs = grantedAtMs;
            this.onConsumed = onConsumed;
        }
    }

    private static final Map<Long, Entry> GRANTS = new HashMap<>();

    private UserTapGrant() {}

    /**
     * Grants message {@code id} a foreground user tap at {@code nowMs} (monotonic clock).
     * {@code onConsumed} (may be null) runs once, on the consuming thread, when the send path
     * reports it has started the activity.
     */
    public static synchronized void grant(long id, long nowMs, Runnable onConsumed) {
        GRANTS.put(id, new Entry(nowMs, onConsumed));
    }

    /** Whether a grant for {@code id} is active at {@code nowMs}. An expired one is dropped. */
    public static synchronized boolean isActive(long id, long nowMs) {
        Entry e = GRANTS.get(id);
        if (e == null) return false;
        long age = nowMs - e.grantedAtMs;
        if (age < 0 || age > MAX_AGE_MS) {
            GRANTS.remove(id);
            return false;
        }
        return true;
    }

    /**
     * The send path started the activity for {@code id}: ends the grant and runs its callback.
     * Returns whether a grant was active.
     */
    public static boolean consume(long id, long nowMs) {
        Runnable callback;
        synchronized (UserTapGrant.class) {
            if (!isActive(id, nowMs)) return false;
            callback = GRANTS.remove(id).onConsumed;
        }
        if (callback != null) {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // the trampoline is gone; nothing else depends on it
            }
        }
        return true;
    }

    /** The trampoline is going away: ends the grant without running its callback. */
    public static synchronized void revoke(long id) {
        GRANTS.remove(id);
    }

    /** Tests only. */
    static synchronized void clearAll() {
        GRANTS.clear();
    }
}
