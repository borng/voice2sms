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
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.voice2sms.sendlater.listener.SendLaterListenerStatus;

import java.util.List;

/**
 * First-install onboarding wizard.
 * Walks the user through 3 setup steps:
 *   1. Set Voice2SMS as default SMS app
 *   2. Run ADB commands to block Gemini's direct SMS (future: Shizuku)
 *   3. Enable the Accessibility Service for Gemini interception
 * plus an optional Send Later card (R37) explaining why the accessibility service also watches
 * Google Voice and why notification access helps; both stay opt-in.
 */
public class SetupActivity extends Activity {

    private static final int REQUEST_DEFAULT_SMS = 4001;

    /** Send Later card status line (R37); null until the card is added. */
    private TextView sendLaterListenerStatus;

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

        addSendLaterCard(btnDone);

        updateStatusIndicators();
    }

    /**
     * Optional Send Later card (R37), inserted above Done. Plain-words reasons for the
     * accessibility service watching Google Voice and for notification access; nothing is
     * turned on from here.
     */
    private void addSendLaterCard(Button btnDone) {
        if (!(btnDone.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) btnDone.getParent();
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFFFFFFFF);
        int pad = (int) (16 * dp);
        card.setPadding(pad, pad, pad, pad);
        card.setElevation(1 * dp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (24 * dp);
        card.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText(R.string.sendlater_settings_onboarding_title);
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(0xFF1A1A1A);
        card.addView(title);

        int[] paragraphs = {
                R.string.sendlater_settings_onboarding_body,
                R.string.sendlater_settings_onboarding_accessibility,
                R.string.sendlater_settings_onboarding_listener,
                R.string.sendlater_settings_onboarding_samsung};
        for (int res : paragraphs) {
            TextView t = new TextView(this);
            t.setText(res);
            t.setTextSize(14);
            t.setTextColor(0xFF555555);
            t.setPadding(0, (int) (8 * dp), 0, 0);
            card.addView(t);
        }

        sendLaterListenerStatus = new TextView(this);
        sendLaterListenerStatus.setTextSize(13);
        sendLaterListenerStatus.setPadding(0, (int) (8 * dp), 0, 0);
        card.addView(sendLaterListenerStatus);

        Button listener = new Button(this);
        listener.setText(R.string.sendlater_settings_onboarding_listener_button);
        listener.setOnClickListener(v -> {
            try {
                startActivity(SendLaterListenerStatus.openSettingsIntent());
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, "Open Settings > Notifications > Notification access",
                        Toast.LENGTH_LONG).show();
            }
        });
        card.addView(listener);

        Button settings = new Button(this);
        settings.setText(R.string.sendlater_settings_onboarding_settings_button);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        card.addView(settings);

        parent.addView(card, parent.indexOfChild(btnDone));
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

        // Send Later card: notification access status (R37)
        if (sendLaterListenerStatus != null) {
            boolean listenerOn = SendLaterListenerStatus.isEnabled(this);
            sendLaterListenerStatus.setText(listenerOn
                    ? R.string.sendlater_settings_onboarding_listener_on
                    : R.string.sendlater_settings_onboarding_listener_off);
            sendLaterListenerStatus.setTextColor(listenerOn ? 0xFF4CAF50 : 0xFF888888);
        }
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
