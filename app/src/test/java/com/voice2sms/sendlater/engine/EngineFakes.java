package com.voice2sms.sendlater.engine;

import android.content.Context;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.NetworkState;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SessionState;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEventSource;
import com.voice2sms.sendlater.domain.Watermark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory fakes for the engine's JVM seam (acceptance: "fakes for repository/sources/notifier/
 * sender via small interfaces"). The seam this test suite pins, all in
 * {@code com.voice2sms.sendlater.engine}:
 *
 * <pre>
 * interface EngineClock      { long nowMs(); }
 * interface EngineConditions extends EngineClock {
 *     SessionState session(); NetworkState network(); boolean listenerEnabled(); OutboundPolicy outboundPolicy(); }
 * interface EngineStore      { same signatures as SendLaterRepository:
 *     ScheduledMessage get(long); boolean markInFlight(long, String); boolean markSentVerified(long);
 *     boolean hold(long, HoldReason); boolean cancel(long); boolean selfPreempt(long); boolean expire(long);
 *     boolean fail(long, String); boolean releaseForRetry(long, String);
 *     boolean reschedule(long, long, boolean, LatenessPolicy, Watermark);
 *     List&lt;ScheduledMessage&gt; allPending(); allHeld(); inFlight(); pendingForThread(String e164); }
 * interface EngineNotifier   { lastCall(m); hold(m, HoldReason, long nowMs); remind(m, RemindReason);
 *     sent(m); repliedDuringSend(m); selfPreempted(m); expired(m); mayHaveSent(m); }
 * interface StateBroadcaster { void stateChanged(long id, String e164); }   // ACTION_STATE_CHANGED
 * enum      AlarmKind        { LAST_CALL, DUE, EXPIRE, RETRY }
 * interface AlarmSink        { boolean canScheduleExact();
 *     void setExact(AlarmKind, long id, long atMs);     // setExactAndAllowWhileIdle
 *     void setInexact(AlarmKind, long id, long atMs);   // setAndAllowWhileIdle
 *     void cancel(AlarmKind, long id); }
 * interface NetworkWaiter    { void awaitNetwork(long id); }   // registerDefaultNetworkCallback
 * final class EngineDeps     { Builder: store, sources(List&lt;ThreadEventSource&gt;), notifier, broadcaster,
 *     alarms, conditions, networkWaiter, executor(java.util.concurrent.Executor); build() }
 * SendLaterEngine.installForTest(EngineDeps) / SendLaterEngine.resetForTest()  (reset also clears the sender)
 * new SendLaterScheduler(AlarmSink, EngineStore, EngineNotifier, EngineClock)
 * </pre>
 *
 * With deps installed the engine never touches the Context (tests pass null) and every entry point
 * except onProcessStart runs synchronously; onProcessStart runs on the deps' executor. New seam
 * methods must be added as Java 8 default methods so these fakes keep compiling.
 */
final class EngineFakes {

    static final long MIN = 60_000L;
    static final long HOUR = 60 * MIN;
    /** 2026-06-10T12:00Z; only differences matter. */
    static final long T0 = 1_781_092_800_000L;
    /** Due time of the default fixture: created T0, due 2 h later (DEFAULT policy budget 30 min). */
    static final long T = T0 + 2 * HOUR;

    /** Reserved fictional number range and a placeholder name: no real personal data. */
    static final String E164 = "+15555550100";
    static final String OTHER_E164 = "+15555550199";
    static final String NAME = "Test Contact";
    static final String TEXT = "Running late, be there at 9";
    static final String REPLY = "ok see you";

    private EngineFakes() {}

    /** Shared, ordered journal of every fake call ("store.hold 7 REPLIED", "source.read", ...). */
    static final class Journal {
        final List<String> lines = new ArrayList<>();
        void add(String s) { lines.add(s); }
        int indexOf(String prefix) {
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(prefix)) return i;
            return -1;
        }
        int lastIndexOf(String prefix) {
            for (int i = lines.size() - 1; i >= 0; i--) if (lines.get(i).startsWith(prefix)) return i;
            return -1;
        }
        int count(String prefix) {
            int n = 0;
            for (String l : lines) if (l.startsWith(prefix)) n++;
            return n;
        }
        @Override public String toString() { return lines.toString(); }
    }

    static Watermark watermark() {
        return new Watermark(Watermark.Quality.TIMESTAMP, null, T0 - 10 * MIN, null, T0, null);
    }

    static ScheduledMessage.Builder message(long id) {
        return new ScheduledMessage.Builder()
                .id(id)
                .createdAt(T0)
                .scheduledFor(T)
                .text(TEXT)
                .recipientE164(E164)
                .recipientDisplayName(NAME)
                .source(ScheduledMessage.Source.NATIVE_GV)
                .holdIfReplied(true)
                .latenessPolicy(LatenessPolicy.DEFAULT)
                .status(Status.SCHEDULED)
                .watermark(watermark());
    }

    static ThreadEvent inboundSms(long t) {
        return new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.SMS, t, ThreadEvent.hashOf(REPLY), REPLY);
    }

    static ThreadEvent inboundCall(long t) {
        return new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.CALL, t, null, null);
    }

    /** An outbound bubble carrying the scheduled text (own send, or the user's similar text). */
    static ThreadEvent outboundSame(long t) {
        return new ThreadEvent(ThreadEvent.Direction.OUT, ThreadEvent.Kind.SMS, t, ThreadEvent.hashOf(TEXT), TEXT);
    }

    // ------------------------------------------------------------------ store

    /** Mirrors SendLaterRepository's guarded transitions over an in-memory map. */
    static final class FakeStore implements EngineStore {
        final Map<Long, ScheduledMessage> rows = new LinkedHashMap<>();
        final Map<Long, String> bodyHash = new LinkedHashMap<>();
        final Journal j;

        FakeStore(Journal j) { this.j = j; }

        void put(ScheduledMessage m) { rows.put(m.getId(), m); }

        Status status(long id) { return rows.get(id).getStatus(); }

        private boolean to(long id, Status to, HoldReason reason, Status... from) {
            ScheduledMessage m = rows.get(id);
            if (m == null || !Arrays.asList(from).contains(m.getStatus())) return false;
            ScheduledMessage.Builder b = m.toBuilder().status(to);
            if (reason != null) b.holdReason(reason);
            rows.put(id, b.build());
            return true;
        }

        @Override public ScheduledMessage get(long id) { j.add("store.get " + id); return rows.get(id); }

        @Override public boolean markInFlight(long id, String hash) {
            j.add("store.markInFlight " + id);
            boolean ok = to(id, Status.IN_FLIGHT, null, Status.SCHEDULED, Status.HELD);
            if (ok) bodyHash.put(id, hash);
            return ok;
        }

        @Override public boolean markSentVerified(long id) {
            j.add("store.markSentVerified " + id);
            return to(id, Status.SENT_VERIFIED, null, Status.IN_FLIGHT);
        }

        @Override public boolean hold(long id, HoldReason r) {
            j.add("store.hold " + id + " " + r);
            return to(id, Status.HELD, r, Status.SCHEDULED, Status.HELD, Status.IN_FLIGHT);
        }

        @Override public boolean cancel(long id) {
            j.add("store.cancel " + id);
            return to(id, Status.CANCELED, null, Status.SCHEDULED, Status.HELD, Status.IN_FLIGHT);
        }

        @Override public boolean selfPreempt(long id) {
            j.add("store.selfPreempt " + id);
            return to(id, Status.SELF_PREEMPTED, null, Status.SCHEDULED, Status.HELD, Status.IN_FLIGHT);
        }

        @Override public boolean expire(long id) {
            j.add("store.expire " + id);
            return to(id, Status.EXPIRED, null, Status.SCHEDULED, Status.HELD);
        }

        @Override public boolean fail(long id, String lastError) {
            j.add("store.fail " + id);
            return to(id, Status.FAILED, null, Status.SCHEDULED, Status.HELD, Status.IN_FLIGHT);
        }

        @Override public boolean releaseForRetry(long id, String lastError) {
            j.add("store.releaseForRetry " + id);
            return to(id, Status.SCHEDULED, null, Status.IN_FLIGHT);
        }

        @Override public boolean reschedule(long id, long scheduledFor, boolean holdIfReplied,
                                            LatenessPolicy policy, Watermark wm) {
            j.add("store.reschedule " + id + " " + scheduledFor);
            ScheduledMessage m = rows.get(id);
            if (m == null || (m.getStatus() != Status.SCHEDULED && m.getStatus() != Status.HELD)) return false;
            ScheduledMessage.Builder b = m.toBuilder().status(Status.SCHEDULED).holdReason(null)
                    .scheduledFor(scheduledFor).holdIfReplied(holdIfReplied).latenessPolicy(policy);
            if (wm != null) b.watermark(wm);
            rows.put(id, b.build());
            return true;
        }

        private List<ScheduledMessage> with(Status s, String e164) {
            List<ScheduledMessage> out = new ArrayList<>();
            for (ScheduledMessage m : rows.values()) {
                if (m.getStatus() == s && (e164 == null || e164.equals(m.getRecipientE164()))) out.add(m);
            }
            return out;
        }

        @Override public List<ScheduledMessage> allPending() { j.add("store.allPending"); return with(Status.SCHEDULED, null); }
        @Override public List<ScheduledMessage> allHeld() { j.add("store.allHeld"); return with(Status.HELD, null); }
        @Override public List<ScheduledMessage> inFlight() { j.add("store.inFlight"); return with(Status.IN_FLIGHT, null); }
        @Override public List<ScheduledMessage> pendingForThread(String e164) {
            j.add("store.pendingForThread");
            return with(Status.SCHEDULED, e164);
        }
    }

    // ------------------------------------------------------------------ sources

    static final class FakeSource implements ThreadEventSource {
        boolean available = true;
        final List<ThreadEvent> events = new ArrayList<>();
        final Journal j;

        FakeSource(Journal j) { this.j = j; }

        @Override public boolean isAvailable() { return available; }

        @Override public List<ThreadEvent> readSince(ConversationRef ref, Watermark wm) {
            if (!available) throw new IllegalStateException("readSince on an unavailable source");
            j.add("source.read");
            return new ArrayList<>(events);
        }
    }

    // ------------------------------------------------------------------ notifier / broadcaster

    static final class FakeNotifier implements EngineNotifier {
        final Journal j;
        final List<String> posted = new ArrayList<>();

        FakeNotifier(Journal j) { this.j = j; }

        private void rec(String s) { posted.add(s); j.add("notify." + s); }

        @Override public void lastCall(ScheduledMessage m) { rec("lastCall " + m.getId()); }
        @Override public void hold(ScheduledMessage m, HoldReason r, long nowMs) { rec("hold " + m.getId() + " " + r); }
        @Override public void remind(ScheduledMessage m, RemindReason r) { rec("remind " + m.getId() + " " + r); }
        @Override public void sent(ScheduledMessage m) { rec("sent " + m.getId()); }
        @Override public void repliedDuringSend(ScheduledMessage m) { rec("repliedDuringSend " + m.getId()); }
        @Override public void selfPreempted(ScheduledMessage m) { rec("selfPreempted " + m.getId()); }
        @Override public void expired(ScheduledMessage m) { rec("expired " + m.getId()); }
        @Override public void mayHaveSent(ScheduledMessage m) { rec("mayHaveSent " + m.getId()); }

        boolean has(String s) { return posted.contains(s); }
    }

    static final class FakeBroadcaster implements StateBroadcaster {
        final List<String> sent = new ArrayList<>();
        final Journal j;

        FakeBroadcaster(Journal j) { this.j = j; }

        @Override public void stateChanged(long id, String e164) {
            sent.add(id + " " + e164);
            j.add("broadcast " + id);
        }
    }

    // ------------------------------------------------------------------ alarms / network / clock

    static final class FakeAlarms implements AlarmSink {
        boolean exactAllowed = true;
        boolean exactThrows = false;
        final List<String> calls = new ArrayList<>();
        final Journal j;

        FakeAlarms(Journal j) { this.j = j; }

        @Override public boolean canScheduleExact() { return exactAllowed; }

        @Override public void setExact(AlarmKind kind, long id, long atMs) {
            if (exactThrows) throw new SecurityException("exact alarms not permitted");
            calls.add("exact " + kind + " " + id + " " + atMs);
            j.add("alarm.exact " + kind + " " + id);
        }

        @Override public void setInexact(AlarmKind kind, long id, long atMs) {
            calls.add("inexact " + kind + " " + id + " " + atMs);
            j.add("alarm.inexact " + kind + " " + id);
        }

        @Override public void cancel(AlarmKind kind, long id) {
            calls.add("cancel " + kind + " " + id);
            j.add("alarm.cancel " + kind + " " + id);
        }

        /** Calls whose text starts with {@code prefix}, e.g. "exact DUE 7". */
        List<String> matching(String prefix) {
            List<String> out = new ArrayList<>();
            for (String c : calls) if (c.startsWith(prefix)) out.add(c);
            return out;
        }

        /** Trigger time of the single registration starting with {@code prefix}, or -1. */
        long timeOf(String prefix) {
            List<String> m = matching(prefix);
            if (m.isEmpty()) return -1;
            String last = m.get(m.size() - 1);
            return Long.parseLong(last.substring(last.lastIndexOf(' ') + 1));
        }

        /** Any registration (exact or inexact) of {@code kind} for {@code id}. */
        List<String> registered(AlarmKind kind, long id) {
            List<String> out = new ArrayList<>(matching("exact " + kind + " " + id + " "));
            out.addAll(matching("inexact " + kind + " " + id + " "));
            return out;
        }
    }

    static final class FakeNetworkWaiter implements NetworkWaiter {
        final List<Long> waits = new ArrayList<>();
        @Override public void awaitNetwork(long id) { waits.add(id); }
    }

    static final class FakeConditions implements EngineConditions {
        long now = T;
        SessionState session = SessionState.READY;
        NetworkState network = NetworkState.CONNECTED;
        boolean listener = true;
        OutboundPolicy outbound = OutboundPolicy.STILL_SEND;

        @Override public long nowMs() { return now; }
        @Override public SessionState session() { return session; }
        @Override public NetworkState network() { return network; }
        @Override public boolean listenerEnabled() { return listener; }
        @Override public OutboundPolicy outboundPolicy() { return outbound; }
    }

    // ------------------------------------------------------------------ sender

    static final class FakeSender implements ScheduledSender {
        final List<Long> started = new ArrayList<>();
        final List<Status> statusAtStart = new ArrayList<>();
        final FakeStore store;
        final Journal j;
        boolean result = true;

        FakeSender(FakeStore store, Journal j) { this.store = store; this.j = j; }

        @Override public boolean start(Context context, ScheduledMessage m) {
            started.add(m.getId());
            statusAtStart.add(store.status(m.getId()));
            j.add("sender.start " + m.getId());
            return result;
        }
    }

    /** One wired test world. */
    static final class World {
        final Journal j = new Journal();
        final FakeStore store = new FakeStore(j);
        final FakeSource source = new FakeSource(j);
        final FakeNotifier notifier = new FakeNotifier(j);
        final FakeBroadcaster broadcaster = new FakeBroadcaster(j);
        final FakeAlarms alarms = new FakeAlarms(j);
        final FakeNetworkWaiter network = new FakeNetworkWaiter();
        final FakeConditions cond = new FakeConditions();
        final FakeSender sender = new FakeSender(store, j);
        final List<Runnable> queued = new ArrayList<>();

        EngineDeps deps(boolean synchronousExecutor) {
            List<ThreadEventSource> sources = new ArrayList<>(Collections.singletonList(source));
            return new EngineDeps.Builder()
                    .store(store)
                    .sources(sources)
                    .notifier(notifier)
                    .broadcaster(broadcaster)
                    .alarms(alarms)
                    .conditions(cond)
                    .networkWaiter(network)
                    .executor(synchronousExecutor ? Runnable::run : queued::add)
                    .build();
        }

        SendLaterScheduler scheduler() {
            return new SendLaterScheduler(alarms, store, notifier, cond);
        }
    }
}
