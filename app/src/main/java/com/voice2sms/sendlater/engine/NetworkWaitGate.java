package com.voice2sms.sendlater.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The WAIT(NO_NETWORK) gate (R27, R40), pure JVM. One registration per waiting message; it fires
 * only when the default network reports INTERNET and VALIDATED (VALIDATED exists on every
 * supported API: minSdk 24 > 23), unregisters on that first fire, and allows at most one in-flight
 * re-evaluation per message. A WAIT produced by that re-evaluation does not re-register (it would
 * fire again at once and spin until the deadline); the inexact RETRY alarm at the budget deadline
 * is the fallback for that rare case (the network dropped between the callback and the ladder).
 */
final class NetworkWaitGate {

    /** Registers / unregisters a platform callback that reports capabilities to the gate. */
    interface Registrar {
        /** Returns a handle, or null when registration failed. */
        Object register(long id, NetworkWaitGate gate);

        void unregister(Object handle);
    }

    /** Re-runs the ladder for {@code id}; must run {@code done} once that finished (or failed). */
    interface Trigger {
        void reevaluate(long id, Runnable done);
    }

    private final Registrar registrar;
    private final Trigger trigger;
    private final Map<Long, Object> waiting = new HashMap<>();
    private final Set<Long> inFlight = new HashSet<>();
    /** Usable reports that arrived while register() was still running. */
    private final Set<Long> earlyUsable = new HashSet<>();

    /** Placeholder handle while register() runs. */
    private static final Object REGISTERING = new Object();
    /** Internal marker: the handle was stored, do not unregister it. */
    private static final Object KEEP = new Object();

    NetworkWaitGate(Registrar registrar, Trigger trigger) {
        this.registrar = registrar;
        this.trigger = trigger;
    }

    /** INTERNET, plus VALIDATED where the platform has it (API 23+). */
    static boolean usable(boolean internet, boolean validated, int sdkInt) {
        return internet && (sdkInt < 23 || validated);
    }

    /** Registers a wait for {@code id} unless one is registered or its re-evaluation is running. */
    void await(long id) {
        synchronized (this) {
            if (waiting.containsKey(id) || inFlight.contains(id)) return;
            waiting.put(id, REGISTERING); // a report arriving before register() returns is kept
        }
        Object handle = registrar.register(id, this);
        boolean fireNow = false;
        synchronized (this) {
            if (waiting.get(id) != REGISTERING) {
                // Cancelled while registering: drop the new callback.
                earlyUsable.remove(id);
            } else if (handle == null) {
                waiting.remove(id);
                earlyUsable.remove(id);
            } else if (earlyUsable.remove(id)) {
                waiting.remove(id);
                fireNow = inFlight.add(id);
            } else {
                waiting.put(id, handle);
                handle = KEEP;
            }
        }
        if (handle != null && handle != KEEP) registrar.unregister(handle);
        if (fireNow) fire(id);
    }

    /** A capabilities report for {@code id}'s callback. Fires at most once per registration. */
    void onCapabilities(long id, boolean internet, boolean validated, int sdkInt) {
        if (!usable(internet, validated, sdkInt)) return;
        Object handle;
        synchronized (this) {
            handle = waiting.get(id);
            if (handle == null) return;
            if (handle == REGISTERING) {
                earlyUsable.add(id);
                return;
            }
            waiting.remove(id);
            if (!inFlight.add(id)) return; // cannot happen: await skips in-flight ids
        }
        registrar.unregister(handle);
        fire(id);
    }

    private void fire(final long id) {
        try {
            trigger.reevaluate(id, () -> finished(id));
        } catch (RuntimeException e) {
            finished(id);
        }
    }

    /** Drops a pending wait for {@code id}, if any (an in-flight re-evaluation still completes). */
    void cancel(long id) {
        Object handle;
        synchronized (this) {
            handle = waiting.remove(id);
            earlyUsable.remove(id);
        }
        if (handle != null && handle != REGISTERING) registrar.unregister(handle);
    }

    private synchronized void finished(long id) {
        inFlight.remove(id);
    }

    synchronized boolean isWaiting(long id) {
        return waiting.containsKey(id) && waiting.get(id) != REGISTERING;
    }

    synchronized boolean isReevaluating(long id) {
        return inFlight.contains(id);
    }
}
