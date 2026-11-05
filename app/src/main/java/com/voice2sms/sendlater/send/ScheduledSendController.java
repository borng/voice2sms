package com.voice2sms.sendlater.send;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.domain.Classification;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.PreSendCheck;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.engine.RemindCause;
import com.voice2sms.sendlater.engine.SendLaterEngine;
import com.voice2sms.sendlater.store.SendLaterRepository;
import com.voice2sms.sendlater.web.ThreadBubble;
import com.voice2sms.sendlater.web.ThreadReadCallback;
import com.voice2sms.sendlater.web.ThreadUrl;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The scheduled branch of GVoiceWebViewActivity (R30, R31, OD10, OD11). The activity owns one
 * controller per instance and delegates to it from onCreate, onNewIntent, onPageFinished and
 * onDestroy; this class holds all the logic.
 *
 * <p>Flow for one launch carrying {@link #EXTRA_SCHEDULED_MESSAGE_ID}: take the launch token
 * ({@link LaunchClaims}); only then put the opaque {@link LockCover} over the WebView and show over
 * the keyguard / turn the screen on (never dismiss the keyguard); load the IN_FLIGHT row off the
 * main thread, ask the activity to open the composer for {e164, text}; once inject.js auto-send is
 * injected, poll the open thread with readThread plus a composer probe ({@link SendWatch}): the
 * first read taken while our text is still unsent is the baseline, and only a NEW outbound bubble
 * with the body hash beyond it verifies ({@link ThreadBaseline}, {@link SendVerifier}), once it
 * has settled: -> SENT_VERIFIED (with a late-reply detail when an inbound landed between the check
 * and the send); nothing settled within the verify window -> SENT_UNVERIFIED (the engine holds it
 * and never re-sends unread, I5); a failure before anything was typed -> CANNOT_SHOW with a
 * {@link SendDetails} detail. An identical older bubble never proves a send. The outcome goes to
 * {@link SendLaterEngine#onSendOutcome}, which owns every status change, at the latest when the
 * verify window ends. Launches that arrive while one is running queue behind it.
 *
 * <p>Never uses the Wear/Gemini auto-send extra or the global auto-send rate limiter: the
 * scheduled queue is the limiter (R30).
 *
 * <p>send-hardening (OD26) on top of fix pass 2 and send-lock-cover:
 * <ul>
 *   <li>Every claimed scheduled job is covered for its whole run, locked or not
 *       ({@link LockCoverPolicy}); the lock flags are set only while the cover is up and cleared
 *       before it comes down. The {@link LockCoverPolicy#HARD_TIMEOUT_MS} timeout and "a pause
 *       ends the job" apply to every job. The keyguard state is re-read on resume, pause, focus
 *       change and screen / unlock broadcasts, and only decides what a job end does (finish while
 *       locked, else uncover and stay).</li>
 *   <li>OD27: this controller is the only producer of SENT_VERIFIED, from its own settle or
 *       server-time rule during the job. The engine never reads this thread to verify or retry
 *       an outcome, so nothing is armed for it and no thread source is registered.</li>
 *   <li>R3: an unclaimed launch (a stale intent, or the relaunch after a renderer crash) clears
 *       the lock flags and, while locked, finishes at once without loading the page.</li>
 *   <li>live-send-path ({@link ScheduledCompose}): inject.js v2sScheduledCompose runs the
 *       compose flow without its own auto-send and is injected again after a cold WebView's
 *       later main-frame loads (LIVE-F6); the job is "typed" only once the page confirms our
 *       body is in the box (LIVE-F8), and Stop stays on the cover until then (LIVE-F7); Send is
 *       tapped only when {@link #mayClick} grants it, at most once per job, never after Stop, a
 *       failure or the outcome.</li>
 *   <li>live-presend (D30, LIVE-F3; {@link PreSendRead}, {@link PreSendCheck}): unless the
 *       engine dispatched with {@link PreSendCheck#off()} (the user's own tap), the open thread is
 *       read from the first injection on, and Send is granted only once two agreeing reads
 *       {@link PreSendRead#MIN_GAP_MS} apart show nothing that stops the send. The compose script
 *       types first and asks for the tap after its delay; the gate refuses it while the check is
 *       pending, so the order is "compose without sending, read, then send". A newer genuine
 *       reply (echoes of our own text excluded) with holdIfReplied, the user's similar text, an
 *       outbound under HOLD_AND_ASK, or an unreadable thread end the job with CANNOT_SHOW and the
 *       verdict's detail; the engine holds, self-preempts or reminds. Nothing is sent then, and
 *       never "may have already sent".</li>
 *   <li>live-thread-compose (LIVE-F10): the job's own thread URL is loaded by a full page load
 *       ({@link ThreadUrl#threadUrlOf}) and the script types into that conversation's box, so the
 *       pre-send check and the verification read the conversation itself; only when GV shows no
 *       conversation for the number ({@link #onNoThread}) does the new-conversation flow run, and
 *       the check then treats the conversation as empty
 *       ({@link PreSendRead#classifyNoConversation}), but only on this job's draft once the box
 *       holds our body, and never once GV showed the number's thread in that flow
 *       ({@link NewConversationReads}); if the thread shows up before a click is granted, the
 *       empty clearance is dropped and the thread is read again. The script asks the tri-state gate
 *       ({@link #clickState}) and polls while it says WAIT, so a check still pending after typing
 *       no longer costs the send. A job that ends with no click granted clears our text from the
 *       box ({@link Host#clearComposer}), so no scheduled text is left behind.</li>
 * </ul>
 */
public final class ScheduledSendController {

    private static final String TAG = "SendLaterSend";

    /** Long id of the IN_FLIGHT message this launch sends. */
    public static final String EXTRA_SCHEDULED_MESSAGE_ID = "com.voice2sms.sendlater.extra.SCHEDULED_MESSAGE_ID";
    /** One-shot token from {@link LaunchClaims}; a replayed intent carries a stale one. */
    public static final String EXTRA_LAUNCH_TOKEN = "com.voice2sms.sendlater.extra.LAUNCH_TOKEN";
    /** Wall time of the engine's thread check that allowed this send (late-reply window). */
    public static final String EXTRA_CHECK_TIME = "com.voice2sms.sendlater.extra.CHECK_TIME";
    /**
     * OD28: the screen was dark (not interactive) when {@link WebViewScheduledSender} planned this
     * launch. Decides {@link #launchedDark}; the activity's own reading is the fallback only when
     * the extra is absent.
     */
    public static final String EXTRA_LAUNCHED_DARK = "com.voice2sms.sendlater.extra.LAUNCHED_DARK";

    /**
     * CANNOT_SHOW cause for a row with no recipient or no text (OD28), from the controller and
     * from {@link WebViewScheduledSender#start}: {@link SendDetails#forEngine} maps it to
     * {@code could_not_send}, the generic "Couldn't send automatically" reminder (not LOCKED).
     */
    static final String DETAIL_NO_RECIPIENT = "no_recipient";

    /** Detail passed with SENT_VERIFIED when an inbound landed between the check and the send. */
    public static final String DETAIL_LATE_REPLY = "late_reply";

    /** Page load plus composer injection must happen within this (else CANNOT_SHOW). */
    static final long COMPOSE_TIMEOUT_MS = 40_000L;
    /**
     * After injection: inject.js may poll the page for up to ~25 s before it clicks Send, then
     * about 20 s for the bubble to show. No new own bubble by then -> SENT_UNVERIFIED.
     */
    static final long VERIFY_WINDOW_MS = 45_000L;
    /** Poll interval until the pre-send baseline is taken (the thread opens, then Send follows). */
    static final long BASELINE_POLL_MS = 300L;
    static final long READ_INTERVAL_MS = 1_000L;
    /**
     * Minimum inject.js auto-send delay on the scheduled path: leaves the polls time to read the
     * opened thread before Send is tapped (a late baseline only ever errs to unverified) and the
     * pre-send check (D30) time for its two agreeing reads before the script asks to tap Send.
     */
    static final long MIN_SEND_DELAY_MS = 4_000L;
    /**
     * D30: the pre-send check must have two agreeing reads within this from the first injection;
     * otherwise the thread is unreadable (CHECK: reminder; REQUIRED: HELD(UNVERIFIED)).
     */
    static final long PRESEND_TIMEOUT_MS = 25_000L;
    /** D30 detail when the check cleared only after the script's Send request was refused. */
    static final String DETAIL_PRESEND_SLOW = "presend_slow";
    /**
     * OD34: thread mode found text in the thread's box that is not our body before typing; the
     * job stops unsent (CANNOT_SHOW -> reminder) and the user's draft is left as it was.
     */
    static final String DETAIL_DRAFT_PRESENT = "draft_present";
    /** Extra compose time after the one reload of the thread page (a second settle plus the load). */
    static final long RELOAD_COMPOSE_GRACE_MS = ScheduledCompose.THREAD_SETTLE_MS + 5_000L;
    /**
     * Upper bound on staying covered after the last outcome (12 s, kept from send-hardening; OD27
     * accepts it). The activity normally finishes on the engine's state broadcast, well before
     * this.
     */
    static final long FINISH_GRACE_MS = 12_000L;
    /** After a job that reported nothing (its row was resolved or canceled meanwhile). */
    static final long IDLE_FINISH_MS = 2_000L;
    /** After the engine's state broadcast for the last outcome. */
    static final long ENGINE_DONE_FINISH_MS = 300L;
    static final int READ_LIMIT = 30;
    /** A pause/stop shorter than this is a launch transient (screen waking). */
    static final long PAUSE_GRACE_MS = 1_000L;

    /** What the controller needs from the activity. Every WebView touch is behind isAlive(). */
    public interface Host {
        /** The activity's isActivityAlive(): WebView present, not finishing or destroyed. */
        boolean isAlive();

        /** Main-frame URL (thread-safe snapshot). */
        String mainFrameUrl();

        /**
         * Loads the thread of {@code e164} (full page load; LIVE-F10) so onPageFinished injects the
         * scheduled composer. False when not alive.
         */
        boolean beginCompose(String e164, String text);

        /**
         * LIVE-F10 fallback: GV shows no conversation for the number; loads the messages list
         * (full load) so onPageFinished injects the new-conversation flow. False when not alive.
         */
        boolean loadMessagesList();

        /**
         * LIVE-F10: empties the message box when it still holds exactly {@code text} (our
         * scheduled body); anything else is left alone. No-op when not alive.
         */
        void clearComposer(String text);

        /** GVoiceWebViewActivity.readThread. */
        void readThread(String e164, int limit, ThreadReadCallback cb);

        /**
         * Main thread callback: TRUE when the message box still holds text (Send not tapped yet),
         * FALSE when it is empty or missing, null when the page could not be asked.
         */
        void probeComposer(ComposerProbe cb);

        /** Activity.finish(). */
        void finish();

        /** The WebView the cover hides (null when not alive). */
        View pageView();
    }

    /** Answer of {@link Host#probeComposer}. */
    public interface ComposerProbe {
        void onProbe(Boolean pending);
    }

    private static final class Job {
        final long id;
        final long checkTimeMs;
        String e164;
        String bodyHash;
        SendWatch watch;
        /**
         * LIVE-F8: the page confirmed our body is in the message box for the current injection
         * (main-thread mirror of {@link ScheduledCompose#isTyped}). Typing has started: Stop goes.
         */
        boolean injected;
        /** LIVE-F6: injections, typed state and the Send click gate; null until the row loaded. */
        ScheduledCompose compose;
        /** inject.js send delay of the last injection. */
        long sendDelayMs;
        long deadlineMs;
        /** Hard end, {@link LockCoverPolicy#HARD_TIMEOUT_MS} after the claim (every job, OD26). */
        final long hardDeadlineMs;
        /** The IN_FLIGHT row as loaded (text, watermarks, policy) for the pre-send check. */
        ScheduledMessage message;
        /** D30: how strict the pre-send check is ({@link PreSendCheck#off()}: none). */
        PreSendCheck check = PreSendCheck.off();
        /** D30: the agreement state of the pre-send reads; null until they start. */
        PreSendRead presend;
        long presendDeadlineMs;
        /** LIVE-F10: GV showed no conversation on the thread page; the new-conversation flow runs. */
        boolean gvShowedNoThread;
        /** LIVE-F10: compose flow of the last injection. */
        ScheduledCompose.Mode mode = ScheduledCompose.Mode.THREAD;
        /** LIVE-F10 F1: the pre-send reads of the new-conversation flow; null in thread mode. */
        NewConversationReads fallbackReads;
        /** LIVE-F10: the end-of-job clear of our text was sent once. */
        boolean composerCleared;
        /** live-presend-failclosed: compose time added once by the thread-page reload. */
        long composeGraceMs;

        Job(long id, long checkTimeMs, long claimedAtMs) {
            this.id = id;
            this.checkTimeMs = checkTimeMs;
            this.hardDeadlineMs = claimedAtMs + LockCoverPolicy.HARD_TIMEOUT_MS;
        }
    }

    private static volatile WeakReference<ScheduledSendController> live = new WeakReference<>(null);
    /** D30: the pre-send check the engine asked for, per message id, until its job loads. */
    private static final Map<Long, PreSendCheck> preSendChecks = new ConcurrentHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final Context app;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private Job current;
    /** Compose state of the running job, read by the JavaBridge thread ({@link #mayClick}). */
    private volatile ScheduledCompose liveCompose;
    private volatile boolean busy;
    /**
     * The keyguard is locked or the screen is dark, as last re-read (resume, pause, focus change,
     * screen / unlock broadcasts, and every finish decision). Decides only what a job end does;
     * never whether the cover shows (OD26).
     */
    private boolean lockedNow;
    /**
     * OD27: the running job was claimed while the screen was dark (a DIRECT/FSI launch that
     * turn-screen-on woke, possibly inside the lock grace period). Such a job finishes at its end
     * instead of uncovering.
     */
    private boolean launchedDark;
    /** {@link #EXTRA_LAUNCHED_DARK} of the launch being claimed (null: absent); read once. */
    private Boolean plannedDark;
    /**
     * OD28: a non-scheduled intent arrived over the keyguard while covered. The lock flags are
     * already cleared; the cover stays until onStop so the thread never shows while the keyguard
     * animates back.
     */
    private boolean coverUntilStop;
    /** An unclaimed launch finished at once over the keyguard: the activity must not load GV (R3). */
    private boolean skipPageLoad;
    /** Id of the last outcome reported; its engine broadcast ends the job. */
    private long awaitingEngineId = -1L;
    private BroadcastReceiver engineDone;
    private BroadcastReceiver screenEvents;
    /** A launch claim succeeded in this activity (lock flags may apply). */
    private boolean claimed;
    private LockCoverPolicy.JobState jobState = LockCoverPolicy.JobState.IDLE;
    private final LockCover cover;
    /** Recipient of the running (or last) job: the only thread that may load while covered. */
    private volatile String targetE164;
    /** Hard deadline of the last job (bounds the post-outcome wait). */
    private long lastHardDeadlineMs;
    private boolean resumed;
    private LockCoverPolicy.Event pauseEvent = LockCoverPolicy.Event.PAUSED;
    private final Runnable hardTimeout = this::onHardTimeout;
    private final Runnable pauseCheck = this::onPausedTooLong;

    public ScheduledSendController(Activity activity, Host host) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        this.host = host;
        this.cover = new LockCover(activity);
        live = new WeakReference<>(this);
    }

    // ================================================================== process-wide view

    /** A live GVoiceWebViewActivity is running or queuing scheduled sends. Any thread. */
    static boolean isBusy() {
        ScheduledSendController c = live.get();
        return c != null && c.busy && c.host.isAlive();
    }

    /**
     * Hands a launch to the live activity when it is already sending (several messages due
     * together), so it queues behind the running send instead of racing it. False when there is
     * no busy live activity; the caller then launches normally. Any thread.
     */
    static boolean offer(final long id, final String token, final long checkTimeMs, long delayMs) {
        final ScheduledSendController c = live.get();
        if (c == null || !c.busy || !c.host.isAlive()) return false;
        c.main.postDelayed(() -> c.acceptHandOff(id, token, checkTimeMs), Math.max(0L, delayMs));
        return true;
    }

    private void acceptHandOff(long id, String token, long checkTimeMs) {
        if (live.get() != this || !host.isAlive()) return;   // the launch watchdog reports it
        if (!LaunchClaims.INSTANCE.take(id, token)) return;
        WebViewScheduledSender.cancelLaunchNotification(app, id);
        onClaimed();
        enqueue(new Job(id, checkTimeMs, System.currentTimeMillis()));
    }

    /**
     * D30: records the pre-send check for the launch of {@code id} ({@link WebViewScheduledSender}
     * calls it before the launch). Any thread.
     */
    static void notePreSendCheck(long id, PreSendCheck check) {
        preSendChecks.put(id, check != null ? check : defaultPreSendCheck());
    }

    /** The check for {@code id}'s job, removed; a job with none recorded is checked strictly. */
    static PreSendCheck takePreSendCheck(long id) {
        PreSendCheck c = preSendChecks.remove(id);
        return c != null ? c : defaultPreSendCheck();
    }

    /** Fail safe when nothing was recorded (never expected): the read is the evidence. */
    static PreSendCheck defaultPreSendCheck() {
        return PreSendCheck.required(OutboundPolicy.STILL_SEND);
    }

    // ================================================================== activity entry points

    /**
     * onCreate / onNewIntent. Returns false when {@code intent} is not a scheduled launch (the
     * activity runs its normal flow). Otherwise the scheduled branch owns the page load and the
     * activity must not reload or inject on its own for this intent.
     */
    public boolean handle(Intent intent, boolean restored) {
        if (intent == null || !intent.hasExtra(EXTRA_SCHEDULED_MESSAGE_ID)) return false;
        long id = intent.getLongExtra(EXTRA_SCHEDULED_MESSAGE_ID, -1L);
        String token = intent.getStringExtra(EXTRA_LAUNCH_TOKEN);
        long checkTime = intent.getLongExtra(EXTRA_CHECK_TIME, 0L);
        Boolean planned = intent.hasExtra(EXTRA_LAUNCHED_DARK)
                ? Boolean.valueOf(intent.getBooleanExtra(EXTRA_LAUNCHED_DARK, false)) : null;
        // A recreate() (renderer crash) or a later re-delivery of this intent must never send again.
        intent.removeExtra(EXTRA_SCHEDULED_MESSAGE_ID);
        intent.removeExtra(EXTRA_LAUNCH_TOKEN);
        intent.removeExtra(EXTRA_CHECK_TIME);
        intent.removeExtra(EXTRA_LAUNCHED_DARK);
        boolean fromHistory = (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        refreshLockState();
        if (restored || fromHistory || id <= 0 || !LaunchClaims.INSTANCE.take(id, token)) {
            // Unclaimed, stale or replayed: no lock flags, no cover, nothing sent.
            if (!isActive()) onUnclaimed();
            return true;
        }
        // Claimed: only now may the cover go up and the activity show over the keyguard.
        coverUntilStop = false;
        plannedDark = planned;
        noteLaunchScreen();
        claimed = true;
        jobState = LockCoverPolicy.JobState.RUNNING;
        updateCover();
        applyLockFlags(LockCoverPolicy.applyLockFlags(claimed, jobState, cover.isShown()));
        listenForScreen();
        WebViewScheduledSender.cancelLaunchNotification(app, id);
        enqueue(new Job(id, checkTime > 0 ? checkTime : System.currentTimeMillis(), System.currentTimeMillis()));
        return true;
    }

    /**
     * R3: a launch with nothing to claim. After a renderer crash recreate() relaunches the same
     * activity record, which keeps the old instance's show-when-locked, while the restored
     * instance is unclaimed (no cover). Clear the flags; while locked (or dark) finish at once and
     * never load the page (it would be the inbox, uncovered, over the keyguard).
     */
    private void onUnclaimed() {
        applyLockFlags(false);
        if (!lockedNow) return;
        skipPageLoad = true;
        endJob(true);
    }

    /** A hand-off claim joined this activity: the job runs under the same lock rules. */
    private void onClaimed() {
        plannedDark = null;   // a hand-off carries no intent: the activity's own reading
        noteLaunchScreen();
        claimed = true;
        jobState = LockCoverPolicy.JobState.RUNNING;
        refreshLockState();
        updateCover();
        applyLockFlags(LockCoverPolicy.applyLockFlags(claimed, jobState, cover.isShown()));
        listenForScreen();
    }

    /** R3: onCreate must not load GV for this launch (it is finishing over the keyguard). */
    public boolean skipsPageLoad() {
        return skipPageLoad;
    }

    /** The cover is up: the activity drops every touch, key and back event (send-lock-cover 2). */
    public boolean isCovered() {
        return cover.isShown();
    }

    /** shouldOverrideUrlLoading (main frame): false blocks the load while covered. */
    public boolean allowsNavigation(String url) {
        return LockCoverPolicy.allowNavigation(cover.isShown(), targetE164, url);
    }

    /** Activity.onResume. */
    public void onResume() {
        resumed = true;
        main.removeCallbacks(pauseCheck);
        refreshLockState();
    }

    /** Activity.onWindowFocusChanged: the keyguard may have come or gone. */
    public void onWindowFocusChanged() {
        refreshLockState();
    }

    /** Activity.onPause / onStop: a real pause ends every running scheduled job (OD26). */
    public void onPauseOrStop(boolean stopped) {
        resumed = false;
        pauseEvent = stopped ? LockCoverPolicy.Event.STOPPED : LockCoverPolicy.Event.PAUSED;
        refreshLockState();
        if (stopped && coverUntilStop) {
            // OD28: the keyguard is back over the activity; the cover may come off now.
            coverUntilStop = false;
            cover.hide();
        }
        if (!LockCoverPolicy.endsJob(pauseEvent, claimed, jobState)) return;
        main.removeCallbacks(pauseCheck);
        main.postDelayed(pauseCheck, PAUSE_GRACE_MS);
    }

    private void onPausedTooLong() {
        if (resumed || activity.isFinishing()) return;
        if (!LockCoverPolicy.endsJob(pauseEvent, claimed, jobState)) return;
        main.removeCallbacksAndMessages(null);
        boolean finish = LockCoverPolicy.finishNow(pauseEvent, refreshLockState());
        abandonAll("paused");
        endJob(finish);
    }

    /** The job has run {@link LockCoverPolicy#HARD_TIMEOUT_MS} since its claim (every job). */
    private void onHardTimeout() {
        if (!LockCoverPolicy.endsJob(LockCoverPolicy.Event.TIMEOUT, claimed, jobState)) return;
        main.removeCallbacksAndMessages(null);
        Job job = current;
        if (job != null) {
            // Typed -> SENT_UNVERIFIED (never re-sent unread, I5); not typed -> CANNOT_SHOW.
            current = null;
            toEngine(job, failureOutcome(job), "timeout");
        }
        abandonAll("timeout");   // queued jobs: nothing typed -> CANNOT_SHOW
        endJob(LockCoverPolicy.finishNow(LockCoverPolicy.Event.TIMEOUT, refreshLockState(), launchedDark));
    }

    /** A scheduled send is queued or running (the activity must not inject its own composer). */
    public boolean isActive() {
        return current != null || !queue.isEmpty();
    }

    /** The running send has asked for the page and is waiting for onPageFinished. */
    public boolean isComposing() {
        Job job = current;
        return job != null && job.e164 != null && job.compose != null && job.compose.mayInject();
    }

    /**
     * LIVE-F6: a later main-frame onPageFinished during the running job. True when the activity
     * must inject the scheduled composer again (see {@link ScheduledCompose#shouldReinject}): the
     * new document wiped the script and no Send click was granted.
     */
    public boolean wantsReinject(String url) {
        Job job = current;
        if (job == null || job.e164 == null || job.compose == null) return false;
        // LIVE-F10: a reload of the job's own thread page re-injects too.
        return ScheduledCompose.shouldReinject(url, job.e164, job.compose.injections(),
                job.compose.isClickGranted(), job.compose.isClosed());
    }

    /**
     * The activity is about to inject the scheduled composer (first time or again, LIVE-F6).
     * Returns the token inject.js echoes in its callbacks, or null when nothing may be injected.
     * A re-injection starts over as not typed: the document that held the text is gone and no
     * click was granted.
     */
    public String onComposeInjection(long autoSendDelayMs) {
        Job job = current;
        if (job == null || job.e164 == null || job.compose == null) return null;
        String token = job.compose.nextInjection();
        if (token == null) return null;
        job.sendDelayMs = Math.max(0L, autoSendDelayMs);
        job.injected = false;
        updateStop();
        startPreSendCheck(job);
        return token;
    }

    /**
     * LIVE-F10: the compose flow of the injection about to run on {@code url} (call right after
     * {@link #onComposeInjection} returned a token): "thread" on the job's own thread page,
     * "new" (voice2sms()'s new-conversation flow) once GV showed no conversation there or on any
     * other page.
     */
    public String composeModeFor(String url) {
        Job job = current;
        if (job == null || job.e164 == null) return ScheduledCompose.Mode.NEW_CONVERSATION.wire();
        job.mode = ScheduledCompose.composeMode(url, job.e164, job.gvShowedNoThread);
        if (job.mode == ScheduledCompose.Mode.NEW_CONVERSATION && job.fallbackReads == null) {
            job.fallbackReads = new NewConversationReads(job.e164);
        }
        return job.mode.wire();
    }

    /** LIVE-F10: how long inject.js polls a WAIT answer before it stops asking (Java decides first). */
    public static long clickWaitMs() {
        // LIVE-F10 F1: a dropped empty clearance reads the thread again with a fresh deadline.
        return 2 * PRESEND_TIMEOUT_MS + ScheduledCompose.CLICK_WAIT_MARGIN_MS;
    }

    /**
     * LIVE-F10, JavaBridge thread: the tri-state Send ask for {@code token} ("yes" | "wait" |
     * "no", {@link ScheduledCompose#clickState}). "yes" at most once per job for the current
     * injection while the gate is open; "wait" only while the pre-send check is pending.
     */
    public static String clickState(String token) {
        return clickState(token, null);
    }

    /**
     * LIVE-F10 F1, JavaBridge thread: as {@link #clickState(String)}, with the page's own location
     * ({@code pageUrl}, may be null). When that page or the last main-frame URL is the job's 1:1
     * thread, an empty-conversation clearance is dropped first (WAIT) and the thread is read again.
     */
    public static String clickState(String token, String pageUrl) {
        final ScheduledSendController c = live.get();
        ScheduledCompose sc = c == null ? null : c.liveCompose;
        if (sc == null) return ScheduledCompose.ClickState.NO.wire();
        String e164 = c.targetE164;
        boolean onJobThread = ThreadUrl.isThreadOf(pageUrl, e164) || ThreadUrl.isThreadOf(c.host.mainFrameUrl(), e164);
        ScheduledCompose.ClickState state = sc.clickState(token, onJobThread);
        if (onJobThread && state == ScheduledCompose.ClickState.WAIT) c.main.post(c::recheckIfDue);
        return state.wire();
    }

    /**
     * LIVE-F10 F1, main thread: a main-frame URL of the running job (the activity's
     * doUpdateVisitedHistory). In the new-conversation flow the job's own thread showing up drops
     * an empty-conversation clearance (no click granted yet) and the thread is read again.
     */
    public void onMainFrameUrl(String url) {
        Job job = current;
        if (job == null || job.compose == null || job.fallbackReads == null) return;
        if (job.fallbackReads.onUrl(url)) job.compose.revokeEmptyClearance();
        recheckIfDue();
    }

    /** LIVE-F10 F1, main thread: restarts the pre-send reads once a dropped clearance asks for it. */
    private void recheckIfDue() {
        final Job job = current;
        if (job == null || job.compose == null || !job.compose.takeRecheckDue()) return;
        if (job.fallbackReads != null) job.fallbackReads.restart();
        job.presend = new PreSendRead();
        job.presendDeadlineMs = System.currentTimeMillis() + PRESEND_TIMEOUT_MS;
        main.postDelayed(() -> preSendReadOnce(job), BASELINE_POLL_MS);
    }

    /**
     * LIVE-F10, main thread: the thread page of the job's number settled without showing the
     * conversation (inject.js, for {@code token}). From now on the job composes with the
     * new-conversation flow: the messages list is loaded and onPageFinished injects it.
     */
    public void onNoThread(String token) {
        Job job = current;
        if (job == null || job.e164 == null || job.compose == null || job.gvShowedNoThread) return;
        if (!job.compose.isCurrent(token) || job.compose.isClickGranted()) return;
        // live-presend-failclosed: a never-texted thread looks exactly like a slow render, so the
        // job's thread page is reloaded once (full load) and settles again before any fallback.
        ScheduledCompose.NoThread next = job.compose.onNoThread(token, ThreadUrl.isThreadOf(host.mainFrameUrl(), job.e164));
        if (next == ScheduledCompose.NoThread.IGNORE) return;
        if (next == ScheduledCompose.NoThread.RELOAD) {
            reloadThread(job);
            return;
        }
        job.gvShowedNoThread = true;
        job.presend = job.presend == null ? null : new PreSendRead();
        // The check reads the new page from scratch: it gets its full time again (hard timeout bounds it).
        if (job.presend != null && job.compose.isPreSendPending()) {
            job.presendDeadlineMs = System.currentTimeMillis() + PRESEND_TIMEOUT_MS;
        }
        if (!host.isAlive() || !host.loadMessagesList()) report(job, failureOutcome(job), "activity_gone");
    }

    /**
     * live-presend-failclosed, main thread: the one reload of the job's thread page. The pre-send
     * reads start over with their full time, the compose timeout gets one more settle, and the
     * page's onPageFinished re-injects the thread composer (the job's own thread URL re-injects).
     */
    private void reloadThread(Job job) {
        if (job.presend != null && job.compose.isPreSendPending()) {
            job.presend = new PreSendRead();
            job.presendDeadlineMs = System.currentTimeMillis() + PRESEND_TIMEOUT_MS;
        }
        job.composeGraceMs = RELOAD_COMPOSE_GRACE_MS;
        if (!host.isAlive() || !host.beginCompose(job.e164, job.message.getText())) {
            report(job, failureOutcome(job), "activity_gone");
        }
    }

    /**
     * OD34, main thread: thread mode found text in the thread's box that is not our body before it
     * typed (inject.js, for {@code token}). A draft the user left is never overwritten or
     * deleted: the job stops unsent (CANNOT_SHOW, "draft_present", reminder); the end-of-job clear
     * removes only our exact body, so the draft stays.
     */
    public void onDraftPresent(String token) {
        Job job = current;
        if (job == null || job.compose == null || !job.compose.isCurrent(token)) return;
        stopUnsent(job, DETAIL_DRAFT_PRESENT);
    }

    /**
     * JavaBridge thread: inject.js asks to tap Send for {@code token}. Granted at most once per
     * job, only for the current injection and while the job's gate is open (Stop, a failure and
     * the outcome close it), so the scheduled script can never send twice or after the job ended.
     */
    public static boolean mayClick(String token) {
        ScheduledSendController c = live.get();
        ScheduledCompose sc = c == null ? null : c.liveCompose;
        return sc != null && sc.grantClick(token);
    }

    /** inject.js auto-send delay for a scheduled send: the user's setting, at least the minimum. */
    public static long sendDelayMs(long preferredMs) {
        return Math.max(MIN_SEND_DELAY_MS, preferredMs);
    }

    /**
     * LIVE-F8: the page confirmed that our body is in the message box (inject.js
     * v2sScheduledCompose, for {@code token}). Only now is the job "typed": Stop goes away and a
     * later failure is SENT_UNVERIFIED. Starts the verify polls once per job; a re-typed
     * injection extends the deadline.
     */
    public void onComposerInjected(String token) {
        final Job job = current;
        if (job == null || job.injected || job.e164 == null || job.compose == null) return;
        if (!job.compose.onTyped(token)) return;
        job.injected = true;
        updateStop();   // OD31 PQ3: typing has started, Stop goes away
        job.deadlineMs = System.currentTimeMillis() + job.sendDelayMs + VERIFY_WINDOW_MS;
        if (job.watch != null) return;
        job.watch = new SendWatch(job.bodyHash);
        main.postDelayed(() -> readOnce(job), BASELINE_POLL_MS);
    }

    /**
     * The page could not be used (no web session, sign-in page, script missing). Before inject.js
     * ran nothing was typed, so it is CANNOT_SHOW with the cause (G2, OD23: the user gets the
     * reminder, not "may have already sent"); after it, SENT_UNVERIFIED (I5).
     */
    public void onWebFailure(String reason) {
        Job job = current;
        if (job == null) return;
        report(job, failureOutcome(job), reason);
    }

    /** Typed -> SENT_UNVERIFIED (never re-sent unread, I5); not typed -> CANNOT_SHOW (G2). */
    static SendOutcome failureOutcome(boolean typed) {
        return typed ? SendOutcome.SENT_UNVERIFIED : SendOutcome.CANNOT_SHOW;
    }

    /**
     * LIVE-F8: the failure outcome of {@code job} right now. Closes its Send gate first, so a
     * CANNOT_SHOW can no longer turn into a send; typed (the page confirmed our body) or a granted
     * click -> SENT_UNVERIFIED, else CANNOT_SHOW.
     */
    private static SendOutcome failureOutcome(Job job) {
        if (job.compose == null) return failureOutcome(false);
        return job.compose.closeForFailure();
    }

    /**
     * A non-scheduled intent is taking the page over: end the scheduled work honestly. The lock
     * flags come down first; over the keyguard (OD28) the cover then stays up until onStop (or an
     * unlock), so the thread never shows while the keyguard animates back.
     */
    public void onOtherIntent() {
        main.removeCallbacksAndMessages(null);
        abandonAll("other_intent");
        stopListeningForEngine();
        stopListeningForScreen();
        awaitingEngineId = -1L;
        jobState = claimed ? LockCoverPolicy.JobState.ENDED : LockCoverPolicy.JobState.IDLE;
        applyLockFlags(false);
        coverUntilStop = LockCoverPolicy.keepCoverUntilStop(cover.isShown(), refreshLockState());
        if (!coverUntilStop) cover.hide();
    }

    public void onDestroy() {
        if (live.get() == this) live = new WeakReference<>(null);
        main.removeCallbacksAndMessages(null);
        abandonAll("activity_gone");
        stopListeningForEngine();
        stopListeningForScreen();
        // R3: a recreate() keeps show-when-locked on the activity record; clear it first.
        applyLockFlags(false);
        cover.hide();   // the WebView is a singleton: restore its long-press and accessibility
    }

    /** The keyguard is locked (or the screen dark): a stale launch must not open the account picker. */
    public boolean isOverKeyguard() {
        return lockedNow;
    }

    // ================================================================== one send

    private void enqueue(Job job) {
        queue.add(job);
        busy = true;
        if (current == null) startNext();
    }

    private void startNext() {
        if (current != null) return;
        final Job job = queue.poll();
        if (job == null) {
            busy = false;
            settle();
            return;
        }
        current = job;
        busy = true;
        updateStop();   // OD31 PQ3: Stop is offered until this job starts typing
        // OD26: every scheduled job has the hard timeout, locked or not.
        lastHardDeadlineMs = job.hardDeadlineMs;
        main.removeCallbacks(hardTimeout);
        main.postDelayed(hardTimeout, Math.max(0L, job.hardDeadlineMs - System.currentTimeMillis()));
        IO.execute(() -> {
            ScheduledMessage m = null;
            try {
                m = SendLaterRepository.get(app).get(job.id);
            } catch (RuntimeException e) {
                Log.w(TAG, "load failed for id " + job.id + ": " + e.getClass().getSimpleName());
            }
            final ScheduledMessage loaded = m;
            main.post(() -> onLoaded(job, loaded));
        });
    }

    private void onLoaded(final Job job, ScheduledMessage m) {
        if (current != job) return;
        if (m == null || m.getStatus() != ScheduledMessage.Status.IN_FLIGHT) {
            // Resolved or canceled meanwhile: nothing to send and nothing to report.
            current = null;
            afterOutcome();
            return;
        }
        if (LaunchRecheck.isLate(m, System.currentTimeMillis(), LaunchRecheck.QUEUE_SLACK_MS)) {
            // F4 (I2 "ever"): this job waited (the launch, a hand-off queued behind another send)
            // more than the queue slack (OD30) past the lateness deadline. Nothing typed:
            // CANNOT_SHOW with the LATE reminder.
            report(job, SendOutcome.CANNOT_SHOW, LaunchRecheck.DETAIL_LATE);
            return;
        }
        if (m.getRecipientE164() == null || m.getText() == null) {
            // A corrupt row (start() never launches one): nothing can be typed, so CANNOT_SHOW
            // and the generic reminder, never "may have already sent" (OD28).
            report(job, SendOutcome.CANNOT_SHOW, DETAIL_NO_RECIPIENT);
            return;
        }
        job.e164 = m.getRecipientE164();
        job.bodyHash = ThreadEvent.hashOf(m.getText());
        job.message = m;
        job.check = takePreSendCheck(job.id);
        targetE164 = job.e164;
        cover.setRecipient(job.e164);
        job.compose = new ScheduledCompose(job.id);
        // D30: no Send tap until the thread check clears it (none for the user's own tap).
        if (!job.check.isOff()) job.compose.requirePreSendCheck();
        liveCompose = job.compose;
        updateStop();
        if (!host.isAlive() || !host.beginCompose(job.e164, m.getText())) {
            report(job, SendOutcome.CANNOT_SHOW, "activity_gone");
            return;
        }
        main.postDelayed(() -> {
            if (current == job && !job.injected && !job.compose.isClickGranted()) {
                if (extendComposeTimeout(job)) return;   // the thread page was reloaded once
                // Nothing typed yet: CANNOT_SHOW with the cause (G2); the gate closes first.
                report(job, failureOutcome(job), isSignInUrl(host.mainFrameUrl()) ? "auth" : "compose_timeout");
            }
        }, COMPOSE_TIMEOUT_MS);
    }

    /**
     * live-presend-failclosed: the compose timeout found grace left by the thread-page reload; it
     * is used once (a second timer with the same cause), then the timeout stands.
     */
    private boolean extendComposeTimeout(final Job job) {
        if (job.composeGraceMs <= 0) return false;
        long grace = job.composeGraceMs;
        job.composeGraceMs = 0;
        main.postDelayed(() -> {
            if (current == job && !job.injected && !job.compose.isClickGranted()) {
                report(job, failureOutcome(job), isSignInUrl(host.mainFrameUrl()) ? "auth" : "compose_timeout");
            }
        }, grace);
        return true;
    }

    // ================================================================== pre-send check (D30)

    /** Starts the pre-send reads once per job, at its first injection. */
    private void startPreSendCheck(final Job job) {
        if (job.presend != null || job.compose == null || !job.compose.isPreSendPending()) return;
        job.presend = new PreSendRead();
        job.presendDeadlineMs = System.currentTimeMillis() + PRESEND_TIMEOUT_MS;
        main.postDelayed(() -> preSendReadOnce(job), BASELINE_POLL_MS);
    }

    private void preSendReadOnce(final Job job) {
        if (current != job || job.compose == null || !job.compose.isPreSendPending()) return;
        if (!host.isAlive()) {
            report(job, failureOutcome(job), "activity_gone");
            return;
        }
        host.readThread(job.e164, READ_LIMIT, bubbles -> onPreSendRead(job, preSendView(job, bubbles)));
    }

    /**
     * Feeds one pre-send read; returns the agreed read or null. Thread mode: the read as read.
     * LIVE-F10 F1, new-conversation flow ({@link NewConversationReads}): a page other than the
     * job's thread counts as an empty conversation only on this job's draft once the box holds
     * our body, and never once the thread was shown; the job's thread page reads as read.
     */
    private NewConversationReads.Agreed preSendView(Job job, List<ThreadBubble> bubbles) {
        long now = System.currentTimeMillis();
        if (job.mode != ScheduledCompose.Mode.NEW_CONVERSATION || job.fallbackReads == null) {
            List<ThreadBubble> agreed = job.presend.onRead(bubbles, now);
            return agreed == null ? null : new NewConversationReads.Agreed(false, agreed);
        }
        return job.fallbackReads.onRead(host.mainFrameUrl(), job.compose.isTyped(), bubbles, now);
    }

    /** One pre-send read: two agreeing reads decide; none by the deadline is "unreadable". */
    private void onPreSendRead(final Job job, NewConversationReads.Agreed agreed) {
        if (current != job || job.compose == null || !job.compose.isPreSendPending()) return;
        long now = System.currentTimeMillis();
        // F1: an empty agreement no longer stands once the job's thread is (or was) the page.
        if (agreed != null && agreed.empty && !job.fallbackReads.emptyStillHolds(host.mainFrameUrl())) {
            agreed = null;
        }
        if (agreed != null) {
            // live-presend-failclosed: every agreed read that shows no bubble (the new-conversation
            // Kind EMPTY, and a thread page whose gv-message-list rendered no item yet) clears
            // only on fail-closed evidence (nothing seen at scheduling, listener on, listener
            // never matched the number). Only Kind EMPTY uses the empty-conversation gate opener.
            boolean emptyRead = PreSendRead.readsAsEmpty(agreed.empty, agreed.bubbles);
            Classification c = emptyRead
                    ? PreSendRead.classifyNoConversation(job.message, READ_LIMIT, now)
                    : PreSendRead.classify(job.message, agreed.bubbles, READ_LIMIT, now);
            PreSendCheck.Verdict v = emptyRead
                    ? PreSendRead.noConversationVerdict(job.check, job.message, c)
                    : job.check.decide(job.message, c);
            onPreSendVerdict(job, v, now, agreed.empty);
            return;
        }
        if (now >= job.presendDeadlineMs) {
            onPreSendVerdict(job, job.check.decide(job.message, Classification.UNVERIFIED), now, false);
            return;
        }
        main.postDelayed(() -> preSendReadOnce(job), BASELINE_POLL_MS);
    }

    /**
     * SEND opens the Send gate (after a last lateness check, I2); every other verdict ends the
     * job unsent with the verdict's detail, so the engine holds, self-preempts or reminds.
     */
    private void onPreSendVerdict(Job job, PreSendCheck.Verdict verdict, long now, boolean emptyConversation) {
        if (verdict == PreSendCheck.Verdict.SEND) {
            if (LaunchRecheck.isLate(job.message, now, LaunchRecheck.QUEUE_SLACK_MS)) {
                stopUnsent(job, LaunchRecheck.DETAIL_LATE);
                return;
            }
            // The script polls the tri-state gate and taps once it says yes. Only an old boolean
            // ask refused while pending (or a closed gate) leaves the job unable to send.
            // F1: a clearance on an empty conversation is dropped if the thread shows up before a click.
            boolean open = emptyConversation
                    ? job.compose.clearPreSendOnEmptyConversation()
                    : job.compose.clearPreSend();
            if (!open) stopUnsent(job, DETAIL_PRESEND_SLOW);
            return;
        }
        stopUnsent(job, verdict.detail());
    }

    /**
     * Ends the job as CANNOT_SHOW: its Send gate shuts first and no click was ever granted (the
     * gate grants none while the check is pending), so nothing was sent, whatever was typed.
     */
    private void stopUnsent(Job job, String detail) {
        if (!job.compose.close()) return;   // a click was granted: the job goes on and verifies
        report(job, SendOutcome.CANNOT_SHOW, detail);
    }

    // ================================================================== verify polls

    /** One poll: read the thread, THEN probe the composer (a pending probe dates the read). */
    private void readOnce(final Job job) {
        if (current != job) return;
        if (!host.isAlive()) {
            report(job, failureOutcome(job), "activity_gone");
            return;
        }
        host.readThread(job.e164, READ_LIMIT, bubbles -> {
            if (current != job) return;
            if (!host.isAlive()) {
                report(job, failureOutcome(job), "activity_gone");
                return;
            }
            host.probeComposer(pending -> onThreadRead(job, bubbles, pending));
        });
    }

    private void onThreadRead(final Job job, List<ThreadBubble> bubbles, Boolean probe) {
        if (current != job) return;
        long now = System.currentTimeMillis();
        boolean pending = Boolean.TRUE.equals(probe);
        List<ThreadEvent> events = job.watch.onPoll(bubbles, pending, now);
        SendVerifier.Result verdict = events == null ? null
                : SendVerifier.verify(job.bodyHash, events, job.checkTimeMs, now);
        // G1: one sighting decides nothing (GV draws the bubble before the server takes it). An
        // unanswered probe (null) is not "the box is empty".
        SendVerifier.Result r = job.watch.settle(verdict, probe == null || pending, now);
        if (r != null) {
            if (r == SendVerifier.Result.VERIFIED) {
                report(job, SendOutcome.SENT_VERIFIED, null);
                return;
            }
            if (r == SendVerifier.Result.VERIFIED_WITH_LATE_REPLY) {
                report(job, SendOutcome.SENT_VERIFIED, DETAIL_LATE_REPLY);
                return;
            }
        }
        if (now >= job.deadlineMs) {
            // Typed -> SENT_UNVERIFIED; re-injected and not typed again -> per the gate (LIVE-F8).
            report(job, job.injected ? SendOutcome.SENT_UNVERIFIED : failureOutcome(job), "no_match");
            return;
        }
        main.postDelayed(() -> readOnce(job),
                job.watch.baseline() == null ? BASELINE_POLL_MS : READ_INTERVAL_MS);
    }

    /** Hands the outcome to the engine (the only status writer), then the next job or settle. */
    private void report(Job job, SendOutcome outcome, String detail) {
        if (current != job) return;
        current = null;
        closeCompose(job);
        awaitingEngineId = job.id;
        toEngine(job, outcome, detail);
        afterOutcome();
    }

    /**
     * The one way an outcome leaves the controller. A SENT_VERIFIED here has passed the settle
     * gate (its only call sites follow {@link SendWatch#settle}); it is the only SENT_VERIFIED the
     * engine ever records (OD27). Every other outcome is final as reported: the engine does not
     * read this thread to upgrade it. The detail is mapped to the engine's contract
     * ({@link SendDetails#forEngine}).
     */
    private void toEngine(Job job, SendOutcome outcome, String cause) {
        closeCompose(job);
        SendLaterEngine.onSendOutcome(app, job.id, outcome, SendDetails.forEngine(outcome, cause));
    }

    /**
     * The job is over: its Send gate shuts for good (no click after the outcome). LIVE-F10: when no
     * click was ever granted (pre-send hold, timeout, Stop, any failure) our text must not stay in
     * GV's box, where it could be sent by hand by mistake: the box is cleared if it still holds
     * exactly our body.
     */
    private void closeCompose(Job job) {
        if (job.compose == null) return;
        boolean unsent = job.compose.close();
        if (liveCompose == job.compose) liveCompose = null;
        if (unsent && !job.composerCleared && job.message != null && job.message.getText() != null) {
            job.composerCleared = true;
            if (host.isAlive()) host.clearComposer(job.message.getText());
        }
    }

    private void afterOutcome() {
        if (!queue.isEmpty()) {
            startNext();
            return;
        }
        busy = false;
        updateStop();
        settle();
    }

    /**
     * Nothing left to send. The job ends on the engine's state broadcast for the last outcome
     * (the covered page stays up until then), at most {@link #FINISH_GRACE_MS}
     * and never past the hard deadline; a job that reported nothing ends after
     * {@link #IDLE_FINISH_MS}. Same for every job, locked or not (OD26).
     */
    private void settle() {
        if (!claimed || jobState != LockCoverPolicy.JobState.RUNNING) return;
        main.removeCallbacks(hardTimeout);
        if (awaitingEngineId > 0) listenForEngine();
        long wait = IDLE_FINISH_MS;
        if (awaitingEngineId > 0) {
            wait = Math.min(FINISH_GRACE_MS, Math.max(0L, lastHardDeadlineMs - System.currentTimeMillis()));
        }
        main.postDelayed(() -> {
            if (!isActive() && !activity.isFinishing()) onJobEnded();
        }, wait);
    }

    /** All work is reported and handed off: finish while locked, else uncover and stay. */
    private void onJobEnded() {
        if (isActive()) return;
        endJob(LockCoverPolicy.finishNow(LockCoverPolicy.Event.JOB_ENDED, refreshLockState(), launchedDark));
    }

    /**
     * Ends the scheduled work: clears every lock flag FIRST, then finishes at once (still locked)
     * or, when the phone is unlocked, removes the cover so the user sees the thread normally.
     */
    private void endJob(boolean finish) {
        main.removeCallbacks(hardTimeout);
        main.removeCallbacks(pauseCheck);
        stopListeningForEngine();
        stopListeningForScreen();
        awaitingEngineId = -1L;
        if (claimed) jobState = LockCoverPolicy.JobState.ENDED;
        updateStop();
        applyLockFlags(false);
        if (finish) {
            if (!activity.isFinishing()) host.finish();
            return;
        }
        updateCover();
    }

    /** Shows or removes the cover per {@link LockCoverPolicy#showCover} (never skipped while running). */
    private void updateCover() {
        if (LockCoverPolicy.showCover(claimed, jobState, lockedNow)) {
            cover.show(host.isAlive() ? host.pageView() : null);
            if (targetE164 != null) cover.setRecipient(targetE164);
        } else {
            cover.hide();
        }
        updateStop();
    }

    /** OD31 PQ3: whether the cover's Stop button shows right now. */
    private boolean stopAllowed() {
        Job job = current;
        return LockCoverPolicy.showStop(claimed, jobState, cover.isShown(), lockedNow,
                job != null, job != null && job.injected);
    }

    /** Shows or hides the Stop button per {@link LockCoverPolicy#showStop}. */
    private void updateStop() {
        cover.setStopVisible(stopAllowed(), this::onStopTapped);
    }

    /**
     * OD31 PQ3: Stop on the cover of an unlocked phone, before typing started. Ends the running
     * and queued jobs without sending (CANNOT_SHOW {@code stopped}: the engine puts the row back
     * and posts a reminder) and finishes, so the user is back where they were. Re-checked here:
     * a tap that lands after typing started, or once the phone locked, does nothing.
     */
    void onStopTapped() {
        refreshLockState();
        if (!stopAllowed() || !closeForStop()) {
            updateStop();
            return;
        }
        main.removeCallbacksAndMessages(null);
        abandonAll(RemindCause.DETAIL_STOPPED);
        endJob(true);
    }

    /**
     * Shuts the running job's Send gate for Stop. False when a click was already granted (the
     * tap came too late: the job goes on and verifies).
     */
    private boolean closeForStop() {
        Job job = current;
        return job == null || job.compose == null || job.compose.close();
    }

    private void listenForEngine() {
        if (engineDone != null) return;
        engineDone = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(SendLaterIntents.EXTRA_MESSAGE_ID, -1L);
                if (id <= 0 || id != awaitingEngineId || isActive()) return;
                awaitingEngineId = -1L;
                main.postDelayed(() -> {
                    if (!isActive() && !activity.isFinishing()) onJobEnded();
                }, ENGINE_DONE_FINISH_MS);
            }
        };
        try {
            ContextCompat.registerReceiver(activity, engineDone,
                    new IntentFilter(SendLaterIntents.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (RuntimeException e) {
            engineDone = null;   // the grace timer still finishes
        }
    }

    private void stopListeningForEngine() {
        if (engineDone == null) return;
        try {
            activity.unregisterReceiver(engineDone);
        } catch (RuntimeException ignored) {
            // not registered
        }
        engineDone = null;
    }

    /** Screen off / on and unlock while a job runs: re-read the keyguard state (OD26). */
    private void listenForScreen() {
        if (screenEvents != null) return;
        screenEvents = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                refreshLockState();
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            ContextCompat.registerReceiver(activity, screenEvents, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (RuntimeException e) {
            screenEvents = null;   // resume / pause / focus still re-read it
        }
    }

    private void stopListeningForScreen() {
        if (screenEvents == null) return;
        try {
            activity.unregisterReceiver(screenEvents);
        } catch (RuntimeException ignored) {
            // not registered
        }
        screenEvents = null;
    }

    /** Ends the running and queued jobs: typed -> SENT_UNVERIFIED, untyped -> CANNOT_SHOW. */
    private void abandonAll(String detail) {
        Job job = current;
        current = null;
        if (job != null) toEngine(job, failureOutcome(job), detail);
        Job q;
        while ((q = queue.poll()) != null) toEngine(q, SendOutcome.CANNOT_SHOW, detail);
        busy = false;
    }

    // ================================================================== lock screen

    private boolean isKeyguardLocked() {
        KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    /** The screen is off (not interactive). */
    private boolean isScreenDark() {
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        return pm != null && !pm.isInteractive();
    }

    /**
     * At a claim, before the lock flags wake the screen: a new job starts dark when the sender
     * planned it on a dark screen ({@link #EXTRA_LAUNCHED_DARK}, OD28), or, when the extra is
     * absent, with the screen as it is now; a hand-off into a running job keeps a dark launch
     * dark (OD27).
     */
    private void noteLaunchScreen() {
        Boolean planned = plannedDark;
        plannedDark = null;
        boolean dark = darkAtLaunch(planned, planned == null && isScreenDark());
        launchedDark = jobState == LockCoverPolicy.JobState.RUNNING ? (launchedDark || dark) : dark;
    }

    /** OD28: the planning-time reading when the launch carried one, else the activity's own. */
    static boolean darkAtLaunch(Boolean planned, boolean darkNow) {
        return planned != null ? planned : darkNow;
    }

    /** A dark screen counts as locked: the keyguard follows within its grace period. */
    private boolean isLockedOrDark() {
        return isKeyguardLocked() || isScreenDark();
    }

    /**
     * Re-reads the keyguard state. It decides only what a job end does; a running job keeps its
     * cover whatever it says. An ended job still covered (locked) uncovers once unlocked.
     */
    private boolean refreshLockState() {
        lockedNow = isLockedOrDark();
        if (jobState == LockCoverPolicy.JobState.ENDED && cover.isShown()) updateCover();
        else updateStop();
        return lockedNow;
    }

    /**
     * Show over the keyguard and wake the screen while a CLAIMED send runs under its cover
     * ({@link LockCoverPolicy#applyLockFlags}); never requests a dismiss. Cleared before any
     * finish and before the cover comes down.
     */
    private void applyLockFlags(boolean on) {
        if (activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            if (on) {
                activity.setShowWhenLocked(true);
                activity.setTurnScreenOn(true);
            } else {
                activity.setShowWhenLocked(false);
                activity.setTurnScreenOn(false);
            }
        } else {
            int legacy = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
            if (on) activity.getWindow().addFlags(legacy);
            else activity.getWindow().clearFlags(legacy);
        }
        // The keyguard screen times out fast; a dark screen pauses the WebView mid-send.
        if (on) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    static boolean isSignInUrl(String url) {
        return url != null && url.startsWith("https://accounts.google.com");
    }
}
