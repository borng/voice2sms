package com.voice2sms.sendlater.domain;

/**
 * Whether the send path can run right now (R27, D15): the GV web session and whether the send
 * activity can be shown (a locked phone where it cannot be shown counts as LOCKED_UNSHOWABLE).
 */
public enum SessionState {
    READY, MISSING, EXPIRED, LOCKED_UNSHOWABLE;

    public static SessionState ready() { return READY; }
    public static SessionState missing() { return MISSING; }
    public static SessionState expired() { return EXPIRED; }
    public static SessionState lockedUnshowable() { return LOCKED_UNSHOWABLE; }
}
