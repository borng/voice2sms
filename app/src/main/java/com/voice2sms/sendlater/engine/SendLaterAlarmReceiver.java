package com.voice2sms.sendlater.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.voice2sms.sendlater.SendLaterIntents;

/**
 * Non-exported receiver for the engine's alarms: T-5 -> last-call notification, T and RETRY ->
 * {@link SendLaterEngine#onDue}, EXPIRE -> expiry check. Work runs on the engine executor.
 */
public final class SendLaterAlarmReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        final long id = intent.getLongExtra(SendLaterIntents.EXTRA_MESSAGE_ID, -1L);
        if (action == null || id < 0) return;
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        SendLaterEngine.runInBackground(app, () -> {
            if (AndroidAlarmSink.ACTION_LAST_CALL.equals(action)) {
                SendLaterEngine.onLastCall(app, id);
            } else if (AndroidAlarmSink.ACTION_DUE.equals(action)) {
                SendLaterEngine.onDueAlarm(app, id);
            } else if (AndroidAlarmSink.ACTION_RETRY.equals(action)) {
                SendLaterEngine.onDue(app, id);
            } else if (SendLaterIntents.ACTION_EXPIRE.equals(action)) {
                SendLaterEngine.onExpireAlarm(app, id);
            }
        }, pending::finish);
    }
}
