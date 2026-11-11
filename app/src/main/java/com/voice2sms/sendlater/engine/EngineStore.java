package com.voice2sms.sendlater.engine;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.Watermark;

import java.util.List;

/**
 * The slice of {@code SendLaterRepository} the engine uses, with the repository's signatures and
 * guarded-transition semantics (boolean = applied). Synchronous: call off the main thread.
 */
public interface EngineStore {
    ScheduledMessage get(long id);

    boolean markInFlight(long id, String bodyHash);

    /**
     * Deliver M2: SCHEDULED/HELD -> IN_FLIGHT only while the row still has the
     * {@code scheduledFor} / {@code updatedAt} snapshot the engine evaluated; false when it was
     * edited in between (or is no longer waiting). Default: compares the snapshot against
     * {@link #get} before the unguarded transition (callers hold the engine lock).
     */
    default boolean markInFlight(long id, String bodyHash, long scheduledFor, long updatedAt) {
        ScheduledMessage now = get(id);
        if (now == null || now.getScheduledFor() != scheduledFor || now.getUpdatedAt() != updatedAt) {
            return false;
        }
        return markInFlight(id, bodyHash);
    }

    boolean markSentVerified(long id);

    boolean hold(long id, HoldReason reason);

    /**
     * Deliver M4: SENT_VERIFIED -> HELD(UNVERIFIED) only (GV failed a send after it was verified);
     * false for any other status. Default: not supported (false), so the row stays as it is.
     */
    default boolean unverifySent(long id) {
        return false;
    }

    boolean cancel(long id);

    boolean selfPreempt(long id);

    boolean expire(long id);

    boolean fail(long id, String lastError);

    boolean releaseForRetry(long id, String lastError);

    boolean reschedule(long id, long scheduledFor, boolean holdIfReplied, LatenessPolicy policy, Watermark newWm);

    /**
     * D49 / R12a: as {@link #reschedule(long, long, boolean, LatenessPolicy, Watermark)} and also
     * stores {@code text}, in the same guarded write (SCHEDULED/HELD only; a refused row keeps its
     * text). Default: not supported (false), so no store silently drops an edited text.
     */
    default boolean reschedule(long id, long scheduledFor, boolean holdIfReplied, LatenessPolicy policy,
                               Watermark newWm, String text) {
        return false;
    }

    List<ScheduledMessage> allPending();

    List<ScheduledMessage> allHeld();

    List<ScheduledMessage> inFlight();

    List<ScheduledMessage> pendingForThread(String e164);

    /**
     * OD22(4): SCHEDULED/HELD at rung {@code from} -> {@code to}, rung reason cleared. Default: not
     * supported (false), so the message stays at its stored rung.
     */
    default boolean upgradeRung(long id, Rung from, Rung to) {
        return false;
    }
}
