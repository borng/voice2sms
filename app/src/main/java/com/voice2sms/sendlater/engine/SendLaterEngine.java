package com.voice2sms.sendlater.engine;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import com.voice2sms.sendlater.battery.BatteryWarning;
import com.voice2sms.sendlater.domain.Classification;
import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.Decision;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LadderEvaluator;
import com.voice2sms.sendlater.domain.LatenessBudget;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.SessionState;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEventClassifier;
import com.voice2sms.sendlater.domain.ThreadEventSource;
import com.voice2sms.sendlater.domain.Watermark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Send Later engine (R22-R31, R33, I2, I5, I6): loads a message, reads its thread from the
 * registered {@link ThreadEventSource}s, runs {@link LadderEvaluator} and applies the Decision.
 * The engine is the ONLY writer of send status and Send Later notifications; the sender reports
 * back through {@link #onSendOutcome}.
 *
 * <p>All entry points are synchronous and serialized on one lock. In the Android wiring a call
 * made on the main thread hops to the engine's single background executor (Room is synchronous);
 * {@link #onProcessStart} always runs on that executor.
 */
public final class SendLaterEngine {

    private static final String TAG = "SendLaterEngine";

    /** Detail the sender passes with SENT_VERIFIED when it saw an inbound after the check (R31). */
    public static final String DETAIL_LATE_REPLY = "late_reply";

    /**
     * "Replied just as your message sent" counts only inbound events timed in
     * [checkTime, sendTime + verify window] (OD23(5)). The engine does not see when a queued
     * attempt really starts (several messages due together are sent one after another, R30), but
     * the sender reports its outcome when its verify window ends, so the outcome's arrival time is
     * that end. The window is therefore [checkTime, outcome time + this slack]; the slack only
     * absorbs clock differences between thread timestamps and the device. A later inbound is an
     * ordinary reply to a sent message.
     */
    static final long LATE_REPLY_WINDOW_MS = 2 * 60_000L;

    /**
     * An outcome arriving more than this long after the thread check is stale (a stuck sender);
     * its window end is capped at checkTime + this. Far above any queue of attempts (about 90 s
     * each), so a queued send is never cut short.
     */
    static final long LATE_REPLY_MAX_SPAN_MS = 30 * 60_000L;

    private static final Object LOCK = new Object();

    private static volatile EngineDeps deps;
    private static volatile SendLaterScheduler scheduler;
    private static volatile ScheduledSender sender;
    private static volatile boolean installedForTest;

    /** Thread-check time of the attempt now IN_FLIGHT, per id (R31 "replied just as it sent"). */
    private static final Map<Long, Long> checkTimes = new HashMap<>();
    /** Extra sources registered by other nodes (production wiring only). */
    private static final List<ThreadEventSource> extraSources = new ArrayList<>();

    private SendLaterEngine() {}

    // ================================================================== wiring

    /** Test seam: with deps installed the engine never touches the Context. */
    static void installForTest(EngineDeps d) {
        synchronized (LOCK) {
            deps = d;
            scheduler = new SendLaterScheduler(d.alarms, d.store, d.notifier, d.conditions, d.marks);
            installedForTest = true;
        }
    }

    /** Clears deps, the sender and in-memory state. */
    static void resetForTest() {
        synchronized (LOCK) {
            deps = null;
            scheduler = null;
            sender = null;
            installedForTest = false;
            checkTimes.clear();
        }
    }

    /** Registers the send path (send-path node). Null restores the default (REMIND(NO_SENDER)). */
    public static void setSender(ScheduledSender s) {
        sender = s;
    }

    /** Adds a thread source for the production wiring (e.g. a future RPC source). Call early. */
    public static void registerSource(ThreadEventSource source) {
        if (source == null) return;
        synchronized (LOCK) {
            extraSources.add(source);
            if (!installedForTest) {
                deps = null; // rebuilt with the new source on next use
                scheduler = null;
            }
        }
    }

    static EngineDeps deps(Context context) {
        EngineDeps d = deps;
        if (d != null) return d;
        synchronized (LOCK) {
            if (deps == null) {
                if (context == null) throw new IllegalStateException("engine not initialized");
                deps = AndroidEngineDeps.create(context.getApplicationContext(),
                        new ArrayList<>(extraSources));
            }
            return deps;
        }
    }

    static SendLaterScheduler scheduler(Context context) {
        EngineDeps d = deps(context);
        SendLaterScheduler s = scheduler;
        if (s != null) return s;
        synchronized (LOCK) {
            if (scheduler == null) scheduler = new SendLaterScheduler(d.alarms, d.store, d.notifier, d.conditions, d.marks);
            return scheduler;
        }
    }

    /** Runs {@code work} on the engine executor, then {@code done} (e.g. PendingResult.finish). */
    static void runInBackground(Context context, final Runnable work, final Runnable done) {
        deps(context).executor.execute(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "background work failed: " + e.getClass().getSimpleName());
            } finally {
                if (done != null) done.run();
            }
        });
    }

    private static void run(Context context, Runnable r) {
        EngineDeps d = deps(context);
        if (d.hopOffMainThread && Looper.myLooper() == Looper.getMainLooper()) {
            runInBackground(context, r, null);
            return;
        }
        synchronized (LOCK) {
            r.run();
        }
    }

    // ================================================================== process start (R23)

    /**
     * Called once from {@code Voice2SmsApplication.onCreate}: on the background executor, resolves
     * IN_FLIGHT rows first (HELD(UNVERIFIED) + "may have already sent", OD27), then sweeps expired HELD/reminded rows,
     * then re-arms every remaining alarm ({@link SendLaterScheduler#reregisterAll}).
     *
     * <p>The re-arm covers what no broadcast reports: a force-stop (user, or Samsung "deep
     * sleeping" apps) and a SCHEDULE_EXACT_ALARM revoke (Android kills the app and drops its exact
     * alarms; PERMISSION_STATE_CHANGED is sent only on a grant) both cancel every alarm the app
     * holds. Any later component start (activity, accessibility service bind, SMS_DELIVER, boot)
     * creates the process and lands here. It is idempotent: the same request codes with
     * FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE, and reminded rows get only their EXPIRE alarm (I6).
     */
    public static void onProcessStart(final Context context) {
        final EngineDeps d;
        try {
            d = deps(context);
        } catch (RuntimeException e) {
            Log.e(TAG, "engine init failed: " + e.getClass().getSimpleName());
            return;
        }
        d.executor.execute(() -> {
            try {
                synchronized (LOCK) {
                    doResolveInFlight(d);
                    // OD24: Send Later off -> only the IN_FLIGHT resolution above; no sweep, no re-arm.
                    if (!enabled(d)) return;
                    doSweepExpired(d);
                    schedulerOf(d).reregisterAll(context);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "process start failed: " + e.getClass().getSimpleName());
            }
        });
    }

    // ================================================================== due time (R25-R27)

    /**
     * The DUE (or RETRY / network-available) alarm for {@code id}. Only SCHEDULED rows that were
     * not reminded act: a reminded message is the user's to send now and never auto-sends (I6).
     */
    public static void onDue(final Context context, final long id) {
        onDue(context, id, false);
    }

    /** The DUE alarm itself fired: also reports how late it was against its target (R39). */
    static void onDueAlarm(final Context context, final long id) {
        onDue(context, id, true);
    }

    private static void onDue(final Context context, final long id, final boolean dueAlarm) {
        run(context, () -> {
            EngineDeps d = deps(context);
            if (!enabled(d)) return; // OD24: off -> nothing sends automatically (alarm or network re-fire)
            ScheduledMessage m = d.store.get(id);
            if (m == null || m.getStatus() != Status.SCHEDULED) return;
            if (d.marks.isReminded(id)) return;
            if (dueAlarm) BatteryWarning.onAlarmLate(context, d.conditions.nowMs() - m.getScheduledFor());
            m = upgradeSessionRung(d, m);
            if (m == null || m.getStatus() != Status.SCHEDULED) return;
            evaluateAndApply(d, context, m);
        });
    }

    /**
     * OD22(4) / D23: a message saved at R3 only because there was no GV web session
     * ({@code rungReason == NO_SESSION}) is evaluated at R1 once a session exists at due time; the
     * stored rung is updated first. Without a session (or when the store refuses) it stays at its
     * stored rung. Returns the message to evaluate (reloaded after an upgrade).
     */
    static ScheduledMessage upgradeSessionRung(EngineDeps d, ScheduledMessage m) {
        if (m.getRung() != Rung.R3 || m.getRungReason() != RemindReason.NO_SESSION) return m;
        if (!hasWebSession(d.conditions.session())) return m;
        if (!d.store.upgradeRung(m.getId(), Rung.R3, Rung.R1)) return m;
        return d.store.get(m.getId());
    }

    /** {@code GvSession.hasWebSession} as the conditions report it: MISSING/EXPIRED mean none. */
    private static boolean hasWebSession(SessionState s) {
        return s == SessionState.READY || s == SessionState.LOCKED_UNSHOWABLE;
    }

    /** The T-5 alarm: last-call notification if the message is still SCHEDULED (R24). */
    public static void onLastCall(final Context context, final long id) {
        run(context, () -> {
            EngineDeps d = deps(context);
            if (!enabled(d)) return;
            ScheduledMessage m = d.store.get(id);
            if (m == null || m.getStatus() != Status.SCHEDULED) return;
            if (d.marks.isReminded(id)) return;
            if (d.conditions.nowMs() >= m.getScheduledFor()) return;
            if (d.marks.isLastCallPosted(id, m.getScheduledFor())) return;
            d.notifier.lastCall(m);
            d.marks.setLastCallPosted(id, m.getScheduledFor());
        });
    }

    /** The +24 h EXPIRE alarm (R29). Early fires re-register. */
    public static void onExpireAlarm(final Context context, final long id) {
        run(context, () -> {
            EngineDeps d = deps(context);
            ScheduledMessage m = d.store.get(id);
            if (m == null) return;
            if (m.getStatus() != Status.SCHEDULED && m.getStatus() != Status.HELD) return;
            if (d.conditions.nowMs() - m.getScheduledFor() >= LadderEvaluator.EXPIRY_MS) {
                expire(d, m);
            } else {
                schedulerOf(d).registerExpiry(m);
            }
        });
    }

    private static void evaluateAndApply(EngineDeps d, Context context, ScheduledMessage m) {
        long now = d.conditions.nowMs();
        List<ThreadEvent> events = readThread(d, m);
        // OD22(1): replies from the (possibly snoozed) watermark, self-preemption from the original.
        Decision decision = LadderEvaluator.evaluate(m, m.getRung(), events, now, d.conditions.session(),
                d.conditions.network(), d.conditions.listenerEnabled(), d.conditions.outboundPolicy(),
                m.getWatermark(), m.getSelfPreemptionBaseline());
        apply(d, context, m, decision, now);
    }

    private static void apply(EngineDeps d, Context context, ScheduledMessage m, Decision decision, long now) {
        long id = m.getId();
        switch (decision.getKind()) {
            case SEND:
                d.networkWaiter.cancel(id);
                startSend(d, context, m, now);
                break;
            case HOLD:
                d.networkWaiter.cancel(id);
                hold(d, m, decision.getHoldReason(), now, false);
                break;
            case REMIND:
                d.networkWaiter.cancel(id);
                remind(d, m, decision.getRemindReason());
                break;
            case CANCEL:
                d.networkWaiter.cancel(id);
                if (m.getStatus() == Status.IN_FLIGHT) {
                    // OD27: a thread read never verifies a send; only the controller's outcome does.
                    checkTimes.remove(id);
                    hold(d, m, HoldReason.UNVERIFIED, now, true);
                } else {
                    selfPreempt(d, m);
                }
                break;
            case WAIT:
                if (decision.getRemindReason() == null) {
                    // Not due yet (an early or stale alarm): never send early, re-arm T.
                    schedulerOf(d).registerDueAt(id, decision.getUntilMs());
                } else {
                    d.networkWaiter.awaitNetwork(id);
                    schedulerOf(d).registerRetry(id, decision.getUntilMs());
                }
                break;
            case EXPIRED:
                d.networkWaiter.cancel(id);
                expire(d, m);
                break;
            default:
                break;
        }
    }

    // ================================================================== effects

    private static void startSend(EngineDeps d, Context context, ScheduledMessage m, long checkTime) {
        ScheduledSender s = sender;
        long id = m.getId();
        if (s == null) {
            remind(d, m, RemindReason.NO_SENDER);
            return;
        }
        if (!d.store.markInFlight(id, ThreadEvent.hashOf(m.getText()))) return;
        d.marks.setReminded(id, false);
        d.marks.clearLastCall(id);
        checkTimes.put(id, checkTime);
        schedulerOf(d).cancelAlarms(id);
        d.broadcaster.stateChanged(id, m.getRecipientE164());
        ScheduledMessage inFlight = d.store.get(id);
        if (inFlight == null) return;
        boolean started;
        try {
            started = s.start(context, inFlight);
        } catch (RuntimeException e) {
            Log.e(TAG, "sender.start threw for id " + id + ": " + e.getClass().getSimpleName());
            started = false;
        }
        if (!started) {
            ScheduledMessage now = d.store.get(id);
            if (now != null && now.getStatus() == Status.IN_FLIGHT) {
                handleOutcome(d, context, now, SendOutcome.CANNOT_SHOW, null);
            }
        }
    }

    private static void hold(EngineDeps d, ScheduledMessage m, HoldReason reason, long now, boolean mayHaveSent) {
        hold(d, m, reason, now, mayHaveSent, true);
    }

    /** {@code armExpiry} false: Send Later is off (OD24); turning it on re-arms HELD expiries. */
    private static void hold(EngineDeps d, ScheduledMessage m, HoldReason reason, long now, boolean mayHaveSent,
                             boolean armExpiry) {
        long id = m.getId();
        if (!d.store.hold(id, reason)) return;
        d.marks.setReminded(id, false);
        d.marks.clearLastCall(id);
        schedulerOf(d).cancelAlarms(id);
        ScheduledMessage held = reload(d, m);
        if (mayHaveSent) {
            d.notifier.mayHaveSent(held);
        } else {
            d.notifier.hold(held, reason, now);
        }
        d.broadcaster.stateChanged(id, m.getRecipientE164());
        if (armExpiry) schedulerOf(d).registerExpiry(held);
    }

    /**
     * A reminded message keeps status SCHEDULED (R29) and expires 24 h past its time. It is marked
     * reminded so no later DUE (boot, time change, stray alarm) auto-sends it (I6).
     */
    private static void remind(EngineDeps d, ScheduledMessage m, RemindReason reason) {
        remind(d, m, reason, null);
    }

    /** As {@link #remind(EngineDeps, ScheduledMessage, RemindReason)}; a cause picks the words. */
    private static void remind(EngineDeps d, ScheduledMessage m, RemindReason reason, RemindCause cause) {
        long id = m.getId();
        d.marks.setReminded(id, true);
        schedulerOf(d).cancelAlarms(id);
        if (cause != null) {
            d.notifier.remindCannotSend(m, cause);
        } else {
            d.notifier.remind(m, reason);
        }
        d.broadcaster.stateChanged(id, m.getRecipientE164());
        schedulerOf(d).registerExpiry(m);
    }

    private static void selfPreempt(EngineDeps d, ScheduledMessage m) {
        long id = m.getId();
        if (!d.store.selfPreempt(id)) return;
        d.marks.clear(id);
        schedulerOf(d).cancelAll(id);
        d.notifier.selfPreempted(reload(d, m));
        d.broadcaster.stateChanged(id, m.getRecipientE164());
    }

    private static void expire(EngineDeps d, ScheduledMessage m) {
        long id = m.getId();
        if (!d.store.expire(id)) return;
        d.marks.clear(id);
        schedulerOf(d).cancelAll(id);
        d.networkWaiter.cancel(id);
        d.notifier.expired(reload(d, m));
        d.broadcaster.stateChanged(id, m.getRecipientE164());
    }

    /**
     * IN_FLIGHT -> SENT_VERIFIED. OD27: called ONLY for the scheduled-send controller's own
     * {@link SendOutcome#SENT_VERIFIED} (its settle or server-time rule during the job); no thread
     * read ever leads here. {@code events} are the listener's inbound events (never a read that
     * sees outbound bubbles), used only to word the notification: an inbound between the thread
     * check and the send is "replied just as your message sent" (R31). {@code detail} is the
     * sender's; {@link #DETAIL_LATE_REPLY} counts only while the outcome arrives inside the
     * late-reply window (OD23(5)), so a stale one is ignored.
     */
    private static void sentVerified(EngineDeps d, ScheduledMessage m, List<ThreadEvent> events, String detail) {
        long id = m.getId();
        Long checkTime = checkTimes.remove(id);
        if (!d.store.markSentVerified(id)) return;
        d.marks.clear(id);
        schedulerOf(d).cancelAll(id);
        ScheduledMessage sent = reload(d, m);
        if (checkTime != null && repliedJustAsSent(events, detail, checkTime, d.conditions.nowMs())) {
            d.notifier.repliedDuringSend(sent);
        } else {
            d.notifier.sent(sent);
        }
        d.broadcaster.stateChanged(id, m.getRecipientE164());
    }

    /**
     * R31 / OD23(5): an inbound timed in [checkTime, end], or the sender's
     * {@link #DETAIL_LATE_REPLY} reported no later than {@link #LATE_REPLY_MAX_SPAN_MS} after the
     * check. {@code end} is the outcome's arrival ({@code nowMs}, the end of the sender's verify
     * window), capped at checkTime + {@link #LATE_REPLY_MAX_SPAN_MS}, plus
     * {@link #LATE_REPLY_WINDOW_MS} slack.
     */
    static boolean repliedJustAsSent(List<ThreadEvent> events, String detail, long checkTime, long nowMs) {
        long end = lateReplyWindowEnd(checkTime, nowMs);
        if (events != null) {
            for (ThreadEvent e : events) {
                if (e != null && e.isInbound() && e.getTimeMs() >= checkTime && e.getTimeMs() <= end) return true;
            }
        }
        return DETAIL_LATE_REPLY.equals(detail) && nowMs >= checkTime
                && nowMs - checkTime <= LATE_REPLY_MAX_SPAN_MS;
    }

    /** End of the late-reply window for an outcome arriving at {@code nowMs}. */
    static long lateReplyWindowEnd(long checkTime, long nowMs) {
        long outcomeAt = Math.max(checkTime, Math.min(nowMs, checkTime + LATE_REPLY_MAX_SPAN_MS));
        return outcomeAt + LATE_REPLY_WINDOW_MS;
    }

    private static ScheduledMessage reload(EngineDeps d, ScheduledMessage fallback) {
        ScheduledMessage m = d.store.get(fallback.getId());
        return m != null ? m : fallback;
    }

    // ================================================================== thread read (R32)

    /**
     * Events since the earlier of the message's two baselines (OD22(1)) from every source available
     * for its conversation ({@link ThreadEventSource#isAvailableFor}, OD22(5)), merged; null when no
     * source is available (classifies as UNVERIFIED). A source that throws counts as unavailable.
     * Used only for the decision before a send (the ladder, Send anyway); OD27: never to verify
     * or retry a send that has started.
     */
    static List<ThreadEvent> readThread(EngineDeps d, ScheduledMessage m) {
        return readFrom(d, m, false);
    }

    /**
     * OD27: the inbound events the late-reply notice may use, from the listener sources only
     * (sources that do not see outbound bubbles: never an {@link OutboundCapableSource}). Outbound
     * events are dropped, so nothing here can say anything about our own send.
     */
    static List<ThreadEvent> listenerInbound(EngineDeps d, ScheduledMessage m) {
        List<ThreadEvent> all = readFrom(d, m, true);
        if (all == null) return null;
        List<ThreadEvent> in = new ArrayList<>();
        for (ThreadEvent e : all) {
            if (e != null && e.isInbound()) in.add(e);
        }
        return in;
    }

    private static List<ThreadEvent> readFrom(EngineDeps d, ScheduledMessage m, boolean listenerOnly) {
        ConversationRef ref = ConversationRef.of(m);
        List<ThreadEvent> out = null;
        for (ThreadEventSource s : d.sources) {
            if (s == null) continue;
            if (listenerOnly && s instanceof OutboundCapableSource) continue;
            boolean available;
            try {
                available = s.isAvailableFor(ref);
            } catch (RuntimeException e) {
                available = false;
            }
            if (!available) continue;
            try {
                List<ThreadEvent> got = s.readSince(ref, readBaseline(m));
                if (out == null) out = new ArrayList<>();
                if (got != null) out.addAll(got);
            } catch (RuntimeException e) {
                Log.w(TAG, "thread source failed: " + e.getClass().getSimpleName());
            }
        }
        return out;
    }

    /**
     * The baseline sources read from: the earlier of the reply watermark and the self-preemption
     * baseline, so a similar text sent before a snooze is still in the read (OD22(1)).
     */
    static Watermark readBaseline(ScheduledMessage m) {
        Watermark reply = m.getWatermark();
        Watermark orig = m.getSelfPreemptionBaseline();
        if (reply == null || orig == null || reply == orig) return reply;
        return floorOf(orig) <= floorOf(reply) ? orig : reply;
    }

    private static long floorOf(Watermark w) {
        long f = w.getCapturedAtDevice();
        if (w.getNewestTs() != null) f = Math.min(f, w.getNewestTs());
        return f;
    }

    // ================================================================== early hold (R33)

    /**
     * A new thread event was recorded (listener or on-screen). An inbound event that counts against
     * a pending message's watermark holds it now when holdIfReplied is on: HELD, alarms cancelled,
     * hold notification, chip refresh broadcast. Outbound events change nothing here.
     *
     * @param e164OrConversationKey the E.164 number or {@code ConversationRef.key()}
     */
    public static void onInboundEvent(final Context context, final String e164OrConversationKey,
                                      final ThreadEvent event) {
        if (event == null || !event.isInbound() || e164OrConversationKey == null) return;
        run(context, () -> {
            EngineDeps d = deps(context);
            long now = d.conditions.nowMs();
            for (ScheduledMessage m : pendingFor(d, e164OrConversationKey)) {
                if (!m.isHoldIfReplied()) continue;
                Classification c = ThreadEventClassifier.classify(m, Collections.singletonList(event), true);
                if (c == Classification.REPLIED) {
                    d.networkWaiter.cancel(m.getId());
                    hold(d, m, HoldReason.REPLIED, now, false);
                } else if (c == Classification.REACHED_OUT) {
                    d.networkWaiter.cancel(m.getId());
                    hold(d, m, HoldReason.REACHED_OUT, now, false);
                }
            }
        });
    }

    private static List<ScheduledMessage> pendingFor(EngineDeps d, String key) {
        if (key.startsWith("+")) {
            List<ScheduledMessage> l = d.store.pendingForThread(key);
            return l != null ? l : Collections.<ScheduledMessage>emptyList();
        }
        List<ScheduledMessage> out = new ArrayList<>();
        List<ScheduledMessage> all = d.store.allPending();
        if (all == null) return out;
        for (ScheduledMessage m : all) {
            if (key.equals(ConversationRef.of(m).key())) out.add(m);
        }
        return out;
    }

    // ================================================================== user taps (R28, I6)

    /**
     * "Send anyway" (and "Send now"): skips the thread check and the lateness budget except the
     * self-preemption check over every outbound since the watermark. Stale taps (terminal or
     * IN_FLIGHT rows, unknown ids) are dropped.
     */
    public static void onSendAnyway(final Context context, final long id) {
        run(context, () -> {
            EngineDeps d = deps(context);
            ScheduledMessage m = d.store.get(id);
            if (!tappable(m)) return;
            long now = d.conditions.nowMs();
            List<ThreadEvent> events = readThread(d, m);
            Decision decision;
            try {
                decision = LadderEvaluator.evaluateSendAnyway(m, events, now, m.getSelfPreemptionBaseline());
            } catch (IllegalArgumentException stale) {
                return;
            }
            d.networkWaiter.cancel(id);
            if (decision.getKind() == Decision.Kind.CANCEL) {
                selfPreempt(d, m);
            } else if (decision.getKind() == Decision.Kind.SEND) {
                d.notifier.dismiss(id);
                startSend(d, context, m, now);
            }
        });
    }

    /**
     * Snooze (OD12, OD21, OD22(1)): +1 h from now at the same rung; budget follows the new time.
     * The reply watermark is replaced by a fresh DEVICE_ONLY one taken now, so the reply that
     * caused the hold (already seen by the user) does not hold the snoozed message again. The
     * store keeps the original watermark as the self-preemption baseline, so a similar text the
     * user sent by hand between the hold and the snooze still cancels it.
     */
    public static void onSnooze(final Context context, final long id) {
        run(context, () -> {
            EngineDeps d = deps(context);
            ScheduledMessage m = d.store.get(id);
            if (!tappable(m)) return;
            long now = d.conditions.nowMs();
            long at = now + LatenessBudget.HOUR_MS;
            Watermark fresh = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, now, null);
            if (!d.store.reschedule(id, at, m.isHoldIfReplied(), m.getLatenessPolicy(), fresh)) return;
            d.networkWaiter.cancel(id);
            d.marks.clearLastCall(id);
            SendLaterScheduler s = schedulerOf(d);
            s.cancelAll(id);
            d.notifier.dismiss(id);
            ScheduledMessage snoozed = d.store.get(id);
            if (snoozed != null) s.registerAlarms(snoozed);
            d.broadcaster.stateChanged(id, m.getRecipientE164());
        });
    }

    /** Cancel tap: SCHEDULED/HELD -> CANCELED, alarms and notification removed. */
    public static void onCancel(final Context context, final long id) {
        run(context, () -> {
            EngineDeps d = deps(context);
            ScheduledMessage m = d.store.get(id);
            if (!tappable(m)) return;
            if (!d.store.cancel(id)) return;
            d.marks.clear(id);
            d.networkWaiter.cancel(id);
            schedulerOf(d).cancelAll(id);
            d.notifier.dismiss(id);
            d.broadcaster.stateChanged(id, m.getRecipientE164());
        });
    }

    /** Whether {@code id} can still be edited from a notification (SCHEDULED or HELD). */
    public static boolean isEditable(Context context, long id) {
        EngineDeps d = deps(context);
        synchronized (LOCK) {
            return tappable(d.store.get(id));
        }
    }

    /** SCHEDULED or HELD; everything else is a stale tap. */
    private static boolean tappable(ScheduledMessage m) {
        return m != null && (m.getStatus() == Status.SCHEDULED || m.getStatus() == Status.HELD);
    }

    // ================================================================== send outcome (R31)

    /**
     * The only path from the sender to a status change. Ignored unless the row is IN_FLIGHT.
     * OD27: only the scheduled-send controller produces SENT_VERIFIED, from its own settle or
     * server-time rule during the job; the engine never upgrades an outcome from a thread read
     * and never retries a started send automatically.
     * <ul>
     *   <li>SENT_VERIFIED: SENT_VERIFIED, then "Sent" or, when the listener saw an inbound timed
     *       after the thread check (or the sender says {@link #DETAIL_LATE_REPLY}), "replied just
     *       as your message sent".</li>
     *   <li>SENT_UNVERIFIED (something may have been typed): HELD(UNVERIFIED) + "may have
     *       already sent", no thread read, no retry. A retry happens only through the user's
     *       Send anyway. The send path never reports FAILED (OD28: a corrupt row is CANNOT_SHOW);
     *       any other outcome takes this conservative branch too.</li>
     *   <li>CANNOT_SHOW: nothing was typed; back to SCHEDULED + a reminder worded by the
     *       detail ({@link RemindCause#fromDetail}: no_session gets Sign in, daily_cap, user_busy,
     *       else locked).</li>
     * </ul>
     */
    public static void onSendOutcome(final Context context, final long id, final SendOutcome outcome,
                                     final String detail) {
        if (outcome == null) return;
        run(context, () -> {
            EngineDeps d = deps(context);
            ScheduledMessage m = d.store.get(id);
            if (m == null || m.getStatus() != Status.IN_FLIGHT) return;
            handleOutcome(d, context, m, outcome, detail);
        });
    }

    private static void handleOutcome(EngineDeps d, Context context, ScheduledMessage m, SendOutcome outcome,
                                      String detail) {
        long id = m.getId();
        long now = d.conditions.nowMs();
        switch (outcome) {
            case SENT_VERIFIED: {
                // The one producer of SENT_VERIFIED (OD27).
                sentVerified(d, m, listenerInbound(d, m), detail);
                return;
            }
            case CANNOT_SHOW: {
                // Nothing was typed: back to SCHEDULED with a reminder (the store counts the
                // release in attemptCount; it is recorded as a free release).
                checkTimes.remove(id);
                RemindCause cause = RemindCause.fromDetail(detail);
                if (d.store.releaseForRetry(id, "cannot_show")) {
                    d.marks.addFreeRelease(id);
                    remind(d, reload(d, m), cause.reason(), cause);
                }
                return;
            }
            case SENT_UNVERIFIED:
            default: {
                // OD27: nothing the engine can read proves the send did or did not go out.
                checkTimes.remove(id);
                hold(d, m, HoldReason.UNVERIFIED, now, true);
            }
        }
    }

    // ================================================================== startup resolution (R23, R31, I5)

    /**
     * Every IN_FLIGHT row found at process start: the attempt was interrupted, so whether it went
     * out is unknown. OD27: HELD(UNVERIFIED) + "may have already sent", with no thread read, never
     * SENT_VERIFIED and never an automatic retry (I5).
     */
    public static void resolveInFlight(final Context context) {
        run(context, () -> doResolveInFlight(deps(context)));
    }

    private static void doResolveInFlight(EngineDeps d) {
        List<ScheduledMessage> rows = d.store.inFlight();
        if (rows == null) return;
        long now = d.conditions.nowMs();
        boolean on = enabled(d);   // OD24: while off, no alarm is registered at process start
        for (ScheduledMessage m : rows) {
            try {
                checkTimes.remove(m.getId());
                hold(d, m, HoldReason.UNVERIFIED, now, true, on);
            } catch (RuntimeException e) {
                Log.e(TAG, "resolve failed for id " + m.getId() + ": " + e.getClass().getSimpleName());
            }
        }
    }

    // ================================================================== master switch (OD24, AC11)

    /**
     * Send Later was switched off: under the engine lock, cancels every alarm (expiry included)
     * and network waiter of each SCHEDULED and HELD row and dismisses its notification. Records
     * are kept; IN_FLIGHT rows finish their attempt. Turning it on again re-arms through
     * {@link SendLaterScheduler#reregisterAll}.
     */
    public static void onDisabled(final Context context) {
        run(context, () -> {
            EngineDeps d = deps(context);
            List<ScheduledMessage> live = new ArrayList<>();
            List<ScheduledMessage> pending = d.store.allPending();
            if (pending != null) live.addAll(pending);
            List<ScheduledMessage> held = d.store.allHeld();
            if (held != null) live.addAll(held);
            SendLaterScheduler s = schedulerOf(d);
            for (ScheduledMessage m : live) {
                long id = m.getId();
                s.cancelAll(id);
                d.networkWaiter.cancel(id);
                d.notifier.dismiss(id);
            }
        });
    }

    private static boolean enabled(EngineDeps d) {
        try {
            return d.conditions.sendLaterEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ================================================================== expiry (R29)

    /** HELD and SCHEDULED (reminded) rows at least 24 h past their time -> EXPIRED + notification. */
    public static void sweepExpired(final Context context) {
        run(context, () -> doSweepExpired(deps(context)));
    }

    private static void doSweepExpired(EngineDeps d) {
        long now = d.conditions.nowMs();
        List<ScheduledMessage> rows = new ArrayList<>();
        List<ScheduledMessage> held = d.store.allHeld();
        if (held != null) rows.addAll(held);
        List<ScheduledMessage> pending = d.store.allPending();
        if (pending != null) rows.addAll(pending);
        for (ScheduledMessage m : rows) {
            if (now - m.getScheduledFor() >= LadderEvaluator.EXPIRY_MS) expire(d, m);
        }
    }

    // ================================================================== helpers

    private static SendLaterScheduler schedulerOf(EngineDeps d) {
        SendLaterScheduler s = scheduler;
        if (s == null) {
            s = new SendLaterScheduler(d.alarms, d.store, d.notifier, d.conditions, d.marks);
            scheduler = s;
        }
        return s;
    }
}
