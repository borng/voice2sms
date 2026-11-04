package com.voice2sms.sendlater.domain;

/** Why a message is HELD (R26, R28). Values pinned (OD8/OD14/OD15). */
public enum HoldReason {
    REPLIED, REACHED_OUT, OUTBOUND_ONLY, UNVERIFIED, LOCKED
}
