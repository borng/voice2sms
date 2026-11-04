package com.voice2sms;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.voice2sms.sendlater.battery.BatteryHealth;

import java.util.List;

/**
 * First-install onboarding wizard.
 * Walks the user through 3 setup steps:
 *   1. Set Voice2SMS as default SMS app
 *   2. Run ADB commands to block Gemini's direct SMS (future: Shizuku)
 *   3. Enable the Accessibility Service for Gemini interception
 */
public class SetupActivity extends Activity {

    private static final int REQUEST_DEFAULT_SMS = 4001;

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

        addBatteryNote(btnDone);

        updateStatusIndicators();
    }

    /**
     * Send Later battery note (R38, D25), inserted above Done: background use, the force-stop
     * caveat, and Samsung's Never sleeping apps list on Samsung phones. Changes nothing itself.
     */
    private void addBatteryNote(Button btnDone) {
        if (!(btnDone.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) btnDone.getParent();
        float dp = getResources().getDisplayMetrics().density;
        StringBuilder text = new StringBuilder()
                .append(getString(R.string.sendlater_battery_setup_title)).append("\n\n")
                .append(getString(R.string.sendlater_battery_setup_body)).append(' ')
                .append(getString(R.string.sendlater_battery_setup_force_stop));
        if (BatteryHealth.isSamsung()) {
            text.append(' ').append(getString(R.string.sendlater_battery_setup_samsung));
        }
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextColor(0xFF444444);
        note.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (24 * dp);
        note.setLayoutParams(lp);
        parent.addView(note, parent.indexOfChild(btnDone));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusIndicators();
    }

    private void updateStatusIndicators() {
        // Step 1 status: is default SMS app?
        TextView step1Status = findViewById(R.id.step1_status);
        boolean isDefault = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null) {
                isDefault = rm.isRoleHeld(RoleManager.ROLE_SMS);
            }
        }
        step1Status.setText(isDefault ? "Status: Default SMS app" : "Status: Not set");
        step1Status.setTextColor(isDefault ? 0xFF4CAF50 : 0xFF888888);

        // Step 3 status: is accessibility service enabled?
        TextView step3Status = findViewById(R.id.step3_status);
        boolean a11yEnabled = isAccessibilityServiceEnabled();
        step3Status.setText(a11yEnabled ? "Status: Enabled" : "Status: Not enabled");
        step3Status.setTextColor(a11yEnabled ? 0xFF4CAF50 : 0xFF888888);
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
