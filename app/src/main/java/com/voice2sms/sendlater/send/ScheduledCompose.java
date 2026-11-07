package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.web.OriginGuard;
import com.voice2sms.sendlater.web.ThreadUrl;

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
 *   <li><b>Pre-send check (D30, LIVE-F3).</b> When the job must check the thread first
 *       ({@link #requirePreSendCheck}), no click is granted until {@link #clearPreSend}. A click
 *       asked for before that through the old boolean ask ({@link #grantClick}) is refused and
 *       remembered ({@link #wasClickRefusedForCheck}): such a job can no longer send.</li>
 *   <li><b>Tri-state ask (LIVE-F10).</b> The scheduled script asks {@link #clickState}: WAIT while
 *       the check is pending and the gate is open (the script polls again every
 *       {@link #CLICK_POLL_MS}, re-checking the box each time), YES at most once per job for the
 *       current token once the check cleared, NO otherwise. A WAIT answer is never remembered as a
 *       refusal, so a check that clears after typing still lets the job send.</li>
 *   <li><b>Thread page (LIVE-F10).</b> The scheduled compose loads the job's own thread URL
 *       ({@link ThreadUrl#threadUrlOf}) and types into that thread's box
 *       ({@link #threadPageState}); only when GV shows no conversation for the number does it use
 *       the new-conversation flow ({@link #composeMode}). A reload of the job's own thread
 *       re-injects like a reload of the messages list ({@link #shouldReinject(String, String, int,
 *       boolean, boolean)}).</li>
 * </ul>
 */
public final class ScheduledCompose {

    /** Bound on injections per job (the first one plus re-injections); COMPOSE_TIMEOUT_MS bounds time. */
    public static final int MAX_INJECTIONS = 6;
    /**
     * LIVE-F11: thread-URL loads per job: the one of beginCompose plus the single reload, shared by
     * {@link #onWrongPage} and {@link #onNoThread}.
     */
    public static final int MAX_THREAD_LOADS = 2;

    /** inject.js polls a WAIT answer this often. */
    public static final long CLICK_POLL_MS = 500L;
    /**
     * LIVE-F10: on the job's thread page, GV must show the conversation (gv-message-list) within
     * this of the injection; otherwise there is none for the number and the new-conversation
     * flow is used. A GV reload of the thread page re-injects and the wait starts over.
     */
    public static final long THREAD_SETTLE_MS = 10_000L;
    /** The script stops polling WAIT this long after the pre-send deadline (Java decides by then). */
    public static final long CLICK_WAIT_MARGIN_MS = 5_000L;

    /** Answer of {@link #clickState}; {@link #wire()} is what inject.js sees. */
    public enum ClickState {
        /** Tap Send now (granted, once per job). */
        YES("yes"),
        /** The pre-send check is still pending and the gate is open: ask again later. */
        WAIT("wait"),
        /** Never tap Send for this token. */
        NO("no");

        private final String wire;

        ClickState(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** Which compose flow an injection runs (LIVE-F10). */
    public enum Mode {
        /** Type into the open thread of the job's number (the page is that thread's URL). */
        THREAD,
        /** voice2sms()'s new-conversation flow (recipient chip); the conversation counts as empty. */
        NEW_CONVERSATION;

        public String wire() {
            return this == THREAD ? "thread" : "new";
        }
    }

    /** What the thread page shows (LIVE-F10, mirrored by inject.js v2sScheduledCompose). */
    public enum ThreadPage {
        /** The job's conversation is shown: type into its box. */
        THREAD,
        /** Not yet rendered: poll again. */
        WAIT,
        /** No conversation for the number (GV went elsewhere, or nothing after the settle time). */
        FALLBACK,
        /**
         * LIVE-F11 ({@link #pageState}): the page is not the job's thread (the messages list, or GV
         * rewrote the URL while booting). Not a fallback: Java loads the job's thread again
         * ({@link #onWrongPage}) or decides the fallback once the thread-load budget is spent.
         */
        WRONG_PAGE
    }

    /** live-presend-failclosed: what to do when the job's thread page settled without the conversation. */
    public enum NoThread {
        /** Full-load the job's thread page once more and settle again (a slow render looks the same). */
        RELOAD,
        /** Use the new-conversation flow. */
        FALLBACK,
        /** Nothing (stale token, closed gate, a granted click). */
        IGNORE
    }

    private final long jobId;
    /** live-presend-failclosed: the job's thread page was reloaded once after a settle without the list. */
    private boolean threadReloaded;
    private int injections;
    private String token;
    private boolean typed;
    private boolean clickGranted;
    private boolean closed;
    private boolean preSendRequired;
    private boolean preSendCleared;
    private boolean clickRefusedForCheck;
    /** LIVE-F10 F1: the check cleared the send on an empty-conversation (new-conversation) read. */
    private boolean clearedOnEmpty;
    /** LIVE-F10 F1: that clearance was revoked; the controller must read the thread again. */
    private boolean recheckDue;

    public ScheduledCompose(long jobId) {
        this.jobId = jobId;
    }

    /**
     * LIVE-F6 decision for a main-frame onPageFinished after the first injection: re-inject when
     * the page is a GV page that is not a thread (a reload of the messages list wiped the script),
     * no Send click was granted, the gate is open and the bound is not reached.
     */
    public static boolean shouldReinject(String url, int injections, boolean clickGranted, boolean closed) {
        if (!OriginGuard.isTrustedGvUrl(url)) return false;
        if (url.contains("itemId=")) return false;   // a thread page: our own run navigated there
        return injections > 0 && injections < MAX_INJECTIONS && !clickGranted && !closed;
    }

    /**
     * LIVE-F10 overload: as {@link #shouldReinject(String, int, boolean, boolean)}, and a reload of
     * the job's own thread page ({@code e164}'s 1:1 thread URL) re-injects too. Any other thread or
     * itemId page (the new-conversation draft) still never does; never after a granted click.
     */
    public static boolean shouldReinject(String url, String e164, int injections, boolean clickGranted,
                                         boolean closed) {
        if (!OriginGuard.isTrustedGvUrl(url)) return false;
        if (url.contains("itemId=") && !ThreadUrl.isThreadOf(url, e164)) return false;
        return injections > 0 && injections < MAX_INJECTIONS && !clickGranted && !closed;
    }

    /**
     * LIVE-F10: the flow an injection on {@code url} runs. THREAD on the job's own thread page,
     * unless GV already showed no conversation there for this job ({@code gvShowedNoThread});
     * anything else (GV redirected away, the messages list) is NEW_CONVERSATION.
     */
    public static Mode composeMode(String url, String e164, boolean gvShowedNoThread) {
        if (!gvShowedNoThread && ThreadUrl.isThreadOf(url, e164)) return Mode.THREAD;
        return Mode.NEW_CONVERSATION;
    }

    /**
     * LIVE-F10 thread-vs-fallback decision on the thread page (inject.js mirrors it):
     * {@code pageE164} is the open thread's number from the location (null when not a 1:1
     * thread), {@code messageListShown} whether gv-message-list is rendered, {@code waitedMs} the
     * time since the injection. Another page -> FALLBACK; the list shown -> THREAD; else WAIT
     * until {@code settleMs}, then FALLBACK.
     */
    public static ThreadPage threadPageState(String pageE164, String jobE164, boolean messageListShown,
                                             long waitedMs, long settleMs) {
        if (pageE164 == null || jobE164 == null || !pageE164.equals(jobE164)) return ThreadPage.FALLBACK;
        if (messageListShown) return ThreadPage.THREAD;
        return waitedMs >= settleMs ? ThreadPage.FALLBACK : ThreadPage.WAIT;
    }

    /**
     * LIVE-F11 thread-vs-fallback decision of the scheduled script (inject.js threadPageState
     * mirrors it): as {@link #threadPageState}, except that a page which is not the job's 1:1
     * thread is {@link ThreadPage#WRONG_PAGE} (the script waits and asks Java to load the job's
     * thread again, {@link #onWrongPage}) instead of an immediate FALLBACK. FALLBACK is left for
     * the job's own thread page settling without the conversation.
     */
    public static ThreadPage pageState(String pageE164, String jobE164, boolean messageListShown,
                                       long waitedMs, long settleMs) {
        if (pageE164 == null || jobE164 == null || !pageE164.equals(jobE164)) return ThreadPage.WRONG_PAGE;
        return threadPageState(pageE164, jobE164, messageListShown, waitedMs, settleMs);
    }

    /**
     * LIVE-F11: the page of the running job is not its thread (a GV load other than the job's thread
     * URL finished, an auth MergeSession continuation, or inject.js saw another location). The
     * job's thread URL is loaded again (RELOAD) while the single reload budget is unspent and the
     * injection bound is not reached: at most {@link #MAX_THREAD_LOADS} thread loads per job,
     * shared with {@link #onNoThread}. After that FALLBACK (the fail-closed pre-send rule then
     * decides the new-conversation flow). IGNORE once a click was granted or the gate is closed.
     */
    public synchronized NoThread onWrongPage() {
        if (closed || clickGranted) return NoThread.IGNORE;
        if (!threadReloaded && injections < MAX_INJECTIONS) {
            threadReloaded = true;
            return NoThread.RELOAD;
        }
        return NoThread.FALLBACK;
    }

    /** LIVE-F11: as {@link #onWrongPage()}, reported by inject.js for {@code t} (stale token: IGNORE). */
    public synchronized NoThread onWrongPage(String t) {
        if (closed || clickGranted || t == null || !t.equals(token)) return NoThread.IGNORE;
        return onWrongPage();
    }

    /**
     * live-presend-failclosed: inject.js reported that the thread page settled without the
     * conversation for {@code t}. On the S22 a never-texted number's thread page shows the box but
     * no gv-message-list, exactly like a slow render: so the first time, while the page is still
     * the job's own thread ({@code pageIsJobThread}), the thread is reloaded once (full load) and
     * given one more settle; after that, or when GV went elsewhere, the new-conversation flow runs.
     */
    public synchronized NoThread onNoThread(String t, boolean pageIsJobThread) {
        if (closed || clickGranted || t == null || !t.equals(token)) return NoThread.IGNORE;
        // The same single reload budget as a wrong page; a page that is not the job's thread falls back.
        return pageIsJobThread ? onWrongPage() : NoThread.FALLBACK;
    }

    /** The job's thread page was reloaded once after a settle without the conversation. */
    public synchronized boolean wasThreadReloaded() {
        return threadReloaded;
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
        if (preSendRequired && !preSendCleared) {
            // D30: the thread check has not cleared the send yet. The old boolean ask cannot wait,
            // so the refusal is remembered; the scheduled script uses clickState (WAIT) instead.
            clickRefusedForCheck = true;
            return false;
        }
        clickGranted = true;
        typed = true;
        return true;
    }

    /**
     * LIVE-F10 tri-state ask (JavaBridge thread). NO for a closed gate, a granted click, a stale
     * or null token; WAIT while the pre-send check is pending (not remembered as a refusal); else
     * YES, once per job (a granted click counts as typed).
     */
    public synchronized ClickState clickState(String t) {
        if (closed || clickGranted || t == null || !t.equals(token)) return ClickState.NO;
        if (preSendRequired && !preSendCleared) return ClickState.WAIT;
        clickGranted = true;
        typed = true;
        return ClickState.YES;
    }

    /**
     * LIVE-F10 F1: as {@link #clickState(String)}, from a page that is ({@code onJobThread}) or is
     * not the job's own 1:1 thread. When the check cleared the send only because the
     * new-conversation flow counted the conversation as empty, and the page is now the job's
     * thread (GV opened the number's conversation), that clearance is dropped first: the answer
     * is WAIT and the controller reads the thread again ({@link #takeRecheckDue}).
     */
    public synchronized ClickState clickState(String t, boolean onJobThread) {
        if (onJobThread) revokeEmptyClearance();
        return clickState(t);
    }

    /**
     * LIVE-F10 F1: the pre-send check found nothing that stops the send, but only by counting the
     * conversation as empty (new-conversation flow). As {@link #clearPreSend}; the clearance is
     * dropped again if the job's thread shows up before a click is granted
     * ({@link #revokeEmptyClearance}).
     */
    public synchronized boolean clearPreSendOnEmptyConversation() {
        clearedOnEmpty = true;
        return clearPreSend();
    }

    /**
     * LIVE-F10 F1: drops a clearance given on an empty-conversation read (the job's thread is
     * shown now). True when it was dropped: the check is pending again and a re-read is due. False
     * when there was none, a click was already granted or the gate is closed.
     */
    public synchronized boolean revokeEmptyClearance() {
        if (!clearedOnEmpty || closed || clickGranted) return false;
        clearedOnEmpty = false;
        preSendCleared = false;
        recheckDue = true;
        return true;
    }

    /** LIVE-F10 F1: true once after {@link #revokeEmptyClearance} dropped a clearance. */
    public synchronized boolean takeRecheckDue() {
        boolean due = recheckDue;
        recheckDue = false;
        return due;
    }

    /** {@code t} is the current injection's token and the gate is open. */
    public synchronized boolean isCurrent(String t) {
        return !closed && t != null && t.equals(token);
    }

    /** Shuts the gate. True when no click was ever granted (nothing can be sent from now on). */
    public synchronized boolean close() {
        closed = true;
        return !clickGranted;
    }

    /**
     * Shuts the gate and returns the outcome of a failure at this moment. D30: CANNOT_SHOW while
     * the pre-send check has not cleared the send (no click can have been granted).
     */
    public synchronized SendOutcome closeForFailure() {
        closed = true;
        // D30: while the pre-send check is pending no click can have been granted: nothing sent.
        if (preSendRequired && !preSendCleared && !clickGranted) return SendOutcome.CANNOT_SHOW;
        return outcomeOnFailure(typed, clickGranted);
    }

    /** D30: no click may be granted until {@link #clearPreSend}. Call before the first injection. */
    public synchronized void requirePreSendCheck() {
        preSendRequired = true;
    }

    /**
     * D30: the pre-send check found nothing that stops the send. True when a click can still be
     * granted (open gate, no click refused while the check was pending).
     */
    public synchronized boolean clearPreSend() {
        preSendCleared = true;
        return !closed && !clickRefusedForCheck;
    }

    /** A pre-send check is required and has not cleared the send. */
    public synchronized boolean isPreSendPending() {
        return preSendRequired && !preSendCleared;
    }

    /** The script asked to tap Send while the check was pending (and was refused). */
    public synchronized boolean wasClickRefusedForCheck() {
        return clickRefusedForCheck;
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
