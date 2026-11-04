package com.voice2sms.sendlater.send;

/**
 * Whether the user is actively using the voice2sms WebView (OD23 ruling 2): GVoiceWebViewActivity
 * is resumed and was touched in the last {@link #BUSY_WINDOW_MS}. A scheduled send then does not
 * take the page over; the sender refuses with {@link #DETAIL_USER_BUSY} and the engine reminds.
 * The activity feeds {@link #noteResumed} / {@link #noteTouch}; the decision is pure JVM.
 */
public final class ForegroundUse {

    public static final long BUSY_WINDOW_MS = 60_000L;
    /** CANNOT_SHOW detail when the user is in the WebView. */
    public static final String DETAIL_USER_BUSY = "user_busy";

    private static volatile boolean resumed;
    private static volatile long lastTouchMs = -1L;

    private ForegroundUse() {}

    public static void noteResumed(boolean isResumed) {
        resumed = isResumed;
    }

    public static void noteTouch(long nowMs) {
        lastTouchMs = nowMs;
    }

    /** Any thread. */
    public static boolean isUserBusy(long nowMs) {
        return isBusy(resumed, lastTouchMs, nowMs);
    }

    public static boolean isBusy(boolean resumed, long lastTouchMs, long nowMs) {
        if (!resumed || lastTouchMs < 0) return false;
        long since = nowMs - lastTouchMs;
        return since >= 0 && since < BUSY_WINDOW_MS;
    }

    /** Tests only. */
    static void reset() {
        resumed = false;
        lastTouchMs = -1L;
    }
}
