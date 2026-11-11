package com.voice2sms.sendlater.engine;

/**
 * WAIT(NO_NETWORK) seam: production registers a {@code registerDefaultNetworkCallback} that re-runs
 * the ladder for {@code id} once a network is available (no job scheduler library).
 */
public interface NetworkWaiter {
    void awaitNetwork(long id);

    /** Drops a pending wait for {@code id}, if any. */
    default void cancel(long id) {}
}
