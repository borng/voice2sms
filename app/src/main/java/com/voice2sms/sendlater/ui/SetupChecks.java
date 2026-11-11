package com.voice2sms.sendlater.ui;

import android.accounts.AccountManager;
import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.preference.PreferenceManager;

import com.voice2sms.DefaultSms;
import com.voice2sms.R;
import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.a11y.A11yWarning;
import com.voice2sms.sendlater.battery.BatteryHealth;
import com.voice2sms.sendlater.battery.BatteryIndicator;
import com.voice2sms.sendlater.listener.SendLaterListenerStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one list of things voice2sms needs set up (D59, OD44), used by the launcher page's group
 * rows and by the rows inside each group (OD45).
 *
 * <p>{@link #missing} and {@link #rowKey} are pure: they read only the {@link Probes} passed in, so
 * the "which items are missing for which group" logic runs on the JVM with fake probes. On the
 * phone, {@link #live} answers each probe with the check the app already uses for that item.
 *
 * <p>Membership follows where each item's row lives in preferences.xml: the default texting app
 * in "Default SMS app", the Google account in "Google account", accessibility in "Send Later" and
 * in "Gemini", the other items in "Send Later". An item for a feature that is off is not flagged:
 * Send Later's items only count while Send Later is on (accessibility still counts for Gemini),
 * and the full-screen-intent permission only exists on Android 14+ (API 34).
 */
public final class SetupChecks {

    /** Home group row keys (preferences.xml). */
    public static final String GROUP_SEND_LATER = "group_send_later";
    public static final String GROUP_DEFAULT_SMS = "group_default_sms";
    public static final String GROUP_ACCOUNT = "group_google_account";
    public static final String GROUP_GEMINI = "group_gemini";

    /** Send Later row for the full-screen-intent permission (API 34+). */
    public static final String KEY_FULL_SCREEN_ROW = "sendlater_full_screen";

    /** A required setup item, with the plain word shown in "Needs: ...". */
    public enum Item {
        DEFAULT_SMS(R.string.sendlater_setup_item_default_sms),
        ACCESSIBILITY(R.string.sendlater_setup_item_accessibility),
        NOTIFICATION_LISTENER(R.string.sendlater_setup_item_listener),
        POST_NOTIFICATIONS(R.string.sendlater_setup_item_notifications),
        EXACT_ALARMS(R.string.sendlater_setup_item_exact_alarms),
        BATTERY(R.string.sendlater_setup_item_battery),
        FULL_SCREEN_INTENT(R.string.sendlater_setup_item_full_screen),
        GOOGLE_ACCOUNT(R.string.sendlater_setup_item_account);

        /** Plain label (lower case, joined into "Needs: a, b"). */
        public final int labelRes;

        Item(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    /** What {@link #missing} reads. {@link #live} answers from the phone; tests pass fakes. */
    public interface Probes {
        /** SendLaterSettings.isEnabled. */
        boolean sendLaterEnabled();

        /** Build.VERSION.SDK_INT. */
        int sdkInt();

        /** The existing check for that item says it is set up. */
        boolean isOk(Item item);
    }

    private SetupChecks() {}

    // ------------------------------------------------------------------ pure logic

    /**
     * The items that belong to {@code groupKey}, apply now and are not set up, in {@link Item}
     * order, without duplicates. Never null; empty for an unknown group.
     */
    public static List<Item> missing(Probes probes, String groupKey) {
        if (probes == null || groupKey == null) return Collections.emptyList();
        List<Item> out = new ArrayList<>();
        for (Item item : Item.values()) {
            if (rowKey(item, groupKey) == null) continue;   // not in this group
            if (!applies(probes, item, groupKey)) continue;
            if (!probes.isOk(item)) out.add(item);
        }
        return out;
    }

    /** True when {@code item} counts for {@code groupKey} right now (feature on, API level). */
    static boolean applies(Probes probes, Item item, String groupKey) {
        if (item == Item.FULL_SCREEN_INTENT && probes.sdkInt() < 34) return false;
        if (GROUP_SEND_LATER.equals(groupKey)) return probes.sendLaterEnabled();
        return true;
    }

    /**
     * Preference key of the row for {@code item} inside {@code groupKey}'s sub-screen, or null when
     * the item does not belong to that group.
     */
    public static String rowKey(Item item, String groupKey) {
        if (item == null || groupKey == null) return null;
        switch (groupKey) {
            case GROUP_DEFAULT_SMS:
                return item == Item.DEFAULT_SMS ? "set_default_sms" : null;
            case GROUP_ACCOUNT:
                return item == Item.GOOGLE_ACCOUNT ? "switch_account" : null;
            case GROUP_GEMINI:
                return item == Item.ACCESSIBILITY ? "gemini_intercept_toggle" : null;
            case GROUP_SEND_LATER:
                switch (item) {
                    case ACCESSIBILITY: return SendLaterSettingsController.KEY_ACCESSIBILITY_ROW;
                    case NOTIFICATION_LISTENER: return SendLaterSettingsController.KEY_LISTENER_ROW;
                    case POST_NOTIFICATIONS: return SendLaterSettingsController.KEY_NOTIFICATIONS_ROW;
                    case EXACT_ALARMS: return SendLaterSettingsController.KEY_EXACT_ALARM_ROW;
                    case BATTERY: return "sendlater_battery";
                    case FULL_SCREEN_INTENT: return KEY_FULL_SCREEN_ROW;
                    default: return null;
                }
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ phone side

    /** Probes answered by the app's existing checks. Each check runs when asked. */
    public static Probes live(Context context) {
        final Context c = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        return new Probes() {
            @Override public boolean sendLaterEnabled() {
                return SendLaterSettings.isEnabled(c);
            }

            @Override public int sdkInt() {
                return Build.VERSION.SDK_INT;
            }

            @Override public boolean isOk(Item item) {
                return check(c, item);
            }
        };
    }

    /** The existing check for one item. A check that throws reads as "not set up". */
    static boolean check(Context c, Item item) {
        try {
            switch (item) {
                case DEFAULT_SMS:
                    return DefaultSms.isDefault(c);
                case ACCESSIBILITY:
                    return A11yWarning.isServiceEnabled(c, false);
                case NOTIFICATION_LISTENER:
                    return SendLaterListenerStatus.isEnabled(c);
                case POST_NOTIFICATIONS:
                    return SendLaterSettingsController.notificationsAllowed(c);
                case EXACT_ALARMS:
                    return SendLaterSettingsController.exactAlarmsAllowed(c);
                case BATTERY:
                    return BatteryIndicator.of(BatteryHealth.check(c)) == BatteryIndicator.OK;
                case FULL_SCREEN_INTENT:
                    return fullScreenIntentAllowed(c);
                case GOOGLE_ACCOUNT:
                    String account = PreferenceManager.getDefaultSharedPreferences(c)
                            .getString("google_account", null);
                    return account != null && !account.isEmpty();
                default:
                    return true;
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The full-screen-intent grant alone (same test as the engine's locked-phone check,
     * AndroidEngineDeps): always allowed below API 34. Notifications being off is its own item.
     */
    static boolean fullScreenIntentAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.canUseFullScreenIntent();
    }

    /** "Needs: a, b" for the given items. */
    public static String needsSummary(Context c, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item item : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.getString(item.labelRes));
        }
        return c.getString(R.string.sendlater_setup_needs, sb.toString());
    }

    /**
     * The screen that fixes {@code item}, or null when there is none on this phone. Reuses the
     * screens the app already opens for each item.
     */
    public static Intent fixIntent(Context c, Item item) {
        Uri pkg = Uri.parse("package:" + c.getPackageName());
        switch (item) {
            case DEFAULT_SMS:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    RoleManager rm = c.getSystemService(RoleManager.class);
                    if (rm != null) return rm.createRequestRoleIntent(RoleManager.ROLE_SMS);
                }
                return new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
            case ACCESSIBILITY:
                return A11yWarning.settingsIntent();
            case NOTIFICATION_LISTENER:
                return SendLaterListenerStatus.openSettingsIntent();
            case POST_NOTIFICATIONS:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
                }
                return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
            case EXACT_ALARMS:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg);
                }
                return null;
            case BATTERY:
                return BatteryHealth.fixIntent(c);
            case FULL_SCREEN_INTENT:
                if (Build.VERSION.SDK_INT >= 34) {
                    return new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg);
                }
                return null;
            case GOOGLE_ACCOUNT:
                return AccountManager.newChooseAccountIntent(
                        null, null, new String[]{"com.google"}, null, null, null, null);
            default:
                return null;
        }
    }
}
