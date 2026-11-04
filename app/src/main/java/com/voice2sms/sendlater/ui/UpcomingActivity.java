package com.voice2sms.sendlater.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.voice2sms.R;
import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.engine.SendLaterEngine;
import com.voice2sms.sendlater.store.SendLaterRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Upcoming (R35): Pending (time, name, first line), Held (reason) and Recent (last 30 days).
 * Row actions: change time (sheet edit mode via ACTION_EDIT), cancel and send now (engine
 * "Send anyway" rules). Launched from Settings, or with ACTION_UPCOMING plus an optional
 * EXTRA_E164 that limits the list to one conversation. Reloads on ACTION_STATE_CHANGED.
 *
 * <p>Repository reads run on a background executor; nothing here logs message text or names.
 */
public class UpcomingActivity extends AppCompatActivity {

    private static final String TAG = "SendLaterUpcoming";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String filterE164;
    private boolean receiverRegistered;

    private LinearLayout list;
    private TextView empty;
    private View filterBar;
    private View disabledBanner;
    /** SCHEDULED ids the engine already reminded about (UI M5); main thread only. */
    private Set<Long> reminded = new HashSet<>();

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && SendLaterIntents.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                reload();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upcoming);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.sendlater_upcoming_title);
        }
        list = findViewById(R.id.sendlater_upcoming_list);
        empty = findViewById(R.id.sendlater_upcoming_empty);
        filterBar = findViewById(R.id.sendlater_upcoming_filter_bar);
        disabledBanner = findViewById(R.id.sendlater_upcoming_disabled);
        Button showAll = findViewById(R.id.sendlater_upcoming_show_all);
        showAll.setOnClickListener(v -> {
            filterE164 = null;
            reload();
        });
        readFilter(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readFilter(intent);
        reload();
    }

    private void readFilter(Intent intent) {
        String f = intent == null ? null : intent.getStringExtra(SendLaterIntents.EXTRA_E164);
        filterE164 = (f == null || f.trim().isEmpty()) ? null : f.trim();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(SendLaterIntents.ACTION_STATE_CHANGED);
        ContextCompat.registerReceiver(this, stateReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ loading

    private void reload() {
        if (isFinishing() || isDestroyed() || io.isShutdown()) return;
        final Context app = getApplicationContext();
        final String filter = filterE164;
        try {
            io.execute(() -> {
                UpcomingSections sections;
                boolean enabled;
                Set<Long> remindedIds = new HashSet<>();
                try {
                    long now = System.currentTimeMillis();
                    SendLaterRepository repo = SendLaterRepository.get(app);
                    List<ScheduledMessage> all = new ArrayList<>();
                    addAll(all, repo.allPending());
                    addAll(all, repo.inFlight());
                    addAll(all, repo.allHeld());
                    addAll(all, repo.recent(now));
                    sections = UpcomingSections.build(all, filter, now);
                    remindedIds = SendLaterEngine.remindedIds(app, sections.getPending());
                    enabled = SendLaterSettings.isEnabled(app);
                } catch (RuntimeException e) {
                    Log.w(TAG, "load failed: " + e.getClass().getSimpleName());
                    sections = null;
                    enabled = true;
                }
                final UpcomingSections result = sections;
                final boolean on = enabled;
                final Set<Long> marks = remindedIds;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    reminded = marks;
                    render(result, filter, on);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // activity is going away
        }
    }

    private static void addAll(List<ScheduledMessage> out, List<ScheduledMessage> in) {
        if (in != null) out.addAll(in);
    }

    // ------------------------------------------------------------------ rendering

    private void render(UpcomingSections s, String filter, boolean enabled) {
        list.removeAllViews();
        disabledBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        filterBar.setVisibility(filter != null ? View.VISIBLE : View.GONE);
        if (s == null) {
            empty.setText(R.string.sendlater_upcoming_load_failed);
            empty.setVisibility(View.VISIBLE);
            return;
        }
        empty.setText(R.string.sendlater_upcoming_empty);
        empty.setVisibility(s.isEmpty() ? View.VISIBLE : View.GONE);
        section(R.string.sendlater_upcoming_section_pending, s.getPending(), enabled);
        section(R.string.sendlater_upcoming_section_held, s.getHeld(), enabled);
        section(R.string.sendlater_upcoming_section_recent, s.getRecent(), enabled);
    }

    private void section(int titleRes, List<ScheduledMessage> rows, boolean enabled) {
        if (rows.isEmpty()) return;
        LayoutInflater inf = getLayoutInflater();
        TextView header = (TextView) inf.inflate(R.layout.sendlater_upcoming_header, list, false);
        header.setText(titleRes);
        list.addView(header);
        for (ScheduledMessage m : rows) list.addView(row(inf, m, enabled));
    }

    private View row(LayoutInflater inf, final ScheduledMessage m, boolean enabled) {
        View v = inf.inflate(R.layout.sendlater_upcoming_row, list, false);
        ((TextView) v.findViewById(R.id.sendlater_upcoming_row_time)).setText(formatTime(m.getScheduledFor()));
        ((TextView) v.findViewById(R.id.sendlater_upcoming_row_name)).setText(nameOf(m));
        ((TextView) v.findViewById(R.id.sendlater_upcoming_row_text))
                .setText(UpcomingSections.firstLine(m.getText()));

        TextView status = v.findViewById(R.id.sendlater_upcoming_row_status);
        String statusText = statusText(m);
        if (statusText != null) {
            status.setText(statusText);
            status.setVisibility(View.VISIBLE);
        }

        Set<UpcomingSections.RowAction> actions = visibleActions(m.getStatus(), enabled);
        View bar = v.findViewById(R.id.sendlater_upcoming_row_actions);
        bar.setVisibility(actions.isEmpty() ? View.GONE : View.VISIBLE);
        bind(v.findViewById(R.id.sendlater_upcoming_row_change),
                actions.contains(UpcomingSections.RowAction.CHANGE_TIME), () -> changeTime(m));
        bind(v.findViewById(R.id.sendlater_upcoming_row_cancel),
                actions.contains(UpcomingSections.RowAction.CANCEL), () -> confirmCancel(m));
        bind(v.findViewById(R.id.sendlater_upcoming_row_send),
                actions.contains(UpcomingSections.RowAction.SEND_NOW), () -> confirmSendNow(m));
        return v;
    }

    /**
     * OD24: while Send Later is off the list is read-only except Cancel; Change time and Send now
     * come back when it is turned on.
     */
    static Set<UpcomingSections.RowAction> visibleActions(Status status, boolean enabled) {
        Set<UpcomingSections.RowAction> all = UpcomingSections.actionsFor(status);
        if (enabled) return all;
        Set<UpcomingSections.RowAction> out = EnumSet.noneOf(UpcomingSections.RowAction.class);
        if (all.contains(UpcomingSections.RowAction.CANCEL)) out.add(UpcomingSections.RowAction.CANCEL);
        return Collections.unmodifiableSet(out);
    }

    private static void bind(View button, boolean visible, final Runnable action) {
        button.setVisibility(visible ? View.VISIBLE : View.GONE);
        button.setOnClickListener(visible ? x -> action.run() : null);
    }

    private String nameOf(ScheduledMessage m) {
        String n = m.getRecipientDisplayName();
        if (n != null && !n.trim().isEmpty()) return n.trim();
        String e = m.getRecipientE164();
        if (e != null && !e.isEmpty()) return e;
        return getString(R.string.sendlater_upcoming_unknown_recipient);
    }

    private String formatTime(long ms) {
        return DateUtils.formatDateTime(this, ms, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_ALL);
    }

    private String statusText(ScheduledMessage m) {
        Status st = m.getStatus();
        if (UpcomingSections.isWaitingForUser(m, reminded)) {
            return getString(R.string.sendlater_upcoming_status_reminded);
        }
        if (st == Status.HELD) return getString(reasonRes(m.getHoldReason()));
        if (st == Status.IN_FLIGHT) return getString(R.string.sendlater_upcoming_sending);
        if (st == Status.SENT_VERIFIED) return getString(R.string.sendlater_upcoming_status_sent);
        if (st == Status.CANCELED) return getString(R.string.sendlater_upcoming_status_canceled);
        if (st == Status.EXPIRED) return getString(R.string.sendlater_upcoming_status_expired);
        if (st == Status.SELF_PREEMPTED) return getString(R.string.sendlater_upcoming_status_self_preempted);
        if (st == Status.FAILED) return getString(R.string.sendlater_upcoming_status_failed);
        return null;
    }

    static int reasonRes(HoldReason r) {
        if (r == null) return R.string.sendlater_upcoming_reason_unknown;
        switch (r) {
            case REPLIED: return R.string.sendlater_upcoming_reason_replied;
            case REACHED_OUT: return R.string.sendlater_upcoming_reason_reached_out;
            case OUTBOUND_ONLY: return R.string.sendlater_upcoming_reason_outbound_only;
            case UNVERIFIED: return R.string.sendlater_upcoming_reason_unverified;
            case LOCKED: return R.string.sendlater_upcoming_reason_locked;
            default: return R.string.sendlater_upcoming_reason_unknown;
        }
    }

    // ------------------------------------------------------------------ actions

    /** Change time: the sheet in edit mode (it re-checks isEditable itself). */
    private void changeTime(ScheduledMessage m) {
        Intent edit = new Intent(SendLaterIntents.ACTION_EDIT)
                .setPackage(getPackageName())
                .putExtra(SendLaterIntents.EXTRA_MESSAGE_ID, m.getId());
        try {
            startActivity(edit);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "no sheet: " + e.getClass().getSimpleName());
        }
    }

    private void confirmCancel(final ScheduledMessage m) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.sendlater_upcoming_confirm_cancel)
                .setPositiveButton(R.string.sendlater_upcoming_cancel, (d, w) -> {
                    SendLaterEngine.onCancel(getApplicationContext(), m.getId());
                    reloadSoon();
                })
                .setNegativeButton(R.string.sendlater_upcoming_keep, null)
                .show();
    }

    /** Send now = the engine's "Send anyway" rules (stale rows are dropped there). */
    private void confirmSendNow(final ScheduledMessage m) {
        boolean mayHaveSent = m.getStatus() == Status.HELD && m.getHoldReason() == HoldReason.UNVERIFIED;
        new AlertDialog.Builder(this)
                .setMessage(mayHaveSent ? R.string.sendlater_upcoming_confirm_send_unverified
                        : R.string.sendlater_upcoming_confirm_send)
                .setPositiveButton(R.string.sendlater_upcoming_send_now, (d, w) -> {
                    SendLaterEngine.onSendAnyway(getApplicationContext(), m.getId());
                    reloadSoon();
                })
                .setNegativeButton(R.string.sendlater_upcoming_keep, null)
                .show();
    }

    /** The engine broadcasts ACTION_STATE_CHANGED; this covers a change it does not announce. */
    private void reloadSoon() {
        if (list != null) list.postDelayed(this::reload, 800);
    }
}
