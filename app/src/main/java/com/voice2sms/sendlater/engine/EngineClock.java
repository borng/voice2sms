package com.voice2sms.sendlater.engine;

/** Wall clock seam for the engine and the scheduler. */
public interface EngineClock {
    long nowMs();
}
