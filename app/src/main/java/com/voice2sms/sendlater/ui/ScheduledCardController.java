package com.voice2sms.sendlater.ui;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.store.SendLaterRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Drives the launcher page's "Scheduled messages" card (R35a, D47, OD37): reads SCHEDULED and
 * HELD records from {@link SendLaterRepository} on its own single-thread executor (never on the
 * main thread), builds the {@link ScheduledCardModel} there and posts it to the card. Reloads in
 * {@link #onResume()} and on every {@link SendLaterIntents#ACTION_STATE_CHANGED} while resumed.
 *
 * <p>Taps: a SCHEDULED row opens the sheet in edit mode ({@link SendLaterIntents#ACTION_EDIT},
 * package-scoped), a HELD row and "See all (N)" open {@link UpcomingActivity}, the Send Later
 * state line runs {@code openSendLaterGroup}.
 */
public final class ScheduledCardController implements ScheduledCardPreference.Listener {

    private static final String TAG = "SendLaterHomeCard";

    private final Fragment fragment;
    private final ScheduledCardPreference card;
    private final Runnable openSendLaterGroup;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && SendLaterIntents.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                reload();
            }
        }
    };

    public ScheduledCardController(Fragment fragment, ScheduledCardPreference card,
                                   Runnable openSendLaterGroup) {
        this.fragment = fragment;
        this.card = card;
        this.openSendLaterGroup = openSendLaterGroup;
        card.setListener(this);
    }

    /** Call from the fragment's onResume: listen for record changes and reload. */
    public void onResume() {
        Context c = fragment.getContext();
        if (c == null) return;
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter(SendLaterIntents.ACTION_STATE_CHANGED);
            ContextCompat.registerReceiver(c, stateReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        reload();
    }

    /** Call from the fragment's onPause. */
    public void onPause() {
        Context c = fragment.getContext();
        if (receiverRegistered && c != null) {
            try {
                c.unregisterReceiver(stateReceiver);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
        }
        receiverRegistered = false;
    }

    /** Call from the fragment's onDestroy. */
    public void onDestroy() {
        onPause();
        io.shutdownNow();
    }

    /** Reloads the card (off the main thread). */
    public void reload() {
        Context c = fragment.getContext();
        if (c == null || io.isShutdown()) return;
        final Context app = c.getApplicationContext();
        try {
            io.execute(() -> {
                ScheduledCardModel model;
                boolean enabled;
                try {
                    SendLaterRepository repo = SendLaterRepository.get(app);
                    List<ScheduledMessage> live = new ArrayList<>();
                    List<ScheduledMessage> pending = repo.allPending();
                    if (pending != null) live.addAll(pending);
                    List<ScheduledMessage> held = repo.allHeld();
                    if (held != null) live.addAll(held);
                    model = ScheduledCardModel.build(live);
                    enabled = SendLaterSettings.isEnabled(app);
                } catch (RuntimeException e) {
                    Log.w(TAG, "load failed: " + e.getClass().getSimpleName());
                    model = null;
                    enabled = true;
                }
                final ScheduledCardModel result = model;
                final boolean on = enabled;
                main.post(() -> {
                    if (!fragment.isAdded() || fragment.getContext() == null) return;
                    card.show(result, on);
                });
            });
        } catch (RejectedExecutionException ignored) {
            // the page is going away
        }
    }

    // ------------------------------------------------------------------ taps

    @Override
    public void onRowTapped(ScheduledMessage m) {
        Context c = fragment.getContext();
        if (c == null || m == null) return;
        if (ScheduledCardModel.tapFor(m) == ScheduledCardModel.Tap.EDIT) {
            Intent edit = new Intent(SendLaterIntents.ACTION_EDIT)
                    .setPackage(c.getPackageName())
                    .putExtra(SendLaterIntents.EXTRA_MESSAGE_ID, m.getId());
            if (start(edit)) return;
        }
        openUpcoming();
    }

    @Override
    public void onSeeAllTapped() {
        openUpcoming();
    }

    @Override
    public void onSwitchStateTapped() {
        if (openSendLaterGroup != null) openSendLaterGroup.run();
    }

    private void openUpcoming() {
        Context c = fragment.getContext();
        if (c == null) return;
        start(new Intent(c, UpcomingActivity.class));
    }

    private boolean start(Intent i) {
        try {
            fragment.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "cannot open: " + e.getClass().getSimpleName());
            return false;
        }
    }
}
