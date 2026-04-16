package com.voice2sms;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

/**
 * Posts a "Sent via Google Voice" notification on any auto-send path (Gemini,
 * RespondViaMessage, etc.) except watch-originated sends, which use the BAL-
 * fallback notification in WearSmsListenerService instead.
 */
public final class AutoSendNotifier {

    static final String CHANNEL_ID = "voice2sms_autosend";
    private static final int NOTIF_ID_BASE = 43000;
    private static final int NOTIF_SLOT_MASK = 0xFF;

    private AutoSendNotifier() {}

    public static void show(Context context, String phone, String body) {
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("show_autosend_notification", true)) return;

        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        ensureChannel(nm);

        String preview = truncate(
                (phone != null ? phone : "") + " \u2014 " + (body != null ? body : ""), 80);
        int id = NOTIF_ID_BASE + (stableId(phone, body) & NOTIF_SLOT_MASK);

        Notification notif = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle("Sent via Google Voice")
                .setContentText(preview)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build();

        nm.notify(id, notif);
    }

    static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Auto-send confirmations",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shows when Voice2SMS auto-sends a message.");
        nm.createNotificationChannel(ch);
    }

    private static int stableId(String phone, String body) {
        int h = 17;
        if (phone != null) h = 31 * h + phone.hashCode();
        if (body != null) h = 31 * h + body.hashCode();
        return h;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "\u2026";
    }
}
