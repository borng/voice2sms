package com.voice2sms.sendlater.a11y;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** LIVE-F5: once-a-day "accessibility off" warning that clears itself when the service is back. */
public class A11yWarningThrottleTest {

    private static final long T0 = 1_781_092_800_000L;
    private static final long HOUR = 60L * 60L * 1000L;
    private static final long DAY = 24L * HOUR;
    private static final String KEY = A11yWarningThrottle.KEY_WARNED_AT;

    private static final A11yHealth.State OK = A11yHealth.State.OK;
    private static final A11yHealth.State IDLE = A11yHealth.State.IDLE;
    private static final A11yHealth.State WARN = A11yHealth.State.WARN;

    static final class Harness implements A11yWarningThrottle.Store, A11yWarningThrottle.Poster {
        final Map<String, Long> m = new HashMap<>();
        int posts;
        int clears;
        final A11yWarningThrottle t = new A11yWarningThrottle(this, this);

        @Override public long getLong(String key, long def) {
            Long v = m.get(key);
            return v == null ? def : v;
        }
        @Override public void putLong(String key, long value) { m.put(key, value); }
        @Override public void post() { posts++; }
        @Override public void clear() { clears++; }
    }

    @Test public void firstWarnPostsAndRecordsTime() {
        Harness h = new Harness();
        assertEquals(A11yWarningThrottle.Result.POSTED, h.t.check(WARN, T0));
        assertEquals(1, h.posts);
        assertEquals(Long.valueOf(T0), h.m.get(KEY));
    }

    @Test public void atMostOncePerDay() {
        Harness h = new Harness();
        h.t.check(WARN, T0);
        assertEquals(A11yWarningThrottle.Result.THROTTLED, h.t.check(WARN, T0 + HOUR));
        assertEquals(A11yWarningThrottle.Result.THROTTLED, h.t.check(WARN, T0 + DAY - 1));
        assertEquals(1, h.posts);
        assertEquals(A11yWarningThrottle.Result.POSTED, h.t.check(WARN, T0 + DAY));
        assertEquals(2, h.posts);
    }

    @Test public void reEnabledClearsAndResetsSoTheNextOffWarnsAgain() {
        Harness h = new Harness();
        h.t.check(WARN, T0);
        assertEquals(A11yWarningThrottle.Result.CLEARED, h.t.check(OK, T0 + HOUR));
        assertEquals(1, h.clears);
        assertEquals(Long.valueOf(0L), h.m.get(KEY));
        assertEquals("turned off again: warned without waiting a day",
                A11yWarningThrottle.Result.POSTED, h.t.check(WARN, T0 + 2 * HOUR));
        assertEquals(2, h.posts);
    }

    @Test public void okNeverPosts() {
        Harness h = new Harness();
        assertEquals(A11yWarningThrottle.Result.CLEARED, h.t.check(OK, T0));
        assertEquals(0, h.posts);
        assertEquals("nothing stored when nothing was warned", null, h.m.get(KEY));
    }

    @Test public void idleClearsTheNotificationButKeepsTheBudget() {
        Harness h = new Harness();
        h.t.check(WARN, T0);
        assertEquals(A11yWarningThrottle.Result.QUIET, h.t.check(IDLE, T0 + HOUR));
        assertEquals(0, h.posts - 1);
        assertEquals(1, h.clears);
        assertEquals("service still off: a new schedule the same day is not re-announced",
                A11yWarningThrottle.Result.THROTTLED, h.t.check(WARN, T0 + 2 * HOUR));
        assertEquals(1, h.posts);
    }

    @Test public void futureTimestampDoesNotMute() {
        Harness h = new Harness();
        h.m.put(KEY, T0 + DAY);
        assertEquals(A11yWarningThrottle.Result.POSTED, h.t.check(WARN, T0));
    }

    @Test public void nullStateIsQuiet() {
        Harness h = new Harness();
        assertEquals(A11yWarningThrottle.Result.QUIET, h.t.check(null, T0));
        assertEquals(0, h.posts);
    }
}
