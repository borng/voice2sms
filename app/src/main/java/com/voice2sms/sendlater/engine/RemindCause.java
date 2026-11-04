package com.voice2sms.sendlater.engine;

import com.voice2sms.sendlater.domain.RemindReason;

/**
 * Why a send that typed nothing (CANNOT_SHOW, or {@code start()} returned false) became a
 * reminder, taken from the sender's detail string (OD23(3), R27). The notification words follow
 * the cause; {@link #reason()} is the pinned {@link RemindReason} it is filed under.
 *
 * <p>{@code RemindReason} values are pinned by {@code DomainExtrasTest.pinnedEnumValues}, so the
 * causes that have no RemindReason of their own (daily cap, user busy, could not send) live here
 * instead.
 */
public enum RemindCause {
    /** Default: the phone is locked and the send activity cannot be shown over it (D15). */
    LOCKED,
    /** No Google Voice web session: the reminder gets the Sign-in action (R27). */
    NO_SESSION,
    /** The scheduled-send daily cap ({@code ScheduledSendQueue.DAILY_CAP}) was reached. */
    DAILY_CAP,
    /** The user was using the voice2sms WebView, so the send did not take it over (OD23(2)). */
    BUSY,
    /**
     * Nothing was typed for a reason other than the lock screen (compose timeout, a launch nobody
     * picked up, the activity paused or gone, a corrupt row, a lit unlocked screen with no
     * accessibility service): "Couldn't send automatically" (F6). Filed under LOCKED.
     */
    COULD_NOT_SEND,
    /**
     * The launch was ready only after the lateness deadline ({@code LatenessBudget.deadlineMs}),
     * so it was not sent (I2: never send late; F4). Filed under {@link RemindReason#LATE}.
     */
    LATE;

    /** Sender detail strings (send-path contract, OD23). */
    public static final String DETAIL_NO_SESSION = "no_session";
    public static final String DETAIL_DAILY_CAP = "daily_cap";
    public static final String DETAIL_USER_BUSY = "user_busy";
    /** The planner could not show the send because the keyguard is locked. */
    public static final String DETAIL_LOCKED = "locked";
    /** Any other pre-typing failure (F6). */
    public static final String DETAIL_COULD_NOT_SEND = "could_not_send";
    /** The launch came after the lateness deadline (F4, I2). Distinct from "late_reply". */
    public static final String DETAIL_LATE = "late";

    /** The cause a sender detail names; anything else (null, unknown) is {@link #LOCKED}. */
    public static RemindCause fromDetail(String detail) {
        if (detail == null) return LOCKED;
        String d = detail.trim();
        if (DETAIL_NO_SESSION.equals(d)) return NO_SESSION;
        if (DETAIL_DAILY_CAP.equals(d)) return DAILY_CAP;
        if (DETAIL_USER_BUSY.equals(d) || "busy".equals(d)) return BUSY;
        if (DETAIL_COULD_NOT_SEND.equals(d)) return COULD_NOT_SEND;
        if (DETAIL_LATE.equals(d)) return LATE;
        return LOCKED;
    }

    /**
     * The pinned reason this cause is filed under: NO_SESSION for a missing session, LATE for a
     * late launch, else LOCKED.
     */
    public RemindReason reason() {
        if (this == NO_SESSION) return RemindReason.NO_SESSION;
        if (this == LATE) return RemindReason.LATE;
        return RemindReason.LOCKED;
    }
}
