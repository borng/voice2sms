package com.voice2sms.sendlater.send;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

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
 * {e164, text}; once inject.js auto-send is injected, poll the open thread with readThread and
 * {@link SendVerifier}: own bubble -> SENT_VERIFIED (with a late-reply detail when an inbound
 * landed between the check and the send), nothing within the window -> SENT_UNVERIFIED (the
 * engine holds it and never re-sends unread, I5), WebView/auth failure -> FAILED. The outcome
 * goes to {@link SendLaterEngine#onSendOutcome}, which owns every status change. Launches that
 * arrive while one is running queue behind it (several messages due together). When the activity
 * was launched over the keyguard it finishes once the queue is empty, so the GV page is not left
 * on the lock screen.
 *
 * <p>Never uses the Wear/Gemini auto-send extra or the global auto-send rate limiter: the
 * scheduled queue is the limiter (R30).
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
     * about 20 s for the bubble to show. No own bubble by then -> SENT_UNVERIFIED.
     */
    static final long VERIFY_WINDOW_MS = 45_000L;
    static final long FIRST_READ_AFTER_SEND_MS = 3_000L;
    static final long READ_INTERVAL_MS = 2_000L;
    /** Lets the engine's own post-send read of the open thread finish before the activity goes. */
    static final long FINISH_GRACE_MS = 2_000L;
    static final int READ_LIMIT = 30;

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

        /** Activity.finish(). */
        void finish();
    }

    private static final class Job {
        final long id;
        final long checkTimeMs;
        String e164;
        String bodyHash;
        boolean injected;
        long deadlineMs;

        Job(long id, long checkTimeMs) {
            this.id = id;
            this.checkTimeMs = checkTimeMs;
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

    public ScheduledSendController(Activity activity, Host host) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        this.host = host;
        live = new WeakReference<>(this);
    }

    // ================================================================== process-wide view

    /** Main-frame URL of the live GVoiceWebViewActivity, or null when none is alive. */
    static String liveMainFrameUrl() {
        ScheduledSendController c = live.get();
        if (c == null || !c.host.isAlive()) return null;
        return c.host.mainFrameUrl();
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
        enqueue(new Job(id, checkTimeMs));
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
        applyLockFlags(true);
        if (restored || fromHistory || id <= 0 || !LaunchClaims.INSTANCE.take(id, token)) {
            if (!isActive()) settle();
            return true;
        }
        WebViewScheduledSender.cancelLaunchNotification(app, id);
        enqueue(new Job(id, checkTime > 0 ? checkTime : System.currentTimeMillis()));
        return true;
    }

    /** A scheduled send is queued or running (the activity must not inject its own composer). */
    public boolean isActive() {
        return current != null || !queue.isEmpty();
    }

    /** The running send has asked for the page and is waiting for onPageFinished. */
    public boolean isComposing() {
        return current != null && current.e164 != null && !current.injected;
    }

    /** The activity injected inject.js auto-send for the running job. */
    public void onComposerInjected(long autoSendDelayMs) {
        final Job job = current;
        if (job == null || job.injected || job.e164 == null) return;
        job.injected = true;
        job.deadlineMs = System.currentTimeMillis() + Math.max(0L, autoSendDelayMs) + VERIFY_WINDOW_MS;
        main.postDelayed(() -> readOnce(job), Math.max(0L, autoSendDelayMs) + FIRST_READ_AFTER_SEND_MS);
    }

    /** The page could not be used (no web session, sign-in page, script missing). */
    public void onWebFailure(String reason) {
        Job job = current;
        if (job == null) return;
        report(job, job.injected ? SendOutcome.SENT_UNVERIFIED : SendOutcome.FAILED, reason);
    }

    /** A non-scheduled intent is taking the page over: end the scheduled work honestly. */
    public void onOtherIntent() {
        main.removeCallbacksAndMessages(null);
        abandonAll("other_intent");
        overKeyguard = false;
        applyLockFlags(false);
    }

    public void onDestroy() {
        if (live.get() == this) live = new WeakReference<>(null);
        main.removeCallbacksAndMessages(null);
        abandonAll("activity_gone");
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
            report(job, SendOutcome.FAILED, "no_recipient");
            return;
        }
        job.e164 = m.getRecipientE164();
        job.bodyHash = ThreadEvent.hashOf(m.getText());
        if (!host.isAlive() || !host.beginCompose(job.e164, m.getText())) {
            report(job, SendOutcome.CANNOT_SHOW, "activity_gone");
            return;
        }
        main.postDelayed(() -> {
            if (current == job && !job.injected) {
                report(job, SendOutcome.FAILED, isSignInUrl(host.mainFrameUrl()) ? "auth" : "compose_timeout");
            }
        }, COMPOSE_TIMEOUT_MS);
    }

    private void readOnce(final Job job) {
        if (current != job) return;
        if (!host.isAlive()) {
            report(job, SendOutcome.SENT_UNVERIFIED, "activity_gone");
            return;
        }
        host.readThread(job.e164, READ_LIMIT, bubbles -> onThreadRead(job, bubbles));
    }

    private void onThreadRead(final Job job, List<ThreadBubble> bubbles) {
        if (current != job) return;
        long now = System.currentTimeMillis();
        if (bubbles != null) {
            List<ThreadEvent> events = WebViewThreadEventSource.toEvents(bubbles, now);
            SendVerifier.Result r = SendVerifier.verify(job.bodyHash, events, job.checkTimeMs, now);
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
        main.postDelayed(() -> readOnce(job), READ_INTERVAL_MS);
    }

    /** Hands the outcome to the engine (the only status writer), then the next job or settle. */
    private void report(Job job, SendOutcome outcome, String detail) {
        if (current != job) return;
        current = null;
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

    /** Nothing left to send: leave the lock screen (finish) or drop the lock-screen flags. */
    private void settle() {
        if (overKeyguard) {
            main.postDelayed(() -> {
                if (!isActive() && !activity.isFinishing()) host.finish();
            }, FINISH_GRACE_MS);
        } else {
            applyLockFlags(false);
        }
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

    /** Show over the keyguard and wake the screen while sending; never requests a dismiss. */
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
