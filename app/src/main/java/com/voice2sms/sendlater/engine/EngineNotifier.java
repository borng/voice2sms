package com.voice2sms.sendlater.engine;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;

/**
 * The engine's notification seam. Production: {@link SendLaterNotifications}. Every kind for one
 * message shares one notification slot, so a newer state replaces the older one.
 */
public interface EngineNotifier {
    /**
     * R24: "Sending to X in 5 min" (the real minutes left, UI M8) with a chronometer countdown;
     * Cancel / Edit / Send now.
     */
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

    /**
     * LIVE-F8: "may have already sent", with the duplicate warning on Send anyway only when
     * {@code duplicateRisk} (the send typed the text: SENT_UNVERIFIED).
     */
    default void mayHaveSent(ScheduledMessage m, boolean duplicateRisk) { mayHaveSent(m); }

    /**
     * R31: the send failed within the budget and was not retried (or the retry failed too). The
     * default falls back to a plain reminder so older notifier fakes keep compiling.
     */
    default void sendFailed(ScheduledMessage m) { remind(m, RemindReason.NO_SENDER); }

    /**
     * A send that typed nothing became a reminder (CANNOT_SHOW, OD23(3)); the words follow the
     * sender's cause. The default files it under {@link RemindCause#reason()}.
     */
    default void remindCannotSend(ScheduledMessage m, RemindCause cause) {
        remind(m, cause != null ? cause.reason() : RemindReason.LOCKED);
    }

    /**
     * D32: nothing was typed because the conversation's box held the user's own draft. Shows the
     * draft and the scheduled text with Replace and send / Keep draft / Send both. {@code draft}
     * is never stored or logged. The default falls back to the generic "Couldn't send
     * automatically" reminder so older notifier fakes keep compiling.
     */
    default void draftInTheWay(ScheduledMessage m, String draft) {
        remindCannotSend(m, RemindCause.COULD_NOT_SEND);
    }

    /** Removes the message's notification (cancel, send under way). */
    default void dismiss(long id) {}

    /**
     * UI M8: the send has started (IN_FLIGHT). A last-call notification still showing is
     * replaced by "Sending to X now" with no Cancel or Send now; nothing is posted otherwise.
     */
    default void sending(ScheduledMessage m) {}

    /**
     * UI M4: a notification tap (Cancel, Send now / Send anyway, Snooze) found the row changed;
     * tells the user what happened ({@link TapResult#feedback}). {@code m} is the row as it is
     * now, or null when it is gone.
     */
    default void staleTap(long id, ScheduledMessage m, TapResult result, boolean cancelTap) {}
}
