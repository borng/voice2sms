package com.voice2sms.sendlater.domain;

/**
 * Why a message is HELD (R26, R28). Values pinned (OD8/OD14/OD15). DRAFT (D42): a send stopped on
 * the user's own draft in the conversation's box; it waits for the user's tap, never sends itself.
 * Stored by name in a TEXT column with no constraint, so adding a value needs no migration.
 */
public enum HoldReason {
    REPLIED, REACHED_OUT, OUTBOUND_ONLY, UNVERIFIED, LOCKED, DRAFT
}
