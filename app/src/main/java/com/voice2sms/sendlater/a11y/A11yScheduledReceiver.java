package com.voice2sms.sendlater.a11y;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.voice2sms.sendlater.SendLaterIntents;

/**
 * Manifest receiver (not exported) for the app-internal {@link SendLaterIntents#ACTION_SCHEDULED}
 * broadcast: a message was just scheduled, so warn (once a day) if voice2sms's accessibility
 * service is off (LIVE-F5). The pending count is a database read, so the check runs on a short
 * worker thread under {@link #goAsync()}.
 */
public final class A11yScheduledReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !SendLaterIntents.ACTION_SCHEDULED.equals(intent.getAction())) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                A11yWarning.check(app, System.currentTimeMillis());
            } finally {
                if (result != null) result.finish();
            }
        }, "sendlater-a11y").start();
    }
}
