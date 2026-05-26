package com.voice2sms;

import android.Manifest;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real SMS_DELIVER handler.
 *
 * As the default SMS app, Voice2SMS is the EXCLUSIVE recipient of incoming SMS
 * via the platform's ordered broadcast (Telephony.Sms.Intents.SMS_DELIVER_ACTION).
 * The Telephony provider does NOT auto-insert into content://sms — that is the
 * default app's responsibility. Without this code the message is permanently
 * lost: the carrier has already been ACK'd by the modem, the platform's internal
 * raw-table row is cleared after the broadcast completes, and content://sms/inbox
 * stays empty. Google Messages reads only from content://sms when restored as
 * default — there is no backfill, no Google-side queue.
 *
 * What we do per broadcast:
 *   1. Reassemble the parts of one multipart SMS into a single body. Each
 *      SMS_DELIVER broadcast carries the N parts of ONE message from ONE sender;
 *      no cross-message grouping is needed.
 *   2. Resolve a thread id via Telephony.Threads.getOrCreateThreadId so the row
 *      threads with prior history.
 *   3. Insert one row into Telephony.Sms.Inbox.CONTENT_URI.
 *   4. Post a NotificationCompat notification with BigTextStyle so the user sees
 *      the body inline — useful for 2FA OTP copy. No content intent in v1 (we
 *      have no incoming-thread UI yet; a tap would just dismiss).
 *
 * RCS note: When Voice2SMS is default, Jibe deregisters Google Messages from RCS.
 * RCS senders' clients fall back to SMS, which arrives here like any other SMS.
 * No separate RCS path to handle on the receive side.
 *
 * MMS gap: {@link MmsReceiver} is still a no-op. Picture / group / long-SMS-as-MMS
 * messages will be lost while Voice2SMS is default. Out of scope for v1.
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "Voice2SMS";
    static final String NOTIF_CHANNEL_ID = "incoming_sms";

    // Unique-per-message ids prevent 2FA OTPs from the same shortcode in quick
    // succession from collapsing into one notification (hiding the older code).
    // Range starts well clear of WearSmsListenerService's reserved 42000-43023 slots.
    private static final AtomicInteger NOTIF_ID = new AtomicInteger(50000);

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) {
            return;
        }

        final SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0 || parts[0] == null) {
            Log.w(TAG, "SMS_DELIVER with no PDUs");
            return;
        }

        // goAsync extends receiver lifetime past onReceive return so the
        // ContentResolver insert + notification can run off the main thread.
        // Budget is ~10s; our work is sub-second.
        final PendingResult pending = goAsync();
        final Context appCtx = context.getApplicationContext();

        new Thread(() -> {
            try {
                String address = parts[0].getDisplayOriginatingAddress();
                long timestampSent = parts[0].getTimestampMillis();
                StringBuilder body = new StringBuilder();
                for (SmsMessage part : parts) {
                    String b = part.getDisplayMessageBody();
                    if (b != null) body.append(b);
                }
                String bodyStr = body.toString();
                long now = System.currentTimeMillis();

                persistToInbox(appCtx, address, bodyStr, now, timestampSent, parts[0]);
                postNotification(appCtx, address, bodyStr);
            } catch (Throwable t) {
                Log.e(TAG, "SMS_DELIVER handling failed", t);
            } finally {
                pending.finish();
            }
        }, "sms-deliver").start();
    }

    private void persistToInbox(
            Context ctx, String address, String body,
            long dateReceived, long dateSent, SmsMessage first) {
        ContentResolver cr = ctx.getContentResolver();
        long threadId = 0;
        try {
            if (address != null) {
                threadId = Telephony.Threads.getOrCreateThreadId(ctx, address);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "getOrCreateThreadId failed for " + address, e);
        }

        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.ADDRESS, address);
        v.put(Telephony.Sms.BODY, body);
        v.put(Telephony.Sms.DATE, dateReceived);
        v.put(Telephony.Sms.DATE_SENT, dateSent);
        v.put(Telephony.Sms.READ, 0);
        v.put(Telephony.Sms.SEEN, 0);
        v.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);
        if (threadId > 0) v.put(Telephony.Sms.THREAD_ID, threadId);
        try {
            v.put(Telephony.Sms.PROTOCOL, first.getProtocolIdentifier());
            String sc = first.getServiceCenterAddress();
            if (sc != null) v.put(Telephony.Sms.SERVICE_CENTER, sc);
        } catch (RuntimeException ignore) {
            // PROTOCOL / SERVICE_CENTER are nice-to-have; never block the insert on them.
        }

        try {
            Uri inserted = cr.insert(Telephony.Sms.Inbox.CONTENT_URI, v);
            if (inserted == null) {
                // null = provider rejected the insert. Almost always means Voice2SMS
                // is not the current default SMS app (the provider gates writes on
                // default-package match). The message will not survive a switch back.
                Log.e(TAG, "Inbox insert returned null — message NOT persisted (default-app?)");
            } else if (BuildConfig.DEBUG) {
                Log.d(TAG, "Inbox insert ok: " + inserted);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Inbox insert threw", e);
        }
    }

    private void postNotification(Context ctx, String address, String body) {
        // POST_NOTIFICATIONS is a runtime permission on Android 13+. We declare it
        // in the manifest; if the user hasn't granted it we silently skip — the
        // inbox row still got persisted so nothing is lost, just no live alert.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String title = (address != null && !address.isEmpty()) ? address : "Unknown sender";
        String text = body != null ? body : "";

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(NOTIF_ID.getAndIncrement(), b.build());
    }
}
