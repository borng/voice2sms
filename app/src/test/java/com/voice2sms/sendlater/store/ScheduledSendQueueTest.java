package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

public class ScheduledSendQueueTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    /** 2026-06-10T12:00Z. */
    private static final long T0 = 1_781_092_800_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    /** In-memory stand-in for the SharedPreferences store. */
    static final class MapStore implements ScheduledSendQueue.Store {
        final Map<String, Long> m = new HashMap<>();
        @Override public long getLong(String key, long def) {
            Long v = m.get(key);
            return v == null ? def : v;
        }
        @Override public void putLongs(String[] keys, long[] values) {
            for (int i = 0; i < keys.length; i++) m.put(keys[i], values[i]);
        }
    }

    private static ScheduledSendQueue queue(MapStore s, int cap) {
        return new ScheduledSendQueue(s, UTC, () -> T0, cap);
    }

    @Test public void twoSendsDueTogetherAreAtLeastTwoSecondsApart() {
        ScheduledSendQueue q = queue(new MapStore(), ScheduledSendQueue.DAILY_CAP);
        long a = q.acquireSlot(T0);
        long b = q.acquireSlot(T0);
        long c = q.acquireSlot(T0);
        assertEquals(0, a);
        assertTrue("second slot >= 2000 ms after the first", (T0 + b) - (T0 + a) >= 2000);
        assertTrue((T0 + c) - (T0 + b) >= 2000);
        assertEquals(4000, c);
    }

    @Test public void slotsAcquiredAtDifferentTimesKeepSpacingAgainstTheLastSlot() {
        ScheduledSendQueue q = queue(new MapStore(), 50);
        assertEquals(0, q.acquireSlot(T0));
        assertEquals(1500, q.acquireSlot(T0 + 500));        // last slot T0, next T0+2000
        assertEquals(0, q.acquireSlot(T0 + 10_000));        // long gap: no wait
    }

    @Test public void clockFromInjectedClockIsUsed() {
        ScheduledSendQueue q = queue(new MapStore(), 50);
        assertEquals(0, q.acquireSlot());
        assertEquals(2000, q.acquireSlot());
    }

    @Test public void dailyCapRefusesBeyondN() {
        ScheduledSendQueue q = queue(new MapStore(), 3);
        for (int i = 0; i < 3; i++) assertTrue(q.acquireSlot(T0 + i * 10_000L) >= 0);
        assertEquals(ScheduledSendQueue.REFUSED, q.acquireSlot(T0 + 40_000));
        assertEquals(3, q.usedToday(T0 + 40_000));
    }

    @Test public void productionCapIsFifty() {
        assertEquals(50, ScheduledSendQueue.DAILY_CAP);
        ScheduledSendQueue q = queue(new MapStore(), ScheduledSendQueue.DAILY_CAP);
        for (int i = 0; i < 50; i++) assertTrue(q.acquireSlot(T0 + i * 3_000L) >= 0);
        assertEquals(ScheduledSendQueue.REFUSED, q.acquireSlot(T0 + 200_000));
    }

    @Test public void dailyCapSurvivesANewQueueInstanceOverTheSameStore() {
        MapStore store = new MapStore();
        ScheduledSendQueue first = queue(store, ScheduledSendQueue.DAILY_CAP);
        for (int i = 0; i < 49; i++) assertTrue(first.acquireSlot(T0 + i * 3_000L) >= 0);
        ScheduledSendQueue second = queue(store, ScheduledSendQueue.DAILY_CAP);
        assertEquals(49, second.usedToday(T0 + 200_000));
        assertTrue(second.acquireSlot(T0 + 200_000) >= 0);
        assertEquals(ScheduledSendQueue.REFUSED, second.acquireSlot(T0 + 300_000));
        assertEquals(ScheduledSendQueue.REFUSED,
                queue(store, ScheduledSendQueue.DAILY_CAP).acquireSlot(T0 + 400_000));
    }

    @Test public void spacingSurvivesANewQueueInstance() {
        MapStore store = new MapStore();
        assertEquals(0, queue(store, 50).acquireSlot(T0));
        assertEquals(2000, queue(store, 50).acquireSlot(T0));
    }

    @Test public void refusedSlotReservesNothing() {
        MapStore store = new MapStore();
        ScheduledSendQueue q = queue(store, 1);
        assertEquals(0, q.acquireSlot(T0));
        Map<String, Long> before = new HashMap<>(store.m);
        assertEquals(ScheduledSendQueue.REFUSED, q.acquireSlot(T0 + 100));
        assertEquals(before, store.m);
    }

    @Test public void countResetsOnTheNextLocalDay() {
        ScheduledSendQueue q = queue(new MapStore(), 2);
        q.acquireSlot(T0);
        q.acquireSlot(T0 + 5_000);
        assertEquals(ScheduledSendQueue.REFUSED, q.acquireSlot(T0 + 10_000));
        assertEquals(0, q.acquireSlot(T0 + DAY));
        assertEquals(1, q.usedToday(T0 + DAY));
    }

    @Test public void dayBoundaryFollowsTheQueueTimeZone() {
        // T0 is 12:00Z; 13 h later is 01:00Z next day but still the same day in UTC-10.
        TimeZone hawaii = TimeZone.getTimeZone("Pacific/Honolulu");
        ScheduledSendQueue q = new ScheduledSendQueue(new MapStore(), hawaii, () -> T0, 1);
        assertEquals(0, q.acquireSlot(T0));
        assertEquals(ScheduledSendQueue.REFUSED, q.acquireSlot(T0 + 13L * 60 * 60 * 1000));
    }

    @Test public void lastSlotFarInTheFutureIsTreatedAsAClockChange() {
        MapStore store = new MapStore();
        ScheduledSendQueue q = queue(store, 50);
        q.acquireSlot(T0 + 3 * DAY);         // clock was ahead
        assertEquals(0, q.acquireSlot(T0));  // clock set back: no multi-day wait
    }

    @Test public void independentOfAutoSendRateLimiter() throws Exception {
        Path src = Paths.get("src/main/java/com/voice2sms/sendlater/store/ScheduledSendQueue.java");
        String code = new String(Files.readAllBytes(src), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        assertFalse(code.contains("AutoSendRateLimiter"));
        // 60 slots in one minute: far above the interactive 10/min limit, all granted.
        ScheduledSendQueue q = queue(new MapStore(), 100);
        for (int i = 0; i < 60; i++) assertTrue(q.acquireSlot(T0 + i * 1_000L) >= 0);
    }
}
