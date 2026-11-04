package com.voice2sms.sendlater.store;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Data at rest (OD31, deliver whole-branch review M1). Message texts do not outlive the Upcoming
 * "Recent" list: a terminal message (and its watermark) is deleted 30 days after it reached its
 * terminal state ({@code updatedAt}). Thread-directory pairs not seen for 90 days
 * ({@code lastSeenMs}) are deleted. Runs from {@code SendLaterEngine.onProcessStart}, at most once
 * a day (the last run is kept in {@link #PREFS}); a clock moved backwards runs it again.
 *
 * <p>The policy ({@link #isDue}, {@link #run}) is pure and JVM-tested; {@link #runIfDue(Context, long)}
 * is the Android wiring. Synchronous: background threads only.
 */
public final class DataRetention {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** Terminal messages are kept this long after their last status change (= the Recent window, R35). */
    public static final long TERMINAL_RETENTION_MS = SendLaterRepository.RECENT_WINDOW_MS;
    /** Thread-directory pairs are kept this long after they were last seen (OD31 P3). */
    public static final long DIRECTORY_RETENTION_MS = 90L * DAY_MS;
    /** At most one purge per this interval. */
    public static final long MIN_INTERVAL_MS = DAY_MS;

    /** Excluded from backup with the other sendlater_* prefs files. */
    static final String PREFS = "sendlater_retention";
    static final String KEY_LAST_RUN = "last_purge_ms";

    /** What gets deleted. */
    public interface Target {
        /** Deletes terminal messages (and watermarks) whose status changed before {@code beforeMs}. */
        int purgeTerminalBefore(long beforeMs);
        /** Deletes directory pairs last seen before {@code beforeMs}. */
        int purgeDirectoryBefore(long beforeMs);
    }

    /** Where the last run time lives. */
    public interface Mark {
        long lastRunMs();
        void setLastRunMs(long ms);
    }

    /** Rows deleted by one run; {@code ran} false when it was not due. */
    public static final class Result {
        public final boolean ran;
        public final int messages;
        public final int directory;

        Result(boolean ran, int messages, int directory) {
            this.ran = ran;
            this.messages = messages;
            this.directory = directory;
        }
    }

    private DataRetention() {}

    /** Due when never run, a day or more since the last run, or the clock is now before it. */
    public static boolean isDue(long lastRunMs, long nowMs) {
        if (lastRunMs <= 0) return true;
        if (nowMs < lastRunMs) return true;
        return nowMs - lastRunMs >= MIN_INTERVAL_MS;
    }

    public static long terminalCutoff(long nowMs) { return nowMs - TERMINAL_RETENTION_MS; }

    public static long directoryCutoff(long nowMs) { return nowMs - DIRECTORY_RETENTION_MS; }

    /**
     * Runs both purges when due and then records {@code nowMs}. The mark is written only after
     * both deletes succeeded, so a failure is retried on the next process start.
     */
    public static Result run(Target target, Mark mark, long nowMs) {
        if (!isDue(mark.lastRunMs(), nowMs)) return new Result(false, 0, 0);
        int messages = target.purgeTerminalBefore(terminalCutoff(nowMs));
        int directory = target.purgeDirectoryBefore(directoryCutoff(nowMs));
        mark.setLastRunMs(nowMs);
        return new Result(true, messages, directory);
    }

    /**
     * Android wiring: does nothing when {@code sendlater.db} was never created (Send Later never
     * used), so the purge does not create it.
     */
    public static Result runIfDue(Context context, long nowMs) {
        final Context app = context.getApplicationContext();
        if (!app.getDatabasePath(SendLaterDatabase.NAME).exists()) return new Result(false, 0, 0);
        final SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final SendLaterRepository repo = SendLaterRepository.get(app);
        return run(new Target() {
            @Override public int purgeTerminalBefore(long beforeMs) { return repo.purgeTerminalBefore(beforeMs); }
            @Override public int purgeDirectoryBefore(long beforeMs) { return repo.purgeDirectoryBefore(beforeMs); }
        }, new Mark() {
            @Override public long lastRunMs() { return prefs.getLong(KEY_LAST_RUN, 0L); }
            @Override public void setLastRunMs(long ms) { prefs.edit().putLong(KEY_LAST_RUN, ms).apply(); }
        }, nowMs);
    }
}
