package com.voice2sms.sendlater.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED and SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
 * re-register every pending alarm (R22). Not directBootAware, so BOOT_COMPLETED arrives after the
 * first unlock (no USER_UNLOCKED receiver needed). Process start ({@code onProcessStart}) has
 * already queued the IN_FLIGHT resolution on the same single-thread executor, so it runs first
 * (R23).
 */
public final class SendLaterBootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        SendLaterEngine.runInBackground(app,
                () -> SendLaterScheduler.get(app).reregisterAll(app),
                pending::finish);
    }
}
