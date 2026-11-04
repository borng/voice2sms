package com.voice2sms.sendlater.ui;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlarmManager;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import com.voice2sms.R;
import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.engine.SendLaterScheduler;
import com.voice2sms.sendlater.listener.SendLaterListenerStatus;
import com.voice2sms.sendlater.store.SendLaterRepository;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Wires the "Send Later" category of the Settings screen (R36, R37, AC11). Keys of stored
 * settings come from {@link SendLaterSettings}; the other row keys below are display-only
 * status rows. Every status summary is recomputed in {@link #refresh()}, which the fragment
 * calls from onResume, so a row never shows a stale "allowed".
 *
 * <p>Turning Send Later off cancels every alarm of pending and held messages (records are
 * kept) and broadcasts {@link SendLaterIntents#ACTION_STATE_CHANGED} to this package so the
 * chip and edge tab hide. Turning it on re-registers the alarms.
 */
public final class SendLaterSettingsController {

    private static final String TAG = "SendLaterSettings";

    static final String KEY_UPCOMING_ROW = "sendlater_upcoming";
    static final String KEY_EXACT_ALARM_ROW = "sendlater_exact_alarm";
    static final String KEY_NOTIFICATIONS_ROW = "sendlater_notifications";
    static final String KEY_ACCESSIBILITY_ROW = "sendlater_accessibility";
    static final String KEY_LISTENER_ROW = "sendlater_listener";
    static final String KEY_CONTACTS_ROW = "sendlater_contacts";

    private static final int[] SLOT_DEFAULTS = {
            SendLaterSettings.DEFAULT_SLOT_MORNING, SendLaterSettings.DEFAULT_SLOT_AFTERNOON,
            SendLaterSettings.DEFAULT_SLOT_EVENING, SendLaterSettings.DEFAULT_SLOT_NIGHT};

    private final PreferenceFragmentCompat fragment;
    private final ActivityResultLauncher<String> permissionLauncher;

    /** Call from onCreatePreferences (before the fragment starts: it registers a result launcher). */
    public SendLaterSettingsController(PreferenceFragmentCompat fragment) {
        this.fragment = fragment;
        this.permissionLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> onPermissionResult());
        bind();
    }

    private Context ctx() {
        return fragment.requireContext();
    }

    private void bind() {
        SwitchPreferenceCompat enabled = fragment.findPreference(SendLaterSettings.KEY_ENABLED);
        if (enabled != null) {
            enabled.setOnPreferenceChangeListener((pref, newValue) -> {
                final boolean on = Boolean.TRUE.equals(newValue);
                final Context app = ctx().getApplicationContext();
                // Runs after the switch has persisted its new value, so every receiver of the
                // broadcast reads the new state.
                new Handler(Looper.getMainLooper()).post(() -> applyEnabled(app, on));
                Toast.makeText(ctx(), on ? R.string.sendlater_settings_enabled_toast
                        : R.string.sendlater_settings_disabled_toast, Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        for (int i = 0; i < SendLaterSettings.SLOT_KEYS.length; i++) {
            final String key = SendLaterSettings.SLOT_KEYS[i];
            final int index = i;
            Preference slot = fragment.findPreference(key);
            if (slot == null) continue;
            slot.setOnPreferenceClickListener(p -> {
                pickSlot(key, index);
                return true;
            });
        }

        click(KEY_UPCOMING_ROW, () -> fragment.startActivity(new Intent(ctx(), UpcomingActivity.class)));
        click(KEY_EXACT_ALARM_ROW, this::requestExactAlarm);
        click(KEY_NOTIFICATIONS_ROW, this::requestNotifications);
        click(KEY_ACCESSIBILITY_ROW, () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        click(KEY_LISTENER_ROW, () -> open(SendLaterListenerStatus.openSettingsIntent()));
        click(KEY_CONTACTS_ROW, this::requestContacts);

        refresh();
    }

    private void click(String key, final Runnable action) {
        Preference p = fragment.findPreference(key);
        if (p == null) return;
        p.setOnPreferenceClickListener(x -> {
            action.run();
            return true;
        });
    }

    /** Recomputes every Send Later summary from live state. Called from SettingsFragment.onResume. */
    public void refresh() {
        if (fragment.getContext() == null) return;
        Context c = ctx();

        int[] slots = SendLaterSettings.slotMinutes(c);
        for (int i = 0; i < SendLaterSettings.SLOT_KEYS.length && i < slots.length; i++) {
            Preference slot = fragment.findPreference(SendLaterSettings.SLOT_KEYS[i]);
            if (slot != null) slot.setSummary(formatMinutes(c, slots[i]));
        }

        boolean exact = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            exact = am != null && am.canScheduleExactAlarms();
        }
        summary(KEY_EXACT_ALARM_ROW, exact ? R.string.sendlater_settings_exact_granted
                : R.string.sendlater_settings_exact_denied);

        summary(KEY_NOTIFICATIONS_ROW, notificationsAllowed(c)
                ? R.string.sendlater_settings_notifications_granted
                : R.string.sendlater_settings_notifications_denied);

        Preference a11y = fragment.findPreference(KEY_ACCESSIBILITY_ROW);
        if (a11y != null) {
            a11y.setSummary(c.getString(isAccessibilityServiceEnabled(c)
                    ? R.string.sendlater_settings_accessibility_on
                    : R.string.sendlater_settings_accessibility_off)
                    + "\n" + c.getString(R.string.sendlater_settings_accessibility_why));
        }

        Preference listener = fragment.findPreference(KEY_LISTENER_ROW);
        if (listener != null) {
            int state;
            if (!SendLaterListenerStatus.isEnabled(c)) {
                state = R.string.sendlater_settings_listener_off;
            } else if (SendLaterListenerStatus.isAvailable(c)) {
                state = R.string.sendlater_settings_listener_on;
            } else {
                state = R.string.sendlater_settings_listener_not_bound;
            }
            listener.setSummary(c.getString(state, SendLaterListenerStatus.SUMMARY)
                    + "\n" + c.getString(R.string.sendlater_settings_listener_why));
        }

        summary(KEY_CONTACTS_ROW, granted(c, Manifest.permission.READ_CONTACTS)
                ? R.string.sendlater_settings_contacts_granted
                : R.string.sendlater_settings_contacts_denied);
    }

    private void summary(String key, int resId) {
        Preference p = fragment.findPreference(key);
        if (p != null) p.setSummary(resId);
    }

    // ------------------------------------------------------------------ enable / disable (AC11)

    /** Off: cancel every alarm, keep records. On: re-register. Then tell the chip and tab. */
    static void applyEnabled(final Context app, final boolean on) {
        new Thread(() -> {
            try {
                SendLaterScheduler scheduler = SendLaterScheduler.get(app);
                if (on) {
                    scheduler.reregisterAll(app);
                } else {
                    SendLaterRepository repo = SendLaterRepository.get(app);
                    List<ScheduledMessage> live = new ArrayList<>();
                    List<ScheduledMessage> pending = repo.allPending();
                    if (pending != null) live.addAll(pending);
                    List<ScheduledMessage> held = repo.allHeld();
                    if (held != null) live.addAll(held);
                    for (ScheduledMessage m : live) scheduler.cancelAll(m.getId());
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "alarm update failed: " + e.getClass().getSimpleName());
            }
            Intent changed = new Intent(SendLaterIntents.ACTION_STATE_CHANGED)
                    .setPackage(app.getPackageName());
            app.sendBroadcast(changed);
        }, "sendlater-settings").start();
    }

    // ------------------------------------------------------------------ slot pickers

    private void pickSlot(final String key, int index) {
        final Context c = ctx();
        int[] slots = SendLaterSettings.slotMinutes(c);
        int current = index < slots.length ? slots[index] : SLOT_DEFAULTS[index];
        new TimePickerDialog(c, (view, hour, minute) -> {
            PreferenceManager.getDefaultSharedPreferences(c).edit()
                    .putInt(key, hour * 60 + minute).apply();
            refresh();
        }, current / 60, current % 60, DateFormat.is24HourFormat(c)).show();
    }

    static String formatMinutes(Context c, int minutesOfDay) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, minutesOfDay / 60);
        cal.set(Calendar.MINUTE, minutesOfDay % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return DateFormat.getTimeFormat(c).format(cal.getTime());
    }

    // ------------------------------------------------------------------ permission rows

    private void requestExactAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + ctx().getPackageName()));
            if (!open(i)) openAppDetails();
        }
    }

    private void requestNotifications() {
        Context c = ctx();
        if (Build.VERSION.SDK_INT >= 33
                && !granted(c, Manifest.permission.POST_NOTIFICATIONS)
                && !askedAndBlocked(Manifest.permission.POST_NOTIFICATIONS)) {
            markAsked(Manifest.permission.POST_NOTIFICATIONS);
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
            if (open(i)) return;
        }
        openAppDetails();
    }

    private void requestContacts() {
        Context c = ctx();
        if (granted(c, Manifest.permission.READ_CONTACTS)) {
            openAppDetails();
        } else if (askedAndBlocked(Manifest.permission.READ_CONTACTS)) {
            openAppDetails();
        } else {
            markAsked(Manifest.permission.READ_CONTACTS);
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS);
        }
    }

    private static final String ASKED_PREFS = "sendlater_settings_asked";

    /**
     * True when the permission was requested before and the system will no longer show its
     * dialog (denied twice, or "don't ask again"); the row then opens App info instead.
     */
    private boolean askedAndBlocked(String permission) {
        boolean asked = ctx().getSharedPreferences(ASKED_PREFS, Context.MODE_PRIVATE)
                .getBoolean(permission, false);
        return asked && !fragment.shouldShowRequestPermissionRationale(permission);
    }

    private void markAsked(String permission) {
        ctx().getSharedPreferences(ASKED_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(permission, true).apply();
    }

    private void onPermissionResult() {
        if (fragment.getContext() == null) return;
        refresh();
    }

    static boolean granted(Context c, String permission) {
        return ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean notificationsAllowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && !granted(c, Manifest.permission.POST_NOTIFICATIONS)) {
            return false;
        }
        return NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    static boolean isAccessibilityServiceEnabled(Context c) {
        AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> enabled =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String targetId = c.getPackageName() + "/.GeminiSmsInterceptService";
        String fullId = c.getPackageName() + "/com.voice2sms.GeminiSmsInterceptService";
        if (enabled == null) return false;
        for (AccessibilityServiceInfo info : enabled) {
            String id = info.getId();
            if (targetId.equals(id) || fullId.equals(id)) return true;
        }
        return false;
    }

    private boolean open(Intent i) {
        try {
            fragment.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private void openAppDetails() {
        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + ctx().getPackageName())));
    }
}
