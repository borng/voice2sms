package com.voice2sms.sendlater.domain;

import java.util.List;

/**
 * MVP stub for the RPC thread read (R32 priority 1, A6, backlog D21/D24): never available.
 * The real implementation is a drop-in replacement.
 */
public final class RpcThreadEventSource implements ThreadEventSource {

    @Override public boolean isAvailable() {
        return false;
    }

    /** Always throws: an unavailable source must not be read (an empty list would mean UNCHANGED). */
    @Override public List<ThreadEvent> readSince(ConversationRef ref, Watermark wm) {
        throw new IllegalStateException("RPC thread read is not available in this build");
    }
}
