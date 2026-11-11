package com.voice2sms;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.voice2sms.sendlater.battery.BatteryHealth;
import com.voice2sms.sendlater.battery.BatteryIndicator;
import com.voice2sms.sendlater.battery.BatteryStatus;
import com.voice2sms.sendlater.listener.SendLaterListenerStatus;
import com.voice2sms.sendlater.ui.SendLaterSettingsController;
import com.voice2sms.sendlater.ui.SetupChecks;

/**
 * First-install onboarding wizard.
 * Walks the user through 6 setup steps:
 *   1. Set Voice2SMS as default SMS app
 *   2. Sign in to Google Voice (D60, OD46): status from the saved google_account, button opens
 *      the Google Voice screen
 *   3. Run ADB commands to block Gemini's direct SMS (future: Shizuku)
 *   4. Enable the Accessibility Service for Gemini interception
 *   5. Send Later (R42, D27): notification access, exact alarms, notifications and (Android 14+
 *      only) full-screen alerts, each with a button and a live status line, plus the plain-words
 *      reasons why the accessibility service also watches Google Voice and why notification
 *      access helps (R37)
 *   6. Battery (R42, R43): allow background use, with a green/amber indicator; on Samsung the
 *      Never sleeping apps advice
 * Every SetupChecks item has a status line here (D60). The statuses come from the SetupChecks
 * probes, the same checks the settings screens use for their "Needs attention" mark, and a
 * missing item shows that mark at the end of its status line. Every status line is recomputed
 * in onResume, so coming back from a system screen shows the new state.
 */
public class SetupActivity extends Activity {

    private static final int REQUEST_DEFAULT_SMS = 4001;
    private static final int REQUEST_POST_NOTIFICATIONS = 4002;
    private static final int REQUEST_GOOGLE_ACCOUNT = 4003;

    private static final String ADB_COMMANDS =
            "adb shell pm revoke com.google.android.googlequicksearchbox android.permission.SEND_SMS\n\n"
            + "adb shell pm set-permission-flags com.google.android.googlequicksearchbox android.permission.SEND_SMS user-fixed\n\n"
            + "adb shell appops set com.google.android.googlequicksearchbox SEND_SMS ignore";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        // Step 1: Set default SMS app
        Button btnDefault = findViewById(R.id.btn_set_default_sms);
        btnDefault.setOnClickListener(v -> requestDefaultSms());

        // Step 2: Google sign-in (D60, OD46). The status reads the saved google_account, which
        // the Google Voice screen does not write on its own, so with no account saved the button
        // first opens the account chooser (as Settings > Switch account does), saves the choice
        // and then opens the Google Voice screen for the sign-in itself.
        findViewById(R.id.btn_google_signin).setOnClickListener(v -> startGoogleSignIn());

        // Step 3: Copy ADB commands
        Button btnCopy = findViewById(R.id.btn_copy_commands);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("ADB commands", ADB_COMMANDS));
                Toast.makeText(this, "Commands copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        // Step 4: Open accessibility settings
        Button btnAccessibility = findViewById(R.id.btn_enable_accessibility);
        btnAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        // Done
        Button btnDone = findViewById(R.id.btn_done);
        btnDone.setOnClickListener(v -> {
            getSharedPreferences("voice2sms", MODE_PRIVATE)
                    .edit()
                    .putBoolean("setup_completed", true)
                    .apply();
            // Go to settings
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
        });

        // Step 5: Send Later (R42)
        findViewById(R.id.btn_sendlater_listener).setOnClickListener(v -> {
            try {
                startActivity(SendLaterListenerStatus.openSettingsIntent());
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.sendlater_settings_onboarding_listener_fallback,
                        Toast.LENGTH_LONG).show();
            }
        });
        Button btnExact = findViewById(R.id.btn_sendlater_exact);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btnExact.setOnClickListener(v -> requestExactAlarms());
        } else {
            btnExact.setEnabled(false); // always allowed below API 31
        }
        findViewById(R.id.btn_sendlater_notifications).setOnClickListener(v -> requestNotifications());
        // Full-screen alerts (D60, OD46): the permission only exists on Android 14+ (API 34)
        findViewById(R.id.sendlater_full_screen_group).setVisibility(
                Build.VERSION.SDK_INT >= 34 ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_sendlater_full_screen).setOnClickListener(v -> requestFullScreenIntent());

        // Step 6: Battery (R42, R43)
        findViewById(R.id.btn_battery_fix).setOnClickListener(v -> openBatteryFix());
        findViewById(R.id.battery_samsung_advice)
                .setVisibility(BatteryHealth.isSamsung() ? View.VISIBLE : View.GONE);

        updateStatusIndicators();
    }

    private void requestExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName()));
        if (!open(i)) openAppDetails();
    }

    /**
     * API 33+: the POST_NOTIFICATIONS runtime request while the system will still show it;
     * once it no longer would (asked before and no rationale), the app's notification settings.
     */
    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            boolean asked = getSharedPreferences(SendLaterSettingsController.ASKED_PREFS, MODE_PRIVATE)
                    .getBoolean(Manifest.permission.POST_NOTIFICATIONS, false);
            if (!asked || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                getSharedPreferences(SendLaterSettingsController.ASKED_PREFS, MODE_PRIVATE).edit()
                        .putBoolean(Manifest.permission.POST_NOTIFICATIONS, true).apply();
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_POST_NOTIFICATIONS);
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            if (open(i)) return;
        }
        openAppDetails();
    }

    /** Android 14+: this app's "Allow full-screen notifications" screen, else App info. */
    private void requestFullScreenIntent() {
        if (Build.VERSION.SDK_INT < 34) return;
        Intent i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                Uri.parse("package:" + getPackageName()));
        if (!open(i)) openAppDetails();
    }

    private void openBatteryFix() {
        Intent fix = BatteryHealth.fixIntent(this);
        if (fix == null || !open(fix)) {
            Toast.makeText(this, R.string.sendlater_battery_no_screen, Toast.LENGTH_LONG).show();
        }
    }

    private boolean open(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private void openAppDetails() {
        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) updateStatusIndicators();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusIndicators();
    }

    private void updateStatusIndicators() {
        // Every status from the SetupChecks probes (D60): the same checks as the settings marks.
        // Accessibility is A11yWarning.isServiceEnabled(c, false) there (fail closed).
        SetupChecks.Probes probes = SetupChecks.live(this);

        // Step 1: default SMS app
        item(probes, SetupChecks.Item.DEFAULT_SMS, R.id.step1_status,
                R.string.sendlater_wizard_default_sms_on, R.string.sendlater_wizard_default_sms_off);

        // Step 2: Google sign-in (the saved google_account)
        item(probes, SetupChecks.Item.GOOGLE_ACCOUNT, R.id.google_signin_status,
                R.string.sendlater_wizard_signin_on, R.string.sendlater_wizard_signin_off);

        // Step 4: accessibility service
        item(probes, SetupChecks.Item.ACCESSIBILITY, R.id.step3_status,
                R.string.sendlater_wizard_accessibility_on, R.string.sendlater_wizard_accessibility_off);

        // Step 5: Send Later statuses (R42)
        item(probes, SetupChecks.Item.NOTIFICATION_LISTENER, R.id.sendlater_listener_status,
                R.string.sendlater_setup_listener_on, R.string.sendlater_setup_listener_off);
        item(probes, SetupChecks.Item.EXACT_ALARMS, R.id.sendlater_exact_status,
                R.string.sendlater_setup_exact_on, R.string.sendlater_setup_exact_off);
        item(probes, SetupChecks.Item.POST_NOTIFICATIONS, R.id.sendlater_notifications_status,
                R.string.sendlater_setup_notifications_on, R.string.sendlater_setup_notifications_off);
        item(probes, SetupChecks.Item.FULL_SCREEN_INTENT, R.id.sendlater_full_screen_status,
                R.string.sendlater_wizard_full_screen_on, R.string.sendlater_wizard_full_screen_off);

        // Step 6: Battery status and indicator (R42, R43)
        item(probes, SetupChecks.Item.BATTERY, R.id.battery_status,
                R.string.sendlater_battery_setup_status_ok, R.string.sendlater_battery_setup_status_limited);
        BatteryIndicator indicator = BatteryIndicator.of(BatteryHealth.check(this));
        ImageView icon = findViewById(R.id.battery_indicator);
        icon.setImageResource(indicator.iconRes);
        icon.setContentDescription(getString(indicator.descriptionRes));
    }

    /**
     * One SetupChecks item's status line: text and colour from its probe, and the setup-checks
     * "Needs attention" mark at the end of the line while the item is missing.
     */
    private void item(SetupChecks.Probes probes, SetupChecks.Item item, int statusId,
                      int onText, int offText) {
        boolean ok = probes.isOk(item);
        TextView t = findViewById(statusId);
        t.setText(ok ? onText : offText);
        t.setTextColor(statusColour(ok));
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, ok ? 0 : R.drawable.sendlater_ic_setup_warn, 0);
        t.setContentDescription(ok ? null
                : getString(R.string.sendlater_setup_needs_attention) + ". " + t.getText());
    }

    /** Status text colour (D51, R42a): resources with night values, 4.5:1 on the step card. */
    private int statusColour(boolean on) {
        return getColor(on ? R.color.sendlater_setup_status_ok : R.color.sendlater_setup_status_off);
    }

    private void startGoogleSignIn() {
        String saved = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("google_account", null);
        if (saved == null || saved.isEmpty()) {
            Intent chooser = SetupChecks.fixIntent(this, SetupChecks.Item.GOOGLE_ACCOUNT);
            if (chooser != null) {
                try {
                    startActivityForResult(chooser, REQUEST_GOOGLE_ACCOUNT);
                    return;
                } catch (ActivityNotFoundException e) {
                    // No account chooser on this phone: fall through to the Google Voice screen.
                }
            }
        }
        openGoogleVoice();
    }

    private void openGoogleVoice() {
        startActivity(new Intent(this, GVoiceWebViewActivity.class));
    }

    private void requestDefaultSms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null) {
                Intent intent = rm.createRequestRoleIntent(RoleManager.ROLE_SMS);
                startActivityForResult(intent, REQUEST_DEFAULT_SMS);
                return;
            }
        }
        Toast.makeText(this, "Go to Settings > Apps > Default apps > SMS app",
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_GOOGLE_ACCOUNT) {
            if (resultCode == RESULT_OK && data != null) {
                String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                if (accountName != null && !accountName.isEmpty()) {
                    PreferenceManager.getDefaultSharedPreferences(this).edit()
                            .putString("google_account", accountName).apply();
                    updateStatusIndicators();
                    openGoogleVoice();
                    return;
                }
            }
            updateStatusIndicators();
            return;
        }
        if (requestCode == REQUEST_DEFAULT_SMS) {
            updateStatusIndicators();
            if (resultCode == RESULT_OK && DefaultSms.isDefault(this)) {
                Toast.makeText(this, "Voice2SMS set as default SMS app",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }
}
