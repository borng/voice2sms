package com.voice2sms.sendlater.listener;

import android.Manifest;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Build;
import android.provider.ContactsContract;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;

import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.engine.SendLaterEngine;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Reply detection (R32-R34, D14, D22): watches Google Voice notifications (primary) and voice2sms
 * incoming-SMS notifications (secondary, only while voice2sms is the default SMS app), records an
 * inbound event for conversations with a live Send Later message, and fires the early hold.
 *
 * <p>R40: an in-memory {@link LiveConversationKeys} gate (E.164 and name keys of SCHEDULED, HELD
 * and IN_FLIGHT rows) is built on the worker when the listener connects and rebuilt on
 * ACTION_STATE_CHANGED / ACTION_SCHEDULED, through a RECEIVER_NOT_EXPORTED receiver that lives
 * from connect to disconnect. A Voice notification no live key matches makes no database call.
 * LIVE-F12: the gate and the recorder also match a contact's name to a number-only message through
 * {@link AndroidContactNames} (READ_CONTACTS only if granted), on the worker thread only.
 * D37: a contacts observer (registered from connect to disconnect once READ_CONTACTS is granted)
 * rebuilds the gate, debounced on the worker; see {@link ContactsChangeWatcher}. A permission grant
 * is picked up at the next connect or state broadcast (there is no grant broadcast).
 *
 * <p>Notification content never reaches a log (I7). Room work runs on one background thread.
 * Voice suppresses notifications for the thread open in native Voice; the on-screen reader covers
 * that case.
 */
public final class SendLaterNotificationListener extends NotificationListenerService {

    private static final String TAG = "SendLaterListener";

    /** Purge at most this often while notifications arrive. */
    private static final long PURGE_INTERVAL_MS = 60 * 60_000L;

    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sendlater-listener");
        t.setDaemon(true);
        return t;
    });

    private static volatile long lastPurgeMs;

    private boolean receiverRegistered;

    /** D37: contacts observer + debounced gate rebuild; created on first connect. */
    private ContactsChangeWatcher contactsWatcher;

    private final ContentObserver contactsObserver = new ContentObserver(null) {
        @Override public void onChange(boolean selfChange) {
            ContactsChangeWatcher w = contactsWatcher;
            if (w != null) w.onContactsChanged();
        }
    };

    /** Rebuilds the live-key gate whenever a Send Later message is scheduled or changes state. */
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (!SendLaterIntents.ACTION_STATE_CHANGED.equals(action)
                    && !SendLaterIntents.ACTION_SCHEDULED.equals(action)) return;
            // D37: READ_CONTACTS may have been granted since connect.
            ContactsChangeWatcher w = contactsWatcher;
            if (w != null) w.ensureRegistered();
            scheduleRebuild(context.getApplicationContext(), false);
        }
    };

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        final Context app = getApplicationContext();
        SendLaterListenerStatus.markConnected(app, System.currentTimeMillis());
        registerStateReceiver();
        contactsWatcher().start();
        // Queued before any notification of this connection, so recording sees the fresh gate.
        scheduleRebuild(app, true);
    }

    @Override public void onListenerDisconnected() {
        unregisterStateReceiver();
        stopContactsWatcher();
        // No broadcasts reach us while disconnected: forget the gate so a reconnect rebuilds it.
        LiveConversationKeys.invalidate();
        SendLaterListenerStatus.markDisconnected(getApplicationContext(), System.currentTimeMillis());
        super.onListenerDisconnected();
    }

    @Override public void onDestroy() {
        unregisterStateReceiver();
        stopContactsWatcher();
        super.onDestroy();
    }

    private ContactsChangeWatcher contactsWatcher() {
        if (contactsWatcher == null) {
            final Context app = getApplicationContext();
            contactsWatcher = new ContactsChangeWatcher(new ContactsChangeWatcher.Host() {
                @Override public boolean readContactsGranted() {
                    return ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
                            == PackageManager.PERMISSION_GRANTED;
                }

                @Override public boolean register() {
                    try {
                        app.getContentResolver().registerContentObserver(
                                ContactsContract.Contacts.CONTENT_URI, true, contactsObserver);
                        return true;
                    } catch (RuntimeException e) {
                        Log.w(TAG, "contacts observer registration failed: " + e.getClass().getSimpleName());
                        return false;
                    }
                }

                @Override public void unregister() {
                    app.getContentResolver().unregisterContentObserver(contactsObserver);
                }

                @Override public void schedule(Runnable task, long delayMs) {
                    WORKER.schedule(task, delayMs, TimeUnit.MILLISECONDS);
                }

                @Override public void rebuildGate() {
                    SendLaterNotificationListener.rebuildGate(app, false);
                }
            });
        }
        return contactsWatcher;
    }

    private void stopContactsWatcher() {
        ContactsChangeWatcher w = contactsWatcher;
        if (w != null) w.stop();
    }

    private void registerStateReceiver() {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(SendLaterIntents.ACTION_STATE_CHANGED);
        f.addAction(SendLaterIntents.ACTION_SCHEDULED);
        try {
            ContextCompat.registerReceiver(this, stateReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "receiver registration failed: " + e.getClass().getSimpleName());
        }
    }

    private void unregisterStateReceiver() {
        if (!receiverRegistered) return;
        receiverRegistered = false;
        try {
            unregisterReceiver(stateReceiver);
        } catch (RuntimeException e) {
            Log.w(TAG, "receiver unregistration failed: " + e.getClass().getSimpleName());
        }
    }

    private static void scheduleRebuild(final Context app, final boolean forcePurge) {
        WORKER.execute(() -> rebuildGate(app, forcePurge));
    }

    private static void rebuildGate(Context app, boolean forcePurge) {
        try {
            LiveConversationKeys gate = LiveConversationKeys.rebuild(new RepositoryListenerStore(app),
                    new AndroidContactNames(app));
            // R34: misses no longer reach the purge, so state changes drive it. Nothing live left
            // means every stored event is an orphan: purge now rather than at the hourly mark.
            long now = System.currentTimeMillis();
            if (forcePurge || now - lastPurgeMs > PURGE_INTERVAL_MS || (gate != null && gate.isEmpty())) {
                purge(app, now);
            }
        } catch (RuntimeException e) {
            // Unknown gate: recording falls back to the store rather than dropping replies.
            LiveConversationKeys.invalidate();
            Log.w(TAG, "gate rebuild failed: " + e.getClass().getSimpleName());
        }
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
            LiveConversationKeys gate = LiveConversationKeys.current();
            if (gate != null && !gate.mayMatch(NotificationParser.parse(fields))) {
                // R40: no live conversation can match; no store, no purge.
                return;
            }
            ListenerEventRecorder recorder = new ListenerEventRecorder(new RepositoryListenerStore(app),
                    (String key, ThreadEvent event) -> SendLaterEngine.onInboundEvent(app, key, event), gate,
                    new AndroidContactNames(app));
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
