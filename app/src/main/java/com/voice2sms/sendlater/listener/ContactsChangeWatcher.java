package com.voice2sms.sendlater.listener;

/**
 * D37 (pure JVM): keeps the listener's live-key gate current with the device contacts. While the
 * listener is connected ({@link #start()} to {@link #stop()}) a contacts observer is registered,
 * but only once READ_CONTACTS is granted (the provider refuses observers without it). Each change
 * schedules a rebuild {@link #DEBOUNCE_MS} later on the listener's worker; a later change in that
 * window replaces it, so a burst (a sync, an edit of many fields) costs one rebuild.
 *
 * <p>There is no broadcast for a runtime permission grant. The listener calls
 * {@link #ensureRegistered()} on every connect and every Send Later state broadcast, which also
 * rebuild the gate with fresh contact names; a grant is therefore picked up at the next connect or
 * schedule/state change, after which contacts edits are observed directly.
 */
final class ContactsChangeWatcher {

    /** Coalescing window for contacts changes. */
    static final long DEBOUNCE_MS = 2_000L;

    /** The Android side, injectable for tests. */
    interface Host {
        boolean readContactsGranted();

        /** Registers the contacts observer; false when the provider refused it. */
        boolean register();

        void unregister();

        /** Runs {@code task} on the listener worker after {@code delayMs}. */
        void schedule(Runnable task, long delayMs);

        /** Rebuilds the live-key gate; called on the worker. */
        void rebuildGate();
    }

    private final Host host;
    private boolean active;
    private boolean registered;
    /** Bumped by every change and by stop(): only the newest pending rebuild runs. */
    private long generation;

    ContactsChangeWatcher(Host host) {
        this.host = host;
    }

    /** Listener connected: observe contacts if allowed. */
    synchronized void start() {
        active = true;
        ensureRegistered();
    }

    /** Registers the observer when connected, not yet registered and READ_CONTACTS is granted. */
    synchronized void ensureRegistered() {
        if (!active || registered) return;
        try {
            if (!host.readContactsGranted()) return;
            registered = host.register();
        } catch (RuntimeException e) {
            registered = false;
        }
    }

    /** Listener disconnected or destroyed: unregister and drop any pending rebuild. */
    synchronized void stop() {
        active = false;
        generation++;
        if (!registered) return;
        registered = false;
        try {
            host.unregister();
        } catch (RuntimeException ignored) {
            // Already gone; nothing to do.
        }
    }

    /** The observer fired: (re)start the debounce window. */
    void onContactsChanged() {
        final long mine;
        synchronized (this) {
            if (!active) return;
            mine = ++generation;
        }
        try {
            host.schedule(() -> {
                synchronized (ContactsChangeWatcher.this) {
                    if (!active || generation != mine) return;
                }
                host.rebuildGate();
            }, DEBOUNCE_MS);
        } catch (RuntimeException ignored) {
            // Worker refused the task: the next state broadcast or connect rebuilds the gate.
        }
    }

    synchronized boolean isRegistered() {
        return registered;
    }
}
