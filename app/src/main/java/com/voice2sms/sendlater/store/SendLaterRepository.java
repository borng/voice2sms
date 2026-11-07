package com.voice2sms.sendlater.store;

import android.content.Context;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.listener.AndroidContactNames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    /** Deliver M4: the only source status of {@link #unverifySent}. */
    static final List<String> SENT_VERIFIED_ONLY = names(Status.SENT_VERIFIED);
    static final List<String> TERMINAL = names(Status.SENT_VERIFIED, Status.CANCELED,
            Status.SELF_PREEMPTED, Status.EXPIRED, Status.FAILED);

    /**
     * UI M2: a capture identical to a live one (same e164, text and source) saved this recently is
     * the same tap twice; {@link #schedule} returns the existing id instead of a second row.
     */
    public static final long DEDUPE_WINDOW_MS = 10_000L;

    /** Injectable clock for updatedAt stamps. */
    public interface Clock { long nowMs(); }

    private static volatile SendLaterRepository instance;

    private final SendLaterDatabase db;
    private final SendLaterDao dao;
    private final Clock clock;
    /** D33: fills a missing display name at save time; null = never. */
    private final RecipientNames names;

    SendLaterRepository(SendLaterDatabase db, Clock clock) {
        this(db, clock, null);
    }

    SendLaterRepository(SendLaterDatabase db, Clock clock, RecipientNames names) {
        this.db = db;
        this.dao = db.dao();
        this.clock = clock;
        this.names = names;
    }

    public static SendLaterRepository get(Context context) {
        SendLaterRepository r = instance;
        if (r == null) {
            synchronized (SendLaterRepository.class) {
                r = instance;
                if (r == null) {
                    r = new SendLaterRepository(SendLaterDatabase.get(context), System::currentTimeMillis,
                            new AndroidContactNames(context));
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
     *
     * <p>UI M2: when a live (SCHEDULED, HELD, IN_FLIGHT) row with the same e164, text and source
     * was created within {@link #DEDUPE_WINDOW_MS}, nothing is inserted and that row's id is
     * returned. The caller can tell by its createdAt, which is then not {@code message}'s.
     *
     * <p>D33: every capture surface saves through here, so a message without a display name gets
     * the first device-contact name for its E.164 (only while READ_CONTACTS is granted; blank on
     * denial or failure), looked up before the transaction. A supplied name is never replaced.
     */
    public long schedule(ScheduledMessage message, Watermark watermark) throws PersistException {
        if (message == null) throw new PersistException("message is null");
        final Watermark wm = watermark != null ? watermark : message.getWatermark();
        final ScheduledMessage named = RecipientNames.fill(message, names);
        final ScheduledMessage toInsert = named.toBuilder().id(0).watermark(wm).build();
        try {
            return db.runInTransaction(() -> {
                long now = clock.nowMs();
                if (toInsert.getRecipientE164() != null) {
                    ScheduledMessageEntity dup = findDuplicate(dao.recentForRecipient(
                            toInsert.getRecipientE164(), now - DEDUPE_WINDOW_MS, LIVE), toInsert, now);
                    if (dup != null) return dup.id; // UI M2: no second row for a double tap
                }
                ScheduledMessageEntity e = StoreMapping.toEntity(toInsert, now);
                e.id = 0;
                long id = dao.insertMessage(e);
                if (id <= 0) throw new VerifyFailed("insert returned " + id);
                WatermarkEntity we = StoreMapping.toEntity(id, wm, toInsert.getOriginalWatermark());
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

    /**
     * UI M2 (pure; JVM-tested): the newest of {@code candidates} that is a live row with the same
     * e164, text and source as {@code incoming}, created within {@link #DEDUPE_WINDOW_MS} before
     * {@code nowMs} (or later, for a clock step). Null when there is none.
     */
    static ScheduledMessageEntity findDuplicate(List<ScheduledMessageEntity> candidates,
                                                ScheduledMessage incoming, long nowMs) {
        if (candidates == null || incoming == null || incoming.getRecipientE164() == null
                || incoming.getText() == null || incoming.getSource() == null) {
            return null;
        }
        ScheduledMessageEntity best = null;
        for (ScheduledMessageEntity e : candidates) {
            if (e == null || !LIVE.contains(e.status)) continue;
            if (e.createdAt < nowMs - DEDUPE_WINDOW_MS) continue;
            if (!incoming.getRecipientE164().equals(e.recipientE164)) continue;
            if (!incoming.getText().equals(e.text)) continue;
            if (!incoming.getSource().name().equals(e.source)) continue;
            if (best == null || e.createdAt > best.createdAt
                    || (e.createdAt == best.createdAt && e.id > best.id)) {
                best = e;
            }
        }
        return best;
    }

    /** Field-by-field comparison ignoring the id (pure; JVM-tested). */
    static boolean sameContent(ScheduledMessage a, ScheduledMessage b) {
        return a.getCreatedAt() == b.getCreatedAt()
                && a.getScheduledFor() == b.getScheduledFor()
                && Objects.equals(a.getText(), b.getText())
                && Objects.equals(a.getRecipientE164(), b.getRecipientE164())
                && Objects.equals(a.getRecipientDisplayName(), b.getRecipientDisplayName())
                && Objects.equals(a.getThreadItemId(), b.getThreadItemId())
                && a.getSource() == b.getSource()
                && a.getRung() == b.getRung()
                && a.isHoldIfReplied() == b.isHoldIfReplied()
                && a.getLatenessPolicy() == b.getLatenessPolicy()
                && a.getStatus() == b.getStatus()
                && a.getHoldReason() == b.getHoldReason()
                && Objects.equals(a.getLastError(), b.getLastError())
                && a.getAttemptCount() == b.getAttemptCount()
                && Objects.equals(a.getWatermark(), b.getWatermark())
                && Objects.equals(a.getOriginalWatermark(), b.getOriginalWatermark())
                && a.getRungReason() == b.getRungReason();
    }

    private static final class VerifyFailed extends RuntimeException {
        VerifyFailed(String m) { super(m); }
    }

    // ------------------------------------------------------------------ edit mode (R16)

    /**
     * Edit mode and snooze: SCHEDULED or HELD -> SCHEDULED with the new time, flags and watermark
     * (null keeps the stored one); clears the hold reason. A new watermark becomes the reply
     * baseline only: the message's first watermark is kept as its self-preemption baseline
     * (OD22(1), {@link StoreMapping#replacing}). Transactional. The caller re-registers alarms.
     */
    public boolean reschedule(long id, long scheduledFor, boolean holdIfReplied,
                              LatenessPolicy latenessPolicy, Watermark newWm) {
        if (latenessPolicy == null) throw new IllegalArgumentException("latenessPolicy");
        return db.runInTransaction(() -> {
            ScheduledMessageEntity before = newWm != null ? dao.message(id) : null;
            int n = dao.reschedule(id, scheduledFor, holdIfReplied, latenessPolicy.name(),
                    WAITING, clock.nowMs());
            if (n == 1 && newWm != null) {
                dao.upsertWatermark(StoreMapping.replacing(id, dao.watermark(id), newWm,
                        before != null ? before.createdAt : 0L));
            }
            return n == 1;
        });
    }

    /**
     * OD22(4): a SCHEDULED/HELD message at rung {@code from} moves to {@code to} and its rung
     * reason is cleared (e.g. a D23 R3 save once a GV web session exists). False when the row is
     * not at {@code from} or not waiting.
     */
    public boolean upgradeRung(long id, Rung from, Rung to) {
        if (from == null || to == null) throw new IllegalArgumentException("rung");
        return dao.upgradeRung(id, from.name(), to.name(), WAITING, clock.nowMs()) == 1;
    }

    // ------------------------------------------------------------------ transitions (R20, R31)

    /** SCHEDULED/HELD -> IN_FLIGHT, recording the body hash (R31) before the send. */
    public boolean markInFlight(long id, String bodyHash) {
        return dao.toInFlight(id, Status.IN_FLIGHT.name(), bodyHash, WAITING, clock.nowMs()) == 1;
    }

    /**
     * Deliver M2: {@link #markInFlight} only while the row still has the {@code scheduledFor} and
     * {@code updatedAt} the engine evaluated ({@link ScheduledMessage#getUpdatedAt}). A row edited
     * in between (new time, flags, baseline) is left alone: false, and the engine re-evaluates.
     */
    public boolean markInFlight(long id, String bodyHash, long scheduledFor, long updatedAt) {
        return dao.toInFlightIfUnchanged(id, Status.IN_FLIGHT.name(), bodyHash, WAITING, scheduledFor,
                updatedAt, clock.nowMs()) == 1;
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

    /**
     * Deliver M4: SENT_VERIFIED -> HELD(UNVERIFIED), the one way out of SENT_VERIFIED: GV marked
     * the bubble "Not sent" after the send path had verified it. Any other status is refused.
     */
    public boolean unverifySent(long id) {
        return to(id, Status.HELD, HoldReason.UNVERIFIED, SENT_VERIFIED_ONLY);
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

    /** Rows with their watermarks: one watermark query per {@link #ID_CHUNK} rows, not per row. */
    private List<ScheduledMessage> load(List<ScheduledMessageEntity> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        Map<Long, WatermarkEntity> wms = new HashMap<>();
        for (List<Long> ids : idChunks(rows, ID_CHUNK)) {
            List<WatermarkEntity> found = dao.watermarks(ids);
            if (found != null) for (WatermarkEntity w : found) if (w != null) wms.put(w.messageId, w);
        }
        return attach(rows, wms);
    }

    /** Below SQLite's 999 bound-variable limit on older devices. */
    static final int ID_CHUNK = 500;

    /** The rows' ids in order, split into lists of at most {@code size} (pure; JVM-tested). */
    static List<List<Long>> idChunks(List<ScheduledMessageEntity> rows, int size) {
        List<List<Long>> out = new ArrayList<>();
        List<Long> cur = null;
        for (ScheduledMessageEntity e : rows) {
            if (e == null) continue;
            if (cur == null || cur.size() >= size) {
                cur = new ArrayList<>(size);
                out.add(cur);
            }
            cur.add(e.id);
        }
        return out;
    }

    /**
     * Each row mapped with its watermark from {@code byId} (null when it has none), in row order
     * (pure; JVM-tested). Same result as one {@code dao.watermark(id)} lookup per row.
     */
    static List<ScheduledMessage> attach(List<ScheduledMessageEntity> rows,
                                         Map<Long, WatermarkEntity> byId) {
        List<ScheduledMessage> out = new ArrayList<>(rows.size());
        for (ScheduledMessageEntity e : rows) out.add(StoreMapping.toDomain(e, byId.get(e.id)));
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

    // ------------------------------------------------------------------ retention (OD31)

    /**
     * Deletes terminal messages (SENT_VERIFIED, CANCELED, SELF_PREEMPTED, EXPIRED, FAILED) whose
     * status changed before {@code beforeMs}, with their watermarks, in one transaction. Live rows
     * are never touched. Returns the number of messages deleted. See {@link DataRetention}.
     */
    public int purgeTerminalBefore(long beforeMs) {
        return db.runInTransaction(() -> {
            // Explicit, so the watermark goes even if the FK cascade is not in force.
            dao.deleteWatermarksOfTerminalBefore(TERMINAL, beforeMs);
            return dao.deleteTerminalBefore(TERMINAL, beforeMs);
        });
    }

    /** Deletes thread-directory pairs last seen before {@code beforeMs} (OD31 P3). */
    public int purgeDirectoryBefore(long beforeMs) {
        return dao.deleteDirectoryBefore(beforeMs);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> names(Status... s) {
        List<String> out = new ArrayList<>(s.length);
        for (Status x : s) out.add(x.name());
        return Collections.unmodifiableList(out);
    }
}
