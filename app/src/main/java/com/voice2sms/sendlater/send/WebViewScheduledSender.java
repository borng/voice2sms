package com.voice2sms.sendlater.send;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.voice2sms.GVoiceWebViewActivity;
import com.voice2sms.GeminiSmsInterceptService;
import com.voice2sms.sendlater.domain.PreSendCheck;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.engine.ScheduledSender;
import com.voice2sms.sendlater.engine.SendLaterEngine;
import com.voice2sms.sendlater.engine.UserTapGrant;
import com.voice2sms.sendlater.store.ScheduledSendQueue;
import com.voice2sms.sendlater.store.SendLaterRepository;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MVP due-time send path (R30, D21, OD10, OD11): no foreground service and no dedicated send
 * activity. {@link #start} takes a {@link ScheduledSendQueue} slot (2 s spacing, daily cap; never
 * the global auto-send rate limiter), confirms the engine's IN_FLIGHT mark with the body hash (R31),
 * then opens GVoiceWebViewActivity with {@link ScheduledSendController#EXTRA_SCHEDULED_MESSAGE_ID}
 * as {@link LaunchPlanner} decides: DIRECT startActivity when voice2sms's accessibility service is
 * enabled, a full-screen-intent notification on a locked or dark screen, otherwise nothing
 * (CANNOT_SHOW, then false: "Unlock to send" while locked (D15), else "Couldn't send
 * automatically"). LIVE-F4: a send started by the user's Send now / Send anyway tap while
 * voice2sms's tap activity is showing ({@link UserTapGrant}) is DIRECT on an unlocked screen,
 * accessibility service or not. D30: the engine's {@link PreSendCheck} travels to the job
 * ({@link ScheduledSendController#notePreSendCheck}); the job reads the thread before Send. A corrupt row (no recipient or text) is CANNOT_SHOW with the generic reminder.
 * At the slot's time the row is reloaded and a launch more than {@link LaunchRecheck#QUEUE_SLACK_MS}
 * past its lateness deadline is CANNOT_SHOW {@code late} (F4, I2, OD30). Every CANNOT_SHOW detail goes through
 * {@link SendDetails#forEngine}.
 *
 * <p>Runs on the engine's background thread. Never writes a status itself: outcomes go through
 * {@link SendLaterEngine#onSendOutcome}. A launch nobody picks up within
 * {@link #CLAIM_TIMEOUT_MS} (the system showed the full-screen intent as a heads-up, the start was
 * blocked) is reported CANNOT_SHOW: nothing was typed.
 */
public final class WebViewScheduledSender implements ScheduledSender {

    private static final String TAG = "SendLaterSend";

    static final String CHANNEL_SEND = "sendlater_send";
    static final String NOTIFICATION_TAG = "sendlater_send";
    /** The activity must take the launch token within this, else CANNOT_SHOW. */
    static final long CLAIM_TIMEOUT_MS = 30_000L;
    /** CANNOT_SHOW detail when the scheduled queue's daily cap refused the slot (G4). */
    public static final String DETAIL_DAILY_CAP = "daily_cap";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Row reloads before a launch (Room is never read on the main thread). */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** D30: the controller reads the thread before it taps Send ({@link PreSendRead}). */
    @Override
    public boolean checksThreadBeforeSend() {
        return true;
    }

    /**
     * Without {@link #start(Context, ScheduledMessage, PreSendCheck)} first the job uses the strict
     * default ({@link ScheduledSendController#defaultPreSendCheck}).
     */
    @Override
    public boolean start(Context context, ScheduledMessage message) {
        if (context == null || message == null) return false;
        final Context app = context.getApplicationContext();
        final long id = message.getId();
        if (message.getRecipientE164() == null || message.getText() == null) {
            // A corrupt row: nothing can be typed. Report it so the reminder is the generic
            // "Couldn't send automatically", never the default "Unlock to send" (gate-residuals).
            return notStarted(app, id, ScheduledSendController.DETAIL_NO_RECIPIENT);
        }
        // Nothing that cannot launch may use up a queue slot (spacing, daily cap).
        if (!rowIsSendable(app, message)) return false;
        if (!ScheduledSendController.isBusy() && ForegroundUse.isUserBusy(System.currentTimeMillis())) {
            // OD23: the user is typing in the voice2sms WebView; never take it over. Reminder.
            return notStarted(app, id, ForegroundUse.DETAIL_USER_BUSY);
        }
        LaunchPlanner.Result plan = currentPlan(app, id);
        if (plan == LaunchPlanner.Result.CANNOT_SHOW && !ScheduledSendController.isBusy()) {
            // CANNOT_SHOW: nothing attempted. Locked: "Unlock to send"; a lit, unlocked screen
            // with no accessibility service: "Couldn't send automatically" (F6).
            return notStarted(app, id, SendDetails.plannerCause(isKeyguardLocked(app)));
        }

        long delayMs = ScheduledSendQueue.get(app).acquireSlot(System.currentTimeMillis());
        if (delayMs == ScheduledSendQueue.REFUSED) {
            // Daily cap reached: nothing attempted; the engine reminds the user to send by hand.
            Log.w(TAG, "scheduled queue refused id " + id);
            return notStarted(app, id, DETAIL_DAILY_CAP);
        }
        if (!markInFlight(app, message)) return false;

        final long checkTime = System.currentTimeMillis();
        final String token = LaunchClaims.INSTANCE.issue(id);
        MAIN.postDelayed(() -> recheckThen(app, id, token, () -> launch(app, id, token, checkTime)), delayMs);
        watchClaim(app, id, token, delayMs);
        return true;
    }

    /**
     * D30: records the engine's pre-send check for this message's job (off for the user's own
     * tap), then starts as {@link #start(Context, ScheduledMessage)}. The job reads the thread
     * before it taps Send.
     */
    @Override
    public boolean start(Context context, ScheduledMessage message, PreSendCheck check) {
        if (message != null) ScheduledSendController.notePreSendCheck(message.getId(), check);
        return start(context, message);
    }

    /**
     * Nothing was attempted: reports CANNOT_SHOW with {@code detail} (so the engine can word the
     * reminder), then returns false as {@link ScheduledSender#start} requires. Called on the
     * engine's thread, the report is handled before start returns; the engine's own
     * "not started" handling then finds the row released and does nothing more.
     */
    private static boolean notStarted(Context app, long id, String detail) {
        SendLaterEngine.onSendOutcome(app, id, SendOutcome.CANNOT_SHOW,
                SendDetails.forEngine(SendOutcome.CANNOT_SHOW, detail));
        return false;
    }

    /**
     * At the slot's time: hand the launch to a live activity that is already sending (messages
     * due together queue behind it there), else open GVoiceWebViewActivity as
     * {@link LaunchPlanner} decides NOW (the screen may have changed during the queue delay).
     */
    private static void launch(Context app, long id, String token, long checkTime) {
        try {
            if (!LaunchClaims.INSTANCE.isPending(id)) return;
            if (ScheduledSendController.offer(id, token, checkTime, 0L)) return;
            if (ForegroundUse.isUserBusy(System.currentTimeMillis())) {
                // OD23: the user started using the WebView during the queue delay.
                if (LaunchClaims.INSTANCE.take(id, token)) {
                    SendLaterEngine.onSendOutcome(app, id, SendOutcome.CANNOT_SHOW,
                            SendDetails.forEngine(SendOutcome.CANNOT_SHOW, ForegroundUse.DETAIL_USER_BUSY));
                }
                return;
            }
            // OD28: whether the screen was dark is recorded now, when the launch is planned, and
            // travels with the intent (the activity's own onCreate reading comes after the wake-up).
            final boolean dark = !isScreenOn(app);
            switch (planFor(app, dark, id)) {
                case DIRECT:
                    launchDirect(app, id, token, checkTime, dark);
                    return;
                case FULL_SCREEN_INTENT:
                    postFullScreenIntent(app, id, token, checkTime, dark);
                    return;
                case CANNOT_SHOW:
                default:
                    // Nothing was typed: the engine releases it with a reminder (locked: "Unlock to
                    // send"; unlocked: "Couldn't send automatically", F6).
                    if (LaunchClaims.INSTANCE.take(id, token)) {
                        SendLaterEngine.onSendOutcome(app, id, SendOutcome.CANNOT_SHOW,
                                SendDetails.forEngine(SendOutcome.CANNOT_SHOW,
                                        SendDetails.plannerCause(isKeyguardLocked(app))));
                    }
            }
        } finally {
            // LIVE-F4: the launch is decided (and a DIRECT start made); the tap activity may go.
            UserTapGrant.consume(id, SystemClock.elapsedRealtime());
        }
    }

    /**
     * F4 (I2 "ever"): the queue delay is a main-Handler delay, which counts uptime and stops in
     * deep sleep, so the slot can come long after the thread check. Before launching, reload the
     * row off the main thread: more than {@link LaunchRecheck#QUEUE_SLACK_MS} past
     * {@code LatenessBudget.deadlineMs} (OD30) it is CANNOT_SHOW with
     * {@link LaunchRecheck#DETAIL_LATE} (the LATE reminder), never a late send; a row that is no
     * longer IN_FLIGHT is dropped silently (the engine already resolved it). An unreadable row
     * launches: the controller reloads and re-checks it before anything is typed.
     */
    private static void recheckThen(final Context app, final long id, final String token, final Runnable launch) {
        if (!LaunchClaims.INSTANCE.isPending(id)) {
            UserTapGrant.consume(id, SystemClock.elapsedRealtime());
            return;
        }
        IO.execute(() -> {
            LaunchRecheck.Verdict verdict = LaunchRecheck.Verdict.PROCEED;
            try {
                verdict = LaunchRecheck.check(SendLaterRepository.get(app).get(id), System.currentTimeMillis(),
                        LaunchRecheck.QUEUE_SLACK_MS);
            } catch (RuntimeException e) {
                Log.w(TAG, "recheck failed for id " + id + ": " + e.getClass().getSimpleName());
            }
            switch (verdict) {
                case LATE:
                    if (LaunchClaims.INSTANCE.take(id, token)) {
                        SendLaterEngine.onSendOutcome(app, id, SendOutcome.CANNOT_SHOW,
                                SendDetails.forEngine(SendOutcome.CANNOT_SHOW, LaunchRecheck.DETAIL_LATE));
                    }
                    UserTapGrant.consume(id, SystemClock.elapsedRealtime());
                    return;
                case GONE:
                    LaunchClaims.INSTANCE.take(id, token);
                    UserTapGrant.consume(id, SystemClock.elapsedRealtime());
                    return;
                case PROCEED:
                default:
                    MAIN.post(launch);
            }
        });
    }

    private static LaunchPlanner.Result currentPlan(Context app, long id) {
        return planFor(app, !isScreenOn(app), id);
    }

    private static LaunchPlanner.Result planFor(Context app, boolean screenDark, long id) {
        return LaunchPlanner.plan(isOwnAccessibilityServiceEnabled(app),
                isKeyguardLocked(app), !screenDark, canUseFullScreenIntent(app), isUserTapForeground(id));
    }

    /** LIVE-F4: the user's tap activity for {@code id} is showing now ({@link UserTapGrant}). */
    static boolean isUserTapForeground(long id) {
        return UserTapGrant.isActive(id, SystemClock.elapsedRealtime());
    }

    /** Cheap pre-check before a slot is taken: the row is IN_FLIGHT with this text. */
    private static boolean rowIsSendable(Context app, ScheduledMessage message) {
        ScheduledMessage stored = SendLaterRepository.get(app).get(message.getId());
        return stored != null && stored.getStatus() == ScheduledMessage.Status.IN_FLIGHT
                && message.getText().equals(stored.getText());
    }

    /**
     * R31 precondition: the row is IN_FLIGHT with this message's body hash before anything can be
     * typed. The write is the engine's {@code markInFlight(id, bodyHash)}, done before it calls
     * {@link #start} (only SendLaterEngine changes send status, SendLaterEngineSourceTest). This
     * re-reads the row and confirms that mark; any other status or text means the row moved on,
     * so nothing is launched. Writes nothing.
     */
    private static boolean markInFlight(Context app, ScheduledMessage message) {
        String bodyHash = ThreadEvent.hashOf(message.getText());
        ScheduledMessage stored = SendLaterRepository.get(app).get(message.getId());
        return stored != null && stored.getStatus() == ScheduledMessage.Status.IN_FLIGHT
                && bodyHash != null && bodyHash.equals(ThreadEvent.hashOf(stored.getText()));
    }

    static Intent launchIntent(Context app, long id, String token, long checkTime, boolean launchedDark) {
        Intent i = new Intent(app, GVoiceWebViewActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(ScheduledSendController.EXTRA_SCHEDULED_MESSAGE_ID, id);
        i.putExtra(ScheduledSendController.EXTRA_LAUNCH_TOKEN, token);
        i.putExtra(ScheduledSendController.EXTRA_CHECK_TIME, checkTime);
        i.putExtra(ScheduledSendController.EXTRA_LAUNCHED_DARK, launchedDark);
        return i;
    }

    private static void launchDirect(Context app, long id, String token, long checkTime, boolean launchedDark) {
        if (!LaunchClaims.INSTANCE.isPending(id)) return;
        try {
            app.startActivity(launchIntent(app, id, token, checkTime, launchedDark));
        } catch (RuntimeException e) {
            Log.w(TAG, "direct launch failed for id " + id + ": " + e.getClass().getSimpleName());
        }
    }

    private static void postFullScreenIntent(Context app, long id, String token, long checkTime,
                                             boolean launchedDark) {
        if (!LaunchClaims.INSTANCE.isPending(id)) return;
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_SEND) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_SEND, "Scheduled sends",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Shown while a scheduled message is being sent");
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(app, (int) id, launchIntent(app, id, token, checkTime, launchedDark),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // No message text or contact name: this shows on the lock screen.
        Notification n = new NotificationCompat.Builder(app, CHANNEL_SEND)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle("Sending a scheduled message")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(CLAIM_TIMEOUT_MS)
                .build();
        try {
            nm.notify(NOTIFICATION_TAG, (int) id, n);
        } catch (RuntimeException e) {
            Log.w(TAG, "full-screen notification failed for id " + id + ": " + e.getClass().getSimpleName());
        }
    }

    static void cancelLaunchNotification(Context app, long id) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_TAG, (int) id);
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /** Nobody took the launch in time: nothing was typed, so CANNOT_SHOW ("Couldn't send automatically"). */
    private static void watchClaim(final Context app, final long id, final String token, long delayMs) {
        MAIN.postDelayed(() -> {
            if (!LaunchClaims.INSTANCE.take(id, token)) return;
            cancelLaunchNotification(app, id);
            SendLaterEngine.onSendOutcome(app, id, SendOutcome.CANNOT_SHOW,
                    SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "launch_timeout"));
        }, delayMs + CLAIM_TIMEOUT_MS);
    }

    // ================================================================== device state

    static boolean isOwnAccessibilityServiceEnabled(Context app) {
        try {
            AccessibilityManager am = (AccessibilityManager) app.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null || !am.isEnabled()) return false;
            List<AccessibilityServiceInfo> enabled =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (enabled == null) return false;
            for (AccessibilityServiceInfo info : enabled) {
                ServiceInfo si = info.getResolveInfo() != null ? info.getResolveInfo().serviceInfo : null;
                if (si != null && app.getPackageName().equals(si.packageName)
                        && GeminiSmsInterceptService.class.getName().equals(si.name)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    static boolean isKeyguardLocked(Context app) {
        KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    static boolean isScreenOn(Context app) {
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    /** Notifications allowed and, on API 34+, the full-screen-intent permission still granted. */
    static boolean canUseFullScreenIntent(Context app) {
        try {
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                return nm != null && nm.canUseFullScreenIntent();
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
