package com.voice2sms.sendlater.store;

import android.content.Context;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Synchronous Send Later store (R20, R21, R23, R34). Every method does disk I/O: call it from a
 * background thread. Status transitions are guarded in SQL and return {@code true} only when the
 * row was in an allowed status and changed; callers re-register alarms themselves.
 */
public final class SendLaterRepository {

    /** Recent window for the Upcoming screen (R35). */
    public static final long RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000;
    /** Event retention (R34). */
    public static final long EVENT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;

    /** Non-terminal statuses: a message in one of these keeps its conversation's events. */
    static final List<String> LIVE = names(Status.SCHEDULED, Status.HELD, Status.IN_FLIGHT);
    static final List<String> WAITING = names(Status.SCHEDULED, Status.HELD);
    static final List<String> IN_FLIGHT = names(Status.IN_FLIGHT);
    static final List<String> HOLDABLE = LIVE;
    static final List<String> TERMINAL = names(Status.SENT_VERIFIED, Status.CANCELED,
            Status.SELF_PREEMPTED, Status.EXPIRED, Status.FAILED);

    /** Injectable clock for updatedAt stamps. */
    public interface Clock { long nowMs(); }

    private static volatile SendLaterRepository instance;

    private final SendLaterDatabase db;
    private final SendLaterDao dao;
    private final Clock clock;

    SendLaterRepository(SendLaterDatabase db, Clock clock) {
        this.db = db;
        this.dao = db.dao();
        this.clock = clock;
    }

    public static SendLaterRepository get(Context context) {
        SendLaterRepository r = instance;
        if (r == null) {
            synchronized (SendLaterRepository.class) {
                r = instance;
                if (r == null) {
                    r = new SendLaterRepository(SendLaterDatabase.get(context), System::currentTimeMillis);
                    instance = r;
                }
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ schedule (R21, I1)

    /**
     * Inserts {@code message} (its id is ignored) with {@code watermark} (or the message's own
     * watermark when null) in one transaction, reads both back and returns the new id only when
     * the read-back matches. Any failure rolls back and throws: the caller then shows an error and
     * must not touch the source app's draft.
     */
    public long schedule(ScheduledMessage message, Watermark watermark) throws PersistException {
        if (message == null) throw new PersistException("message is null");
        final Watermark wm = watermark != null ? watermark : message.getWatermark();
        final ScheduledMessage toInsert = message.toBuilder().id(0).watermark(wm).build();
        try {
            return db.runInTransaction(() -> {
                ScheduledMessageEntity e = StoreMapping.toEntity(toInsert, clock.nowMs());
                e.id = 0;
                long id = dao.insertMessage(e);
                if (id <= 0) throw new VerifyFailed("insert returned " + id);
                WatermarkEntity we = StoreMapping.toEntity(id, wm);
                if (we != null) dao.upsertWatermark(we);
                ScheduledMessageEntity back = dao.message(id);
                if (back == null) throw new VerifyFailed("row not found after insert");
                ScheduledMessage readBack = StoreMapping.toDomain(back, dao.watermark(id));
                if (!sameContent(toInsert, readBack)) throw new VerifyFailed("read-back mismatch");
                return id;
            });
        } catch (VerifyFailed ex) {
            throw new PersistException("schedule not verified: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new PersistException("schedule failed", ex);
        }
    }

    /** Field-by-field comparison ignoring the id (pure; JVM-tested). */
    static boolean sameContent(ScheduledMessage a, ScheduledMessage b) {
        return a.getCreatedAt() == b.getCreatedAt()
                && a.getScheduledFor() == b.getScheduledFor()
                && eq(a.getText(), b.getText())
                && eq(a.getRecipientE164(), b.getRecipientE164())
                && eq(a.getRecipientDisplayName(), b.getRecipientDisplayName())
                && eq(a.getThreadItemId(), b.getThreadItemId())
                && a.getSource() == b.getSource()
                && a.getRung() == b.getRung()
                && a.isHoldIfReplied() == b.isHoldIfReplied()
                && a.getLatenessPolicy() == b.getLatenessPolicy()
                && a.getStatus() == b.getStatus()
                && a.getHoldReason() == b.getHoldReason()
                && eq(a.getLastError(), b.getLastError())
                && a.getAttemptCount() == b.getAttemptCount()
                && eq(a.getWatermark(), b.getWatermark());
    }

    private static final class VerifyFailed extends RuntimeException {
        VerifyFailed(String m) { super(m); }
    }

    // ------------------------------------------------------------------ edit mode (R16)

    /**
     * Edit mode: SCHEDULED or HELD -> SCHEDULED with the new time, flags and watermark (null keeps
     * the stored one); clears the hold reason. Transactional. The caller re-registers alarms.
     */
    public boolean reschedule(long id, long scheduledFor, boolean holdIfReplied,
                              LatenessPolicy latenessPolicy, Watermark newWm) {
        if (latenessPolicy == null) throw new IllegalArgumentException("latenessPolicy");
        return db.runInTransaction(() -> {
            int n = dao.reschedule(id, scheduledFor, holdIfReplied, latenessPolicy.name(),
                    WAITING, clock.nowMs());
            if (n == 1 && newWm != null) dao.upsertWatermark(StoreMapping.toEntity(id, newWm));
            return n == 1;
        });
    }

    // ------------------------------------------------------------------ transitions (R20, R31)

    /** SCHEDULED/HELD -> IN_FLIGHT, recording the body hash (R31) before the send. */
    public boolean markInFlight(long id, String bodyHash) {
        return dao.toInFlight(id, Status.IN_FLIGHT.name(), bodyHash, WAITING, clock.nowMs()) == 1;
    }

    /** IN_FLIGHT -> SENT_VERIFIED, only after a matching outbound was read (R31). */
    public boolean markSentVerified(long id) {
        return to(id, Status.SENT_VERIFIED, null, IN_FLIGHT);
    }

    /** SCHEDULED/HELD/IN_FLIGHT -> HELD with {@code reason}. */
    public boolean hold(long id, HoldReason reason) {
        if (reason == null) throw new IllegalArgumentException("reason");
        return to(id, Status.HELD, reason, HOLDABLE);
    }

    /** Any non-terminal -> CANCELED. */
    public boolean cancel(long id) {
        return to(id, Status.CANCELED, null, LIVE);
    }

    /** Any non-terminal -> SELF_PREEMPTED (the user sent the same text themselves). */
    public boolean selfPreempt(long id) {
        return to(id, Status.SELF_PREEMPTED, null, LIVE);
    }

    /**
     * SCHEDULED/HELD -> EXPIRED. IN_FLIGHT rows are excluded: they are resolved by a thread read
     * first (R31), never expired blind.
     */
    public boolean expire(long id) {
        return to(id, Status.EXPIRED, null, WAITING);
    }

    /** Any non-terminal -> FAILED with {@code lastError}; attemptCount + 1. */
    public boolean fail(long id, String lastError) {
        return dao.transitionWithAttempt(id, Status.FAILED.name(), lastError, LIVE, clock.nowMs()) == 1;
    }

    /**
     * IN_FLIGHT -> SCHEDULED after a thread read showed the send did not go out (R31 retry within
     * budget); records {@code lastError} and attemptCount + 1.
     */
    public boolean releaseForRetry(long id, String lastError) {
        return dao.transitionWithAttempt(id, Status.SCHEDULED.name(), lastError, IN_FLIGHT,
                clock.nowMs()) == 1;
    }

    private boolean to(long id, Status to, HoldReason reason, List<String> from) {
        return dao.transition(id, to.name(), reason == null ? null : reason.name(), from,
                clock.nowMs()) == 1;
    }

    // ------------------------------------------------------------------ queries

    /** The message with its watermark, or null. */
    public ScheduledMessage get(long id) {
        ScheduledMessageEntity e = dao.message(id);
        return e == null ? null : StoreMapping.toDomain(e, dao.watermark(id));
    }

    /** SCHEDULED messages for {@code e164}, soonest first. */
    public List<ScheduledMessage> pendingForThread(String e164) {
        return load(dao.byStatusForThread(Status.SCHEDULED.name(), e164));
    }

    /** HELD messages for {@code e164}, soonest first. */
    public List<ScheduledMessage> heldForThread(String e164) {
        return load(dao.byStatusForThread(Status.HELD.name(), e164));
    }

    /** Every SCHEDULED message, soonest first (alarm re-registration, Upcoming "Pending"). */
    public List<ScheduledMessage> allPending() {
        return load(dao.byStatus(Status.SCHEDULED.name()));
    }

    /** Every HELD message, soonest first (Upcoming "Held"). */
    public List<ScheduledMessage> allHeld() {
        return load(dao.byStatus(Status.HELD.name()));
    }

    /** Every IN_FLIGHT message: resolve these first at startup (R23, R31). */
    public List<ScheduledMessage> inFlight() {
        return load(dao.byStatus(Status.IN_FLIGHT.name()));
    }

    /** Terminal messages whose status changed in the last 30 days, newest first (R35). */
    public List<ScheduledMessage> recent(long nowMs) {
        return load(dao.byStatusesUpdatedSince(TERMINAL, nowMs - RECENT_WINDOW_MS));
    }

    private List<ScheduledMessage> load(List<ScheduledMessageEntity> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        List<ScheduledMessage> out = new ArrayList<>(rows.size());
        for (ScheduledMessageEntity e : rows) out.add(StoreMapping.toDomain(e, dao.watermark(e.id)));
        return out;
    }

    // ------------------------------------------------------------------ thread events (R32, R34)

    /** Records one event; {@code ev.getTextHash()} must be {@code ThreadEvent.hashOf(text)}. */
    public long recordEvent(ConversationRef ref, ThreadEvent ev, String source, long recordedAt) {
        return dao.insertEvent(StoreMapping.toEntity(ref, ev, source, recordedAt));
    }

    /** Events of {@code conversationKey} (ConversationRef.key()) at or after {@code sinceMs}, oldest first. */
    public List<ThreadEvent> eventsSince(String conversationKey, long sinceMs) {
        List<ThreadEventEntity> rows = dao.eventsSince(conversationKey, sinceMs);
        List<ThreadEvent> out = new ArrayList<>(rows.size());
        for (ThreadEventEntity e : rows) out.add(StoreMapping.toDomain(e));
        return out;
    }

    /** Conversation keys that have a SCHEDULED, HELD or IN_FLIGHT message (R34 store filter). */
    public List<String> liveConversationKeys() {
        return dao.liveConversationKeys(LIVE);
    }

    /**
     * Deletes events timed before {@code olderThanMs} and, when {@code orphans}, events of
     * conversations with no non-terminal message (R34). Returns the number of rows deleted.
     */
    public int purgeEvents(long olderThanMs, boolean orphans) {
        return db.runInTransaction(() -> {
            int n = dao.deleteEventsOlderThan(olderThanMs);
            if (orphans) n += dao.deleteOrphanEvents(LIVE);
            return n;
        });
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> names(Status... s) {
        List<String> out = new ArrayList<>(s.length);
        for (Status x : s) out.add(x.name());
        return Collections.unmodifiableList(out);
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
