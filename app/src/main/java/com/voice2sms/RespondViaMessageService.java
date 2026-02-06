package com.voice2sms;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

/**
 * Handles RESPOND_VIA_MESSAGE intents fired when the user rejects a call with a text reply.
 * Extracts recipient and body, then launches GVoiceWebViewActivity with force_auto_send.
 */
public class RespondViaMessageService extends Service {

    private static final String TAG = "Voice2SMS";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String recipient = null;
        Uri data = intent.getData();
        if (data != null) {
            String ssp = data.getSchemeSpecificPart();
            if (ssp != null) {
                int qIdx = ssp.indexOf('?');
                if (qIdx >= 0) {
                    ssp = ssp.substring(0, qIdx);
                }
                recipient = ssp.trim();
                if (recipient.isEmpty()) {
                    recipient = null;
                }
            }
        }

        String body = intent.getStringExtra(Intent.EXTRA_TEXT);

        if (recipient != null) {
            Intent webIntent = new Intent(this, GVoiceWebViewActivity.class);
            webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            webIntent.putExtra("recipient", recipient);
            if (body != null) {
                webIntent.putExtra("body", body);
            }
            webIntent.putExtra("force_auto_send", true);
            startActivity(webIntent);
            Log.d(TAG, "RespondViaMessage: launching composer for " + recipient);
        } else {
            Log.w(TAG, "RespondViaMessage: no recipient found, ignoring");
        }

        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
