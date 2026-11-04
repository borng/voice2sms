package com.voice2sms.sendlater.engine;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import com.voice2sms.sendlater.domain.Classification;
import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.Decision;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LadderEvaluator;
import com.voice2sms.sendlater.domain.LatenessBudget;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEventClassifier;
import com.voice2sms.sendlater.domain.ThreadEventSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** At most this many automatic retries after a thread read showed no send (R31). */
    static final int MAX_RETRIES = 1;

    private static final Object LOCK = new Object();

    private static volatile EngineDeps deps;
    private static volatile SendLaterScheduler scheduler;
    private static volatile ScheduledSender sender;
    private static volatile boolean installedForTest;

    /** Thread-check time of the attempt now IN_FLIGHT, per id (R31 "replied just as it sent"). */
    private static final Map<Long, Long> checkTimes = new HashMap<>();
    /** Ids retried in this process (the store's attemptCount is the durable guard). */
    private static final Set<Long> retried = new HashSet<>();
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
            retried.clear();
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
     * IN_FLIGHT rows by the Schrödinger rule (R31) first, then sweeps expired HELD/reminded rows,
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
                    doResolveInFlight(d, context);
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
        run(context, () -> {
            EngineDeps d = deps(context);
            ScheduledMessage m = d.store.get(id);
            if (m == null || m.getStatus() != Status.SCHEDULED) return;
            if (d.marks.isReminded(id)) return;
            evaluateAndApply(d, context, m);
        });
    }

    /** The T-5 alarm: last-call notification if the message is still SCHEDULED (R24). */
    public static void onLastCall(final Context context, final long id) {
        run(context, () -> {
            EngineDeps d = deps(context);
            ScheduledMessage m = d.store.get(id);
            if (m == null || m.getStatus() != Status.SCHEDULED) return;
            if (d.marks.isReminded(id)) return;
            if (d.conditions.nowMs() >= m.getScheduledFor()) return;
            d.notifier.lastCall(m);
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
        Decision decision = LadderEvaluator.evaluate(m, m.getRung(), events, now, d.conditions.session(),
                d.conditions.network(), d.conditions.listenerEnabled(), d.conditions.outboundPolicy());
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
                    sentVerified(d, m, null);
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
                handleOutcome(d, context, now, SendOutcome.CANNOT_SHOW, "sender_not_started");
            }
        }
    }

    private static void hold(EngineDeps d, ScheduledMessage m, HoldReason reason, long now, boolean mayHaveSent) {
        long id = m.getId();
        if (!d.store.hold(id, reason)) return;
        d.marks.setReminded(id, false);
        schedulerOf(d).cancelAlarms(id);
        ScheduledMessage held = reload(d, m);
        if (mayHaveSent) {
            d.notifier.mayHaveSent(held);
        } else {
            d.notifier.hold(held, reason, now);
        }
        d.broadcaster.stateChanged(id, m.getRecipientE164());
        schedulerOf(d).registerExpiry(held);
    }

    /**
     * A reminded message keeps status SCHEDULED (R29) and expires 24 h past its time. It is marked
     * reminded so no later DUE (boot, time change, stray alarm) auto-sends it (I6).
     */
    private static void remind(EngineDeps d, ScheduledMessage m, RemindReason reason) {
        long id = m.getId();
        d.marks.setReminded(id, true);
        schedulerOf(d).cancelAlarms(id);
        d.notifier.remind(m, reason);
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
     * IN_FLIGHT -> SENT_VERIFIED. {@code events} (read after the send) decide whether an inbound
     * arrived between the thread check and the send (R31).
     */
    private static void sentVerified(EngineDeps d, ScheduledMessage m, List<ThreadEvent> events) {
        long id = m.getId();
        Long checkTime = checkTimes.remove(id);
        retried.remove(id);
        if (!d.store.markSentVerified(id)) return;
        d.marks.clear(id);
        schedulerOf(d).cancelAll(id);
        ScheduledMessage sent = reload(d, m);
        if (checkTime != null && inboundAfter(events, checkTime)) {
            d.notifier.repliedDuringSend(sent);
        } else {
            d.notifier.sent(sent);
        }
        d.broadcaster.stateChanged(id, m.getRecipientE164());
    }

    private static boolean inboundAfter(List<ThreadEvent> events, long sinceMs) {
        if (events == null) return false;
        for (ThreadEvent e : events) {
            if (e != null && e.isInbound() && e.getTimeMs() > sinceMs) return true;
        }
        return false;
    }

    private static ScheduledMessage reload(EngineDeps d, ScheduledMessage fallback) {
        ScheduledMessage m = d.store.get(fallback.getId());
        return m != null ? m : fallback;
    }

    // ================================================================== thread read (R32)

    /**
     * Events since the watermark from every available source, merged; null when no source is
     * available (classifies as UNVERIFIED). A source that throws counts as unavailable.
     */
    static List<ThreadEvent> readThread(EngineDeps d, ScheduledMessage m) {
        ConversationRef ref = ConversationRef.of(m);
        List<ThreadEvent> out = null;
        for (ThreadEventSource s : d.sources) {
            if (s == null) continue;
            boolean available;
            try {
                available = s.isAvailable();
            } catch (RuntimeException e) {
                available = false;
            }
            if (!available) continue;
            try {
                List<ThreadEvent> got = s.readSince(ref, m.getWatermark());
                if (out == null) out = new ArrayList<>();
                if (got != null) out.addAll(got);
            } catch (RuntimeException e) {
                Log.w(TAG, "thread source failed: " + e.getClass().getSimpleName());
            }
        }
        return out;
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
                decision = LadderEvaluator.evaluateSendAnyway(m, events, now);
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

    /** Snooze (OD12): +1 h from now at the same rung and watermark; budget follows the new time. */
    public static void onSnooze(final Context context, final long id) {
        run(context, () -> {
            EngineDeps d = deps(context);
            ScheduledMessage m = d.store.get(id);
            if (!tappable(m)) return;
            long at = d.conditions.nowMs() + LatenessBudget.HOUR_MS;
            if (!d.store.reschedule(id, at, m.isHoldIfReplied(), m.getLatenessPolicy(), null)) return;
            d.networkWaiter.cancel(id);
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
     * <ul>
     *   <li>SENT_VERIFIED: the sender saw its bubble. SENT_VERIFIED, then "Sent" or, when an
     *       inbound was read timed after the thread check, "replied just as your message sent".</li>
     *   <li>SENT_UNVERIFIED: own bubble in the thread read -> SENT_VERIFIED; otherwise
     *       HELD(UNVERIFIED) + "may have already sent" (never retried).</li>
     *   <li>FAILED: own bubble read -> SENT_VERIFIED; otherwise one retry within budget, decided
     *       on the thread just read (I5), else FAILED + reminder.</li>
     *   <li>CANNOT_SHOW: nothing was typed; back to SCHEDULED + REMIND(LOCKED).</li>
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
                sentVerified(d, m, readThread(d, m));
                return;
            }
            case SENT_UNVERIFIED: {
                List<ThreadEvent> events = readThread(d, m);
                if (events != null && ownBubbleSeen(d, m, events, now)) {
                    sentVerified(d, m, events);
                } else {
                    checkTimes.remove(id);
                    hold(d, m, HoldReason.UNVERIFIED, now, true);
                }
                return;
            }
            case CANNOT_SHOW: {
                // Nothing was typed: the release must not use up the retry after a real failure
                // (the store counts it in attemptCount; the engine subtracts these).
                checkTimes.remove(id);
                if (d.store.releaseForRetry(id, "cannot_show")) {
                    d.marks.addFreeRelease(id);
                    remind(d, reload(d, m), RemindReason.LOCKED);
                }
                return;
            }
            case FAILED:
            default: {
                List<ThreadEvent> events = readThread(d, m);
                if (events != null && ownBubbleSeen(d, m, events, now)) {
                    sentVerified(d, m, events);
                    return;
                }
                checkTimes.remove(id);
                if (events != null && canRetry(d, m, now)) {
                    retryAfterRead(d, context, m, events, now, detail);
                } else if (d.store.fail(id, detail != null ? detail : "failed")) {
                    d.marks.clear(id);
                    schedulerOf(d).cancelAll(id);
                    ScheduledMessage failed = reload(d, m);
                    if (now > LatenessBudget.deadlineMs(m)) {
                        d.notifier.remind(failed, RemindReason.LATE);
                    } else {
                        d.notifier.sendFailed(failed);
                    }
                    d.broadcaster.stateChanged(id, m.getRecipientE164());
                }
            }
        }
    }

    /** Whether the IN_FLIGHT message's own outbound appears in {@code events} (CANCEL for IN_FLIGHT). */
    private static boolean ownBubbleSeen(EngineDeps d, ScheduledMessage inFlight, List<ThreadEvent> events,
                                         long now) {
        Decision dec = LadderEvaluator.evaluate(inFlight, inFlight.getRung(), events, now,
                d.conditions.session(), d.conditions.network(), d.conditions.listenerEnabled(),
                d.conditions.outboundPolicy());
        return dec.getKind() == Decision.Kind.CANCEL;
    }

    /** Real attempts only: CANNOT_SHOW releases (nothing typed) do not count against the retry. */
    private static boolean canRetry(EngineDeps d, ScheduledMessage m, long now) {
        int attempts = Math.max(0, m.getAttemptCount() - d.marks.freeReleases(m.getId()));
        return now <= LatenessBudget.deadlineMs(m)
                && attempts < MAX_RETRIES
                && !retried.contains(m.getId());
    }

    /**
     * R31 retry: only after {@code events} were read and showed no send. IN_FLIGHT -> SCHEDULED,
     * then the ladder runs on the same read (so a reply in it still holds).
     */
    private static void retryAfterRead(EngineDeps d, Context context, ScheduledMessage m, List<ThreadEvent> events,
                                       long now, String detail) {
        long id = m.getId();
        if (!d.store.releaseForRetry(id, detail != null ? detail : "retry")) return;
        retried.add(id);
        ScheduledMessage released = d.store.get(id);
        if (released == null || released.getStatus() != Status.SCHEDULED) return;
        Decision decision = LadderEvaluator.evaluate(released, released.getRung(), events, now,
                d.conditions.session(), d.conditions.network(), d.conditions.listenerEnabled(),
                d.conditions.outboundPolicy());
        apply(d, context, released, decision, now);
    }

    // ================================================================== startup resolution (R23, R31, I5)

    /**
     * Schrödinger rule for every IN_FLIGHT row: read the thread first. Own bubble -> SENT_VERIFIED.
     * No source -> HELD(UNVERIFIED) + "may have already sent", never retried (I5). No bubble ->
     * one retry within budget, else HELD(UNVERIFIED) + "may have already sent".
     */
    public static void resolveInFlight(final Context context) {
        run(context, () -> doResolveInFlight(deps(context), context));
    }

    private static void doResolveInFlight(EngineDeps d, Context context) {
        List<ScheduledMessage> rows = d.store.inFlight();
        if (rows == null) return;
        for (ScheduledMessage m : rows) {
            try {
                resolveOne(d, context, m);
            } catch (RuntimeException e) {
                Log.e(TAG, "resolve failed for id " + m.getId() + ": " + e.getClass().getSimpleName());
            }
        }
    }

    private static void resolveOne(EngineDeps d, Context context, ScheduledMessage m) {
        long now = d.conditions.nowMs();
        List<ThreadEvent> events = readThread(d, m);
        if (events == null) {
            checkTimes.remove(m.getId());
            hold(d, m, HoldReason.UNVERIFIED, now, true);
            return;
        }
        if (ownBubbleSeen(d, m, events, now)) {
            sentVerified(d, m, events);
            return;
        }
        checkTimes.remove(m.getId());
        if (canRetry(d, m, now)) {
            retryAfterRead(d, context, m, events, now, "restart");
        } else {
            hold(d, m, HoldReason.UNVERIFIED, now, true);
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
