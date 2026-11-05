package com.voice2sms.sendlater.a11y;

/**
 * Pure core of the "accessibility service is off" warning (LIVE-F5). Same budget as the battery
 * warning (D25): at most one post per {@link #INTERVAL_MS}, and only while
 * {@link A11yHealth#decide} says {@link A11yHealth.State#WARN}. Only a real post records the time.
 *
 * <p>The warning clears itself: {@link A11yHealth.State#OK} (service re-enabled) removes the
 * notification and forgets the timestamp, so turning the service off again warns at the next
 * check. {@link A11yHealth.State#IDLE} (Send Later off or nothing pending) removes the
 * notification but keeps the timestamp, so a service that stays off is not re-announced on every
 * new schedule.
 */
public final class A11yWarningThrottle {

    public static final String KEY_WARNED_AT = "sendlater_a11y_warned_at";
    public static final long INTERVAL_MS = 24L * 60L * 60L * 1000L;

    /** What a {@link #check} did. */
    public enum Result { POSTED, THROTTLED, CLEARED, QUIET }

    /** The timestamp store (SharedPreferences in production). */
    public interface Store {
        long getLong(String key, long def);
        void putLong(String key, long value);
    }

    /** The notification side. */
    public interface Poster {
        void post();
        void clear();
    }

    private final Store store;
    private final Poster poster;

    public A11yWarningThrottle(Store store, Poster poster) {
        this.store = store;
        this.poster = poster;
    }

    public Result check(A11yHealth.State state, long nowMs) {
        if (state == A11yHealth.State.OK) {
            poster.clear();
            if (store.getLong(KEY_WARNED_AT, 0L) != 0L) store.putLong(KEY_WARNED_AT, 0L);
            return Result.CLEARED;
        }
        if (state != A11yHealth.State.WARN) {
            poster.clear();
            return Result.QUIET;
        }
        long last = store.getLong(KEY_WARNED_AT, 0L);
        // A timestamp in the future (clock set back) is stale: it must not mute the warning.
        if (last > 0L && last <= nowMs && nowMs - last < INTERVAL_MS) return Result.THROTTLED;
        poster.post();
        store.putLong(KEY_WARNED_AT, nowMs);
        return Result.POSTED;
    }
}
