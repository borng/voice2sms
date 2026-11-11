package com.voice2sms.sendlater.domain;

/** Result of one send attempt (R31). Values pinned. */
public enum SendOutcome {
    SENT_VERIFIED, SENT_UNVERIFIED, FAILED, CANNOT_SHOW
}
