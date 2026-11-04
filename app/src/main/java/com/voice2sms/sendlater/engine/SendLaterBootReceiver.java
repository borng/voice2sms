package com.voice2sms.sendlater.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED and SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
 * re-register every pending alarm (R22). Not directBootAware, so BOOT_COMPLETED arrives after the
 * first unlock (no USER_UNLOCKED receiver needed). Process start ({@code onProcessStart}) has
 * already queued the IN_FLIGHT resolution on the same single-thread executor, so it runs first
 * (R23). The receiver is exported for the system broadcasts, so any other action (an explicit
 * intent from another app) is ignored.
 */
public final class SendLaterBootReceiver extends BroadcastReceiver {

    /** AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31). */
    static final String ACTION_EXACT_ALARM_PERMISSION =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED";

    /** Only the four system actions the manifest filter lists. */
    static boolean accepts(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || ACTION_EXACT_ALARM_PERMISSION.equals(action);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !accepts(intent.getAction())) return;
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        SendLaterEngine.runInBackground(app,
                () -> SendLaterScheduler.get(app).reregisterAll(app),
                pending::finish);
    }
}
