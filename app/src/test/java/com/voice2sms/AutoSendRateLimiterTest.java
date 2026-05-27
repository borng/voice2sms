package com.voice2sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;
import org.junit.Test;

/**
 * Pure-JVM tests for the global auto-send rate limiter.
 *
 * Time is injected so window-recovery tests don't have to sleep. Concurrency
 * test exercises the synchronized check-then-add to prove the boundary holds
 * under load — i.e., MAX_PER_WINDOW + 1 simultaneous callers don't all squeeze
 * through.
 */
public class AutoSendRateLimiterTest {

    private final AtomicLong virtualNow = new AtomicLong();

    @Before
    public void resetState() {
        AutoSendRateLimiter.resetForTest();
        virtualNow.set(1_000_000L);
        AutoSendRateLimiter.clock = virtualNow::get;
    }

    @Test
    public void allowsExactlyMaxThenBlocks() {
        for (int i = 0; i < AutoSendRateLimiter.MAX_PER_WINDOW; i++) {
            assertTrue("call " + i + " should be allowed", AutoSendRateLimiter.allow());
        }
        assertFalse("over-limit call must be blocked", AutoSendRateLimiter.allow());
        assertFalse("subsequent calls within the window stay blocked", AutoSendRateLimiter.allow());
    }

    @Test
    public void allowsAgainAfterWindowExpires() {
        for (int i = 0; i < AutoSendRateLimiter.MAX_PER_WINDOW; i++) {
            assertTrue(AutoSendRateLimiter.allow());
        }
        assertFalse(AutoSendRateLimiter.allow());

        // Advance past the window — every prior timestamp expires.
        virtualNow.addAndGet(AutoSendRateLimiter.WINDOW_MS + 1);
        assertTrue("post-window call must be allowed", AutoSendRateLimiter.allow());
        assertEquals(1, AutoSendRateLimiter.recentCount());
    }

    @Test
    public void slidingWindowFreesOneSlotAtATime() {
        // Fill the window with timestamps spaced 1ms apart.
        for (int i = 0; i < AutoSendRateLimiter.MAX_PER_WINDOW; i++) {
            virtualNow.incrementAndGet();
            assertTrue(AutoSendRateLimiter.allow());
        }
        assertFalse(AutoSendRateLimiter.allow());

        // Advance just past the FIRST timestamp's window expiry. Only one slot frees.
        long firstTs = virtualNow.get() - AutoSendRateLimiter.MAX_PER_WINDOW + 1;
        virtualNow.set(firstTs + AutoSendRateLimiter.WINDOW_MS + 1);

        assertTrue("one slot freed — call permitted", AutoSendRateLimiter.allow());
        assertFalse("only one slot freed — next call still blocked",
                AutoSendRateLimiter.allow());
    }

    @Test
    public void recentCountTracksWindow() {
        assertEquals(0, AutoSendRateLimiter.recentCount());
        AutoSendRateLimiter.allow();
        AutoSendRateLimiter.allow();
        AutoSendRateLimiter.allow();
        assertEquals(3, AutoSendRateLimiter.recentCount());

        virtualNow.addAndGet(AutoSendRateLimiter.WINDOW_MS + 1);
        assertEquals("stale entries pruned on read", 0, AutoSendRateLimiter.recentCount());
    }

    @Test
    public void staleEntriesDoNotBlockFreshCall() {
        // Long-idle process: a timestamp from "yesterday" must not count.
        for (int i = 0; i < AutoSendRateLimiter.MAX_PER_WINDOW; i++) {
            AutoSendRateLimiter.allow();
        }
        // Simulate 24h of process idle.
        virtualNow.addAndGet(24L * 60 * 60 * 1000);
        assertTrue("idle expiry must clear the window", AutoSendRateLimiter.allow());
    }

    @Test
    public void concurrentCallersRespectLimit() throws InterruptedException {
        // Real-clock test — synchronized method must serialize. We fire ~5x the
        // limit in parallel; exactly MAX_PER_WINDOW must succeed.
        AutoSendRateLimiter.resetForTest(); // back to real clock
        int threads = AutoSendRateLimiter.MAX_PER_WINDOW * 5;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (AutoSendRateLimiter.allow()) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }, "rate-test-" + i).start();
        }
        start.countDown();
        assertTrue("threads finished", done.await(5, TimeUnit.SECONDS));
        assertEquals("synchronized check-then-add must hold the boundary",
                AutoSendRateLimiter.MAX_PER_WINDOW, allowed.get());
    }
}
