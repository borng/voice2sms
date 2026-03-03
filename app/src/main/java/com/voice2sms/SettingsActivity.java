package com.voice2sms;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accounts.AccountManager;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.webkit.CookieManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

public class SettingsActivity extends AppCompatActivity {

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
                    .commit();
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Voice2SMS Settings");
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

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
                String currentAccount = prefs.getString("google_account", "Not set");
                switchAccountBtn.setSummary("Current: " + currentAccount);

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

            // Auto-send disabled placeholder
            Preference autoSendDisabled = findPreference("auto_send_disabled");
            if (autoSendDisabled != null) {
                autoSendDisabled.setOnPreferenceClickListener(pref -> {
                    Toast.makeText(requireContext(),
                            "Auto-send is disabled for now",
                            Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            Preference geminiToggle = findPreference("gemini_intercept_toggle");
            if (geminiToggle != null) {
                updateGeminiInterceptStatus(geminiToggle);
            }
        }

        private void updateGeminiInterceptStatus(Preference pref) {
            boolean enabled = isAccessibilityServiceEnabled();
            pref.setSummary(enabled
                    ? "Enabled \u2014 Gemini Send taps are intercepted"
                    : "Tap to open Accessibility Settings");
        }

        private boolean isAccessibilityServiceEnabled() {
            AccessibilityManager am = (AccessibilityManager)
                    requireContext().getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            java.util.List<AccessibilityServiceInfo> enabled =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC);
            String targetId = requireContext().getPackageName() + "/.GeminiSmsInterceptService";
            for (AccessibilityServiceInfo info : enabled) {
                if (info.getId() != null && info.getId().equals(targetId)) return true;
            }
            return false;
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
                // Refresh fragment to update summary
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.settings_container, new SettingsFragment())
                        .commit();
            }
        } else if (requestCode == REQUEST_DEFAULT_SMS) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Voice2SMS set as default SMS app", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
