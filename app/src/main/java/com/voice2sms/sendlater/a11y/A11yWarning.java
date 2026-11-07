package com.voice2sms.sendlater.a11y;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.voice2sms.GeminiSmsInterceptService;
import com.voice2sms.R;
import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.store.SendLaterRepository;

import java.util.List;

/**
 * Android side of the "accessibility service is off" warning (LIVE-F5). Decision in
 * {@link A11yHealth}, throttle in {@link A11yWarningThrottle}; this class wires them to
 * {@code Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES}, the Send Later switch, the pending count,
 * SharedPreferences and one low-importance notification whose tap opens Accessibility settings.
 * The notification never names a contact or shows message text. It never turns the service on.
 */
public final class A11yWarning {

    private static final String TAG = "SendLaterA11y";
    static final String PREFS = "sendlater_a11y";
    static final String CHANNEL_ID = "sendlater_a11y";
    static final String NOTIFICATION_TAG = "sendlater_a11y";
    static final int NOTIFICATION_ID = 0x5B48;

    private A11yWarning() {}

    /**
     * voice2sms's accessibility service is listed in the system's enabled services. Unreadable:
     * assume on, so a read failure never posts a false alarm (the warning's fail-open rule).
     */
    public static boolean isServiceEnabled(Context context) {
        return isServiceEnabled(context, true);
    }

    /**
     * The one "is our accessibility service on" test ({@code Settings.Secure} listing, matched by
     * {@link A11yHealth#isServiceListed}). {@code failOpen} is the answer when the setting cannot be
     * read (no context, a throwing resolver): true for the warning, false where "off" is the safe
     * reading (a status row that must not claim the service is on).
     */
    public static boolean isServiceEnabled(Context context, boolean failOpen) {
        try {
            Context c = app(context);
            String listed = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return A11yHealth.isServiceListed(listed, c.getPackageName(),
                    GeminiSmsInterceptService.class.getName());
        } catch (RuntimeException e) {
            return failOpen;
        }
    }

    /**
     * A message was scheduled (or another full check is due): reads the switch, the pending count
     * (database: call off the main thread) and the service, then posts / throttles / clears.
     */
    public static A11yWarningThrottle.Result check(Context context, long nowMs) {
        try {
            Context c = app(context);
            boolean on = SendLaterSettings.isEnabled(c);
            int pending = 0;
            if (on) {
                List<ScheduledMessage> rows = SendLaterRepository.get(c).allPending();
                pending = rows == null ? 0 : rows.size();
            }
            return apply(c, A11yHealth.decide(on, pending, isServiceEnabled(c)), nowMs);
        } catch (RuntimeException e) {
            Log.w(TAG, "accessibility check skipped: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** The engine woke with these facts (it already holds the pending list). Never throws. */
    public static A11yWarningThrottle.Result onEngineWake(Context context, boolean sendLaterOn,
            int pendingCount, long nowMs) {
        if (context == null) return null;
        try {
            Context c = app(context);
            return apply(c, A11yHealth.decide(sendLaterOn, pendingCount, isServiceEnabled(c)), nowMs);
        } catch (RuntimeException e) {
            Log.w(TAG, "accessibility check skipped: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * No database read (safe on the main thread): when the service is back on, removes the
     * warning and resets the throttle. Returns whether the service is enabled.
     */
    public static boolean clearIfEnabled(Context context) {
        boolean enabled = isServiceEnabled(context);
        if (!enabled) return false;
        try {
            apply(app(context), A11yHealth.State.OK, System.currentTimeMillis());
        } catch (RuntimeException e) {
            Log.w(TAG, "accessibility clear skipped: " + e.getClass().getSimpleName());
        }
        return true;
    }

    /** The screen where the user turns the service back on. */
    public static Intent settingsIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static A11yWarningThrottle.Result apply(final Context c, A11yHealth.State state, long nowMs) {
        final SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new A11yWarningThrottle(
                new A11yWarningThrottle.Store() {
                    @Override public long getLong(String key, long def) { return prefs.getLong(key, def); }
                    @Override public void putLong(String key, long value) { prefs.edit().putLong(key, value).apply(); }
                },
                new A11yWarningThrottle.Poster() {
                    @Override public void post() { A11yWarning.post(c); }
                    @Override public void clear() { A11yWarning.cancel(c); }
                }).check(state, nowMs);
    }

    private static Context app(Context context) {
        return context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    private static void cancel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
    }

    private static void post(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(c, nm);
        String text = c.getString(R.string.sendlater_a11y_warning_text);
        PendingIntent pi = PendingIntent.getActivity(c, NOTIFICATION_ID, settingsIntent(),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(c.getString(R.string.sendlater_a11y_warning_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(0, c.getString(R.string.sendlater_a11y_fix), pi);
        nm.notify(NOTIFICATION_TAG, NOTIFICATION_ID, b.build());
    }

    /** Own low-importance channel, created lazily on the first post. */
    private static void ensureChannel(Context c, NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                c.getString(R.string.sendlater_a11y_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.sendlater_a11y_channel_description));
        nm.createNotificationChannel(ch);
    }
}
