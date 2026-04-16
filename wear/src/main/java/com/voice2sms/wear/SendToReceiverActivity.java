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
import java.util.concurrent.ThreadLocalRandom;

import org.json.JSONException;
import org.json.JSONObject;

/** Theme.NoDisplay trampoline — see GALAXY-WATCH-SMS.md for the wire protocol. */
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
            req = WearPayloadValidator.fromFields(parsed.phone, parsed.body, newRequestId());
        } catch (WearPayloadValidator.ValidationException e) {
            Log.w(TAG, "reject local: " + e.getMessage());
            toast("Voice2SMS: " + friendly(e.getMessage()));
            return;
        }

        sendToPhone(req);
    }

    private void sendToPhone(WearPayloadValidator.Request req) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("phone", req.phone);
            obj.put("body", req.body);
            obj.put("requestId", req.requestId);
            byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);

            // Phone-side WearSmsListenerService reads DataItem.getData() and hands it
            // straight to WearPayloadValidator.parse(). PutDataMapRequest would wrap
            // the bytes in a serialized DataMap and the validator would reject it as
            // malformed_json — use PutDataRequest so bytes arrive verbatim.
            PutDataRequest put = PutDataRequest.create(PATH_PREFIX + req.requestId);
            put.setData(bytes);
            put.setUrgent();
            Wearable.getDataClient(getApplicationContext())
                    .putDataItem(put)
                    .addOnFailureListener(e -> Log.w(TAG, "DataClient putDataItem failed", e));
            Log.i(TAG, "SendToReceiverActivity DataClient putDataItem " + PATH_PREFIX + req.requestId);
            toast("Sending via Voice2SMS…");
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "sendToPhone threw", e);
            toast("Voice2SMS: couldn't reach phone");
        }
    }

    /**
     * Avoids UUID.randomUUID()'s SecureRandom path — on a cold-booted watch it can
     * block 100-500ms while /dev/urandom seeds, right on the speak→sent hot path.
     * Request IDs only need short-window uniqueness for phone-side dedup.
     */
    private static String newRequestId() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return new UUID(r.nextLong(), r.nextLong()).toString();
    }

    private void toast(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

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
            default:
                return "invalid request";
        }
    }
}
