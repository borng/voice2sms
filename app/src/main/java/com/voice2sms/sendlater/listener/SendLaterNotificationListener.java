package com.voice2sms.sendlater.listener;

import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.engine.SendLaterEngine;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reply detection (R32-R34, D14, D22): watches Google Voice notifications (primary) and voice2sms
 * incoming-SMS notifications (secondary, only while voice2sms is the default SMS app), records an
 * inbound event for conversations with a live Send Later message, and fires the early hold.
 *
 * <p>Notification content never reaches a log (I7). Room work runs on one background thread.
 * Voice suppresses notifications for the thread open in native Voice; the on-screen reader covers
 * that case.
 */
public final class SendLaterNotificationListener extends NotificationListenerService {

    private static final String TAG = "SendLaterListener";

    /** Purge at most this often while notifications arrive. */
    private static final long PURGE_INTERVAL_MS = 60 * 60_000L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sendlater-listener");
        t.setDaemon(true);
        return t;
    });

    private static volatile long lastPurgeMs;

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        final Context app = getApplicationContext();
        SendLaterListenerStatus.markConnected(app, System.currentTimeMillis());
        WORKER.execute(() -> purge(app, System.currentTimeMillis()));
    }

    @Override public void onListenerDisconnected() {
        SendLaterListenerStatus.markDisconnected(getApplicationContext(), System.currentTimeMillis());
        super.onListenerDisconnected();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (!NotificationParser.VOICE_PACKAGE.equals(pkg) && !NotificationParser.V2S_PACKAGE.equals(pkg)) return;
        final NotificationFields fields;
        try {
            fields = fieldsOf(sbn);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read a notification: " + e.getClass().getSimpleName());
            return;
        }
        if (fields == null || NotificationParser.parse(fields) == null) return;
        final Context app = getApplicationContext();
        WORKER.execute(() -> record(app, fields));
    }

    private static void record(final Context app, NotificationFields fields) {
        try {
            long now = System.currentTimeMillis();
            ListenerEventRecorder recorder = new ListenerEventRecorder(new RepositoryListenerStore(app),
                    (String key, ThreadEvent event) -> SendLaterEngine.onInboundEvent(app, key, event));
            recorder.onNotification(fields, now);
            if (now - lastPurgeMs > PURGE_INTERVAL_MS) purge(app, now);
        } catch (RuntimeException e) {
            Log.w(TAG, "recording failed: " + e.getClass().getSimpleName());
        }
    }

    private static void purge(Context app, long now) {
        try {
            lastPurgeMs = now;
            new ListenerEventRecorder(new RepositoryListenerStore(app), null).purge(now);
        } catch (RuntimeException e) {
            Log.w(TAG, "purge failed: " + e.getClass().getSimpleName());
        }
    }

    /** Copies the fields the parser needs; null when the notification has no extras. */
    static NotificationFields fieldsOf(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return null;
        String channel = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? n.getChannelId() : null;
        String convTitle = str(n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        String title = str(n.extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(n.extras.getCharSequence(Notification.EXTRA_TEXT));

        NotificationFields.Message[] messages = new NotificationFields.Message[0];
        NotificationCompat.MessagingStyle style =
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n);
        if (style != null) {
            if (convTitle == null) convTitle = str(style.getConversationTitle());
            List<NotificationCompat.MessagingStyle.Message> list = style.getMessages();
            messages = new NotificationFields.Message[list.size()];
            for (int i = 0; i < list.size(); i++) {
                NotificationCompat.MessagingStyle.Message m = list.get(i);
                messages[i] = new NotificationFields.Message(str(m.getText()), m.getTimestamp(),
                        senderOf(m.getPerson(), style.getUser()));
            }
        }
        return new NotificationFields(sbn.getPackageName(), sbn.getTag(), channel, n.category, n.when,
                convTitle, title, text, messages);
    }

    /** Null for the device user (no person, or the style's own user); "" for an unnamed other person. */
    private static String senderOf(Person person, Person user) {
        if (person == null) return null;
        if (user != null && samePerson(person, user)) return null;
        String name = str(person.getName());
        return name != null ? name : "";
    }

    private static boolean samePerson(Person a, Person b) {
        if (a.getKey() != null && b.getKey() != null) return a.getKey().equals(b.getKey());
        String an = str(a.getName());
        String bn = str(b.getName());
        return an != null && an.equals(bn);
    }

    private static String str(CharSequence cs) {
        return cs == null ? null : cs.toString();
    }
}
