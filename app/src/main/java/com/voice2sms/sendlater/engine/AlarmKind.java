package com.voice2sms.sendlater.engine;

/** The engine's alarms per message. */
public enum AlarmKind {
    /** T - 5 min: last-call notification (R24). */
    LAST_CALL,
    /** T: run the ladder (R25). */
    DUE,
    /** +24 h after the scheduled time of a HELD or reminded message (R29). */
    EXPIRE,
    /** WAIT(NO_NETWORK) budget deadline: re-run the ladder (R27). */
    RETRY
}
