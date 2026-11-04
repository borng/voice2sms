package com.voice2sms.sendlater.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Engine-owned per-message marks that the store does not model (I6, R31):
 * <ul>
 *   <li><b>reminded</b>: the message fell to the reminder rung (it keeps status SCHEDULED, R29).
 *       A reminded row never auto-sends again: its DUE/LAST_CALL alarms are not re-armed (boot,
 *       time change) and a stray DUE fire is ignored; only the +24 h expiry stays. Cleared by an
 *       explicit (re)schedule, a snooze, a hold or a user-started send.</li>
 *   <li><b>free releases</b>: IN_FLIGHT -> SCHEDULED releases where nothing was typed
 *       (CANNOT_SHOW). The store counts them in attemptCount; the engine subtracts them so they
 *       do not use up the single retry after a real failure.</li>
 * </ul>
 * Production: SharedPreferences ({@link AndroidEngineDeps}); tests: {@link InMemory}.
 */
public interface EngineMarks {

    boolean isReminded(long id);

    void setReminded(long id, boolean reminded);

    int freeReleases(long id);

    void addFreeRelease(long id);

    /** Drops every mark of {@code id} (terminal states). */
    void clear(long id);

    /** Process-local marks (the default when no other implementation is installed). */
    final class InMemory implements EngineMarks {
        private final Set<Long> reminded = new HashSet<>();
        private final Map<Long, Integer> free = new HashMap<>();

        @Override public synchronized boolean isReminded(long id) { return reminded.contains(id); }

        @Override public synchronized void setReminded(long id, boolean on) {
            if (on) reminded.add(id); else reminded.remove(id);
        }

        @Override public synchronized int freeReleases(long id) {
            Integer n = free.get(id);
            return n != null ? n : 0;
        }

        @Override public synchronized void addFreeRelease(long id) { free.put(id, freeReleases(id) + 1); }

        @Override public synchronized void clear(long id) {
            reminded.remove(id);
            free.remove(id);
        }
    }
}
