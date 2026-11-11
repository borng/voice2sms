package com.voice2sms.sendlater.send;

/**
 * deliver M1 (F2): the arithmetic of a launch's claim watchdog, kept pure for JVM tests. The
 * watchdog's deadline restarts at every launch() pass, so neither the row reload nor a bounded
 * wait for a regular send ({@link RegularSend#MAX_LAUNCH_WAIT_MS}) eats the activity's window.
 */
final class ClaimWindow {

    private ClaimWindow() {}

    /** The deadline after a launch() pass at {@code now}: at least {@code windowMs} away, never shorter. */
    static long rearmed(long deadline, long now, long windowMs) {
        return Math.max(deadline, now + windowMs);
    }

    /** How long the watchdog still waits; 0 fires it (also when nothing was recorded). */
    static long timeLeft(Long deadline, long now) {
        return deadline == null ? 0L : Math.max(0L, deadline - now);
    }
}
