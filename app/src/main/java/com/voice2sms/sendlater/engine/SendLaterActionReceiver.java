package com.voice2sms.sendlater.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.voice2sms.sendlater.SendLaterIntents;

/**
 * Non-exported receiver for notification buttons. The engine re-reads the stored status before
 * acting and drops stale taps. ACTION_SEND_ANYWAY / ACTION_SEND_NOW: the user's "send anyway"
 * (self-preemption check only). ACTION_SNOOZE: +1 h. ACTION_CANCEL: cancel. ACTION_EDIT: opens the
 * sheet via SendLaterIntents.ACTION_EDIT when the row is still editable (notifications launch the
 * sheet directly, since Android blocks activity starts from notification trampolines).
 */
public final class SendLaterActionReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        final long id = intent.getLongExtra(SendLaterIntents.EXTRA_MESSAGE_ID, -1L);
        if (action == null || id < 0) return;
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        SendLaterEngine.runInBackground(app, () -> {
            if (SendLaterIntents.ACTION_SEND_ANYWAY.equals(action) || SendLaterIntents.ACTION_SEND_NOW.equals(action)) {
                SendLaterEngine.onSendAnyway(app, id);
            } else if (SendLaterIntents.ACTION_SNOOZE.equals(action)) {
                SendLaterEngine.onSnooze(app, id);
            } else if (SendLaterIntents.ACTION_CANCEL.equals(action)) {
                SendLaterEngine.onCancel(app, id);
            } else if (SendLaterIntents.ACTION_EDIT.equals(action)) {
                if (!SendLaterEngine.isEditable(app, id)) return;
                Intent edit = SendLaterNotifications.editIntent(app, id);
                if (edit == null) return;
                try {
                    app.startActivity(edit);
                } catch (RuntimeException blocked) {
                    // Background activity start refused: the notification's own Edit tap still works.
                }
            }
        }, pending::finish);
    }
}
