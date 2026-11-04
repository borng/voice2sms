package com.voice2sms.sendlater.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import com.voice2sms.BuildConfig;
import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.NetworkState;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.SessionState;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.engine.GvSession;
import com.voice2sms.sendlater.engine.SendLaterEngine;
import com.voice2sms.sendlater.engine.SendLaterScheduler;
import com.voice2sms.sendlater.store.SendLaterRepository;
import com.voice2sms.sendlater.store.ThreadEventEntity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Debug-build-only adb hooks for Send Later (smoke script {@code tests/smoke/sendlater-smoke.sh}).
 *
 * <p>Declared only in {@code app/src/debug/AndroidManifest.xml}, exported and protected by
 * {@code android.permission.DUMP} (signature|privileged|development: the adb shell holds it,
 * third-party apps cannot). {@link #onReceive} also returns at once in a non-debug build.
 *
 * <pre>
 * am broadcast -n com.voice2sms/com.voice2sms.sendlater.debug.SendLaterDebugReceiver \
 *   -a com.voice2sms.sendlater.DEBUG_SEED --es e164 +1… --es text … --ei offset_min 3 --ez hold true
 *   -a …DEBUG_DUMP | …DEBUG_EVALUATE --el id N | …DEBUG_FIRE --el id N [--ez allow_send true
 *   --es allowed_recipient +1…] | …DEBUG_INBOUND --es e164 +1… --es text … | …DEBUG_LAST_CALL --el id N
 *   | …DEBUG_CANCEL --el id N
 * </pre>
 * The one-line result comes back as the broadcast's result data and in logcat tag
 * {@code SendLaterDebug}. DEBUG_DUMP also logs one JSON line under {@code SendLaterDump} and writes
 * it to {@code cache/sendlater-dump.json} ({@code run-as com.voice2sms cat cache/sendlater-dump.json}).
 */
public final class SendLaterDebugReceiver extends BroadcastReceiver {

    static final String TAG = "SendLaterDebug";
    static final String DUMP_TAG = "SendLaterDump";
    static final String DUMP_FILE = "sendlater-dump.json";
    /** Logcat drops the tail of longer lines; longer dumps are read from the file instead. */
    static final int MAX_LOG_LINE = 3800;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sendlater-debug");
        t.setDaemon(true);
        return t;
    });

    @Override public void onReceive(Context context, final Intent intent) {
        if (!BuildConfig.DEBUG) return;
        if (intent == null || intent.getAction() == null) return;
        if (!intent.getAction().startsWith(SendLaterDebugCommands.ACTION_PREFIX)) return;
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        EXECUTOR.execute(() -> {
            String result;
            try {
                result = handle(app, intent);
            } catch (RuntimeException e) {
                result = "error=" + e.getClass().getSimpleName();
            }
            Log.i(TAG, intent.getAction().substring(SendLaterDebugCommands.ACTION_PREFIX.length()) + " " + result);
            try {
                pending.setResultCode(result.startsWith("error=") || result.contains(" error=") ? 1 : 0);
                pending.setResultData(result);
            } finally {
                pending.finish();
            }
        });
    }

    private static String handle(Context app, Intent i) {
        SendLaterDebugCommands c = new SendLaterDebugCommands(new AndroidBackend(app));
        String action = i.getAction();
        long id = i.getLongExtra(SendLaterDebugCommands.EXTRA_ID, -1L);
        if (SendLaterDebugCommands.ACTION_SEED.equals(action)) {
            return c.seed(i.getStringExtra(SendLaterDebugCommands.EXTRA_E164),
                    i.getStringExtra(SendLaterDebugCommands.EXTRA_TEXT),
                    i.getIntExtra(SendLaterDebugCommands.EXTRA_OFFSET_MIN, SendLaterDebugCommands.DEFAULT_OFFSET_MIN),
                    i.getBooleanExtra(SendLaterDebugCommands.EXTRA_HOLD, true),
                    i.getStringExtra(SendLaterDebugCommands.EXTRA_SOURCE),
                    i.getBooleanExtra(SendLaterDebugCommands.EXTRA_ALLOW_SEND, false),
                    i.getStringExtra(SendLaterDebugCommands.EXTRA_ALLOWED_RECIPIENT));
        } else if (SendLaterDebugCommands.ACTION_DUMP.equals(action)) {
            return dump(app, c.dump());
        } else if (SendLaterDebugCommands.ACTION_EVALUATE.equals(action)) {
            return c.evaluate(id);
        } else if (SendLaterDebugCommands.ACTION_FIRE.equals(action)) {
            return c.fire(id, i.getBooleanExtra(SendLaterDebugCommands.EXTRA_ALLOW_SEND, false),
                    i.getStringExtra(SendLaterDebugCommands.EXTRA_ALLOWED_RECIPIENT));
        } else if (SendLaterDebugCommands.ACTION_INBOUND.equals(action)) {
            return c.inbound(i.getStringExtra(SendLaterDebugCommands.EXTRA_E164),
                    i.getStringExtra(SendLaterDebugCommands.EXTRA_TEXT));
        } else if (SendLaterDebugCommands.ACTION_LAST_CALL.equals(action)) {
            return c.lastCall(id);
        } else if (SendLaterDebugCommands.ACTION_CANCEL.equals(action)) {
            return c.cancel(id);
        }
        return "error=unknown_action";
    }

    private static String dump(Context app, String json) {
        String where = "file=cache/" + DUMP_FILE;
        File f = new File(app.getCacheDir(), DUMP_FILE);
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(json.getBytes(Charset.forName("UTF-8")));
        } catch (IOException e) {
            where = "file=error:" + e.getClass().getSimpleName();
        }
        if (json.length() <= MAX_LOG_LINE) {
            Log.i(DUMP_TAG, json);
        } else {
            Log.i(DUMP_TAG, "{\"truncated\":true,\"length\":" + json.length() + "}");
        }
        return "dump=ok bytes=" + json.length() + " " + where;
    }

    // ------------------------------------------------------------------ Android backend

    static final class AndroidBackend implements DebugBackend {
        private final Context app;

        AndroidBackend(Context app) { this.app = app; }

        private SendLaterRepository repo() { return SendLaterRepository.get(app); }

        @Override public long nowMs() { return System.currentTimeMillis(); }

        @Override public ScheduledMessage get(long id) { return id < 0 ? null : repo().get(id); }

        @Override public List<ScheduledMessage> allRecords() {
            Map<Long, ScheduledMessage> byId = new LinkedHashMap<>();
            add(byId, repo().allPending());
            add(byId, repo().allHeld());
            add(byId, repo().inFlight());
            add(byId, repo().recent(nowMs()));
            return new ArrayList<>(byId.values());
        }

        private static void add(Map<Long, ScheduledMessage> into, List<ScheduledMessage> from) {
            if (from == null) return;
            for (ScheduledMessage m : from) {
                if (m != null && !into.containsKey(m.getId())) into.put(m.getId(), m);
            }
        }

        @Override public List<DebugDump.KeyedEvent> allEvents() {
            Set<String> keys = new LinkedHashSet<>();
            List<String> live = repo().liveConversationKeys();
            if (live != null) keys.addAll(live);
            for (ScheduledMessage m : allRecords()) keys.add(ConversationRef.of(m).key());
            List<DebugDump.KeyedEvent> out = new ArrayList<>();
            for (String k : keys) {
                List<ThreadEvent> evs = repo().eventsSince(k, 0L);
                if (evs == null) continue;
                for (ThreadEvent e : evs) out.add(new DebugDump.KeyedEvent(k, e));
            }
            return out;
        }

        /** Approximates the engine's sources with the events the store recorded. */
        @Override public List<ThreadEvent> eventsFor(ScheduledMessage m) {
            if (!listenerEnabled()) return null;
            return repo().eventsSince(ConversationRef.of(m).key(), 0L);
        }

        @Override public SessionState session() {
            return GvSession.hasWebSession(app) ? SessionState.READY : SessionState.MISSING;
        }

        @Override public NetworkState network() {
            try {
                ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network n = cm == null ? null : cm.getActiveNetwork();
                NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        ? NetworkState.CONNECTED : NetworkState.NONE;
            } catch (RuntimeException e) {
                return NetworkState.NONE;
            }
        }

        @Override public boolean listenerEnabled() {
            try {
                return NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.getPackageName());
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override public OutboundPolicy outboundPolicy() {
            try {
                return SendLaterSettings.outboundPolicy(app);
            } catch (RuntimeException e) {
                return SendLaterSettings.DEFAULT_OUTBOUND_POLICY;
            }
        }

        @Override public long schedule(ScheduledMessage m, Watermark wm) throws Exception {
            return repo().schedule(m, wm);
        }

        @Override public void registerAlarms(ScheduledMessage m) { SendLaterScheduler.get(app).registerAlarms(m); }

        @Override public boolean hold(long id, HoldReason reason) { return repo().hold(id, reason); }

        @Override public void cancelAlarms(long id) { SendLaterScheduler.get(app).cancelAlarms(id); }

        @Override public void recordInbound(String e164, ThreadEvent event) {
            repo().recordEvent(new ConversationRef(e164, null), event, ThreadEventEntity.SOURCE_LISTENER, nowMs());
        }

        @Override public void engineOnInbound(String e164, ThreadEvent event) {
            SendLaterEngine.onInboundEvent(app, e164, event);
        }

        @Override public void engineOnLastCall(long id) { SendLaterEngine.onLastCall(app, id); }

        @Override public void engineOnCancel(long id) { SendLaterEngine.onCancel(app, id); }

        @Override public boolean makeDueNow(long id, long nowMs) {
            ScheduledMessage m = repo().get(id);
            if (m == null) return false;
            return repo().reschedule(id, nowMs, m.isHoldIfReplied(), m.getLatenessPolicy(), null);
        }

        @Override public void engineOnDue(long id) { SendLaterEngine.onDue(app, id); }
    }
}
