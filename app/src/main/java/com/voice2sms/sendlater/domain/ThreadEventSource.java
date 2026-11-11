package com.voice2sms.sendlater.domain;

import java.util.List;

/**
 * A place the thread check can read conversation events from (R32): RPC thread read (backlog),
 * the notification-listener store, on-screen bubbles. Callers must check {@link #isAvailable()}
 * first and treat "no available source" as {@code events == null}, which classifies as
 * {@link Classification#UNVERIFIED}.
 */
public interface ThreadEventSource {

    /** False when the source is not enabled (permission off, backlog stub, ...). */
    boolean isAvailable();

    /**
     * Whether this source can vouch for {@code ref}'s conversation (OD22(5)). A source that can
     * only see some conversations (the notification listener, which matches by display name or by
     * a number it has seen) returns false for the others, so the thread check reads nothing from it
     * and an empty read is never taken as "no reply". Default: {@link #isAvailable()}.
     */
    default boolean isAvailableFor(ConversationRef ref) {
        return isAvailable();
    }

    /** Events in {@code ref}'s conversation newer than {@code wm}, oldest first. Never null. */
    List<ThreadEvent> readSince(ConversationRef ref, Watermark wm);
}
