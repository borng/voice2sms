package com.voice2sms.sendlater.engine;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.voice2sms.sendlater.SendLaterIntents;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Send now / Send anyway from a notification, on every API level, open this invisible,
 * non-exported, no-history activity instead of a broadcast (LIVE-F4). Two reasons:
 * <ul>
 *   <li>L1 (OD31): the action never runs from the lock screen without an unlock. The activity is
 *       not show-when-locked, so Android asks for the unlock before it appears (on API 31+ the
 *       action is also marked authentication-required); it checks the keyguard itself too:
 *       unlocked: acts; still locked, API 26+: {@code requestDismissKeyguard}, acts only on
 *       success; still locked, API 24/25: does nothing (fails closed).</li>
 *   <li>LIVE-F4: the send it starts is planned from the foreground. While this activity is
 *       showing, voice2sms may start GVoiceWebViewActivity itself, so the send does not depend
 *       on the accessibility service (a broadcast trampoline cannot start an activity on API
 *       31+). It grants {@link UserTapGrant} for the message, hands the tap to the engine (same
 *       path as the action receiver), and stays until the send path has started the activity
 *       ({@link UserTapGrant#consume}), the engine started no send, or {@link #HOLD_MS}.</li>
 * </ul>
 */
public final class SendLaterUnlockActivity extends Activity {

    /** Longest the activity waits for the send path to start GVoiceWebViewActivity. */
    static final long HOLD_MS = 5_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean acted;
    private boolean released;
    private long grantedId = -1L;

    /** The Send now / Send anyway PendingIntent (immutable, distinct per id and action). */
    static PendingIntent pendingIntent(Context c, String action, long id) {
        Intent i = new Intent(c, SendLaterUnlockActivity.class)
                .setAction(action)
                .setData(new Uri.Builder().scheme("voice2sms-sendlater").authority("unlock")
                        .appendPath(String.valueOf(action)).appendPath(String.valueOf(id)).build())
                .putExtra(SendLaterIntents.EXTRA_MESSAGE_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return PendingIntent.getActivity(c, SendLaterNotifications.requestCode(action, id), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Only Send now / Send anyway and the draft notice's Replace and send / Send both (D32). */
    static boolean isUnlockAction(String action) {
        return SendLaterIntents.ACTION_SEND_NOW.equals(action) || SendLaterIntents.ACTION_SEND_ANYWAY.equals(action)
                || DraftNotice.ACTION_REPLACE.equals(action) || DraftNotice.ACTION_BOTH.equals(action);
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
        grantedId = id;
        // LIVE-F4: from here until release() the send path may plan DIRECT for this message.
        UserTapGrant.grant(id, SystemClock.elapsedRealtime(), () -> main.post(this::release));
        final AtomicBoolean launching = new AtomicBoolean(false);
        try {
            SendLaterEngine.runInBackground(app, () -> {
                TapResult r = SendLaterEngine.onNotificationTap(app, action, id);
                launching.set(r == TapResult.DONE && SendLaterEngine.isInFlight(app, id));
            }, () -> main.post(() -> afterTap(id, launching.get())));
        } catch (RuntimeException e) {
            release();   // engine unavailable: nothing sent
        }
    }

    /** The engine has handled the tap; wait for the launch only when a send is under way. */
    private void afterTap(long id, boolean launching) {
        if (released) return;
        if (!launching || !UserTapGrant.isActive(id, SystemClock.elapsedRealtime())) {
            release();
            return;
        }
        main.postDelayed(this::release, HOLD_MS);
    }

    /** Ends the grant and the activity (once). */
    private void release() {
        if (released) return;
        released = true;
        main.removeCallbacksAndMessages(null);
        if (grantedId >= 0) UserTapGrant.revoke(grantedId);
        finish();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (grantedId >= 0 && !released) UserTapGrant.revoke(grantedId);
        released = true;
        super.onDestroy();
    }
}
