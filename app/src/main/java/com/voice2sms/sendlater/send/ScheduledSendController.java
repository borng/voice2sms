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
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.engine.SendLaterEngine;
import com.voice2sms.sendlater.store.SendLaterRepository;
import com.voice2sms.sendlater.web.ThreadBubble;
import com.voice2sms.sendlater.web.ThreadReadCallback;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The scheduled branch of GVoiceWebViewActivity (R30, R31, OD10, OD11). The activity owns one
 * controller per instance and delegates to it from onCreate, onNewIntent, onPageFinished and
 * onDestroy; this class holds all the logic.
 *
 * <p>Flow for one launch carrying {@link #EXTRA_SCHEDULED_MESSAGE_ID}: show over the keyguard and
 * turn the screen on (never dismiss the keyguard), take the launch token ({@link LaunchClaims}),
 * load the IN_FLIGHT row off the main thread, ask the activity to open the composer for
 * {e164, text}; once inject.js auto-send is injected, poll the open thread with readThread plus a
 * composer probe ({@link SendWatch}): the first read taken while our text is still unsent is the
 * baseline, and only a NEW outbound bubble with the body hash beyond it verifies
 * ({@link ThreadBaseline}, {@link SendVerifier}): -> SENT_VERIFIED (with a late-reply detail when
 * an inbound landed between the check and the send); nothing new within the window ->
 * SENT_UNVERIFIED (the engine holds it and never re-sends unread, I5); WebView/auth failure ->
 * FAILED. An identical older bubble never proves a send. The outcome goes to
 * {@link SendLaterEngine#onSendOutcome}, which owns every status change. Launches that arrive
 * while one is running queue behind it (several messages due together). When the activity was
 * launched over the keyguard it finishes once the queue is empty and the engine has finished its
 * own post-send read of the thread (its state broadcast, or {@link #FINISH_GRACE_MS}), so the GV
 * page is not left on the lock screen.
 *
 * <p>Never uses the Wear/Gemini auto-send extra or the global auto-send rate limiter: the
 * scheduled queue is the limiter (R30).
 *
 * <p>Fix pass 2: a match must settle ({@link SendWatch#settle}, G1) before it verifies; failures
 * before anything was typed report CANNOT_SHOW with the cause (G2, OD23).
 *
 * <p>send-lock-cover ({@link LockCoverPolicy}): lock flags only after a successful launch claim;
 * over the keyguard an opaque {@link LockCover} hides the WebView and eats all input, only the
 * target thread may load, the job ends after {@link LockCoverPolicy#HARD_TIMEOUT_MS} at the
 * latest, and on job end, pause or stop the flags are cleared and the activity finishes. If the
 * phone was unlocked meanwhile, the cover comes off at job end and the activity stays. The
 * unlocked path (not launched over the keyguard) is unchanged.
 */
public final class ScheduledSendController {

    private static final String TAG = "SendLaterSend";

    /** Long id of the IN_FLIGHT message this launch sends. */
    public static final String EXTRA_SCHEDULED_MESSAGE_ID = "com.voice2sms.sendlater.extra.SCHEDULED_MESSAGE_ID";
    /** One-shot token from {@link LaunchClaims}; a replayed intent carries a stale one. */
    public static final String EXTRA_LAUNCH_TOKEN = "com.voice2sms.sendlater.extra.LAUNCH_TOKEN";
    /** Wall time of the engine's thread check that allowed this send (late-reply window). */
    public static final String EXTRA_CHECK_TIME = "com.voice2sms.sendlater.extra.CHECK_TIME";

    /** Detail passed with SENT_VERIFIED when an inbound landed between the check and the send. */
    public static final String DETAIL_LATE_REPLY = "late_reply";

    /** Page load plus composer injection must happen within this (else FAILED). */
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
     * opened thread before Send is tapped (a late baseline only ever errs to unverified).
     */
    static final long MIN_SEND_DELAY_MS = 3_000L;
    /**
     * Upper bound on staying over the keyguard after the last outcome. The engine answers it with
     * its own read of this thread (up to {@link WebViewThreadEventSource#WAIT_MS}); the activity
     * normally finishes on the engine's state broadcast, well before this.
     */
    static final long FINISH_GRACE_MS = WebViewThreadEventSource.WAIT_MS + 5_000L;
    /** Over the keyguard with nothing reported (stale or replayed launch). */
    static final long IDLE_FINISH_MS = 2_000L;
    /** After the engine's state broadcast for the last outcome. */
    static final long ENGINE_DONE_FINISH_MS = 300L;
    static final int READ_LIMIT = 30;
    /** A pause/stop over the keyguard shorter than this is a launch transient (screen waking). */
    static final long PAUSE_GRACE_MS = 1_000L;

    /** What the controller needs from the activity. Every WebView touch is behind isAlive(). */
    public interface Host {
        /** The activity's isActivityAlive(): WebView present, not finishing or destroyed. */
        boolean isAlive();

        /** Main-frame URL (thread-safe snapshot). */
        String mainFrameUrl();

        /** Loads GV so onPageFinished injects the scheduled composer. False when not alive. */
        boolean beginCompose(String e164, String text);

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
        boolean injected;
        long deadlineMs;
        /** Over the keyguard: hard end, {@link LockCoverPolicy#HARD_TIMEOUT_MS} after the claim. */
        final long hardDeadlineMs;

        Job(long id, long checkTimeMs, long claimedAtMs) {
            this.id = id;
            this.checkTimeMs = checkTimeMs;
            this.hardDeadlineMs = claimedAtMs + LockCoverPolicy.HARD_TIMEOUT_MS;
        }
    }

    private static volatile WeakReference<ScheduledSendController> live = new WeakReference<>(null);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final Context app;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private Job current;
    private volatile boolean busy;
    private boolean overKeyguard;
    /** Id of the last outcome reported over the keyguard; its engine broadcast lets us finish. */
    private long awaitingEngineId = -1L;
    private BroadcastReceiver engineDone;
    /** A launch claim succeeded in this activity (lock flags may apply). */
    private boolean claimed;
    private LockCoverPolicy.JobState jobState = LockCoverPolicy.JobState.IDLE;
    private final LockCover cover;
    /** Recipient of the running (or last) job: the only thread that may load while covered. */
    private String targetE164;
    /** Hard deadline of the last job over the keyguard (bounds the post-outcome wait). */
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

    /** Main-frame URL of the live GVoiceWebViewActivity, or null when none is alive. */
    static String liveMainFrameUrl() {
        ScheduledSendController c = live.get();
        if (c == null || !c.host.isAlive()) return null;
        return c.host.mainFrameUrl();
    }

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
        // A recreate() (renderer crash) or a later re-delivery of this intent must never send again.
        intent.removeExtra(EXTRA_SCHEDULED_MESSAGE_ID);
        intent.removeExtra(EXTRA_LAUNCH_TOKEN);
        intent.removeExtra(EXTRA_CHECK_TIME);
        boolean fromHistory = (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        if (!isActive()) overKeyguard = isKeyguardLocked();
        if (restored || fromHistory || id <= 0 || !LaunchClaims.INSTANCE.take(id, token)) {
            // Unclaimed, stale or replayed: no lock flags, no cover, nothing sent.
            if (!isActive()) settle();
            return true;
        }
        // Claimed: only now may the activity show over the keyguard (send-lock-cover 1).
        claimed = true;
        jobState = LockCoverPolicy.JobState.RUNNING;
        applyLockFlags(LockCoverPolicy.applyLockFlags(claimed, jobState));
        updateCover();
        WebViewScheduledSender.cancelLaunchNotification(app, id);
        enqueue(new Job(id, checkTime > 0 ? checkTime : System.currentTimeMillis(), System.currentTimeMillis()));
        return true;
    }

    /** A hand-off claim joined this activity: the job runs under the same lock rules. */
    private void onClaimed() {
        claimed = true;
        jobState = LockCoverPolicy.JobState.RUNNING;
        applyLockFlags(LockCoverPolicy.applyLockFlags(claimed, jobState));
        updateCover();
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
    }

    /** Activity.onPause / onStop: over the keyguard, a real pause ends the job and finishes. */
    public void onPauseOrStop(boolean stopped) {
        resumed = false;
        pauseEvent = stopped ? LockCoverPolicy.Event.STOPPED : LockCoverPolicy.Event.PAUSED;
        if (!claimed || !overKeyguard || jobState == LockCoverPolicy.JobState.IDLE) return;
        main.removeCallbacks(pauseCheck);
        main.postDelayed(pauseCheck, PAUSE_GRACE_MS);
    }

    private void onPausedTooLong() {
        if (resumed || activity.isFinishing()) return;
        if (!LockCoverPolicy.finishNow(pauseEvent, overKeyguard, isKeyguardLocked())) return;
        main.removeCallbacksAndMessages(null);
        abandonAll("paused");
        endJob(true);
    }

    /** Over the keyguard: the job has run {@link LockCoverPolicy#HARD_TIMEOUT_MS}. */
    private void onHardTimeout() {
        if (!overKeyguard || jobState != LockCoverPolicy.JobState.RUNNING) return;
        main.removeCallbacksAndMessages(null);
        Job job = current;
        if (job != null) {
            // Typed -> SENT_UNVERIFIED (never re-sent unread, I5); not typed -> CANNOT_SHOW.
            current = null;
            if (job.injected && job.watch != null) {
                WebViewThreadEventSource.armPostSendRead(job.e164, job.watch.baseline(), System.currentTimeMillis());
            }
            SendLaterEngine.onSendOutcome(app, job.id, failureOutcome(job.injected), "timeout");
        }
        abandonAll("timeout");   // queued jobs: nothing typed -> CANNOT_SHOW
        endJob(LockCoverPolicy.finishNow(LockCoverPolicy.Event.TIMEOUT, overKeyguard, isKeyguardLocked()));
    }

    /** A scheduled send is queued or running (the activity must not inject its own composer). */
    public boolean isActive() {
        return current != null || !queue.isEmpty();
    }

    /** The running send has asked for the page and is waiting for onPageFinished. */
    public boolean isComposing() {
        return current != null && current.e164 != null && !current.injected;
    }

    /** inject.js auto-send delay for a scheduled send: the user's setting, at least the minimum. */
    public static long sendDelayMs(long preferredMs) {
        return Math.max(MIN_SEND_DELAY_MS, preferredMs);
    }

    /** The activity injected inject.js auto-send for the running job. */
    public void onComposerInjected(long autoSendDelayMs) {
        final Job job = current;
        if (job == null || job.injected || job.e164 == null) return;
        job.injected = true;
        job.watch = new SendWatch(job.bodyHash);
        job.deadlineMs = System.currentTimeMillis() + Math.max(0L, autoSendDelayMs) + VERIFY_WINDOW_MS;
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
        report(job, failureOutcome(job.injected), reason);
    }

    /** Typed -> SENT_UNVERIFIED (never re-sent unread, I5); not typed -> CANNOT_SHOW (G2). */
    static SendOutcome failureOutcome(boolean typed) {
        return typed ? SendOutcome.SENT_UNVERIFIED : SendOutcome.CANNOT_SHOW;
    }

    /** A non-scheduled intent is taking the page over: end the scheduled work honestly. */
    public void onOtherIntent() {
        main.removeCallbacksAndMessages(null);
        abandonAll("other_intent");
        stopListeningForEngine();
        awaitingEngineId = -1L;
        overKeyguard = false;
        jobState = claimed ? LockCoverPolicy.JobState.ENDED : LockCoverPolicy.JobState.IDLE;
        applyLockFlags(false);
        cover.hide();
    }

    public void onDestroy() {
        if (live.get() == this) live = new WeakReference<>(null);
        main.removeCallbacksAndMessages(null);
        abandonAll("activity_gone");
        stopListeningForEngine();
        cover.hide();   // the WebView is a singleton: restore its long-press and accessibility
    }

    /** Over the keyguard, a stale launch must not open the account picker either. */
    public boolean isOverKeyguard() {
        return overKeyguard;
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
        if (overKeyguard) {
            lastHardDeadlineMs = job.hardDeadlineMs;
            main.removeCallbacks(hardTimeout);
            main.postDelayed(hardTimeout, Math.max(0L, job.hardDeadlineMs - System.currentTimeMillis()));
        }
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
        if (m.getRecipientE164() == null || m.getText() == null) {
            // A corrupt row (start() never launches one): a real failure, not a reminder.
            report(job, SendOutcome.FAILED, "no_recipient");
            return;
        }
        job.e164 = m.getRecipientE164();
        job.bodyHash = ThreadEvent.hashOf(m.getText());
        targetE164 = job.e164;
        cover.setRecipient(job.e164);
        if (!host.isAlive() || !host.beginCompose(job.e164, m.getText())) {
            report(job, SendOutcome.CANNOT_SHOW, "activity_gone");
            return;
        }
        main.postDelayed(() -> {
            if (current == job && !job.injected) {
                // Nothing typed yet: CANNOT_SHOW with the cause (G2).
                report(job, failureOutcome(false), isSignInUrl(host.mainFrameUrl()) ? "auth" : "compose_timeout");
            }
        }, COMPOSE_TIMEOUT_MS);
    }

    /** One poll: read the thread, THEN probe the composer (a pending probe dates the read). */
    private void readOnce(final Job job) {
        if (current != job) return;
        if (!host.isAlive()) {
            report(job, SendOutcome.SENT_UNVERIFIED, "activity_gone");
            return;
        }
        host.readThread(job.e164, READ_LIMIT, bubbles -> {
            if (current != job) return;
            if (!host.isAlive()) {
                report(job, SendOutcome.SENT_UNVERIFIED, "activity_gone");
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
            report(job, SendOutcome.SENT_UNVERIFIED, "no_match");
            return;
        }
        main.postDelayed(() -> readOnce(job),
                job.watch.baseline() == null ? BASELINE_POLL_MS : READ_INTERVAL_MS);
    }

    /** Hands the outcome to the engine (the only status writer), then the next job or settle. */
    private void report(Job job, SendOutcome outcome, String detail) {
        if (current != job) return;
        current = null;
        if (job.watch != null && (outcome == SendOutcome.SENT_VERIFIED || outcome == SendOutcome.SENT_UNVERIFIED)) {
            // The engine answers these with one read of this thread: date it against the baseline.
            WebViewThreadEventSource.armPostSendRead(job.e164, job.watch.baseline(), System.currentTimeMillis());
        }
        awaitingEngineId = job.id;
        SendLaterEngine.onSendOutcome(app, job.id, outcome, detail);
        afterOutcome();
    }

    private void afterOutcome() {
        if (!queue.isEmpty()) {
            startNext();
            return;
        }
        busy = false;
        settle();
    }

    /**
     * Nothing left to send. Not over the keyguard: drop the lock flags (unchanged path). Over the
     * keyguard: the job ends on the engine's state broadcast for the last outcome (it reads this
     * thread first), at most {@link #FINISH_GRACE_MS} and never past the hard deadline; a stale
     * launch with nothing reported finishes after {@link #IDLE_FINISH_MS}.
     */
    private void settle() {
        if (overKeyguard) {
            main.removeCallbacks(hardTimeout);
            if (awaitingEngineId > 0) listenForEngine();
            long wait = IDLE_FINISH_MS;
            if (awaitingEngineId > 0) {
                wait = Math.min(FINISH_GRACE_MS, Math.max(0L, lastHardDeadlineMs - System.currentTimeMillis()));
            }
            main.postDelayed(() -> {
                if (!isActive() && !activity.isFinishing()) onJobEnded();
            }, wait);
        } else {
            awaitingEngineId = -1L;
            if (claimed) jobState = LockCoverPolicy.JobState.ENDED;
            applyLockFlags(false);
        }
    }

    /** Over the keyguard, all work is reported and handed off. */
    private void onJobEnded() {
        if (isActive()) return;
        endJob(LockCoverPolicy.finishNow(LockCoverPolicy.Event.JOB_ENDED, overKeyguard, isKeyguardLocked()));
    }

    /**
     * Ends the scheduled work: clears every lock flag FIRST, then finishes at once (still locked)
     * or, when the phone was unlocked during the send, removes the cover so the user sees the
     * thread normally.
     */
    private void endJob(boolean finish) {
        main.removeCallbacks(hardTimeout);
        main.removeCallbacks(pauseCheck);
        stopListeningForEngine();
        awaitingEngineId = -1L;
        if (claimed) jobState = LockCoverPolicy.JobState.ENDED;
        applyLockFlags(false);
        if (finish) {
            if (!activity.isFinishing()) host.finish();
            return;
        }
        updateCover();
    }

    /** Shows or removes the cover per {@link LockCoverPolicy#showCover}. */
    private void updateCover() {
        if (LockCoverPolicy.showCover(claimed, overKeyguard, jobState, isKeyguardLocked())) {
            cover.show(host.isAlive() ? host.pageView() : null);
            if (targetE164 != null) cover.setRecipient(targetE164);
        } else {
            cover.hide();
        }
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

    /** Ends the running and queued jobs: typed -> SENT_UNVERIFIED, untyped -> CANNOT_SHOW. */
    private void abandonAll(String detail) {
        Job job = current;
        current = null;
        if (job != null) {
            SendLaterEngine.onSendOutcome(app, job.id,
                    job.injected ? SendOutcome.SENT_UNVERIFIED : SendOutcome.CANNOT_SHOW, detail);
        }
        Job q;
        while ((q = queue.poll()) != null) {
            SendLaterEngine.onSendOutcome(app, q.id, SendOutcome.CANNOT_SHOW, detail);
        }
        busy = false;
    }

    // ================================================================== lock screen

    private boolean isKeyguardLocked() {
        KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    /**
     * Show over the keyguard and wake the screen while a CLAIMED send runs ({@link
     * LockCoverPolicy#applyLockFlags}); never requests a dismiss. Cleared before any finish.
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
