package com.voice2sms.sendlater.engine;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import com.voice2sms.sendlater.SendLaterIntents;

/**
 * L1 (OD31) below API 31: Send now / Send anyway from a notification open this invisible,
 * non-exported activity instead of a broadcast, so the action never runs from the lock screen
 * without an unlock. The activity is not show-when-locked, so Android asks for the unlock before
 * it appears; it also checks the keyguard itself:
 * <ul>
 *   <li>unlocked: applies the tap (same engine path as the action receiver) and finishes;</li>
 *   <li>still locked, API 26+: {@code requestDismissKeyguard}; acts only on success;</li>
 *   <li>still locked, API 24/25: does nothing (fails closed); the notification stays tappable.</li>
 * </ul>
 * On API 31+ the notification uses an authenticated broadcast action instead
 * ({@link SendLaterNotifications#usesAuthenticatedBroadcast}).
 */
public final class SendLaterUnlockActivity extends Activity {

    private boolean acted;

    /** The Send now / Send anyway PendingIntent for API < 31 (immutable, distinct per id and action). */
    static PendingIntent pendingIntent(Context c, String action, long id) {
        Intent i = new Intent(c, SendLaterUnlockActivity.class)
                .setAction(action)
                .setData(new Uri.Builder().scheme("voice2sms-sendlater").authority("unlock")
                        .appendPath(String.valueOf(action)).appendPath(String.valueOf(id)).build())
                .putExtra(SendLaterIntents.EXTRA_MESSAGE_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Only Send now / Send anyway are accepted here. */
    static boolean isUnlockAction(String action) {
        return SendLaterIntents.ACTION_SEND_NOW.equals(action) || SendLaterIntents.ACTION_SEND_ANYWAY.equals(action);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        final String action = intent != null ? intent.getAction() : null;
        final long id = intent != null ? intent.getLongExtra(SendLaterIntents.EXTRA_MESSAGE_ID, -1L) : -1L;
        if (savedInstanceState != null || !isUnlockAction(action) || id < 0) {
            finish();
            return;
        }
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked()) {
            act(action, id);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { act(action, id); }
                @Override public void onDismissCancelled() { finish(); }
                @Override public void onDismissError() { finish(); }
            });
            return;
        }
        finish();   // API 24/25 and still locked: never act over the keyguard
    }

    private void act(String action, long id) {
        if (acted) return;
        acted = true;
        final Context app = getApplicationContext();
        try {
            SendLaterEngine.runInBackground(app, () -> SendLaterEngine.onNotificationTap(app, action, id), null);
        } catch (RuntimeException e) {
            // engine unavailable: nothing sent
        }
        finish();
    }
}
