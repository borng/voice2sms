package com.voice2sms.sendlater.battery;

/**
 * Pure core of the once-a-day battery warning (R39). Both triggers (a message was scheduled; a
 * due alarm fired more than {@link #LATE_THRESHOLD_MS} late) share one budget: at most one post
 * per {@link #INTERVAL_MS}, and never while the status is healthy. Only a real post records the
 * time, so a healthy check or an on-time alarm never uses up the day's warning.
 */
public final class BatteryWarningThrottle {

    public static final String KEY_WARNED_AT = "sendlater_battery_warned_at";
    public static final long INTERVAL_MS = 24L * 60L * 60L * 1000L;
    public static final long LATE_THRESHOLD_MS = 2L * 60L * 1000L;

    /** The timestamp store (SharedPreferences in production). */
    public interface Store {
        long getLong(String key, long def);
        void putLong(String key, long value);
    }

    public interface Clock { long nowMs(); }

    public interface Health { boolean isHealthy(); }

    public interface Poster { void post(); }

    private final Store store;
    private final Clock clock;
    private final Health health;
    private final Poster poster;

    public BatteryWarningThrottle(Store store, Clock clock, Health health, Poster poster) {
        this.store = store;
        this.clock = clock;
        this.health = health;
        this.poster = poster;
    }

    /** Posts the warning if limited and none was posted in the last 24 h. True when posted. */
    public boolean maybeNotify(long nowMs) {
        long last = store.getLong(KEY_WARNED_AT, 0L);
        // A timestamp in the future (clock set back) is stale: it must not mute the warning.
        if (last > 0L && last <= nowMs && nowMs - last < INTERVAL_MS) return false;
        if (health.isHealthy()) return false;
        poster.post();
        store.putLong(KEY_WARNED_AT, nowMs);
        return true;
    }

    /** A due alarm fired {@code lateMs} after its target: over 2 min takes the throttled path. */
    public boolean onAlarmLate(long lateMs) {
        if (lateMs <= LATE_THRESHOLD_MS) return false;
        return maybeNotify(clock.nowMs());
    }
}
