package com.voice2sms.sendlater.engine;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.service.notification.StatusBarNotification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.voice2sms.GVoiceWebViewActivity;
import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessBudget;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;

/**
 * Send Later notifications (R24, R28, R29, R31). Own channels, created lazily here (like
 * AutoSendNotifier.ensureChannel), never in Application. Every state of one message uses the same
 * (tag, id) slot, so a newer state replaces the older one. All PendingIntents are immutable.
 * Reminders open GVoiceWebViewActivity in review mode (recipient + body; never auto-send).
 */
public final class SendLaterNotifications {

    static final String CHANNEL_LAST_CALL = "sendlater_last_call";
    static final String CHANNEL_HOLD = "sendlater_hold";
    static final String CHANNEL_INFO = "sendlater_info";
    static final String TAG = "sendlater";
    /** Extra naming the state a posted notification shows ("hold:REPLIED", "lastCall", ...). */
    static final String EXTRA_KIND = "com.voice2sms.sendlater.notification_kind";

    private SendLaterNotifications() {}

    // ================================================================== kinds

    /** R24: "Sending to X in 5 min", chronometer countdown to T; Cancel / Edit / Send now. */
    public static void lastCall(Context c, ScheduledMessage m) {
        NotificationCompat.Builder b = base(c, CHANNEL_LAST_CALL, m)
                .setContentTitle("Sending to " + who(m) + " in 5 min")
                .setContentText(preview(m))
                .setWhen(m.getScheduledFor())
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(Math.max(60_000L, m.getScheduledFor() - System.currentTimeMillis() + 60_000L))
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(0, "Cancel", broadcast(c, SendLaterIntents.ACTION_CANCEL, m.getId()));
        PendingIntent edit = editActivity(c, m.getId());
        if (edit != null) b.addAction(0, "Edit", edit).setContentIntent(edit);
        b.addAction(0, "Send now", broadcast(c, SendLaterIntents.ACTION_SEND_NOW, m.getId()));
        post(c, m.getId(), b.build(), "lastCall");
    }

    /**
     * R28: held. Buttons Send anyway (label shows lateness past budget) / Snooze / Cancel; Edit is
     * the tap on the notification (Android shows at most three action buttons) when an editor
     * exists, else the tap opens review mode.
     */
    public static void hold(Context c, ScheduledMessage m, HoldReason reason, long nowMs) {
        // OD29: the listener keeps no reply text (R34), so no reply preview is known here; the
        // body shows the user's own text labelled as theirs, never as if it were the reply.
        String body = holdBody(m, null, nowMs);
        NotificationCompat.Builder b = base(c, CHANNEL_HOLD, m)
                .setContentTitle(holdTitle(m, reason))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .addAction(0, sendAnywayLabel(m, nowMs), broadcast(c, SendLaterIntents.ACTION_SEND_ANYWAY, m.getId()))
                .addAction(0, "Snooze 1 h", broadcast(c, SendLaterIntents.ACTION_SNOOZE, m.getId()))
                .addAction(0, "Cancel", broadcast(c, SendLaterIntents.ACTION_CANCEL, m.getId()));
        PendingIntent edit = editActivity(c, m.getId());
        b.setContentIntent(edit != null ? edit : reviewMode(c, m, true));
        post(c, m.getId(), b.build(), "hold:" + reason);
    }

    /**
     * R29: tap opens review mode with the message pre-filled (no auto-send). R27: a missing GV
     * session adds a Sign-in action that opens Google Voice without the message.
     * With a {@code cause} (OD23(3): a send that typed nothing) the title follows the
     * sender's cause ({@link #cannotSendTitle}). Only a missing session gets Sign in (R27).
     */
    public static void remind(Context c, ScheduledMessage m, RemindReason reason, RemindCause cause) {
        String title = cause != null ? cannotSendTitle(m, cause) : remindTitle(m, reason);
        boolean signIn = cause != null ? hasSignInAction(cause) : hasSignInAction(reason);
        String kind = "remind:" + (cause != null ? cause : reason);
        NotificationCompat.Builder b = base(c, CHANNEL_HOLD, m)
                .setContentTitle(title)
                .setContentText("Tap to review and send: " + preview(m))
                .setStyle(new NotificationCompat.BigTextStyle().bigText("Tap to review and send: " + preview(m)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setContentIntent(reviewMode(c, m, true));
        if (signIn) b.addAction(0, "Sign in", signIn(c, m.getId()));
        post(c, m.getId(), b.build(), kind);
    }

    /**
     * R29: tap opens review mode with the message pre-filled (no auto-send). R27: a missing GV
     * session adds a Sign-in action that opens Google Voice without the message.
     */
    public static void remind(Context c, ScheduledMessage m, RemindReason reason) {
        remind(c, m, reason, null);
    }

    /** OD23(3): a send that typed nothing, worded by the sender's cause. */
    public static void remindCannotSend(Context c, ScheduledMessage m, RemindCause cause) {
        RemindCause k = cause != null ? cause : RemindCause.LOCKED;
        remind(c, m, k.reason(), k);
    }

    /** R31: the send failed and was not retried; tap opens review mode to send it by hand. */
    public static void sendFailed(Context c, ScheduledMessage m) {
        NotificationCompat.Builder b = base(c, CHANNEL_HOLD, m)
                .setContentTitle(sendFailedTitle(m))
                .setContentText("Tap to review and send: " + preview(m))
                .setStyle(new NotificationCompat.BigTextStyle().bigText("Tap to review and send: " + preview(m)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(reviewMode(c, m, true));
        post(c, m.getId(), b.build(), "sendFailed");
    }

    public static void sent(Context c, ScheduledMessage m) {
        info(c, m, "Sent to " + who(m), preview(m), null, "sent");
    }

    /** R31: an inbound arrived between the thread check and the send. */
    public static void repliedDuringSend(Context c, ScheduledMessage m) {
        info(c, m, who(m) + " replied just as your message sent", preview(m), reviewMode(c, m, false),
                "repliedDuringSend");
    }

    public static void selfPreempted(Context c, ScheduledMessage m) {
        info(c, m, "Not sent: you already messaged " + who(m), preview(m), null, "selfPreempted");
    }

    /** R29: also posted when no reminder came first (UP_TO_24H). */
    public static void expired(Context c, ScheduledMessage m) {
        info(c, m, "Scheduled message to " + who(m) + " expired",
                "Not sent. Tap to review: " + preview(m), reviewMode(c, m, true), "expired");
    }

    /** I5: an interrupted send could not be verified. Never retried automatically. */
    public static void mayHaveSent(Context c, ScheduledMessage m) {
        NotificationCompat.Builder b = base(c, CHANNEL_HOLD, m)
                .setContentTitle("Message to " + who(m) + " may have already sent")
                .setContentText("Check the conversation before sending again: " + preview(m))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Check the conversation before sending again: " + preview(m)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(reviewMode(c, m, false))
                .addAction(0, sendAnywayLabel(m, System.currentTimeMillis(), true),
                        broadcast(c, SendLaterIntents.ACTION_SEND_ANYWAY, m.getId()))
                .addAction(0, "Cancel", broadcast(c, SendLaterIntents.ACTION_CANCEL, m.getId()));
        post(c, m.getId(), b.build(), "mayHaveSent");
    }

    public static void dismiss(Context c, long id) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(TAG, slot(id));
    }

    // ================================================================== pure labels (JVM-tested)

    /** "Send anyway" within the lateness budget, "Send anyway (3 h late)" past it (R28). */
    public static String sendAnywayLabel(ScheduledMessage m, long nowMs) {
        return sendAnywayLabel(m, nowMs, false);
    }

    /**
     * As {@link #sendAnywayLabel(ScheduledMessage, long)}; after "may have already sent" (I5) the
     * button reads "Send anyway (may be a duplicate)" instead (OD21), lateness or not.
     */
    public static String sendAnywayLabel(ScheduledMessage m, long nowMs, boolean mayHaveSent) {
        if (mayHaveSent) return "Send anyway (may be a duplicate)";
        if (nowMs <= LatenessBudget.deadlineMs(m)) return "Send anyway";
        return "Send anyway (" + lateness(nowMs - m.getScheduledFor()) + " late)";
    }

    /**
     * Whether the slot must be cleared before posting {@code newKind}: hold and remind use
     * setOnlyAlertOnce so a re-post of the same state (a re-arm after TIME_SET) stays silent, but
     * that flag also silences any update of a showing notification, so a different state (a hold
     * replacing the last call) is posted fresh to alert.
     */
    static boolean needsFreshPost(String activeKind, String newKind) {
        return activeKind != null && !activeKind.equals(newKind);
    }

    /** "3 h", "45 min". */
    static String lateness(long lateMs) {
        long hours = lateMs / LatenessBudget.HOUR_MS;
        if (hours >= 1) return hours + " h";
        return Math.max(1, lateMs / LatenessBudget.MINUTE_MS) + " min";
    }

    static String lateSuffix(ScheduledMessage m, long nowMs) {
        long late = nowMs - m.getScheduledFor();
        return late >= LatenessBudget.MINUTE_MS ? " (" + lateness(late) + " late)" : "";
    }

    /**
     * OD29 / R28: the hold body. The reply's own preview when it is known ({@code replyPreview}),
     * else the user's scheduled text labelled "Your message:", so it never reads as the reply.
     * Lateness is appended either way.
     */
    static String holdBody(ScheduledMessage m, String replyPreview, long nowMs) {
        String lead = replyPreview != null && !replyPreview.trim().isEmpty()
                ? clip(replyPreview.trim())
                : "Your message: " + preview(m);
        return lead + lateSuffix(m, nowMs);
    }

    static String holdTitle(ScheduledMessage m, HoldReason reason) {
        if (reason == null) return "Held: message to " + who(m);
        switch (reason) {
            case REPLIED: return who(m) + " texted since you scheduled this";
            case REACHED_OUT: return who(m) + " called since you scheduled this";
            case OUTBOUND_ONLY: return "You messaged " + who(m) + " since you scheduled this";
            case UNVERIFIED: return "Couldn't check your conversation with " + who(m);
            case LOCKED:
            default: return "Held: message to " + who(m);
        }
    }

    static String remindTitle(ScheduledMessage m, RemindReason reason) {
        if (reason == null) return "Send your message to " + who(m);
        switch (reason) {
            case LATE: return "Too late to send automatically to " + who(m);
            case NO_SESSION: return "Sign in to Google Voice in voice2sms to send to " + who(m);
            case NO_NETWORK: return "No connection: message to " + who(m) + " not sent";
            case LOCKED: return "Unlock to send your message to " + who(m);
            case NO_SENDER:
            default: return "Send your message to " + who(m);
        }
    }

    /** OD23(3): reminder title for a send that typed nothing, by the sender's cause. */
    static String cannotSendTitle(ScheduledMessage m, RemindCause cause) {
        if (cause == null) return remindTitle(m, RemindReason.LOCKED);
        switch (cause) {
            case NO_SESSION: return remindTitle(m, RemindReason.NO_SESSION);
            case DAILY_CAP: return "Daily scheduled-send limit reached: tap to send to " + who(m);
            case BUSY: return "You were using voice2sms; tap to send to " + who(m) + " now";
            case COULD_NOT_SEND: return "Couldn't send automatically to " + who(m);
            case LATE: return remindTitle(m, RemindReason.LATE);
            case LOCKED:
            default: return remindTitle(m, RemindReason.LOCKED);
        }
    }

    /** R27: only a missing GV session gets the Sign-in action. */
    static boolean hasSignInAction(RemindCause cause) {
        return cause == RemindCause.NO_SESSION;
    }

    /** R27: only a missing or expired GV session gets the Sign-in action. */
    static boolean hasSignInAction(RemindReason reason) {
        return reason == RemindReason.NO_SESSION;
    }

    static String sendFailedTitle(ScheduledMessage m) {
        return "Couldn't send your message to " + who(m);
    }

    static String who(ScheduledMessage m) {
        String n = m.getRecipientDisplayName();
        if (n != null && !n.trim().isEmpty()) return n.trim();
        return m.getRecipientE164() != null ? m.getRecipientE164() : "your contact";
    }

    private static String preview(ScheduledMessage m) {
        return clip(m.getText());
    }

    private static String clip(String t) {
        if (t == null) return "";
        return t.length() <= 120 ? t : t.substring(0, 119) + "…";
    }

    // ================================================================== plumbing

    private static void info(Context c, ScheduledMessage m, String title, String content, PendingIntent tap,
                             String kind) {
        NotificationCompat.Builder b = base(c, CHANNEL_INFO, m)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (tap != null) b.setContentIntent(tap);
        post(c, m.getId(), b.build(), "info:" + kind);
    }

    private static NotificationCompat.Builder base(Context c, String channel, ScheduledMessage m) {
        return new NotificationCompat.Builder(c, channel)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true);
    }

    private static void post(Context c, long id, Notification n, String kind) {
        if (c == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannels(nm);
        if (n.extras != null) n.extras.putString(EXTRA_KIND, kind);
        if (needsFreshPost(activeKind(nm, id), kind)) nm.cancel(TAG, slot(id));
        nm.notify(TAG, slot(id), n);
    }

    /** The kind of the notification showing in {@code id}'s slot, or null (none, or unreadable). */
    private static String activeKind(NotificationManager nm, long id) {
        try {
            StatusBarNotification[] active = nm.getActiveNotifications();
            if (active == null) return null;
            int slot = slot(id);
            for (StatusBarNotification sbn : active) {
                if (sbn == null || sbn.getId() != slot || !TAG.equals(sbn.getTag())) continue;
                Notification shown = sbn.getNotification();
                return shown != null && shown.extras != null ? shown.extras.getString(EXTRA_KIND) : null;
            }
        } catch (RuntimeException e) {
            // Unreadable: post as an update (may stay silent under setOnlyAlertOnce).
        }
        return null;
    }

    private static int slot(long id) {
        return (int) (id ^ (id >>> 32));
    }

    /** Lazily creates the three channels (no-op pre-O, cheap once they exist). */
    static void ensureChannels(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (nm.getNotificationChannel(CHANNEL_LAST_CALL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_LAST_CALL, "Send Later: last call",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Five minutes before a scheduled message sends.");
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_HOLD) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_HOLD, "Send Later: needs you",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("A scheduled message was held or needs you to send it.");
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_INFO) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_INFO, "Send Later: results",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Sent, not sent and expired scheduled messages.");
            nm.createNotificationChannel(ch);
        }
    }

    private static PendingIntent broadcast(Context c, String action, long id) {
        Intent i = new Intent(action)
                .setClass(c, SendLaterActionReceiver.class)
                .setPackage(c.getPackageName())
                .setData(Uri.parse("voice2sms-sendlater://action/" + id))
                .putExtra(SendLaterIntents.EXTRA_MESSAGE_ID, id);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** ACTION_EDIT for the sheet, or null when no activity in this package handles it yet. */
    static PendingIntent editActivity(Context c, long id) {
        Intent i = editIntent(c, id);
        if (i == null) return null;
        return PendingIntent.getActivity(c, slot(id), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Distinct per id through the request code (the sheet's filter may not accept a data URI). */
    static Intent editIntent(Context c, long id) {
        Intent i = new Intent(SendLaterIntents.ACTION_EDIT)
                .setPackage(c.getPackageName())
                .putExtra(SendLaterIntents.EXTRA_MESSAGE_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (i.resolveActivity(c.getPackageManager()) == null) return null;
        } catch (RuntimeException e) {
            return null;
        }
        return i;
    }

    /** GVoiceWebViewActivity with no recipient or body: Google Voice's own sign-in (R27). */
    private static PendingIntent signIn(Context c, long id) {
        Intent i = new Intent(c, GVoiceWebViewActivity.class)
                .setData(Uri.parse("voice2sms-sendlater://signin/" + id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** GVoiceWebViewActivity in review mode: recipient (+ body when {@code withBody}). */
    private static PendingIntent reviewMode(Context c, ScheduledMessage m, boolean withBody) {
        Intent i = new Intent(c, GVoiceWebViewActivity.class)
                .setData(Uri.parse("voice2sms-sendlater://review/" + m.getId() + (withBody ? "/body" : "")))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (m.getRecipientE164() != null) i.putExtra("recipient", m.getRecipientE164());
        if (withBody && m.getText() != null) i.putExtra("body", m.getText());
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
