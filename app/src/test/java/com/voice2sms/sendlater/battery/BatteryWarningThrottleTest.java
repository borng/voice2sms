package com.voice2sms.sendlater.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Once-a-day battery warning (R39) through the pure core behind
 * {@code BatteryWarning.maybeNotify(Context, nowMs)} / {@code BatteryWarning.onAlarmLate(Context, lateMs)}.
 * Clock, SharedPreferences, health status and the notification post are injected.
 */
public class BatteryWarningThrottleTest {

    /** 2026-06-10T12:00Z. */
    private static final long T0 = 1_781_092_800_000L;
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;
    private static final String KEY = "sendlater_battery_warned_at";

    /** In-memory stand-in for SharedPreferences. */
    static final class MapStore implements BatteryWarningThrottle.Store {
        final Map<String, Long> m = new HashMap<>();
        @Override public long getLong(String key, long def) {
            Long v = m.get(key);
            return v == null ? def : v;
        }
        @Override public void putLong(String key, long value) { m.put(key, value); }
    }

    static final class Harness {
        final MapStore store = new MapStore();
        long now = T0;
        boolean healthy = false;
        int posts = 0;

        BatteryWarningThrottle throttle() {
            return new BatteryWarningThrottle(store, () -> now, () -> healthy, () -> posts++);
        }
    }

    @Test public void constantsMatchTheSpec() {
        assertEquals(KEY, BatteryWarningThrottle.KEY_WARNED_AT);
        assertEquals(DAY, BatteryWarningThrottle.INTERVAL_MS);
        assertEquals(2 * MIN, BatteryWarningThrottle.LATE_THRESHOLD_MS);
    }

    @Test public void notifiesWhenLimitedAndRecordsTheTime() {
        Harness h = new Harness();
        assertTrue(h.throttle().maybeNotify(T0));
        assertEquals(1, h.posts);
        assertEquals(Long.valueOf(T0), h.store.m.get(KEY));
    }

    @Test public void doesNotNotifyWhenHealthy() {
        Harness h = new Harness();
        h.healthy = true;
        assertFalse(h.throttle().maybeNotify(T0));
        assertEquals(0, h.posts);
        assertNull("a healthy check must not use up the daily warning", h.store.m.get(KEY));
    }

    @Test public void healthyCheckDoesNotBlockALaterLimitedWarning() {
        Harness h = new Harness();
        h.healthy = true;
        h.throttle().maybeNotify(T0);
        h.healthy = false;
        assertTrue(h.throttle().maybeNotify(T0 + HOUR));
        assertEquals(1, h.posts);
    }

    @Test public void atMostOncePer24Hours() {
        Harness h = new Harness();
        BatteryWarningThrottle t = h.throttle();
        assertTrue(t.maybeNotify(T0));
        assertFalse(t.maybeNotify(T0 + 1));
        assertFalse(t.maybeNotify(T0 + HOUR));
        assertFalse(t.maybeNotify(T0 + DAY - 1));
        assertEquals(1, h.posts);
        assertEquals("a suppressed call does not move the timestamp", Long.valueOf(T0), h.store.m.get(KEY));
        assertTrue(t.maybeNotify(T0 + DAY + 1));
        assertEquals(2, h.posts);
        assertEquals(Long.valueOf(T0 + DAY + 1), h.store.m.get(KEY));
        assertFalse(t.maybeNotify(T0 + DAY + HOUR));
        assertEquals(2, h.posts);
    }

    @Test public void throttleSurvivesANewInstanceOverTheSamePrefs() {
        Harness h = new Harness();
        assertTrue(h.throttle().maybeNotify(T0));
        assertFalse("timestamp lives in prefs, not in memory", h.throttle().maybeNotify(T0 + 2 * HOUR));
        assertEquals(1, h.posts);
    }

    @Test public void lateAlarmOverTwoMinutesNotifiesWhenLimited() {
        Harness h = new Harness();
        assertTrue(h.throttle().onAlarmLate(2 * MIN + 1));
        assertEquals(1, h.posts);
        assertEquals("uses the injected clock for the timestamp", Long.valueOf(T0), h.store.m.get(KEY));
    }

    @Test public void lateAlarmWellOverThresholdNotifies() {
        Harness h = new Harness();
        assertTrue(h.throttle().onAlarmLate(45 * MIN));
        assertEquals(1, h.posts);
    }

    @Test public void lateAlarmAtOrUnderTwoMinutesDoesNotNotify() {
        Harness h = new Harness();
        BatteryWarningThrottle t = h.throttle();
        assertFalse(t.onAlarmLate(2 * MIN));
        assertFalse(t.onAlarmLate(30_000L));
        assertFalse(t.onAlarmLate(0L));
        assertFalse(t.onAlarmLate(-5_000L));
        assertEquals(0, h.posts);
        assertNull(h.store.m.get(KEY));
        // An on-time alarm must not use up the daily slot either.
        assertTrue(t.maybeNotify(T0 + MIN));
    }

    @Test public void lateAlarmWhileHealthyDoesNotNotify() {
        // R39: never while the status is healthy, whichever path triggers it.
        Harness h = new Harness();
        h.healthy = true;
        assertFalse(h.throttle().onAlarmLate(10 * MIN));
        assertEquals(0, h.posts);
        assertNull(h.store.m.get(KEY));
    }

    @Test public void lateAlarmSharesTheDailyThrottleWithScheduling() {
        Harness h = new Harness();
        assertTrue(h.throttle().maybeNotify(T0));
        h.now = T0 + HOUR;
        assertFalse("same once-a-day budget", h.throttle().onAlarmLate(10 * MIN));
        h.now = T0 + DAY + MIN;
        assertTrue(h.throttle().onAlarmLate(10 * MIN));
        assertEquals(2, h.posts);
    }

    @Test public void schedulingAfterALateAlarmWarningIsThrottled() {
        Harness h = new Harness();
        assertTrue(h.throttle().onAlarmLate(5 * MIN));
        assertFalse(h.throttle().maybeNotify(T0 + 3 * HOUR));
        assertEquals(1, h.posts);
    }
}
