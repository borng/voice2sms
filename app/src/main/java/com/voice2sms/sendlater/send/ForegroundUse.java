package com.voice2sms.sendlater.send;

/**
 * Whether the user is actively using the voice2sms WebView (OD23 ruling 2): GVoiceWebViewActivity
 * is resumed and either was touched in the last {@link #BUSY_WINDOW_MS} or its message box holds
 * text (send-hardening R5: typing on the soft keyboard reaches the IME window, not the activity,
 * so a long draft shows no touches). A scheduled send then does not take the page over (its
 * reload would drop the draft); the sender refuses with {@link #DETAIL_USER_BUSY} and the engine
 * reminds. The activity feeds {@link #noteResumed} / {@link #noteTouch} / {@link #noteComposer};
 * the decision is pure JVM.
 */
public final class ForegroundUse {

    public static final long BUSY_WINDOW_MS = 60_000L;
    /** CANNOT_SHOW detail when the user is in the WebView. */
    public static final String DETAIL_USER_BUSY = "user_busy";

    /** The activity probes its message box this often while resumed and Send Later is on (R5, R40). */
    public static final long COMPOSER_PROBE_INTERVAL_MS = 2_000L;
    /** A composer sample older than this says nothing (the probe stopped). */
    public static final long COMPOSER_SAMPLE_MAX_AGE_MS = 5_000L;

    private static volatile boolean resumed;
    private static volatile long lastTouchMs = -1L;
    private static volatile boolean composerHasText;
    private static volatile long composerSampleMs = -1L;

    private ForegroundUse() {}

    public static void noteResumed(boolean isResumed) {
        resumed = isResumed;
    }

    /** Whether GVoiceWebViewActivity is resumed (as last noted). */
    public static boolean isResumed() {
        return resumed;
    }

    public static void noteTouch(long nowMs) {
        lastTouchMs = nowMs;
    }

    /**
     * The message box holds text ({@code hasText}) as probed at {@code nowMs}. The activity
     * reports false while a scheduled send owns the page (that text is ours, not a draft).
     */
    public static void noteComposer(boolean hasText, long nowMs) {
        composerSampleMs = nowMs;
        composerHasText = hasText;
    }

    /** Any thread. */
    public static boolean isUserBusy(long nowMs) {
        return isBusy(resumed, lastTouchMs, composerHasText, composerSampleMs, nowMs);
    }

    /** Resumed and (touched in the last minute, or a fresh probe found text in the box). */
    public static boolean isBusy(boolean resumed, long lastTouchMs, boolean composerHasText,
                                 long composerSampleMs, long nowMs) {
        if (!resumed) return false;
        if (isBusy(true, lastTouchMs, nowMs)) return true;
        if (!composerHasText || composerSampleMs < 0) return false;
        long age = nowMs - composerSampleMs;
        return age >= 0 && age <= COMPOSER_SAMPLE_MAX_AGE_MS;
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
        composerHasText = false;
        composerSampleMs = -1L;
    }
}
