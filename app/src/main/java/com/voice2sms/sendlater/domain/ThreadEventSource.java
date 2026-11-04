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

    /** Events in {@code ref}'s conversation newer than {@code wm}, oldest first. Never null. */
    List<ThreadEvent> readSince(ConversationRef ref, Watermark wm);
}
