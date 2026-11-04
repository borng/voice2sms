package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.web.ThreadUrl;

/**
 * Lock-screen rules for the scheduled branch of GVoiceWebViewActivity (send-lock-cover, OD26,
 * R30, I7, I8). Pure JVM; {@link ScheduledSendController} applies the answers.
 *
 * <p>OD26 (replaces OD25): every claimed scheduled job is covered for its whole run, locked or
 * not. The keyguard state is never a reason to skip the cover; it only decides what happens when
 * the job ends (finish, or uncover and stay).
 * <ul>
 *   <li>{@link #showCover}: an opaque cover hides the WebView and eats input from the claim until
 *       the job ends; after the end it stays (until the finish) only while the phone is locked.</li>
 *   <li>{@link #applyLockFlags}: show-when-locked / turn-screen-on / keep-screen-on only while a
 *       CLAIMED job runs AND the cover is visible; never for an unclaimed, stale or replayed
 *       intent, never on an uncovered page. The controller clears them before the cover comes
 *       down.</li>
 *   <li>{@link #endsJob}: the {@link #HARD_TIMEOUT_MS} timeout and a pause or stop end every
 *       running scheduled job, wherever it was launched.</li>
 *   <li>{@link #allowNavigation}: while covered, only the target 1:1 thread may load.</li>
 *   <li>{@link #finishNow}: a pause or stop finishes the activity (the user left or locked the
 *       phone); a job end or timeout finishes it only while the phone is locked (or dark). When
 *       unlocked the cover comes off and the user sees the thread.</li>
 * </ul>
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

    /** A scheduled job never runs longer than this from its launch claim (every job, OD26). */
    public static final long HARD_TIMEOUT_MS = 60_000L;

    private LockCoverPolicy() {}

    /** Lock flags only for a claimed, running job whose cover is up (OD26). */
    public static boolean applyLockFlags(boolean claimed, JobState state, boolean coverShown) {
        return claimed && state == JobState.RUNNING && coverShown;
    }

    /**
     * Cover for every claimed running job, locked or not (OD26). After the job ended it stays
     * only while {@code lockedNow} (the activity is about to finish).
     */
    public static boolean showCover(boolean claimed, JobState state, boolean lockedNow) {
        if (!claimed) return false;
        if (state == JobState.RUNNING) return true;
        return state == JobState.ENDED && lockedNow;
    }

    /** The hard timeout, a pause and a stop end every running scheduled job (OD26). */
    public static boolean endsJob(Event event, boolean claimed, JobState state) {
        return event != null && claimed && state == JobState.RUNNING;
    }

    /** While covered only the target thread's URL may load; uncovered, the usual rules apply. */
    public static boolean allowNavigation(boolean covered, String targetE164, String url) {
        if (!covered) return true;
        return ThreadUrl.isThreadOf(url, targetE164);
    }

    /**
     * Finish at once: on a pause or stop always (the user left, or locked the phone); on job end
     * or the hard timeout while the phone is locked or dark ({@code lockedNow}, re-read at that
     * moment). Unlocked at job end: never forced; the cover comes off and the user sees the
     * thread.
     */
    public static boolean finishNow(Event event, boolean lockedNow) {
        if (event == null) return false;
        if (event == Event.PAUSED || event == Event.STOPPED) return true;
        return lockedNow;
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
