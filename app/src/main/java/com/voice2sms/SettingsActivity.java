package com.voice2sms;

import android.accounts.AccountManager;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
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

            // Auto-send delay summary
            SeekBarPreference delayPref = findPreference("auto_send_delay");
            if (delayPref != null) {
                delayPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    preference.setSummary(newValue + " seconds");
                    return true;
                });
                delayPref.setSummary(delayPref.getValue() + " seconds");
            }
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
