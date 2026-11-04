package com.voice2sms.sendlater.engine;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import com.voice2sms.sendlater.SendLaterIntents;

/**
 * AlarmManager-backed {@link AlarmSink}. One PendingIntent per (kind, id), made distinct by its
 * data URI, delivered to the non-exported {@link SendLaterAlarmReceiver}. RTC_WAKEUP.
 */
final class AndroidAlarmSink implements AlarmSink {

    static final String ACTION_LAST_CALL = "com.voice2sms.sendlater.engine.alarm.LAST_CALL";
    static final String ACTION_DUE = "com.voice2sms.sendlater.engine.alarm.DUE";
    static final String ACTION_RETRY = "com.voice2sms.sendlater.engine.alarm.RETRY";

    private final Context context;

    AndroidAlarmSink(Context context) {
        this.context = context;
    }

    private AlarmManager am() {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    @Override public boolean canScheduleExact() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = am();
        return am != null && am.canScheduleExactAlarms();
    }

    @Override public void setExact(AlarmKind kind, long id, long atMs) {
        AlarmManager am = am();
        if (am == null) return;
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(kind, id));
    }

    @Override public void setInexact(AlarmKind kind, long id, long atMs) {
        AlarmManager am = am();
        if (am == null) return;
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(kind, id));
    }

    @Override public void cancel(AlarmKind kind, long id) {
        AlarmManager am = am();
        if (am == null) return;
        PendingIntent pi = pending(kind, id);
        am.cancel(pi);
        pi.cancel();
    }

    private PendingIntent pending(AlarmKind kind, long id) {
        Intent i = new Intent(actionFor(kind))
                .setClass(context, SendLaterAlarmReceiver.class)
                .setPackage(context.getPackageName())
                .setData(Uri.parse("voice2sms-sendlater://alarm/" + kind.name() + "/" + id))
                .putExtra(SendLaterIntents.EXTRA_MESSAGE_ID, id);
        return PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static String actionFor(AlarmKind kind) {
        switch (kind) {
            case LAST_CALL: return ACTION_LAST_CALL;
            case DUE: return ACTION_DUE;
            case EXPIRE: return SendLaterIntents.ACTION_EXPIRE;
            case RETRY:
            default: return ACTION_RETRY;
        }
    }
}
