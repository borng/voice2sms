package com.voice2sms.sendlater.send;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * In-process registry of scheduled launches that have been issued but not yet picked up (R31).
 * Pure JVM.
 *
 * <p>Every launch of {@code GVoiceWebViewActivity} for a scheduled message carries a fresh token.
 * Exactly one party can {@link #take} it: the activity (which then sends) or the launch watchdog
 * (which then reports CANNOT_SHOW). A replayed intent (recreate after a renderer crash, a restore
 * from recents, a late tap on an old full-screen notification, a new process) finds no pending
 * token and sends nothing, so one launch can drive at most one send.
 */
final class LaunchClaims {

    static final LaunchClaims INSTANCE = new LaunchClaims();

    private final Map<Long, String> pending = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** Registers a new launch for {@code id}, replacing any earlier one, and returns its token. */
    synchronized String issue(long id) {
        String token = Long.toHexString(random.nextLong()) + Long.toHexString(System.nanoTime());
        pending.put(id, token);
        return token;
    }

    /** True exactly once for the current token of {@code id}; false for stale or unknown tokens. */
    synchronized boolean take(long id, String token) {
        if (token == null) return false;
        String current = pending.get(id);
        if (current == null || !current.equals(token)) return false;
        pending.remove(id);
        return true;
    }

    synchronized boolean isPending(long id) {
        return pending.containsKey(id);
    }
}
