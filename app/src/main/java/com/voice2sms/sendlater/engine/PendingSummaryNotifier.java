package com.voice2sms.sendlater.engine;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.voice2sms.R;
import com.voice2sms.SettingsActivity;
import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.store.SendLaterRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * R45 / OD39 (D47): the one "N scheduled · next &lt;who&gt; &lt;time&gt;" notification while Send
 * Later is on and at least one message is SCHEDULED or HELD.
 *
 * <p>Own channel "Scheduled messages" (IMPORTANCE_LOW, no sound, no vibration), one notification
 * id, setOnlyAlertOnce, dismissible (not ongoing), no foreground service (R40): it is a plain
 * notification re-posted by {@link #refresh} whenever the pending set may have changed (schedule
 * from the sheet, every engine state change, the master switch, boot / time change). Cancelled
 * when nothing is pending or Send Later is off (OD24). Tap opens the launcher page's Scheduled
 * card ({@link SettingsActivity#EXTRA_SHOW_SCHEDULED}). The lock screen gets a public version
 * without names, numbers or text. A missing POST_NOTIFICATIONS grant is skipped silently.
 * Message text, names and numbers are never logged.
 */
public final class PendingSummaryNotifier {

    public static final String CHANNEL_ID = "sendlater_scheduled";
    public static final int NOTIFICATION_ID = 0x5C4ED;

    private static final String TAG = "SendLaterPending";
    private static final int REQUEST_CODE = 0x5C4ED;

    /** One worker: refreshes run in order, so the last one decides what shows. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private PendingSummaryNotifier() {}

    /**
     * Re-reads the SCHEDULED and HELD rows off the calling thread and posts, updates or cancels
     * the notification. Safe from any thread; never throws.
     */
    public static void refresh(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        try {
            EXECUTOR.execute(() -> {
                try {
                    boolean enabled = SendLaterSettings.isEnabled(app);
                    List<ScheduledMessage> rows = new ArrayList<>();
                    if (enabled) {
                        SendLaterRepository repo = SendLaterRepository.get(app);
                        rows.addAll(repo.allPending());
                        rows.addAll(repo.allHeld());
                    }
                    PendingSummary summary = PendingSummary.build(rows, enabled,
                            System.currentTimeMillis(), TimeZone.getDefault());
                    show(app, summary);
                } catch (RuntimeException e) {
                    Log.w(TAG, "refresh failed: " + e.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "refresh not queued: " + e.getClass().getSimpleName());
        }
    }

    /** Posts {@code summary}, or cancels the notification when it is null. */
    private static void show(Context c, PendingSummary summary) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (summary == null) {
            nm.cancel(NOTIFICATION_ID);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (!nm.areNotificationsEnabled()) return;
        ensureChannel(c, nm);
        Notification publicVersion = new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentTitle(summary.getPublicText())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentTitle(summary.getText())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setOngoing(false)
                .setAutoCancel(false)
                .setContentIntent(openScheduledCard(c));
        try {
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS revoked between the check and the post: skip silently.
        }
    }

    /** Tap: the launcher page scrolled to its Scheduled card (home-scheduled contract). */
    private static PendingIntent openScheduledCard(Context c) {
        Intent i = new Intent(c, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SHOW_SCHEDULED, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, REQUEST_CODE, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Lazily creates the silent low-importance channel (no-op pre-O, cheap once it exists). */
    private static void ensureChannel(Context c, NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                c.getString(R.string.sendlater_pending_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.sendlater_pending_channel_description));
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }
}
