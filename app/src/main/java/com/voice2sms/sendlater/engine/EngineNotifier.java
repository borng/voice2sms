package com.voice2sms.sendlater.engine;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;

/**
 * The engine's notification seam. Production: {@link SendLaterNotifications}. Every kind for one
 * message shares one notification slot, so a newer state replaces the older one.
 */
public interface EngineNotifier {
    /** R24: "Sending to X in 5 min" with a chronometer countdown; Cancel / Edit / Send now. */
    void lastCall(ScheduledMessage m);

    /** R28: held; Send anyway (with "(3 h late)" past budget) / Edit / Snooze / Cancel. */
    void hold(ScheduledMessage m, HoldReason reason, long nowMs);

    /** R29: opens GVoiceWebViewActivity in review mode, never auto-send. */
    void remind(ScheduledMessage m, RemindReason reason);

    void sent(ScheduledMessage m);

    /** R31: an inbound arrived between the thread check and the send. */
    void repliedDuringSend(ScheduledMessage m);

    /** CANCEL(SELF_PREEMPTED): the user already sent a similar text. */
    void selfPreempted(ScheduledMessage m);

    /** R29: expired after 24 h (under UP_TO_24H it may come with no prior remind). */
    void expired(ScheduledMessage m);

    /** I5: an IN_FLIGHT row could not be verified; the send may have gone out. */
    void mayHaveSent(ScheduledMessage m);

    /** Removes the message's notification (cancel, send under way). */
    default void dismiss(long id) {}
}
