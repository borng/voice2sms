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
        } else if (Intent.ACTION_SEND.equals(action)) {
            handleShareIntent(intent);
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
        // Dump all intent details for debugging
        android.util.Log.d("Voice2SMS", "SmsHandler intent: action=" + intent.getAction()
                + ", data=" + intent.getData()
                + ", type=" + intent.getType());
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                android.util.Log.d("Voice2SMS", "  extra: " + key + " = " + extras.get(key));
            }
        } else {
            android.util.Log.d("Voice2SMS", "  no extras");
        }

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

            // Try to get body from URI query param.
            // sms:/smsto: URIs are opaque (not hierarchical), so getQueryParameter()
            // throws UnsupportedOperationException. Parse manually for opaque URIs.
            if (data.isHierarchical()) {
                String queryBody = data.getQueryParameter("body");
                if (queryBody != null) {
                    body = queryBody;
                }
            } else {
                // Opaque URI — parse query manually from scheme-specific part
                // e.g. smsto:+1234?body=Hello
                String ssp2 = data.getSchemeSpecificPart();
                if (ssp2 != null && ssp2.contains("body=")) {
                    int bodyIdx = ssp2.indexOf("body=");
                    String bodyVal = ssp2.substring(bodyIdx + 5);
                    int ampIdx = bodyVal.indexOf('&');
                    if (ampIdx >= 0) bodyVal = bodyVal.substring(0, ampIdx);
                    try {
                        body = java.net.URLDecoder.decode(bodyVal, "UTF-8");
                    } catch (Exception e) {
                        body = bodyVal;
                    }
                    android.util.Log.d("Voice2SMS", "Parsed body from opaque URI: " + body);
                }
            }
        }

        // Fallback: body from intent extras
        if (body == null) {
            body = intent.getStringExtra("sms_body");
        }
        if (body == null) {
            body = intent.getStringExtra(Intent.EXTRA_TEXT);
        }

        android.util.Log.d("Voice2SMS", "SmsHandler: recipient=" + recipient
                + ", body=" + (body != null ? "\"" + body + "\"" : "null"));
        launchWebView(recipient, body);
    }

    private void handleShareIntent(Intent intent) {
        String body = intent.getStringExtra(Intent.EXTRA_TEXT);
        launchWebView(null, body);
    }

    private void launchWebView(String recipient, String body) {
        Intent webIntent = new Intent(this, GVoiceWebViewActivity.class);
        if (recipient != null) {
            webIntent.putExtra("recipient", recipient);
        }
        if (body != null) {
            webIntent.putExtra("body", body);
        }
        // Auto-send when both recipient and a non-empty body are present
        // (e.g. Gemini "Edit" button with a pre-composed message)
        if (recipient != null && body != null && !body.isEmpty()) {
            webIntent.putExtra("force_auto_send", true);
        }

        // Forward explicit force_auto_send from source intent, but only allow
        // opt-out (false). Never let a forwarded true override the empty-body guard above.
        Intent src = getIntent();
        if (src.hasExtra("force_auto_send") && !src.getBooleanExtra("force_auto_send", false)) {
            webIntent.putExtra("force_auto_send", false);
        }
        startActivity(webIntent);
        finish();
    }
}
