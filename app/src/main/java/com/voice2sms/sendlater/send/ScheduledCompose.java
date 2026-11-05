package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.domain.SendOutcome;

import java.util.UUID;

/**
 * One scheduled job's compose attempts on the GV page (LIVE-F6, LIVE-F7, LIVE-F8; R30, R31,
 * OD27, OD28). Pure JVM and thread-safe: the activity's main thread injects and learns "typed",
 * the JavaBridge thread asks {@link #grantClick} synchronously.
 *
 * <ul>
 *   <li><b>Injection (LIVE-F6).</b> On a cold WebView GV fires further main-frame onPageFinished
 *       after the first one; each is a new document that wiped the running script. Every later
 *       GV messages-list onPageFinished re-injects ({@link #shouldReinject}) while no Send click
 *       was granted, up to {@link #MAX_INJECTIONS}; inject.js's generation counter cancels an
 *       older run in the same document. Each injection gets a fresh token; answers carrying an
 *       older token are ignored.</li>
 *   <li><b>Typed (LIVE-F8).</b> "Typed" means the page confirmed that our body is in the message
 *       box ({@link #onTyped}). Before that every failure is CANNOT_SHOW; after it
 *       SENT_UNVERIFIED ({@link #outcomeOnFailure}). A re-injection (the document that held the
 *       text is gone, no click was granted) starts over as not typed.</li>
 *   <li><b>Click gate.</b> The scheduled script never taps Send on its own: it asks
 *       {@link #grantClick}, which says yes at most once per job, only for the current token and
 *       only while the gate is open. {@link #close} (Stop, any failure, the outcome) shuts it, so
 *       a job reported CANNOT_SHOW can no longer send, and re-injection can never send twice.</li>
 * </ul>
 */
public final class ScheduledCompose {

    /** Bound on injections per job (the first one plus re-injections); COMPOSE_TIMEOUT_MS bounds time. */
    public static final int MAX_INJECTIONS = 6;

    private static final String GV_PREFIX = "https://voice.google.com/";

    private final long jobId;
    private int injections;
    private String token;
    private boolean typed;
    private boolean clickGranted;
    private boolean closed;

    public ScheduledCompose(long jobId) {
        this.jobId = jobId;
    }

    /**
     * LIVE-F6 decision for a main-frame onPageFinished after the first injection: re-inject when
     * the page is a GV page that is not a thread (a reload of the messages list wiped the script),
     * no Send click was granted, the gate is open and the bound is not reached.
     */
    public static boolean shouldReinject(String url, int injections, boolean clickGranted, boolean closed) {
        if (url == null || !url.startsWith(GV_PREFIX)) return false;
        if (url.contains("itemId=")) return false;   // a thread page: our own run navigated there
        return injections > 0 && injections < MAX_INJECTIONS && !clickGranted && !closed;
    }

    /** LIVE-F8: typed (or a click granted) -> SENT_UNVERIFIED; nothing typed -> CANNOT_SHOW. */
    public static SendOutcome outcomeOnFailure(boolean typed, boolean clickGranted) {
        return typed || clickGranted ? SendOutcome.SENT_UNVERIFIED : SendOutcome.CANNOT_SHOW;
    }

    /** A first or further injection may run now. */
    public synchronized boolean mayInject() {
        return !closed && !clickGranted && injections < MAX_INJECTIONS;
    }

    /**
     * Starts one injection: a fresh token, not typed. Null when {@link #mayInject} is false (the
     * caller injects nothing).
     */
    public synchronized String nextInjection() {
        if (!mayInject()) return null;
        injections++;
        typed = false;
        token = jobId + "." + injections + "." + UUID.randomUUID();
        return token;
    }

    /** The page confirmed our body is in the box for {@code t}. False for a stale token or a closed gate. */
    public synchronized boolean onTyped(String t) {
        if (closed || t == null || !t.equals(token)) return false;
        typed = true;
        return true;
    }

    /**
     * The script asks to tap Send (JavaBridge thread). Yes at most once per job, only for the
     * current token and while open. A granted click counts as typed.
     */
    public synchronized boolean grantClick(String t) {
        if (closed || clickGranted || t == null || !t.equals(token)) return false;
        clickGranted = true;
        typed = true;
        return true;
    }

    /** Shuts the gate. True when no click was ever granted (nothing can be sent from now on). */
    public synchronized boolean close() {
        closed = true;
        return !clickGranted;
    }

    /** Shuts the gate and returns the outcome of a failure at this moment. */
    public synchronized SendOutcome closeForFailure() {
        closed = true;
        return outcomeOnFailure(typed, clickGranted);
    }

    public synchronized boolean isTyped() {
        return typed;
    }

    public synchronized boolean isClickGranted() {
        return clickGranted;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized int injections() {
        return injections;
    }
}
