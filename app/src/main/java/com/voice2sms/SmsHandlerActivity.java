package com.voice2sms;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * Transparent activity that handles sms:/smsto: intents.
 * When launched from home screen (MAIN action), shows a prompt to set as default SMS app.
 * When launched via SMS intent, parses recipient + body and forwards to GVoiceWebViewActivity.
 */
public class SmsHandlerActivity extends Activity {

    private static final String TAG = "Voice2SMS";
    private static final int REQUEST_DEFAULT_SMS = 1001;

    /**
     * Timestamp of the last SENDTO intent received. Used by
     * GeminiSmsInterceptService to avoid double-firing when the Edit
     * button already sent a SENDTO intent to us.
     */
    static volatile long lastSendtoTimestamp = 0;

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
        // First launch? Show setup wizard
        boolean setupDone = getSharedPreferences("voice2sms", MODE_PRIVATE)
                .getBoolean("setup_completed", false);
        if (!setupDone) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

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
        // Record timestamp so AccessibilityService can avoid double-fire
        lastSendtoTimestamp = System.currentTimeMillis();
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "SmsHandler intent: action=" + intent.getAction()
                    + ", data=" + intent.getData());
        }

        Uri data = intent.getData();
        String recipient = null;
        String body = null;

        if (data != null) {
            // Parse phone number from URI: sms:+15551234567 or smsto:+15551234567
            // Some callers use '&' instead of '?' (e.g. sms:+1234&body=Hello)
            String ssp = data.getSchemeSpecificPart();
            if (ssp != null) {
                // Strip query params at '?' or '&', whichever comes first
                int qIdx = ssp.indexOf('?');
                int aIdx = ssp.indexOf('&');
                int sepIdx = -1;
                if (qIdx >= 0 && aIdx >= 0) sepIdx = Math.min(qIdx, aIdx);
                else if (qIdx >= 0) sepIdx = qIdx;
                else if (aIdx >= 0) sepIdx = aIdx;
                if (sepIdx >= 0) {
                    ssp = ssp.substring(0, sepIdx);
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
                    if (BuildConfig.DEBUG) Log.d(TAG, "Parsed body from opaque URI");
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

        if (BuildConfig.DEBUG) Log.d(TAG, "SmsHandler: recipient=" + recipient
                + ", body=" + (body != null ? "[present]" : "null"));
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
        // Forward explicit force_auto_send if present (e.g. from
        // GeminiSmsInterceptService or RespondViaMessageService).
        // Modify/Edit button intents do NOT auto-send — user reviews first.
        Intent src = getIntent();
        if (src.hasExtra("force_auto_send")) {
            webIntent.putExtra("force_auto_send", src.getBooleanExtra("force_auto_send", false));
        }
        startActivity(webIntent);
        finish();
    }
}
