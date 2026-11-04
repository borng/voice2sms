package com.voice2sms.sendlater.voice.overlay;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.core.content.ContextCompat;

import com.voice2sms.R;
import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.engine.SendLaterEngine;
import com.voice2sms.sendlater.store.SendLaterRepository;
import com.voice2sms.sendlater.voice.logic.VoiceBubbleParser;
import com.voice2sms.sendlater.voice.logic.VoiceScreenSignature;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Send Later on the native Google Voice thread screen (A3: part of GeminiSmsInterceptService).
 *
 * <p>Event path (R40): {@link #onEvent} only filters to WINDOW_STATE / WINDOW_CONTENT changes and
 * (re)posts one coalescing 250 ms debounce on the main Handler. The debounced refresh skips all
 * work while the screen is off or Send Later is disabled, re-acquires the R8 nodes by view id
 * (never a tree walk), classifies the screen (R2), and shows the edge tab (R1) and the chip
 * (R17, R19) from {@link VoiceOverlayState}. Chip data comes from an in-memory {@link PendingIndex}
 * rebuilt off the main thread on ACTION_STATE_CHANGED / ACTION_SCHEDULED, never a query per event.
 *
 * <p>Tab tap: snapshot (R3), recipient candidates (R4), sheet via ACTION_CAPTURE. ACTION_SCHEDULED
 * for that capture: {@link VoiceDraftFlow} verifies the record, re-checks title and draft (R7/OD7),
 * clears the draft (R5, D13) and offers Undo for 10 s (R6). Chip tap: edit or filtered Upcoming
 * (R18). While a thread with a holdIfReplied message is open, new inbound bubbles go to
 * {@link SendLaterEngine#onInboundEvent} (R33). Never logs Voice content (I7).
 */
public final class VoiceOverlayController {

    private static final String TAG = "Voice2SMS-SLV";

    /** Debounce of the screen refresh after Voice events. */
    static final long DEBOUNCE_MS = 250L;
    /** Confirmation and Undo show for this long (R5, R6). */
    static final long CONFIRM_MS = 10_000L;
    /** How long ACTION_SCHEDULED waits for the Voice thread to be back in front after the sheet. */
    static final long SCHEDULED_WAIT_MS = 3_000L;
    /** A capture older than this no longer matches an ACTION_SCHEDULED broadcast. */
    static final long CAPTURE_MATCH_MS = 30 * 60 * 1000L;
    private static final int MAX_REPORTED = 200;

    /** One background thread for the repository, contacts and the draft flow. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sendlater-voice");
        t.setDaemon(true);
        return t;
    });

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = this::refresh;
    private final Runnable scheduledTimeout = this::onScheduledTimeout;
    private final Runnable hideToastRunnable = this::hideToast;

    // Main thread only.
    private AccessibilityService service;
    private Context app;
    private ScreenNodes nodes;
    private OverlayWindows windows;
    private RepositoryRecords records;
    private VoiceDraftFlow flow;
    private boolean receiverRegistered;
    private PendingIndex index = PendingIndex.empty();
    private PendingIndex.Entry openEntry;
    private VoiceDraftSnapshot capture;
    private long captureAt;
    private PendingScheduled scheduled;
    private PendingScheduled undo;
    private final Set<String> reported = new HashSet<>();

    private static final class PendingScheduled {
        final VoiceDraftSnapshot snapshot;
        final long id;
        final String label;
        final boolean clear;

        PendingScheduled(VoiceDraftSnapshot snapshot, long id, String label, boolean clear) {
            this.snapshot = snapshot;
            this.id = id;
            this.label = label;
            this.clear = clear;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** From onServiceConnected. */
    public void attach(AccessibilityService s) {
        if (s == null) return;
        if (service != null) detach();
        service = s;
        app = s.getApplicationContext();
        nodes = new ScreenNodes(s);
        windows = new OverlayWindows(s, this::onTabTap, this::onChipTap);
        records = new RepositoryRecords(app);
        flow = new VoiceDraftFlow(nodes, records);
        OnScreenThreadSource.INSTANCE.bind(nodes);
        OnScreenThreadSource.registerOnce();
        IntentFilter f = new IntentFilter();
        f.addAction(SendLaterIntents.ACTION_SCHEDULED);
        f.addAction(SendLaterIntents.ACTION_STATE_CHANGED);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            ContextCompat.registerReceiver(s, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "receiver registration failed: " + e.getClass());
        }
        reloadIndex();
    }

    /** From onDestroy: removes every overlay and stops listening. */
    public void detach() {
        main.removeCallbacks(refreshRunnable);
        main.removeCallbacks(scheduledTimeout);
        main.removeCallbacks(hideToastRunnable);
        if (windows != null) windows.hideAll();
        OnScreenThreadSource.INSTANCE.setOpen(null);
        OnScreenThreadSource.INSTANCE.bind(null);
        if (receiverRegistered && service != null) {
            try {
                service.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // already gone
            }
        }
        receiverRegistered = false;
        service = null;
        windows = null;
        nodes = null;
        flow = null;
        records = null;
        openEntry = null;
        capture = null;
        scheduled = null;
        undo = null;
    }

    /** From onInterrupt: removes the overlays, keeps listening. */
    public void hideOverlays() {
        if (windows != null) windows.hideAll();
        OnScreenThreadSource.INSTANCE.setOpen(null);
    }

    /** True while any overlay window is up (the service then forwards other packages' window changes). */
    public boolean isShowing() {
        return windows != null && windows.isShowing();
    }

    // ------------------------------------------------------------------ event path (R40)

    /** A Google Voice accessibility event: debounce only, no node or repository work. */
    public void onEvent(AccessibilityEvent event) {
        if (service == null || event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        main.removeCallbacks(refreshRunnable);
        main.postDelayed(refreshRunnable, DEBOUNCE_MS);
    }

    /** Another package's window came to the front while an overlay is up: re-check now. */
    public void onForeignEvent(AccessibilityEvent event) {
        if (service == null || event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // Our own overlay windows must not re-trigger the refresh that placed them.
        CharSequence pkg = event.getPackageName();
        if (pkg != null && app.getPackageName().contentEquals(pkg)) return;
        main.removeCallbacks(refreshRunnable);
        main.post(refreshRunnable);
    }

    private void scheduleRefresh() {
        main.removeCallbacks(refreshRunnable);
        main.postDelayed(refreshRunnable, DEBOUNCE_MS);
    }

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    /** Debounced, main thread. */
    private void refresh() {
        if (service == null) return;
        if (!isScreenOn()) {
            hideOverlays();
            return;
        }
        if (!SendLaterSettings.isEnabled(app)) {
            hideOverlays();
            openEntry = null;
            return;
        }
        ScreenNodes.Reading r = nodes.read();
        if (r == null) {
            // Voice is not in front: package changed, remove everything.
            windows.hideAll();
            openEntry = null;
            OnScreenThreadSource.INSTANCE.setOpen(null);
            return;
        }
        boolean high = r.level == VoiceScreenSignature.Level.HIGH;
        boolean group = !high || VoiceOverlayState.isGroupTitle(r.title);
        PendingIndex.Entry entry = high && !group ? index.lookup(r.title) : null;
        VoiceOverlayState st = VoiceOverlayState.compute(r.level,
                r.draft != null && !r.draft.isEmpty(), group, true, entry != null ? entry.total() : 0);
        openEntry = st.isChipVisible() ? entry : null;
        String chipLabel = st.isChipVisible() ? entry.chipLabel(TimeZone.getDefault()) : null;
        windows.update(st.isTabVisible(), chipLabel, r.anchor);
        OnScreenThreadSource.INSTANCE.setOpen(high && !group
                ? new OnScreenThreadSource.OpenThread(r.title, r.atNewest) : null);

        if (high && scheduled != null) runScheduled();
        if (entry != null && entry.hasHoldWatch()) earlyHold(entry);
    }

    // ------------------------------------------------------------------ chip state (R17)

    private void reloadIndex() {
        final Context c = app;
        if (c == null) return;
        WORKER.execute(() -> {
            PendingIndex built;
            if (!SendLaterSettings.isEnabled(c)) {
                built = PendingIndex.empty();
            } else {
                try {
                    SendLaterRepository repo = SendLaterRepository.get(c);
                    built = PendingIndex.build(repo.allPending(), repo.allHeld());
                } catch (RuntimeException e) {
                    Log.w(TAG, "pending index unavailable: " + e.getClass());
                    built = PendingIndex.empty();
                }
            }
            final PendingIndex ready = built;
            main.post(() -> {
                if (service == null) return;
                index = ready;
                scheduleRefresh();
            });
        });
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                hideOverlays();
            } else if (SendLaterIntents.ACTION_STATE_CHANGED.equals(action)) {
                reloadIndex();
            } else if (SendLaterIntents.ACTION_SCHEDULED.equals(action)) {
                reloadIndex();
                onScheduledBroadcast(intent);
            }
        }
    };

    // ------------------------------------------------------------------ tab tap (R3, R4)

    private void onTabTap() {
        if (service == null) return;
        final String title = nodes.readTitle();
        if (title == null || VoiceOverlayState.isGroupTitle(title)) return;
        final Context c = app;
        WORKER.execute(() -> {
            final List<String> contacts = CandidateLookup.contacts(c, title);
            final List<String> directory = CandidateLookup.directory(c, title);
            main.post(() -> openSheet(title, contacts, directory));
        });
    }

    private void openSheet(String title, List<String> contacts, List<String> directory) {
        if (service == null) return;
        VoiceDraftSnapshot snap = VoiceDraftSnapshot.capture(nodes, System.currentTimeMillis(),
                TimeZone.getDefault(), contacts, directory);
        // The candidates were looked up for `title`: drop the tap when the thread changed meanwhile.
        if (snap == null || !title.equals(snap.getDisplayName())) return;
        List<String> cands = snap.getCandidates();
        Intent i = new Intent(SendLaterIntents.ACTION_CAPTURE)
                .setPackage(app.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SendLaterIntents.EXTRA_SOURCE, ScheduledMessage.Source.NATIVE_GV.name())
                .putExtra(SendLaterIntents.EXTRA_DISPLAY_NAME, snap.getDisplayName())
                .putExtra(SendLaterIntents.EXTRA_DRAFT, snap.getDraftText())
                .putExtra(SendLaterIntents.EXTRA_CANDIDATES, cands.toArray(new String[0]))
                .putExtra(SendLaterIntents.EXTRA_WATERMARK_QUALITY, snap.getWatermark().getQuality().name())
                .putExtra(SendLaterIntents.EXTRA_WATERMARK_CAPTURED_AT, snap.getWatermark().getCapturedAtDevice());
        if (snap.getE164() != null) i.putExtra(SendLaterIntents.EXTRA_E164, snap.getE164());
        if (snap.getWatermark().getNewestTs() != null) {
            i.putExtra(SendLaterIntents.EXTRA_WATERMARK_NEWEST_TS, snap.getWatermark().getNewestTs().longValue());
        }
        if (snap.getWatermark().getBubbleHash() != null) {
            i.putExtra(SendLaterIntents.EXTRA_WATERMARK_BUBBLE_HASH, snap.getWatermark().getBubbleHash());
        }
        capture = snap;
        captureAt = SystemClock.elapsedRealtime();
        if (start(i)) windows.hideTabAndChip();
    }

    private boolean start(Intent i) {
        try {
            service.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no Send Later activity for " + i.getAction());
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "Send Later activity start failed: " + e.getClass());
            return false;
        }
    }

    // ------------------------------------------------------------------ chip tap (R18)

    private void onChipTap() {
        PendingIndex.Entry e = openEntry;
        if (service == null || e == null) return;
        Intent i;
        long only = e.singleMessageId();
        if (only > 0) {
            i = new Intent(SendLaterIntents.ACTION_EDIT).putExtra(SendLaterIntents.EXTRA_MESSAGE_ID, only);
        } else if (e.total() > 1) {
            i = new Intent(SendLaterIntents.ACTION_UPCOMING);
            if (e.e164() != null) i.putExtra(SendLaterIntents.EXTRA_E164, e.e164());
        } else {
            return;
        }
        i.setPackage(app.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(i);
    }

    // ------------------------------------------------------------------ scheduled: clear + undo (R5-R7)

    private void onScheduledBroadcast(Intent in) {
        if (!ScheduledMessage.Source.NATIVE_GV.name().equals(in.getStringExtra(SendLaterIntents.EXTRA_SOURCE))) return;
        long id = in.getLongExtra(SendLaterIntents.EXTRA_MESSAGE_ID, -1L);
        VoiceDraftSnapshot snap = capture;
        if (id <= 0 || snap == null || SystemClock.elapsedRealtime() - captureAt > CAPTURE_MATCH_MS) return;
        String e164 = in.getStringExtra(SendLaterIntents.EXTRA_E164);
        List<String> cands = snap.getCandidates();
        // With candidates, the sheet's number must be one of them; with none, the sheet asked for it.
        if (!cands.isEmpty() && (e164 == null || !cands.contains(e164))) return;
        capture = null;
        scheduled = new PendingScheduled(snap, id, labelOf(in), SendLaterSettings.clearDraft(app));
        // The sheet is still in front: run once the Voice thread is back, or after the wait.
        main.removeCallbacks(scheduledTimeout);
        main.postDelayed(scheduledTimeout, SCHEDULED_WAIT_MS);
        scheduleRefresh();
    }

    private void onScheduledTimeout() {
        if (scheduled != null) runScheduled();
    }

    private void runScheduled() {
        final PendingScheduled p = scheduled;
        scheduled = null;
        main.removeCallbacks(scheduledTimeout);
        if (p == null || service == null) return;
        if (!p.clear) {
            showToast(service.getString(R.string.sendlater_overlay_scheduled, p.label), p);
            return;
        }
        final VoiceDraftFlow f = flow;
        WORKER.execute(() -> {
            final VoiceDraftFlow.ScheduledOutcome o = f.onScheduled(p.snapshot, p.id);
            main.post(() -> showOutcome(p, o));
        });
    }

    private void showOutcome(PendingScheduled p, VoiceDraftFlow.ScheduledOutcome o) {
        if (service == null) return;
        switch (o) {
            case CLEARED:
                showToast(service.getString(R.string.sendlater_overlay_scheduled, p.label), p);
                break;
            case CLEAR_FAILED:
                showToast(service.getString(R.string.sendlater_overlay_clear_failed), p);
                break;
            case CONVERSATION_CHANGED:
                showToast(service.getString(R.string.sendlater_overlay_changed), null);
                break;
            case NOT_PERSISTED:
            default:
                break;
        }
    }

    private void showToast(String text, final PendingScheduled undoable) {
        undo = undoable;
        windows.showToast(text, undoable == null ? null : () -> onUndo(undoable));
        main.removeCallbacks(hideToastRunnable);
        main.postDelayed(hideToastRunnable, CONFIRM_MS);
    }

    private void hideToast() {
        undo = null;
        if (windows != null) windows.hideToast();
    }

    private void onUndo(final PendingScheduled p) {
        if (service == null || undo != p) return;
        undo = null;
        main.removeCallbacks(hideToastRunnable);
        final VoiceDraftFlow f = flow;
        final RepositoryRecords rec = records;
        WORKER.execute(() -> {
            final VoiceDraftFlow.UndoOutcome o = f.undo(p.snapshot, p.id);
            final boolean canceled = rec.lastCancelApplied();
            main.post(() -> {
                if (service == null) return;
                int res = o == VoiceDraftFlow.UndoOutcome.RESTORED ? R.string.sendlater_overlay_undone
                        : canceled ? R.string.sendlater_overlay_undone_kept : R.string.sendlater_overlay_undo_failed;
                windows.showToast(service.getString(res), null);
                main.removeCallbacks(hideToastRunnable);
                main.postDelayed(hideToastRunnable, CONFIRM_MS / 2);
            });
        });
    }

    private static String labelOf(Intent intent) {
        String label = intent.getStringExtra(SendLaterIntents.EXTRA_LABEL);
        if (label != null && !label.trim().isEmpty()) return label;
        long at = intent.getLongExtra(SendLaterIntents.EXTRA_SCHEDULED_FOR, -1L);
        if (at <= 0) return "later";
        SimpleDateFormat f = new SimpleDateFormat("EEE h:mm a", Locale.getDefault());
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(at));
    }

    // ------------------------------------------------------------------ early hold (R33)

    /** Only called when the open thread has a holdIfReplied message (entry.hasHoldWatch()). */
    private void earlyHold(PendingIndex.Entry entry) {
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        List<ThreadEvent> rows = new ArrayList<>();
        for (String d : nodes.readBubbleDescriptions()) rows.add(VoiceBubbleParser.parse(d, now, zone));
        for (PendingIndex.HoldWatch w : entry.holdWatches()) {
            for (ThreadEvent ev : EarlyHoldFilter.newInbound(rows, w.watermark, w.createdAt)) {
                String k = w.e164 + "|" + ev.getTimeMs() + "|" + ev.getTextHash();
                if (reported.size() > MAX_REPORTED) reported.clear();
                if (reported.add(k)) SendLaterEngine.onInboundEvent(app, w.e164, ev);
            }
        }
    }
}
