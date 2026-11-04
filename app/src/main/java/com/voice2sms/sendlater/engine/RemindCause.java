package com.voice2sms.sendlater.engine;

import com.voice2sms.sendlater.domain.RemindReason;

/**
 * Why a send that typed nothing (CANNOT_SHOW, or {@code start()} returned false) became a
 * reminder, taken from the sender's detail string (OD23(3), R27). The notification words follow
 * the cause; {@link #reason()} is the pinned {@link RemindReason} it is filed under.
 *
 * <p>{@code RemindReason} values are pinned by {@code DomainExtrasTest.pinnedEnumValues}, so the
 * two causes that have no RemindReason of their own (daily cap, user busy) live here instead.
 */
public enum RemindCause {
    /** Default: the phone is locked and the send activity cannot be shown over it (D15). */
    LOCKED,
    /** No Google Voice web session: the reminder gets the Sign-in action (R27). */
    NO_SESSION,
    /** The scheduled-send daily cap ({@code ScheduledSendQueue.DAILY_CAP}) was reached. */
    DAILY_CAP,
    /** The user was using the voice2sms WebView, so the send did not take it over (OD23(2)). */
    BUSY;

    /** Sender detail strings (send-path contract, OD23). */
    public static final String DETAIL_NO_SESSION = "no_session";
    public static final String DETAIL_DAILY_CAP = "daily_cap";
    public static final String DETAIL_USER_BUSY = "user_busy";

    /** The cause a sender detail names; anything else (null, unknown) is {@link #LOCKED}. */
    public static RemindCause fromDetail(String detail) {
        if (detail == null) return LOCKED;
        String d = detail.trim();
        if (DETAIL_NO_SESSION.equals(d)) return NO_SESSION;
        if (DETAIL_DAILY_CAP.equals(d)) return DAILY_CAP;
        if (DETAIL_USER_BUSY.equals(d) || "busy".equals(d)) return BUSY;
        return LOCKED;
    }

    /** The pinned reason this cause is filed under: NO_SESSION for a missing session, else LOCKED. */
    public RemindReason reason() {
        return this == NO_SESSION ? RemindReason.NO_SESSION : RemindReason.LOCKED;
    }
}
