package com.voice2sms.sendlater.domain;

/** What an unrelated outbound message since the watermark means (R26, D8). Default STILL_SEND. */
public enum OutboundPolicy {
    STILL_SEND, HOLD_AND_ASK
}
