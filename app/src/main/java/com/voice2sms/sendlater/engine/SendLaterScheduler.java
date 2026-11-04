package com.voice2sms.sendlater.engine;

import android.content.Context;

import com.voice2sms.sendlater.domain.LadderEvaluator;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import java.util.List;

/**
 * Alarm registration (R22, R24). Exact alarms ({@code setExactAndAllowWhileIdle}) for T-5 (last
 * call) and T (due) when {@code canScheduleExactAlarms()} allows it, inexact
 * ({@code setAndAllowWhileIdle}) otherwise or when the exact call throws SecurityException.
 * USE_EXACT_ALARM is never declared. Store calls are synchronous: call off the main thread.
 */
public final class SendLaterScheduler {

    /** The last call fires this long before the due time (R24). */
    public static final long LAST_CALL_LEAD_MS = 5 * 60_000L;

    private final AlarmSink alarms;
    private final EngineStore store;
    private final EngineNotifier notifier;
    private final EngineClock clock;
    private final EngineMarks marks;

    public SendLaterScheduler(AlarmSink alarms, EngineStore store, EngineNotifier notifier, EngineClock clock) {
        this(alarms, store, notifier, clock, new EngineMarks.InMemory());
    }

    public SendLaterScheduler(AlarmSink alarms, EngineStore store, EngineNotifier notifier, EngineClock clock,
                              EngineMarks marks) {
        this.alarms = alarms;
        this.store = store;
        this.notifier = notifier;
        this.clock = clock;
        this.marks = marks != null ? marks : new EngineMarks.InMemory();
    }

    /** The scheduler wired to the engine's production (or installed test) dependencies. */
    public static SendLaterScheduler get(Context context) {
        return SendLaterEngine.scheduler(context);
    }

    /**
     * Registers T-5 and T for {@code m}. Less than 5 min ahead: the last call posts now and only T
     * is registered (R24). Only rung R1 has a last call (R0 is silent; R2/R3 never auto-send).
     * This is an explicit (re)schedule, so it clears the engine's "reminded" mark (I6).
     */
    public void registerAlarms(ScheduledMessage m) {
        if (m == null) return;
        marks.setReminded(m.getId(), false);
        registerTimes(m);
    }

    private void registerTimes(ScheduledMessage m) {
        long now = clock.nowMs();
        long due = m.getScheduledFor();
        long lastCallAt = due - LAST_CALL_LEAD_MS;
        boolean wantsLastCall = m.getRung() == null || m.getRung() == Rung.R1;
        if (wantsLastCall && lastCallAt > now) {
            set(AlarmKind.LAST_CALL, m.getId(), lastCallAt);
        } else {
            alarms.cancel(AlarmKind.LAST_CALL, m.getId());
            if (wantsLastCall && due > now) notifier.lastCall(m);
        }
        set(AlarmKind.DUE, m.getId(), due);
    }

    /** Cancels the last-call, due and retry alarms of {@code id} (not the expiry alarm). */
    public void cancelAlarms(long id) {
        alarms.cancel(AlarmKind.LAST_CALL, id);
        alarms.cancel(AlarmKind.DUE, id);
        alarms.cancel(AlarmKind.RETRY, id);
    }

    /** Cancels every alarm of {@code id}, including the expiry alarm (terminal states). */
    public void cancelAll(long id) {
        cancelAlarms(id);
        alarms.cancel(AlarmKind.EXPIRE, id);
    }

    /**
     * Boot, time or time-zone change, exact-alarm permission change: re-registers T-5/T for every
     * SCHEDULED message that was not reminded, and only the expiry alarm of every reminded or
     * HELD one. A reminded message never gets a due alarm back: it must not auto-send after the
     * user was told to send it by hand (I6).
     */
    public void reregisterAll(Context context) {
        List<ScheduledMessage> pending = store.allPending();
        if (pending != null) {
            for (ScheduledMessage m : pending) {
                if (marks.isReminded(m.getId())) {
                    registerExpiry(m);
                } else {
                    registerTimes(m);
                }
            }
        }
        List<ScheduledMessage> held = store.allHeld();
        if (held != null) for (ScheduledMessage m : held) registerExpiry(m);
    }

    /** Inexact expiry alarm at scheduledFor + 24 h for a HELD or reminded message (R29). */
    public void registerExpiry(ScheduledMessage m) {
        alarms.setInexact(AlarmKind.EXPIRE, m.getId(), m.getScheduledFor() + LadderEvaluator.EXPIRY_MS);
    }

    /** Re-runs the ladder at {@code atMs} (a not-yet-due WAIT): exact when permitted. */
    void registerDueAt(long id, long atMs) {
        set(AlarmKind.DUE, id, atMs);
    }

    /** Inexact retry at the budget deadline of a WAIT(NO_NETWORK) (R27). */
    void registerRetry(long id, long atMs) {
        alarms.setInexact(AlarmKind.RETRY, id, atMs);
    }

    private void set(AlarmKind kind, long id, long atMs) {
        if (alarms.canScheduleExact()) {
            try {
                alarms.setExact(kind, id, atMs);
                return;
            } catch (SecurityException revoked) {
                // Permission revoked between the check and the call: fall back to inexact.
            }
        }
        alarms.setInexact(kind, id, atMs);
    }
}
