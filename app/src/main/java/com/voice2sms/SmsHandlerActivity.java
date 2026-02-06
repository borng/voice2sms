package com.voice2sms;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Transparent activity that handles sms:/smsto: intents.
 * When launched from home screen (MAIN action), shows a prompt to set as default SMS app.
 * When launched via SMS intent, parses recipient + body and forwards to GVoiceWebViewActivity.
 */
public class SmsHandlerActivity extends Activity {

    private static final int REQUEST_DEFAULT_SMS = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String action = intent.getAction();

        if (Intent.ACTION_MAIN.equals(action)) {
            handleLauncherStart();
        } else if (Intent.ACTION_SENDTO.equals(action) || Intent.ACTION_VIEW.equals(action)) {
            handleSmsIntent(intent);
        } else {
            // Unknown action — just open GV web
            launchWebView(null, null);
        }
    }

    private void handleLauncherStart() {
        // Offer to become default SMS app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = getSystemService(RoleManager.class);
            if (roleManager != null && !roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                Intent roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS);
                startActivityForResult(roleIntent, REQUEST_DEFAULT_SMS);
                return;
            }
        }

        // Already default or old Android — open settings
        Intent settingsIntent = new Intent(this, SettingsActivity.class);
        startActivity(settingsIntent);
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DEFAULT_SMS) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Voice2SMS set as default SMS app", Toast.LENGTH_SHORT).show();
            }
            Intent settingsIntent = new Intent(this, SettingsActivity.class);
            startActivity(settingsIntent);
            finish();
        }
    }

    private void handleSmsIntent(Intent intent) {
        Uri data = intent.getData();
        String recipient = null;
        String body = null;

        if (data != null) {
            // Parse phone number from URI: sms:+15551234567 or smsto:+15551234567
            String ssp = data.getSchemeSpecificPart();
            if (ssp != null) {
                // Remove query params if present (e.g. ?body=Hello)
                int qIdx = ssp.indexOf('?');
                if (qIdx >= 0) {
                    ssp = ssp.substring(0, qIdx);
                }
                recipient = ssp.trim();
                if (recipient.isEmpty()) {
                    recipient = null;
                }
            }

            // Try to get body from URI query param
            String queryBody = data.getQueryParameter("body");
            if (queryBody != null) {
                body = queryBody;
            }
        }

        // Fallback: body from intent extras
        if (body == null) {
            body = intent.getStringExtra("sms_body");
        }
        if (body == null) {
            body = intent.getStringExtra(Intent.EXTRA_TEXT);
        }

        launchWebView(recipient, body);
    }

    private void launchWebView(String recipient, String body) {
        Intent webIntent = new Intent(this, GVoiceWebViewActivity.class);
        if (recipient != null) {
            webIntent.putExtra("recipient", recipient);
        }
        if (body != null) {
            webIntent.putExtra("body", body);
        }
        startActivity(webIntent);
        finish();
    }
}
