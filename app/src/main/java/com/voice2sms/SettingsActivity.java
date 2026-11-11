package com.voice2sms;

import android.accounts.AccountManager;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import java.util.List;

import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.a11y.A11yWarning;
import com.voice2sms.sendlater.ui.ScheduledCardController;
import com.voice2sms.sendlater.ui.ScheduledCardPreference;
import com.voice2sms.sendlater.ui.SendLaterSettingsController;
import com.voice2sms.sendlater.ui.SetupChecks;
import com.voice2sms.sendlater.battery.BatteryAdvice;
import com.voice2sms.sendlater.battery.BatteryHealth;
import com.voice2sms.sendlater.battery.BatteryIndicator;
import com.voice2sms.sendlater.battery.BatteryStatus;

/**
 * The launcher page (R35a, R36a; D47, D48): the "Scheduled messages" card on top, then one row per
 * settings group (Send Later, Default SMS app, Google account, Gemini, Notifications, About), each
 * with a live one-line summary. A group row opens its sub-screen (a nested PreferenceScreen of
 * preferences.xml shown by a {@link SettingsFragment} rooted at that screen's key), which holds
 * that group's former rows with their old keys and wiring.
 */
public class SettingsActivity extends AppCompatActivity
        implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    /**
     * Boolean extra: true = show the launcher page with the Scheduled messages card in view
     * (leaves any open group sub-screen). Used by the pending-messages notification (R45).
     */
    public static final String EXTRA_SHOW_SCHEDULED = "com.voice2sms.extra.SHOW_SCHEDULED";

    private static final int REQUEST_ACCOUNT_PICKER = 3001;
    private static final int REQUEST_DEFAULT_SMS = 3002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commitNow();
            showScheduledIfAsked(getIntent());
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.sendlater_home_title);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showScheduledIfAsked(intent);
    }

    /** EXTRA_SHOW_SCHEDULED: back to the root page and scroll the card into view. */
    private void showScheduledIfAsked(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_SHOW_SCHEDULED, false)) return;
        FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved()) return;
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        Fragment f = fm.findFragmentById(R.id.settings_container);
        if (f instanceof SettingsFragment) ((SettingsFragment) f).scrollToScheduled();
    }

    /** A group row was tapped: open its sub-screen (R36a). */
    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen pref) {
        return openGroup(pref.getKey());
    }

    boolean openGroup(String key) {
        if (key == null) return false;
        FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved()) return false;
        SettingsFragment f = new SettingsFragment();
        Bundle args = new Bundle();
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, key);
        f.setArguments(args);
        fm.beginTransaction()
                .replace(R.id.settings_container, f)
                .addToBackStack(key)
                .commit();
        return true;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        /** "Send Later" category rows (R36); summaries refreshed in onResume. */
        private SendLaterSettingsController sendLater;
        /** Send Later "Battery" row (R38, R43); summary and indicator icon refreshed in onResume. */
        private static final String KEY_SENDLATER_BATTERY = "sendlater_battery";

        /** The Scheduled messages card (R35a), root page only. */
        static final String KEY_SCHEDULED_CARD = "home_scheduled_card";
        /** Group rows of the root page (R36a); each is the nested screen of its group. */
        static final String KEY_GROUP_SEND_LATER = "group_send_later";
        static final String KEY_GROUP_DEFAULT_SMS = "group_default_sms";
        static final String KEY_GROUP_ACCOUNT = "group_google_account";
        static final String KEY_GROUP_GEMINI = "group_gemini";
        static final String KEY_GROUP_NOTIFICATIONS = "group_notifications";
        static final String KEY_GROUP_ABOUT = "group_about";

        /** Group rows checked against SetupChecks (D59): every row of the root page. */
        private static final String[] GROUP_KEYS = {KEY_GROUP_SEND_LATER, KEY_GROUP_DEFAULT_SMS,
                KEY_GROUP_ACCOUNT, KEY_GROUP_GEMINI, KEY_GROUP_NOTIFICATIONS, KEY_GROUP_ABOUT};

        /** True on the root page (card + group rows); false on a group sub-screen. */
        private boolean home;
        /** Key of the group this sub-screen shows (null on the root page). */
        private String group;
        private ScheduledCardController card;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
            home = rootKey == null;
            group = rootKey;
            if (home) {
                // The root also contains the nested group screens; their rows are wired only
                // when a group's own sub-screen is shown.
                ScheduledCardPreference cardPref = findPreference(KEY_SCHEDULED_CARD);
                if (cardPref != null) {
                    card = new ScheduledCardController(this, cardPref, () -> {
                        if (getActivity() instanceof SettingsActivity) {
                            ((SettingsActivity) getActivity()).openGroup(KEY_GROUP_SEND_LATER);
                        }
                    });
                }
                refreshGroupSummaries();
                return;
            }
            if (findPreference(SendLaterSettings.KEY_ENABLED) != null) {
                sendLater = new SendLaterSettingsController(this);
            }

            // Set default SMS app button
            Preference defaultSmsBtn = findPreference("set_default_sms");
            if (defaultSmsBtn != null) {
                defaultSmsBtn.setOnPreferenceClickListener(pref -> {
                    requestDefaultSms();
                    return true;
                });
            }

            // Switch account button
            Preference switchAccountBtn = findPreference("switch_account");
            if (switchAccountBtn != null) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                refreshAccount(switchAccountBtn);

                switchAccountBtn.setOnPreferenceClickListener(pref -> {
                    // Clear cookies and saved account
                    CookieManager.getInstance().removeAllCookies(null);
                    prefs.edit().remove("google_account").apply();

                    Intent pickIntent = AccountManager.newChooseAccountIntent(
                            null, null, new String[]{"com.google"}, null, null, null, null);
                    requireActivity().startActivityForResult(pickIntent, REQUEST_ACCOUNT_PICKER);
                    return true;
                });
            }

            // Gemini interception toggle — opens Accessibility Settings
            Preference geminiToggle = findPreference("gemini_intercept_toggle");
            if (geminiToggle != null) {
                updateGeminiInterceptStatus(geminiToggle);
                geminiToggle.setOnPreferenceClickListener(pref -> {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    return true;
                });
            }

            // Gemini auto-send toggle
            SwitchPreferenceCompat geminiAutoSend = findPreference("gemini_auto_send");
            if (geminiAutoSend != null) {
                geminiAutoSend.setOnPreferenceChangeListener((pref, newValue) -> {
                    boolean enabled = (Boolean) newValue;
                    Toast.makeText(requireContext(),
                            enabled ? "Gemini SMS will auto-send via Google Voice"
                                    : "Gemini SMS will open for review before sending",
                            Toast.LENGTH_SHORT).show();
                    return true;
                });
            }

            // Setup wizard shortcut
            Preference setupWizard = findPreference("setup_wizard");
            if (setupWizard != null) {
                setupWizard.setOnPreferenceClickListener(pref -> {
                    startActivity(new Intent(requireContext(), SetupActivity.class));
                    return true;
                });
            }

            // Send Later battery row (R38): live status, tap opens the right screen
            Preference battery = findPreference(KEY_SENDLATER_BATTERY);
            if (battery != null) {
                updateBatteryStatus(battery);
                battery.setOnPreferenceClickListener(pref -> {
                    openBatteryFix();
                    return true;
                });
            }

            // Send Later full-screen-intent row (D59): Android 14+ only; live status in onResume
            Preference fullScreen = findPreference(SetupChecks.KEY_FULL_SCREEN_ROW);
            if (fullScreen != null) fullScreen.setVisible(Build.VERSION.SDK_INT >= 34);

            // D59 / OD45: a setup row without its own handler opens the screen that fixes it.
            wireSetupFixes();

            // Version + build (long-press to copy for bug reports)
            Preference version = findPreference("app_version");
            if (version != null) {
                String suffix = BuildConfig.DEBUG ? " · debug" : "";
                String versionText = "v" + BuildConfig.VERSION_NAME
                        + " · " + BuildConfig.VERSION_CODE
                        + " · " + BuildConfig.GIT_SHA
                        + " · " + BuildConfig.BUILD_DATE
                        + suffix;
                version.setSummary(versionText);
                version.setOnPreferenceClickListener(pref -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "Voice2SMS version", versionText));
                        Toast.makeText(requireContext(),
                                "Copied: " + versionText, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            if (sendLater != null) {
                sendLater.refresh();
            }
            Preference battery = home ? null : findPreference(KEY_SENDLATER_BATTERY);
            if (battery != null) {
                updateBatteryStatus(battery);
            }
            if (home) {
                requireActivity().setTitle(R.string.sendlater_home_title);
                refreshGroupSummaries();
                if (card != null) card.onResume();
                return;
            }
            Preference geminiToggle = findPreference("gemini_intercept_toggle");
            if (geminiToggle != null) {
                updateGeminiInterceptStatus(geminiToggle);
            }
            Preference account = findPreference("switch_account");
            if (account != null) {
                refreshAccount(account);
            }
            Preference defaultSms = findPreference("set_default_sms");
            if (defaultSms != null) {
                updateDefaultSmsStatus(defaultSms);
            }
            Preference fullScreen = findPreference(SetupChecks.KEY_FULL_SCREEN_ROW);
            if (fullScreen != null && fullScreen.isVisible()) {
                fullScreen.setSummary(SetupChecks.live(requireContext())
                        .isOk(SetupChecks.Item.FULL_SCREEN_INTENT)
                        ? R.string.sendlater_setup_full_screen_on
                        : R.string.sendlater_setup_full_screen_off);
            }
            refreshSetupMarks();
            PreferenceScreen screen = getPreferenceScreen();
            if (screen != null && screen.getTitle() != null) {
                requireActivity().setTitle(screen.getTitle());
            }
        }

        @Override
        public void onPause() {
            if (card != null) card.onPause();
            super.onPause();
        }

        @Override
        public void onDestroy() {
            if (card != null) card.onDestroy();
            super.onDestroy();
        }

        /** Brings the Scheduled messages card into view (EXTRA_SHOW_SCHEDULED). */
        void scrollToScheduled() {
            if (home) scrollToPreference(KEY_SCHEDULED_CARD);
        }

        /** R36a: one live line per group row, recomputed on every resume. */
        private void refreshGroupSummaries() {
            android.content.Context c = getContext();
            if (c == null) return;
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
            // D59 / OD44: one list of required items decides which rows need attention.
            SetupChecks.Probes probes = SetupChecks.live(c);

            Preference sendLaterRow = findPreference(KEY_GROUP_SEND_LATER);
            if (sendLaterRow != null) {
                // D55: "<state> · exact alarms on|off · battery OK|may delay sends"
                boolean exact = SendLaterSettingsController.exactAlarmsAllowed(c);
                BatteryStatus status = BatteryHealth.check(c);
                boolean batteryOk = BatteryIndicator.of(status) == BatteryIndicator.OK;
                int res;
                if (!SendLaterSettings.isEnabled(c)) {
                    res = SendLaterSettings.offByRestore(c)
                            ? R.string.sendlater_home_summary_send_later_off_restore
                            : R.string.sendlater_home_summary_send_later_off;
                } else {
                    // A missing item replaces this line with "Needs: ..." below.
                    res = R.string.sendlater_home_summary_send_later_on;
                }
                sendLaterRow.setSummary(c.getString(R.string.sendlater_home_summary_send_later_detail,
                        c.getString(res), onOff(c, exact),
                        c.getString(batteryOk ? R.string.sendlater_home_summary_battery_ok
                                : R.string.sendlater_home_summary_battery_fix)));
            }

            Preference defaultSmsRow = findPreference(KEY_GROUP_DEFAULT_SMS);
            if (defaultSmsRow != null) {
                defaultSmsRow.setSummary(DefaultSms.isDefault(c)
                        ? R.string.sendlater_home_summary_default_sms_yes
                        : R.string.sendlater_home_summary_default_sms_no);
            }

            Preference accountRow = findPreference(KEY_GROUP_ACCOUNT);
            if (accountRow != null) refreshAccount(accountRow);

            Preference geminiRow = findPreference(KEY_GROUP_GEMINI);
            if (geminiRow != null) {
                geminiRow.setSummary(c.getString(R.string.sendlater_home_summary_gemini,
                        onOff(c, A11yWarning.isServiceEnabled(c, false)),
                        onOff(c, prefs.getBoolean("gemini_auto_send", true))));
            }

            Preference notificationsRow = findPreference(KEY_GROUP_NOTIFICATIONS);
            if (notificationsRow != null) {
                notificationsRow.setSummary(c.getString(R.string.sendlater_home_summary_notifications,
                        onOff(c, prefs.getBoolean("show_autosend_notification", true)),
                        onOff(c, prefs.getBoolean("incoming_sms_notify", true))));
            }

            Preference aboutRow = findPreference(KEY_GROUP_ABOUT);
            if (aboutRow != null) {
                aboutRow.setSummary(c.getString(R.string.sendlater_home_summary_about,
                        BuildConfig.VERSION_NAME));
            }

            // OD45: a group with a missing item shows the warning icon in place of the chevron
            // and "Needs: <labels>" as its summary; an OK group keeps its chevron and line.
            for (String key : GROUP_KEYS) {
                Preference row = findPreference(key);
                if (row == null) continue;
                List<SetupChecks.Item> missing = SetupChecks.missing(probes, key);
                row.setWidgetLayoutResource(missing.isEmpty()
                        ? R.layout.sendlater_home_chevron : R.layout.sendlater_setup_warn);
                if (!missing.isEmpty()) row.setSummary(SetupChecks.needsSummary(c, missing));
            }
        }

        /**
         * OD45, inside a group: the row of each missing item shows the warning icon at its end;
         * an OK row has no end widget. Rechecked on every resume.
         */
        private void refreshSetupMarks() {
            android.content.Context c = getContext();
            if (c == null || group == null) return;
            List<SetupChecks.Item> missing = SetupChecks.missing(SetupChecks.live(c), group);
            for (SetupChecks.Item item : SetupChecks.Item.values()) {
                String key = SetupChecks.rowKey(item, group);
                Preference row = key == null ? null : findPreference(key);
                if (row == null) continue;
                row.setWidgetLayoutResource(missing.contains(item) ? R.layout.sendlater_setup_warn : 0);
            }
        }

        /**
         * OD45: tapping a setup row opens the screen that fixes it. Rows that already have a
         * handler keep it (each already opens that item's screen, some with a better flow such as
         * the in-app permission prompt); any other setup row gets SetupChecks.fixIntent.
         */
        private void wireSetupFixes() {
            if (group == null) return;
            for (final SetupChecks.Item item : SetupChecks.Item.values()) {
                String key = SetupChecks.rowKey(item, group);
                Preference row = key == null ? null : findPreference(key);
                if (row == null || row.getOnPreferenceClickListener() != null) continue;
                row.setOnPreferenceClickListener(pref -> {
                    openSetupFix(item);
                    return true;
                });
            }
        }

        private void openSetupFix(SetupChecks.Item item) {
            Intent fix = SetupChecks.fixIntent(requireContext(), item);
            try {
                if (fix != null) {
                    startActivity(fix);
                    return;
                }
            } catch (android.content.ActivityNotFoundException | SecurityException e) {
                // falls through to the hint below
            }
            Toast.makeText(requireContext(), R.string.sendlater_setup_no_screen,
                    Toast.LENGTH_LONG).show();
        }

        private static String onOff(android.content.Context c, boolean on) {
            return c.getString(on ? R.string.sendlater_home_on : R.string.sendlater_home_off);
        }

        private void refreshAccount(Preference pref) {
            String account = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString("google_account", null);
            pref.setSummary(account == null || account.isEmpty()
                    ? getString(R.string.sendlater_home_summary_account_none)
                    : getString(R.string.sendlater_home_summary_account, account));
        }

        /** D54: the Default SMS sub-screen row shows the live status, rechecked on resume. */
        private void updateDefaultSmsStatus(Preference pref) {
            pref.setSummary(DefaultSms.isDefault(requireContext())
                    ? R.string.sendlater_home_summary_default_sms_yes
                    : R.string.sendlater_home_summary_default_sms_no);
        }

        private void updateBatteryStatus(Preference pref) {
            BatteryStatus status = BatteryHealth.check(requireContext());
            pref.setSummary(BatteryAdvice.text(status, status.isSamsung()));
            // R43: green check / amber warning; the summary above carries the words for TalkBack
            pref.setIcon(BatteryIndicator.of(status).iconRes);
        }

        private void openBatteryFix() {
            Intent fix = BatteryHealth.fixIntent(requireContext());
            if (fix == null) {
                Toast.makeText(requireContext(), R.string.sendlater_battery_no_screen,
                        Toast.LENGTH_LONG).show();
                return;
            }
            try {
                startActivity(fix);
            } catch (android.content.ActivityNotFoundException | SecurityException e) {
                Toast.makeText(requireContext(), R.string.sendlater_battery_no_screen,
                        Toast.LENGTH_LONG).show();
            }
        }

        private void updateGeminiInterceptStatus(Preference pref) {
            // OD44: the one accessibility test, fail-closed (an unreadable setting shows "off").
            boolean enabled = A11yWarning.isServiceEnabled(requireContext(), false);
            pref.setSummary(enabled
                    ? "Enabled \u2014 Gemini Send taps are intercepted"
                    : "Tap to open Accessibility Settings");
        }

        private void requestDefaultSms() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roleManager = requireContext().getSystemService(RoleManager.class);
                if (roleManager != null) {
                    Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS);
                    requireActivity().startActivityForResult(intent, REQUEST_DEFAULT_SMS);
                    return;
                }
            }
            Toast.makeText(requireContext(),
                    "Go to Settings > Apps > Default apps > SMS app",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ACCOUNT_PICKER && resultCode == RESULT_OK && data != null) {
            String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (accountName != null) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit().putString("google_account", accountName).apply();
                Toast.makeText(this, "Account set to " + accountName, Toast.LENGTH_SHORT).show();
                // The account row and the Google account group row refresh in onResume.
            }
        } else if (requestCode == REQUEST_DEFAULT_SMS) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Voice2SMS set as default SMS app", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
