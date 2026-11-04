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

    boolean markSentVerified(long id);

    boolean hold(long id, HoldReason reason);

    boolean cancel(long id);

    boolean selfPreempt(long id);

    boolean expire(long id);

    boolean fail(long id, String lastError);

    boolean releaseForRetry(long id, String lastError);

    boolean reschedule(long id, long scheduledFor, boolean holdIfReplied, LatenessPolicy policy, Watermark newWm);

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
