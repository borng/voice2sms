package com.voice2sms.sendlater.domain;

/** Why a message falls to the reminder rung (R27). Values pinned. */
public enum RemindReason {
    LATE, NO_SESSION, NO_NETWORK, LOCKED, NO_SENDER
}
