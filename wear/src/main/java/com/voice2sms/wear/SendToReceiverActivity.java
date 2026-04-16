package com.voice2sms.wear;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Theme.NoDisplay trampoline. Assistant fires ACTION_SENDTO on the watch, we
 * validate locally, write the payload to the Wearable Data Layer, toast, and
 * finish. The phone's WearSmsListenerService picks it up on the other side.
 *
 * No UI intentionally — the watch user spoke their command to Assistant; any
 * extra friction here breaks the "speak → sent" experience we're trying to
 * create in the first place.
 */
public class SendToReceiverActivity extends Activity {

    private static final String TAG = "V2SWear";
    private static final String PATH_PREFIX = "/voice2sms/requests/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            handleIntent(getIntent());
        } finally {
            finish();
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            toast("Voice2SMS: empty request");
            return;
        }

        Uri data = intent.getData();
        String ssp = (data != null) ? data.getSchemeSpecificPart() : null;

        String bodyExtra = intent.getStringExtra("sms_body");
        if (bodyExtra == null) bodyExtra = intent.getStringExtra(Intent.EXTRA_TEXT);

        SendToIntentParser.Parsed parsed = SendToIntentParser.parse(ssp, bodyExtra);

        WearPayloadValidator.Request req;
        try {
            req = WearPayloadValidator.fromFields(
                    parsed.phone, parsed.body, UUID.randomUUID().toString());
        } catch (WearPayloadValidator.ValidationException e) {
            Log.w(TAG, "reject local: " + e.getMessage());
            toast("Voice2SMS: " + friendly(e.getMessage()));
            return;
        }

        if (!sendToPhone(req)) {
            toast("Voice2SMS: couldn't reach phone");
            return;
        }
        toast("Sending via Voice2SMS…");
    }

    private boolean sendToPhone(WearPayloadValidator.Request req) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("phone", req.phone);
            obj.put("body", req.body);
            obj.put("requestId", req.requestId);
            byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);

            // Phone-side WearSmsListenerService.handleRequest reads DataItem.getData()
            // and hands it straight to WearPayloadValidator.parse(). Use PutDataRequest
            // so those bytes arrive verbatim — PutDataMapRequest would wrap them in a
            // serialized DataMap that the phone-side validator rejects as malformed_json.
            PutDataRequest put = PutDataRequest.create(PATH_PREFIX + req.requestId);
            put.setData(bytes);
            // setUrgent forces prompt BT sync; the user just spoke and hit send.
            put.setUrgent();
            Wearable.getDataClient(this)
                    .putDataItem(put)
                    .addOnFailureListener(e -> Log.w(TAG, "DataClient putDataItem failed", e));
            Log.i(TAG, "SendToReceiverActivity DataClient putDataItem " + PATH_PREFIX + req.requestId);
            return true;
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "sendToPhone threw", e);
            return false;
        }
    }

    private void toast(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    /** Reason codes are log-safe short strings; surface user-friendly text on the watch. */
    private static String friendly(String reason) {
        if (reason == null) return "invalid request";
        switch (reason) {
            case "invalid_phone":
            case "missing_phone":
                return "unrecognized phone number";
            case "empty_body":
            case "missing_body":
                return "empty message";
            case "body_too_long":
                return "message too long";
            case "empty_payload":
                return "empty request";
            default:
                return "invalid request";
        }
    }
}
