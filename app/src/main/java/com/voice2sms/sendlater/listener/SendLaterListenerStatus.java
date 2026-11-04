package com.voice2sms.sendlater.listener;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Status of the Send Later notification listener (R32, R37). The settings rows (upcoming-settings
 * node) use {@link #isEnabled}, {@link #SUMMARY} and {@link #openSettingsIntent()}.
 */
public final class SendLaterListenerStatus {

    /** Summary under the listener's settings row. */
    public static final String SUMMARY = "Needs the Google Voice app with notifications on";

    /** Secure setting holding the enabled listener components, colon separated. */
    static final String ENABLED_LISTENERS = "enabled_notification_listeners";

    /** How long after a disconnect the listener still counts as bound (system rebinds quickly). */
    static final long REBIND_GRACE_MS = 10 * 60_000L;

    private static final String PREFS = "sendlater_listener";
    private static final String KEY_CONNECTED_AT = "connected_at";
    private static final String KEY_DISCONNECTED_AT = "disconnected_at";

    private SendLaterListenerStatus() {}

    /** True when the user turned on notification access for this app's listener component. */
    public static boolean isEnabled(Context context) {
        try {
            String flat = Settings.Secure.getString(context.getContentResolver(), ENABLED_LISTENERS);
            if (flat == null || flat.isEmpty()) return false;
            ComponentName mine = new ComponentName(context, SendLaterNotificationListener.class);
            for (String part : flat.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(part);
                if (mine.equals(cn)) return true;
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Enabled and bound recently: the service has connected at least once and is still connected,
     * or disconnected less than {@link #REBIND_GRACE_MS} ago. A process restart keeps the last
     * connect time, since the system rebinds an enabled listener on its own.
     */
    public static boolean isAvailable(Context context) {
        if (!isEnabled(context)) return false;
        SharedPreferences p = prefs(context);
        long connected = p.getLong(KEY_CONNECTED_AT, 0L);
        long disconnected = p.getLong(KEY_DISCONNECTED_AT, 0L);
        return boundRecently(connected, disconnected, System.currentTimeMillis());
    }

    /** Pure rule behind {@link #isAvailable}. */
    static boolean boundRecently(long connectedAt, long disconnectedAt, long nowMs) {
        if (connectedAt <= 0) return false;
        if (disconnectedAt < connectedAt) return true;
        return nowMs - disconnectedAt < REBIND_GRACE_MS;
    }

    /** The system screen where the user turns notification access on. */
    public static Intent openSettingsIntent() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static void markConnected(Context context, long nowMs) {
        prefs(context).edit().putLong(KEY_CONNECTED_AT, nowMs).apply();
    }

    static void markDisconnected(Context context, long nowMs) {
        prefs(context).edit().putLong(KEY_DISCONNECTED_AT, nowMs).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
