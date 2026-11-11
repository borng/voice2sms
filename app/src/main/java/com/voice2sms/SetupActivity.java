package com.voice2sms;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
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
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.voice2sms.sendlater.battery.BatteryHealth;
import com.voice2sms.sendlater.battery.BatteryIndicator;
import com.voice2sms.sendlater.battery.BatteryStatus;
import com.voice2sms.sendlater.listener.SendLaterListenerStatus;
import com.voice2sms.sendlater.ui.SendLaterSettingsController;

import java.util.List;

/**
 * First-install onboarding wizard.
 * Walks the user through 5 setup steps:
 *   1. Set Voice2SMS as default SMS app
 *   2. Run ADB commands to block Gemini's direct SMS (future: Shizuku)
 *   3. Enable the Accessibility Service for Gemini interception
 *   4. Send Later (R42, D27): notification access, exact alarms and notifications, each with a
 *      button and a live status line, plus the plain-words reasons why the accessibility service
 *      also watches Google Voice and why notification access helps (R37)
 *   5. Battery (R42, R43): allow background use, with a green/amber indicator; on Samsung the
 *      Never sleeping apps advice
 * Every status line is recomputed in onResume, so coming back from a system screen shows the
 * new state.
 */
public class SetupActivity extends Activity {

    private static final int REQUEST_DEFAULT_SMS = 4001;
    private static final int REQUEST_POST_NOTIFICATIONS = 4002;

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

        // Step 2: Copy ADB commands
        Button btnCopy = findViewById(R.id.btn_copy_commands);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("ADB commands", ADB_COMMANDS));
                Toast.makeText(this, "Commands copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });

        // Step 3: Open accessibility settings
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

        // Step 4: Send Later (R42)
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

        // Step 5: Battery (R42, R43)
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
        // Step 1 status: is default SMS app?
        TextView step1Status = findViewById(R.id.step1_status);
        boolean isDefault = DefaultSms.isDefault(this);
        step1Status.setText(isDefault ? "Status: Default SMS app" : "Status: Not set");
        step1Status.setTextColor(statusColour(isDefault));

        // Step 3 status: is accessibility service enabled?
        TextView step3Status = findViewById(R.id.step3_status);
        boolean a11yEnabled = isAccessibilityServiceEnabled();
        step3Status.setText(a11yEnabled ? "Status: Enabled" : "Status: Not enabled");
        step3Status.setTextColor(statusColour(a11yEnabled));

        // Step 4: Send Later statuses (R42)
        boolean listenerOn = SendLaterListenerStatus.isEnabled(this);
        status(R.id.sendlater_listener_status, listenerOn,
                R.string.sendlater_setup_listener_on, R.string.sendlater_setup_listener_off);
        status(R.id.sendlater_exact_status, SendLaterSettingsController.exactAlarmsAllowed(this),
                R.string.sendlater_setup_exact_on, R.string.sendlater_setup_exact_off);
        status(R.id.sendlater_notifications_status, SendLaterSettingsController.notificationsAllowed(this),
                R.string.sendlater_setup_notifications_on, R.string.sendlater_setup_notifications_off);

        // Step 5: Battery status and indicator (R42, R43)
        BatteryStatus battery = BatteryHealth.check(this);
        BatteryIndicator indicator = BatteryIndicator.of(battery);
        status(R.id.battery_status, indicator == BatteryIndicator.OK,
                R.string.sendlater_battery_setup_status_ok, R.string.sendlater_battery_setup_status_limited);
        ImageView icon = findViewById(R.id.battery_indicator);
        icon.setImageResource(indicator.iconRes);
        icon.setContentDescription(getString(indicator.descriptionRes));
    }

    /** A step status line, coloured like step 1 and step 3. */
    private void status(int viewId, boolean on, int onText, int offText) {
        TextView t = findViewById(viewId);
        t.setText(on ? onText : offText);
        t.setTextColor(statusColour(on));
    }

    /** Status text colour (D51, R42a): resources with night values, 4.5:1 on the step card. */
    private int statusColour(boolean on) {
        return getColor(on ? R.color.sendlater_setup_status_ok : R.color.sendlater_setup_status_off);
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;

        List<AccessibilityServiceInfo> enabled =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC);
        String targetId = getPackageName() + "/.GeminiSmsInterceptService";
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getId() != null && info.getId().equals(targetId)) {
                return true;
            }
        }
        return false;
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
        if (requestCode == REQUEST_DEFAULT_SMS) {
            updateStatusIndicators();
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Voice2SMS set as default SMS app",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }
}
