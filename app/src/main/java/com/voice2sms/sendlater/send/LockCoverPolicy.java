package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.web.ThreadUrl;

/**
 * Lock-screen rules for the scheduled branch of GVoiceWebViewActivity (send-lock-cover, R30, I7,
 * I8). Pure JVM; {@link ScheduledSendController} applies the answers.
 *
 * <ul>
 *   <li>{@link #applyLockFlags}: show-when-locked / turn-screen-on / keep-screen-on only while a
 *       CLAIMED launch is running; never for an unclaimed, stale or replayed intent.</li>
 *   <li>{@link #showCover}: over the keyguard the WebView is hidden behind an opaque cover that
 *       eats input, from the claim until the job ends; after the end it stays (until the finish)
 *       while the phone is still locked. Removed only when the job ended and the phone was
 *       unlocked meanwhile (the user then sees the thread normally).</li>
 *   <li>{@link #allowNavigation}: while covered, only the target 1:1 thread may load.</li>
 *   <li>{@link #finishNow}: over the keyguard (still locked) the activity finishes at once on
 *       job end, on the hard timeout and when it is paused or stopped.</li>
 * </ul>
 * The unlocked path (not launched over the keyguard) is unchanged: no cover, no navigation rule,
 * no forced finish.
 */
public final class LockCoverPolicy {

    /** Where the scheduled work of this activity stands. */
    public enum JobState {
        /** Nothing claimed yet (or a stale launch): nothing runs. */
        IDLE,
        /** A claimed send is running or queued, or its engine hand-off is pending. */
        RUNNING,
        /** Everything reported and handed off (or the hard timeout fired). */
        ENDED
    }

    /** Lifecycle moments at which {@link #finishNow} is asked. */
    public enum Event { JOB_ENDED, TIMEOUT, PAUSED, STOPPED }

    /** Over the keyguard a scheduled job never runs longer than this from its launch claim. */
    public static final long HARD_TIMEOUT_MS = 60_000L;

    private LockCoverPolicy() {}

    public static boolean applyLockFlags(boolean claimed, JobState state) {
        return claimed && state == JobState.RUNNING;
    }

    /**
     * @param overKeyguard the activity was launched over the keyguard
     * @param lockedNow    the keyguard is still locked
     */
    public static boolean showCover(boolean claimed, boolean overKeyguard, JobState state, boolean lockedNow) {
        if (!claimed || !overKeyguard) return false;
        if (state == JobState.RUNNING) return true;
        return state == JobState.ENDED && lockedNow;
    }

    /** While covered only the target thread's URL may load; uncovered, the usual rules apply. */
    public static boolean allowNavigation(boolean covered, String targetE164, String url) {
        if (!covered) return true;
        return ThreadUrl.isThreadOf(url, targetE164);
    }

    /**
     * Finish immediately: when launched over the keyguard and still locked, on job end, on the
     * hard timeout and on a pause or stop. Unlocked during the send: never forced; at job end the
     * cover comes off and the user sees the thread (send-lock-cover 6).
     */
    public static boolean finishNow(Event event, boolean overKeyguard, boolean lockedNow) {
        if (event == null) return false;
        return overKeyguard && lockedNow;
    }

    /** Masked recipient for the cover: last four digits only, never a name or message text. */
    public static String maskedNumber(String e164) {
        if (e164 == null) return "";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < e164.length(); i++) {
            char c = e164.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        if (digits.length() < 4) return "";
        return "••• " + digits.substring(digits.length() - 4);
    }
}
