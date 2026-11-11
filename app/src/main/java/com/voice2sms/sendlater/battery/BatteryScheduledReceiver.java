package com.voice2sms.sendlater.battery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.voice2sms.sendlater.SendLaterIntents;

/**
 * Manifest receiver (not exported) for the app-internal {@link SendLaterIntents#ACTION_SCHEDULED}
 * broadcast: a message was just scheduled, so warn once a day if the battery status may make it
 * late (R39). The work is two system-service reads and at most one notification.
 */
public final class BatteryScheduledReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !SendLaterIntents.ACTION_SCHEDULED.equals(intent.getAction())) return;
        BatteryWarning.maybeNotify(context, System.currentTimeMillis());
    }
}
