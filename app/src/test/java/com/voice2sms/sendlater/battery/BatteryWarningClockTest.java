package com.voice2sms.sendlater.battery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Implementer's extra cases for {@link BatteryWarningThrottle}: clock changes and the exact edge. */
public class BatteryWarningClockTest {

    private static final long T0 = 1_781_092_800_000L;
    private static final long DAY = BatteryWarningThrottle.INTERVAL_MS;

    private final Map<String, Long> m = new HashMap<>();
    private int posts;

    private BatteryWarningThrottle throttle() {
        return new BatteryWarningThrottle(new BatteryWarningThrottle.Store() {
            @Override public long getLong(String key, long def) { Long v = m.get(key); return v == null ? def : v; }
            @Override public void putLong(String key, long value) { m.put(key, value); }
        }, () -> T0, () -> false, () -> posts++);
    }

    @Test public void clockSetBackDoesNotMuteTheWarningForever() {
        m.put(BatteryWarningThrottle.KEY_WARNED_AT, T0 + 10 * DAY);
        assertTrue(throttle().maybeNotify(T0));
    }

    @Test public void exactlyOneIntervalLaterIsAllowed() {
        assertTrue(throttle().maybeNotify(T0));
        assertFalse(throttle().maybeNotify(T0 + DAY - 1));
        assertTrue(throttle().maybeNotify(T0 + DAY));
    }

    @Test public void healthyTextNeverMentionsForceStop() {
        String t = BatteryAdvice.text(new BatteryStatus(false, true, false), false);
        assertFalse(t, t.contains("force-stopped"));
    }
}
