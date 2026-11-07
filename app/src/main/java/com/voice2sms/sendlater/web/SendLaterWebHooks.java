package com.voice2sms.sendlater.web;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.engine.SendLaterEngine;
import com.voice2sms.sendlater.store.SendLaterRepository;
import com.voice2sms.sendlater.store.ThreadDirectory;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

/**
 * Send Later wiring for {@code GVoiceWebViewActivity} (R4, R10, R11, R17, R18): the activity's
 * edits stay thin and delegate here. This class never holds the WebView; every page touch goes
 * through {@link Host}, whose implementation runs on the UI thread behind isActivityAlive().
 *
 * <p>Threads: methods named {@code bridge*} run on the JavaBridge (binder) thread and may read the
 * repository; {@code on*}, {@link #openSheet}, {@link #beginThreadRead} and the receiver run on
 * the main thread. Never logs drafts, names or numbers.
 */
public final class SendLaterWebHooks {

    private static final String TAG = "Voice2SMS-SL";

    /** The page must answer a v2sReadThread request within this time, or the callback gets null. */
    static final long THREAD_READ_TIMEOUT_MS = 5000;
    /** Undo is accepted this long after the clear (the bar shows for 10 s). */
    static final long UNDO_WINDOW_MS = 12000;
    /** A second capture this soon after a sheet start is a double tap and is dropped (UI M2). */
    static final long DOUBLE_TAP_MS = 1500;
    /** A capture older than this no longer matches an ACTION_SCHEDULED broadcast. */
    static final long CAPTURE_MATCH_MS = 30 * 60 * 1000L;

    /** What the activity provides; every method is safe to call when the activity is gone. */
    public interface Host {
        /** Evaluate {@code js} on the UI thread, skipped when the activity is no longer alive. */
        void evaluate(String js);

        /** Current WebView URL; main thread only; null when the activity is gone. */
        String currentUrl();

        /**
         * deliver sec-M1: the bridge nonce issued for the current document (BridgeNonce), or null
         * when none is (the page is not a finished trusted GV page). Never logged.
         */
        String bridgeNonce();

        /** {@code GVoiceWebViewActivity.readThread}. */
        void readThread(String e164, int limit, ThreadReadCallback cb);

        /**
         * Main thread: the Send Later switch may have changed (ACTION_STATE_CHANGED, or the
         * activity resumed). The activity restarts or stops its Send Later-only work (R40).
         */
        void onSendLaterSwitch(boolean enabled);
    }

    private static volatile WeakReference<SendLaterWebHooks> active = new WeakReference<>(null);

    private final Activity activity;
    private final Context app;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Guarded by `this`.
    private CapturePayload lastCapture;
    private long lastCaptureAt;
    private PendingClear pendingClear;
    private long undoMessageId = -1;
    private long undoUntil;
    private final List<PendingRead> reads = new ArrayList<>();

    // Main thread only.
    private boolean resumed;
    private boolean receiverRegistered;
    private boolean sheetOpen;
    /** elapsedRealtime of the last sheet start; 0 = none since the last resume. */
    private long sheetStartedAt;
    private String moduleScript;
    /** The current page load got the module (moduleScript() answered non-null for it). */
    private boolean moduleOnPage;

    private static final class PendingClear {
        final long messageId;
        final String draft;
        final String label;

        PendingClear(long messageId, String draft, String label) {
            this.messageId = messageId;
            this.draft = draft;
            this.label = label;
        }
    }

    private static final class PendingRead {
        final String e164;
        final ThreadReadCallback cb;

        PendingRead(String e164, ThreadReadCallback cb) {
            this.e164 = e164;
            this.cb = cb;
        }
    }

    public SendLaterWebHooks(Activity activity, Host host) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        this.host = host;
        active = new WeakReference<>(this);
    }

    /** Hooks of the live GVoiceWebViewActivity, or null (for the send path). */
    public static SendLaterWebHooks active() {
        return active.get();
    }

    /** Reads the open thread's newest bubbles; see {@link ThreadReadCallback}. */
    public void readThread(String e164, int limit, ThreadReadCallback cb) {
        host.readThread(e164, limit, cb);
    }

    // ------------------------------------------------------------------ page load

    /**
     * Main thread: inject.js plus {@code v2sSendLaterInit()}; evaluated by the activity from
     * onPageFinished. Null while Send Later is off (R40, I8: with the switch off a GV page runs
     * none of the module); {@link #syncModule} installs it on the live page once the switch goes
     * on.
     */
    public String moduleScript() {
        moduleOnPage = false;
        if (!SendLaterSettings.isEnabled(app)) return null;
        if (moduleScript == null) {
            // The same process-wide copy of inject.js the activity evaluates (read once).
            moduleScript = AssetScripts.read(activity.getAssets()::open, "inject.js");
            if (moduleScript == null) {
                Log.w(TAG, "inject.js unreadable; Send Later module not installed");
                return null;
            }
        }
        moduleOnPage = true;
        // deliver sec-M1: the module's bridge calls carry this document's nonce.
        return moduleScript + BridgeNonce.pageStatement(host.bridgeNonce())
                + "\n;if (typeof v2sSendLaterInit === 'function') v2sSendLaterInit();";
    }

    /**
     * Main thread: the switch may have changed. On -> installs the module on the live GV page
     * when this page load did not get it (v2sSendLaterInit is idempotent). Then the page re-reads
     * the chip state; with the switch off the page's own tick stops on that answer.
     */
    private void syncModule() {
        boolean on = SendLaterSettings.isEnabled(app);
        if (on && !moduleOnPage && OriginGuard.isTrustedGvUrl(host.currentUrl())) {
            String js = moduleScript();
            if (js != null) host.evaluate(js);
        }
        refreshChip();
        host.onSendLaterSwitch(on);
    }

    // ------------------------------------------------------------------ lifecycle (main thread)

    public void onResume() {
        resumed = true;
        sheetOpen = false;
        sheetStartedAt = 0;
        register();
        PendingClear c;
        synchronized (this) {
            c = pendingClear;
            pendingClear = null;
        }
        if (c != null) applyClear(c);
        else syncModule();
    }

    public void onPause() {
        resumed = false;
        // While our own sheet is on top the ACTION_SCHEDULED broadcast must still reach us so the
        // composer can be cleared on return; the receiver is dropped on the next pause or destroy.
        if (!sheetOpen) unregister();
    }

    public void onDestroy() {
        resumed = false;
        unregister();
        List<PendingRead> failed;
        synchronized (this) {
            failed = new ArrayList<>(reads);
            reads.clear();
        }
        for (PendingRead r : failed) r.cb.onThreadRead(null);
        if (active.get() == this) active = new WeakReference<>(null);
    }

    private void register() {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(SendLaterIntents.ACTION_SCHEDULED);
        f.addAction(SendLaterIntents.ACTION_STATE_CHANGED);
        ContextCompat.registerReceiver(activity, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private void unregister() {
        if (!receiverRegistered) return;
        try {
            activity.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // already gone
        }
        receiverRegistered = false;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (SendLaterIntents.ACTION_SCHEDULED.equals(action)) {
                onScheduled(intent);
            } else if (SendLaterIntents.ACTION_STATE_CHANGED.equals(action)) {
                syncModule();
            }
        }
    };

    /** Main thread: the main frame moved (SPA navigation); the page re-reads the chip state. */
    public void onMainFrameUrlChanged() {
        refreshChip();
    }

    /**
     * Pushes the switch state to the page and has it re-read the chip. The bridge's chip state
     * answers only for the open thread (L3), so this push is how a page with no thread open
     * learns whether Send Later is on (tick, R4 directory scrape).
     */
    private void refreshChip() {
        boolean on = SendLaterSettings.isEnabled(app);
        host.evaluate("if (window.v2sSendLaterRefresh) window.v2sSendLaterRefresh(" + on + ");");
    }

    /** ACTION_SCHEDULED: clear the composer when this WebView captured that draft (R11). */
    private void onScheduled(Intent intent) {
        String source = intent.getStringExtra(SendLaterIntents.EXTRA_SOURCE);
        String e164 = intent.getStringExtra(SendLaterIntents.EXTRA_E164);
        long id = intent.getLongExtra(SendLaterIntents.EXTRA_MESSAGE_ID, -1);
        PendingClear clear = null;
        synchronized (this) {
            CapturePayload cap = lastCapture;
            boolean fresh = cap != null && SystemClock.elapsedRealtime() - lastCaptureAt < CAPTURE_MATCH_MS;
            boolean ours = fresh && id > 0 && cap.getE164().equals(e164)
                    && (source == null || ScheduledMessage.Source.WEBVIEW.name().equals(source));
            if (ours) {
                lastCapture = null;
                if (SendLaterSettings.clearDraft(app)) {
                    clear = new PendingClear(id, cap.getDraftText(), ChipLabel.scheduledLabel(
                            intent.getStringExtra(SendLaterIntents.EXTRA_LABEL),
                            intent.getLongExtra(SendLaterIntents.EXTRA_SCHEDULED_FOR, -1L),
                            TimeZone.getDefault()));
                }
            }
        }
        if (clear == null) {
            refreshChip();
            return;
        }
        if (resumed) {
            applyClear(clear);
        } else {
            synchronized (this) {
                pendingClear = clear;
            }
        }
    }

    private void applyClear(PendingClear c) {
        synchronized (this) {
            undoMessageId = c.messageId;
            undoUntil = SystemClock.elapsedRealtime() + UNDO_WINDOW_MS;
        }
        host.evaluate("if (window.v2sSendLaterClear) window.v2sSendLaterClear("
                + c.messageId + ", " + JSONObject.quote(c.draft) + ", " + JSONObject.quote(c.label) + ");");
    }

    // ------------------------------------------------------------------ capture (R10)

    /** Binder thread: validated payload, or null (bad JSON, Send Later off). */
    public CapturePayload bridgeAcceptCapture(String json) {
        CapturePayload p = CapturePayload.parse(json);
        if (p == null) {
            Log.w(TAG, "capture rejected: invalid payload");
            return null;
        }
        if (!SendLaterSettings.isEnabled(app)) return null;
        return p;
    }

    /**
     * Main thread: opens the sheet with {@link SendLaterIntents#ACTION_CAPTURE} when the WebView
     * still shows the captured thread (the page cannot pick another recipient).
     */
    public void openSheet(CapturePayload p, String currentUrl) {
        if (!ThreadUrl.isThreadOf(currentUrl, p.getE164())) {
            Log.w(TAG, "capture dropped: page no longer shows that thread");
            return;
        }
        // UI M2: a second tab tap before the first sheet covered the page opens no second sheet.
        if (sheetStartedAt != 0 && SystemClock.elapsedRealtime() - sheetStartedAt < DOUBLE_TAP_MS) {
            return;
        }
        long now = System.currentTimeMillis();
        Intent i = new Intent(SendLaterIntents.ACTION_CAPTURE)
                .setPackage(app.getPackageName())
                .putExtra(SendLaterIntents.EXTRA_SOURCE, ScheduledMessage.Source.WEBVIEW.name())
                .putExtra(SendLaterIntents.EXTRA_E164, p.getE164())
                .putExtra(SendLaterIntents.EXTRA_CANDIDATES, new String[]{p.getE164()})
                .putExtra(SendLaterIntents.EXTRA_DRAFT, p.getDraftText())
                .putExtra(SendLaterIntents.EXTRA_THREAD_ITEM_ID, p.threadItemId())
                .putExtra(SendLaterIntents.EXTRA_WATERMARK_QUALITY, p.getWatermarkQuality().name())
                .putExtra(SendLaterIntents.EXTRA_WATERMARK_CAPTURED_AT, now);
        if (p.getDisplayName() != null) i.putExtra(SendLaterIntents.EXTRA_DISPLAY_NAME, p.getDisplayName());
        if (p.getNewestMessageId() != null) {
            i.putExtra(SendLaterIntents.EXTRA_WATERMARK_NEWEST_ID, p.getNewestMessageId());
        }
        if (p.getNewestMessageTimeMs() != null) {
            i.putExtra(SendLaterIntents.EXTRA_WATERMARK_NEWEST_TS, p.getNewestMessageTimeMs().longValue());
        }
        synchronized (this) {
            lastCapture = p;
            lastCaptureAt = SystemClock.elapsedRealtime();
        }
        if (start(i)) {
            sheetOpen = true;
            sheetStartedAt = SystemClock.elapsedRealtime();
        }
    }

    private boolean start(Intent i) {
        try {
            activity.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no Send Later activity for " + i.getAction());
            return false;
        }
    }

    // ------------------------------------------------------------------ chip (R17, R18)

    /**
     * Binder thread: chip JSON for the thread (see {@link ChipState}). {@code mainFrameUrl} is the
     * activity's main-frame URL; the page only learns about the thread it shows, so any other
     * number answers the disabled state.
     */
    public String bridgeChipState(String e164, String mainFrameUrl) {
        // L3: the thread binding comes first; nothing (not even the switch) is answered for a
        // number the main frame does not show. The page learns the switch from refreshChip().
        if (!ThreadDirectory.isE164(e164) || !ThreadUrl.isThreadOf(mainFrameUrl, e164)) {
            return ChipState.disabled();
        }
        if (!SendLaterSettings.isEnabled(app)) return ChipState.disabled();
        try {
            SendLaterRepository repo = SendLaterRepository.get(app);
            List<ScheduledMessage> pending = repo.pendingForThread(e164);
            return ChipState.toJson(true, pending, repo.heldForThread(e164),
                    SendLaterEngine.remindedIds(app, pending), System.currentTimeMillis(),
                    TimeZone.getDefault());
        } catch (RuntimeException e) {
            Log.w(TAG, "chip state unavailable: " + e.getClass().getSimpleName());
            return ChipState.toJson(true, null, null, TimeZone.getDefault());
        }
    }

    /**
     * Binder thread: one message -> edit mode, several -> Upcoming filtered to the thread. Does
     * nothing unless {@code mainFrameUrl} is the 1:1 thread of {@code e164}.
     */
    public void bridgeChipTap(String e164, String mainFrameUrl) {
        if (!ThreadUrl.isThreadOf(mainFrameUrl, e164)) return;
        if (!SendLaterSettings.isEnabled(app) || !ThreadDirectory.isE164(e164)) return;
        final Intent i;
        try {
            SendLaterRepository repo = SendLaterRepository.get(app);
            List<ScheduledMessage> pending = repo.pendingForThread(e164);
            List<ScheduledMessage> held = repo.heldForThread(e164);
            // OD31 PQ4: a single HELD message opens Upcoming filtered to the thread.
            long only = ChipState.editMessageId(pending, held);
            if (only > 0) {
                i = new Intent(SendLaterIntents.ACTION_EDIT).putExtra(SendLaterIntents.EXTRA_MESSAGE_ID, only);
            } else if (pending.size() + held.size() >= 1) {
                i = new Intent(SendLaterIntents.ACTION_UPCOMING).putExtra(SendLaterIntents.EXTRA_E164, e164);
            } else {
                return;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "chip tap failed: " + e.getClass().getSimpleName());
            return;
        }
        i.setPackage(app.getPackageName());
        main.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            start(i);
        });
    }

    // ------------------------------------------------------------------ undo (R11, I4)

    /**
     * Binder thread: cancels the message just cleared from the composer. Only the most recent
     * clear, within its undo window, and only while it is still SCHEDULED or HELD. The cancel goes
     * through {@link SendLaterEngine#onCancel} (inline under the engine lock on this thread), so
     * its alarms, marks and notification go with it and the engine broadcasts the state change,
     * exactly like the native Undo (R11). The page restores the draft itself, and only when this
     * returns true (the row reads back CANCELED).
     */
    public boolean bridgeUndo(long messageId) {
        synchronized (this) {
            if (messageId <= 0 || messageId != undoMessageId
                    || SystemClock.elapsedRealtime() > undoUntil) {
                return false;
            }
            undoMessageId = -1;
        }
        try {
            SendLaterRepository repo = SendLaterRepository.get(app);
            ScheduledMessage m = repo.get(messageId);
            if (m == null || (m.getStatus() != ScheduledMessage.Status.SCHEDULED
                    && m.getStatus() != ScheduledMessage.Status.HELD)) {
                return false;
            }
            SendLaterEngine.onCancel(app, messageId);
            ScheduledMessage after = repo.get(messageId);
            return after != null && after.getStatus() == ScheduledMessage.Status.CANCELED;
        } catch (RuntimeException e) {
            Log.w(TAG, "undo failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    // ------------------------------------------------------------------ thread read

    /**
     * Main thread: registers {@code cb} and returns the JS that asks the page for the newest
     * {@code limit} bubbles. The callback gets null after {@link #THREAD_READ_TIMEOUT_MS}, also
     * when the page has no reader.
     */
    public String beginThreadRead(String e164, int limit, ThreadReadCallback cb) {
        final PendingRead r = new PendingRead(e164, cb);
        synchronized (this) {
            reads.add(r);
        }
        main.postDelayed(() -> {
            boolean removed;
            synchronized (SendLaterWebHooks.this) {
                removed = reads.remove(r);
            }
            if (removed) r.cb.onThreadRead(null);
        }, THREAD_READ_TIMEOUT_MS);
        String q = JSONObject.quote(e164);
        int n = Math.max(1, Math.min(limit, ThreadBubble.MAX_BUBBLES));
        // No page reader: nothing to answer from here (the bridge needs the page nonce); the
        // timeout above answers null.
        return "if (window.v2sReadThread) { window.v2sReadThread(" + q + ", " + n + "); }";
    }

    /** Binder thread: delivers a page answer to every waiting reader of that thread. */
    public void bridgeThreadRead(String json) {
        ThreadBubble.Report report = ThreadBubble.parseReport(json);
        if (report == null) return;
        final List<PendingRead> ready = new ArrayList<>();
        synchronized (this) {
            Iterator<PendingRead> it = reads.iterator();
            while (it.hasNext()) {
                PendingRead r = it.next();
                if (r.e164.equals(report.e164)) {
                    ready.add(r);
                    it.remove();
                }
            }
        }
        if (ready.isEmpty()) return;
        final List<ThreadBubble> bubbles = report.bubbles;
        main.post(() -> {
            for (PendingRead r : ready) r.cb.onThreadRead(bubbles);
        });
    }

    // ------------------------------------------------------------------ thread directory (R4)

    /** Binder thread: upserts the (name, number) pairs the page saw in its thread list. */
    public void bridgeThreadDirectory(String json) {
        // Nothing is stored for a feature that is switched off (default: on, D26).
        if (!SendLaterSettings.isEnabled(app)) return;
        List<DirectoryReport.Entry> entries = DirectoryReport.parse(json);
        if (entries.isEmpty()) return;
        try {
            List<String[]> pairs = new ArrayList<>(entries.size());
            for (DirectoryReport.Entry e : entries) pairs.add(new String[] {e.displayName, e.e164});
            int n = ThreadDirectory.get(app).upsert(pairs, System.currentTimeMillis());
            if (com.voice2sms.BuildConfig.DEBUG) Log.d(TAG, "thread directory upserted " + n);
        } catch (RuntimeException e) {
            Log.w(TAG, "thread directory upsert failed: " + e.getClass().getSimpleName());
        }
    }
}
