package com.voice2sms;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Cross-path auto-send rate limiter (sliding window).
 *
 * Every code path that auto-sends SMS through Google Voice — Gemini interception,
 * RESPOND_VIA_MESSAGE call-reject, Wear companion, future scheduled-send — must
 * call {@link #allow()} before initiating the send. If it returns false, the
 * caller should NOT auto-send; either fall back to manual review or surface the
 * rate limit to the user. {@link com.voice2sms.wear.WearSmsListenerService} keeps
 * its own per-node limit as a separate layer; this global limit catches runaway
 * bursts regardless of which path triggered them.
 *
 * Design notes:
 * - Sliding window via a synchronized deque of timestamps. Synchronized methods
 *   make check-then-add atomic; two simultaneous callers cannot both squeeze
 *   through at the boundary.
 * - Stale entries are pruned at the start of every call so a long-idle process
 *   doesn't carry over expired timestamps that would block a fresh user action.
 * - In-memory only: process restart resets the counter. Acceptable for "basic
 *   reasonable" rate limiting; user can force-restart to bypass, but that's an
 *   explicit action.
 * - The {@code clock} hook is for unit testing and is otherwise immutable in
 *   production callers.
 *
 * Enforced by {@code SourceSafetyTest}: the auto-send chokepoint in
 * {@code GVoiceWebViewActivity.injectComposer} must call {@link #allow()} so a
 * new send path can't silently bypass the limit.
 */
public final class AutoSendRateLimiter {
    private AutoSendRateLimiter() {}

    /** Max auto-sends permitted within {@link #WINDOW_MS}. */
    public static final int MAX_PER_WINDOW = 10;
    /** Sliding window length. */
    public static final long WINDOW_MS = 60_000L;

    /** Timestamps of accepted sends within the active window. Newest at tail. */
    private static final Deque<Long> TIMESTAMPS = new ArrayDeque<>();

    /** Clock source. Replaceable in tests; production callers should never touch it. */
    static LongSupplier clock = System::currentTimeMillis;

    /**
     * Atomically check the window and, if under the limit, record this send.
     *
     * @return {@code true} if the caller may auto-send (timestamp now recorded);
     *         {@code false} if the limit is hit and the caller must abort or
     *         fall back to manual review. Callers must not retry on false.
     */
    public static synchronized boolean allow() {
        long now = clock.getAsLong();
        prune(now);
        if (TIMESTAMPS.size() >= MAX_PER_WINDOW) {
            return false;
        }
        TIMESTAMPS.offerLast(now);
        return true;
    }

    /** Number of sends counted in the active window. Useful for diagnostics / UI. */
    public static synchronized int recentCount() {
        prune(clock.getAsLong());
        return TIMESTAMPS.size();
    }

    private static void prune(long now) {
        long cutoff = now - WINDOW_MS;
        while (!TIMESTAMPS.isEmpty() && TIMESTAMPS.peekFirst() < cutoff) {
            TIMESTAMPS.pollFirst();
        }
    }

    /** Test-only. Clears state and resets the clock. */
    static synchronized void resetForTest() {
        TIMESTAMPS.clear();
        clock = System::currentTimeMillis;
    }
}
