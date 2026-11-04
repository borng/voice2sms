package com.voice2sms.sendlater.battery;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.voice2sms.R;

/**
 * Android side of the once-a-day "Scheduled messages may be late" warning (R39). Throttling lives
 * in {@link BatteryWarningThrottle}; this class wires it to SharedPreferences, the wall clock,
 * {@link BatteryHealth} and a low-importance notification with a Fix action.
 */
public final class BatteryWarning {

    private static final String TAG = "SendLaterBattery";
    static final String PREFS = "sendlater_battery";
    static final String CHANNEL_ID = "sendlater_battery";
    static final int NOTIFICATION_ID = 0x5B47;

    private BatteryWarning() {}

    /** A message was just scheduled: warn if battery-limited, at most once per 24 h. */
    public static boolean maybeNotify(Context context, long nowMs) {
        try {
            return throttle(context).maybeNotify(nowMs);
        } catch (RuntimeException e) {
            Log.w(TAG, "battery warning skipped: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** A due alarm fired {@code lateMs} after its target; over 2 min shares the daily warning. */
    public static boolean onAlarmLate(Context context, long lateMs) {
        if (lateMs <= BatteryWarningThrottle.LATE_THRESHOLD_MS) return false;
        try {
            return throttle(context).onAlarmLate(lateMs);
        } catch (RuntimeException e) {
            Log.w(TAG, "late-alarm warning skipped: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static BatteryWarningThrottle throttle(Context context) {
        final Context c = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final BatteryStatus[] status = new BatteryStatus[1];
        return new BatteryWarningThrottle(
                new BatteryWarningThrottle.Store() {
                    @Override public long getLong(String key, long def) { return prefs.getLong(key, def); }
                    @Override public void putLong(String key, long value) { prefs.edit().putLong(key, value).apply(); }
                },
                System::currentTimeMillis,
                () -> {
                    status[0] = BatteryHealth.check(c);
                    return status[0].isHealthy();
                },
                () -> post(c, status[0] != null ? status[0] : BatteryHealth.check(c)));
    }

    private static void post(Context c, BatteryStatus status) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(c, nm);
        String text = BatteryAdvice.text(status, status.isSamsung());
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(c.getString(R.string.sendlater_battery_warning_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true);
        Intent fix = BatteryHealth.fixIntent(c);
        if (fix != null) {
            PendingIntent pi = PendingIntent.getActivity(c, NOTIFICATION_ID, fix,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.setContentIntent(pi);
            b.addAction(0, c.getString(R.string.sendlater_battery_fix), pi);
        }
        nm.notify(NOTIFICATION_ID, b.build());
    }

    /** Own low-importance channel, created lazily on the first post. */
    private static void ensureChannel(Context c, NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                c.getString(R.string.sendlater_battery_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.sendlater_battery_channel_description));
        nm.createNotificationChannel(ch);
    }
}
