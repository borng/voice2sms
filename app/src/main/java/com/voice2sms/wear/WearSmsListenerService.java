package com.voice2sms.wear;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONException;
import org.json.JSONObject;

import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import com.voice2sms.BuildConfig;
import com.voice2sms.SmsHandlerActivity;

/**
 * Receives SMS send requests from the Voice2SMS watch companion over the Wearable
 * Data Layer and hands them off to {@link SmsHandlerActivity} via ACTION_SENDTO.
 *
 * See GALAXY-WATCH-SMS.md for the full wire protocol, ack semantics, and
 * architectural invariants (all enforced by {@code SourceSafetyTest}).
 */
public class WearSmsListenerService extends WearableListenerService {

    private static final String TAG = "V2SWear";

    // --- Protocol paths ---
    static final String PATH_REQUEST_PREFIX = "/voice2sms/requests/";
    static final String PATH_ACK = "/voice2sms/ack";

    // --- Notification ---
    private static final String NOTIF_CHANNEL_ID = "voice2sms_wear";
    private static final int NOTIF_ID_BASE = 42000;
    private static final int NOTIF_SLOT_MASK = 0x3FF; // 1024 slots above NOTIF_ID_BASE

    // --- Dedup + rate limit ---
    /** requestIds seen recently. Survives across invocations (static) but not process death. */
    private static final Map<String, Long> SEEN = new ConcurrentHashMap<>();
    static final long SEEN_TTL_MS = 5 * 60 * 1000L;

    /** Rate history per sourceNodeId — deque of timestamps within the window. */
    private static final Map<String, Deque<Long>> RATE_HISTORY = new ConcurrentHashMap<>();
    static final int RATE_LIMIT_COUNT = 10;
    static final long RATE_LIMIT_WINDOW_MS = 60_000L;

    // "dispatched" means "handed to GV WebView pipeline with force_auto_send=true";
    // it does NOT mean "delivered to carrier". Final confirmation requires GV
    // conversation sync — the watch UX should show "Sending…" not "Sent" on dispatched.
    static final String STATUS_DISPATCHED = "dispatched";
    static final String STATUS_INVALID = "invalid_payload";
    static final String STATUS_DUPLICATE = "duplicate";
    static final String STATUS_RATE_LIMITED = "rate_limited";

    // Prune seen-set only when it has grown enough to matter — keeps the hot path O(1)
    // amortized rather than O(n) on every request.
    private static final int PRUNE_SEEN_THRESHOLD = 64;

    // --- Intent extras ---
    /** Extra on the SENDTO intent so SmsHandlerActivity knows the request came from a watch. */
    public static final String EXTRA_WEAR_REQUEST_ID = "wear_request_id";

    // =================================================================
    //  Wearable callbacks
    // =================================================================

    /**
     * Primary durable path. DataClient items persist and re-sync after reconnect,
     * so this survives transient BT drops and process death on either side.
     */
    @Override
    public void onDataChanged(DataEventBuffer events) {
        try {
            for (DataEvent event : events) {
                if (event.getType() != DataEvent.TYPE_CHANGED) continue;
                DataItem item = event.getDataItem();
                Uri uri = item.getUri();
                String path = uri.getPath();
                if (path == null || !path.startsWith(PATH_REQUEST_PREFIX)) continue;
                handleRequest(item.getData(), uri.getHost(), uri);
            }
        } finally {
            events.release();
        }
    }

    /**
     * Hot-path alternative. Same payload; no persistence. Useful when the watch wants
     * low-latency dispatch and is willing to retry on failure.
     */
    @Override
    public void onMessageReceived(MessageEvent event) {
        if (event == null || event.getPath() == null) return;
        if (!event.getPath().startsWith(PATH_REQUEST_PREFIX)) return;
        handleRequest(event.getData(), event.getSourceNodeId(), null);
    }

    // =================================================================
    //  Core request handling
    // =================================================================

    private void handleRequest(byte[] bytes, String sourceNodeId, Uri dataItemUri) {
        WearPayloadValidator.Request req;
        try {
            req = WearPayloadValidator.parse(bytes);
        } catch (WearPayloadValidator.ValidationException e) {
            Log.w(TAG, "reject payload from " + sourceNodeId + ": " + e.getMessage());
            sendAck(sourceNodeId, null, STATUS_INVALID, e.getMessage());
            consumeDataItem(dataItemUri);
            return;
        }

        prune();
        if (SEEN.putIfAbsent(req.requestId, System.currentTimeMillis()) != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "duplicate " + req.requestId + " from " + sourceNodeId);
            sendAck(sourceNodeId, req.requestId, STATUS_DUPLICATE, null);
            consumeDataItem(dataItemUri);
            return;
        }

        if (!allowRate(sourceNodeId)) {
            Log.w(TAG, "rate_limited " + sourceNodeId);
            sendAck(sourceNodeId, req.requestId, STATUS_RATE_LIMITED, null);
            consumeDataItem(dataItemUri);
            return;
        }

        // Notification first: on Android 14+ BAL can silently block the startActivity
        // below. The notification gives the user a tappable recovery path.
        Intent sendIntent = buildSendIntent(req);
        postSendNotification(req, sendIntent);

        boolean launched = launchSendActivity(sendIntent);
        String reason = launched ? "activity_launched" : "notification_only";
        sendAck(sourceNodeId, req.requestId, STATUS_DISPATCHED, reason);

        consumeDataItem(dataItemUri);
    }

    // =================================================================
    //  Activity dispatch (trampoline — NO WebView coupling)
    // =================================================================

    private boolean launchSendActivity(Intent sendIntent) {
        try {
            startActivity(sendIntent);
            return true;
        } catch (Exception e) {
            // BackgroundActivityStartException on Android 14+, SecurityException on
            // some OEMs. Already mitigated by the notification posted above.
            Log.w(TAG, "startActivity blocked; user must tap notification", e);
            return false;
        }
    }

    /**
     * Builds the SENDTO intent handed to SmsHandlerActivity. Watch-origin requests
     * carry {@code force_auto_send=true} — the user already confirmed the send on
     * the watch, so the phone WebView should not prompt again.
     */
    private Intent buildSendIntent(WearPayloadValidator.Request req) {
        Intent i = new Intent(Intent.ACTION_SENDTO);
        i.setData(Uri.parse("smsto:" + req.phone));
        i.putExtra(Intent.EXTRA_TEXT, req.body);
        i.putExtra(EXTRA_WEAR_REQUEST_ID, req.requestId);
        i.putExtra(SmsHandlerActivity.EXTRA_FORCE_AUTO_SEND, true);
        i.setClass(this, SmsHandlerActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    // =================================================================
    //  Notification (always posted — user-facing recovery path)
    // =================================================================

    private void postSendNotification(WearPayloadValidator.Request req, Intent contentIntent) {
        if (!androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("show_autosend_notification", true)) return;

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — BAL fallback notification will be suppressed");
            return;
        }

        ensureChannel(nm);

        int id = stableId(req.requestId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, id, contentIntent, flags);

        String preview = truncate(req.phone + " — " + req.body, 80);

        Notification notif = new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle("Sent via Google Voice")
                .setContentText(preview)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        // Mask to NOTIF_SLOT_BITS so the notify ID stays in a small reserved range and
        // re-posts of the same requestId collapse into a single notification slot.
        nm.notify(NOTIF_ID_BASE + (id & NOTIF_SLOT_MASK), notif);
    }

    private void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Watch SMS requests",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Shows when the paired Voice2SMS watch requests a send.");
        nm.createNotificationChannel(ch);
    }

    // =================================================================
    //  Ack back to watch
    // =================================================================

    private void sendAck(String sourceNodeId, String requestId, String status, String reason) {
        if (sourceNodeId == null || sourceNodeId.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject();
            if (requestId != null) obj.put("requestId", requestId);
            obj.put("status", status);
            if (reason != null) obj.put("reason", reason);
            byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
            Wearable.getMessageClient(this).sendMessage(sourceNodeId, PATH_ACK, bytes);
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "ack send failed", e);
        }
    }

    // =================================================================
    //  Housekeeping
    // =================================================================

    private void consumeDataItem(Uri dataItemUri) {
        if (dataItemUri == null) return;
        try {
            // deleteDataItems(uri) with a node-specific authority only deletes the
            // local replica — the authoring watch node still owns its copy, which
            // re-syncs on reconnect and would re-trigger onDataChanged. Rewrite to
            // wildcard authority so the delete propagates to all nodes that hold
            // this request. FILTER_LITERAL matches the exact path on all authorities.
            Uri wildcard = new Uri.Builder()
                    .scheme(dataItemUri.getScheme() != null ? dataItemUri.getScheme() : "wear")
                    .authority("*")
                    .path(dataItemUri.getPath())
                    .build();
            Wearable.getDataClient(this)
                    .deleteDataItems(wildcard, DataClient.FILTER_LITERAL);
        } catch (RuntimeException e) {
            Log.w(TAG, "deleteDataItems failed", e);
        }
    }

    /**
     * Lazy maintenance. Skips when the seen-set hasn't grown, and opportunistically
     * evicts rate-history entries whose deque is empty after trimming — without this,
     * RATE_HISTORY would slowly accumulate one entry per distinct node ever seen.
     */
    private static void prune() {
        long now = System.currentTimeMillis();

        if (SEEN.size() >= PRUNE_SEEN_THRESHOLD) {
            long seenCutoff = now - SEEN_TTL_MS;
            Iterator<Map.Entry<String, Long>> it = SEEN.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() < seenCutoff) it.remove();
            }
        }

        long rateCutoff = now - RATE_LIMIT_WINDOW_MS;
        Iterator<Map.Entry<String, Deque<Long>>> rit = RATE_HISTORY.entrySet().iterator();
        while (rit.hasNext()) {
            Deque<Long> hist = rit.next().getValue();
            synchronized (hist) {
                while (!hist.isEmpty() && hist.peekFirst() < rateCutoff) hist.pollFirst();
                if (hist.isEmpty()) rit.remove();
            }
        }
    }

    private static boolean allowRate(String node) {
        // Null/empty nodeId shouldn't happen in practice (DataClient URIs carry the
        // writer-node authority; MessageClient events have non-null sourceNodeId), but
        // the _unknown bucket caps any abnormal caller at the same RATE_LIMIT_COUNT.
        if (node == null || node.isEmpty()) node = "_unknown";
        long now = System.currentTimeMillis();
        long cutoff = now - RATE_LIMIT_WINDOW_MS;
        // computeIfAbsent is atomic — prevents TOCTOU where two concurrent callbacks
        // on a brand-new node could each land before either trims.
        Deque<Long> hist = RATE_HISTORY.computeIfAbsent(node, k -> new ArrayDeque<>());
        synchronized (hist) {
            while (!hist.isEmpty() && hist.peekFirst() < cutoff) hist.pollFirst();
            if (hist.size() >= RATE_LIMIT_COUNT) return false;
            hist.offerLast(now);
            return true;
        }
    }

    private static int stableId(String requestId) {
        return requestId == null ? 0 : requestId.hashCode();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
