package com.voice2sms.sendlater.domain;

/** Result of the thread check (R26). */
public enum Classification {
    REPLIED, REACHED_OUT, SELF_PREEMPTED, OUTBOUND_ONLY, UNCHANGED, UNVERIFIED
}
