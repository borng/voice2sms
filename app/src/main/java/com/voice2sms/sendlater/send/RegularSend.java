package com.voice2sms.sendlater.send;

/**
 * Deliver M1: a regular compose / auto-send (Gemini, Wear, RESPOND_VIA_MESSAGE, a plain
 * SENDTO review) is running in the shared WebView. GVoiceWebViewActivity marks it when a regular
 * compose intent arrives and again when it injects voice2sms() ({@link #begin}); the mark ends
 * when the script clicks Send (after {@link #AFTER_CLICK_GRACE_MS}, so GV can post the message
 * before any page load), gives up, finishes filling a review-mode compose, or when the renderer
 * dies ({@link #clear}). It never outlives {@link #MAX_MS} from the last begin.
 *
 * <p>While it is running a scheduled job never loads a page over it: the sender's launch waits
 * up to {@link #MAX_LAUNCH_WAIT_MS} for it to end, then refuses with
 * {@link ForegroundUse#DETAIL_USER_BUSY} (the engine's existing user-busy reminder); the
 * controller refuses a claimed launch the same way. The script's progress is read from its own
 * console lines (voice2sms() stays as on main, I8); a page that prints those lines can only end
 * the mark early, which is the old behaviour. Pure JVM; any thread.
 */
public final class RegularSend {

    /** The mark never lasts longer than this after the last {@link #begin}. */
    public static final long MAX_MS = 60_000L;
    /** After the script clicked Send, page loads wait this long so GV can post the message. */
    public static final long AFTER_CLICK_GRACE_MS = 5_000L;
    /** The scheduled launch re-checks this often while a regular send runs. */
    public static final long LAUNCH_WAIT_POLL_MS = 500L;
    /**
     * The longest a scheduled launch waits for a regular send. Below the sender's claim timeout
     * (30 s), so the launch watchdog never fires on a wait.
     */
    public static final long MAX_LAUNCH_WAIT_MS = 20_000L;

    /** What a voice2sms() console line says about the regular compose. */
    public enum Event {
        /** Not a progress line. */
        NONE,
        /** The script clicked Send. */
        SENT,
        /** The script gave up (page, recipient field, message box or Send button not found). */
        GAVE_UP,
        /** A review-mode compose finished filling (the user sends by hand). */
        FILLED
    }

    /** The scheduled launch's next step while a regular send may be running. */
    public enum LaunchWait { PROCEED, WAIT, BUSY }

    static final String PREFIX = "[Voice2SMS] ";
    static final String CLICKING_SEND = PREFIX + "Clicking send";
    static final String NO_SEND_BUTTON = PREFIX + "Could not find send button";
    static final String GAVE_UP = PREFIX + "Gave up waiting for ";
    static final String PAGE_NOT_READY = PREFIX + "Page did not become ready in time";
    static final String FILLING_BODY = PREFIX + "Found message textarea, filling body";
    static final String NO_BODY = PREFIX + "No body to fill";

    private static long startMs = -1L;
    private static long endMs = -1L;
    private static boolean autoSend;

    private RegularSend() {}

    /** A regular compose starts (or is injected again) at {@code nowMs}. */
    public static synchronized void begin(long nowMs, boolean isAutoSend) {
        startMs = nowMs;
        endMs = -1L;
        autoSend = isAutoSend;
    }

    /** A voice2sms() console line arrived at {@code nowMs}; ends the mark when it says so. */
    public static synchronized void onConsole(String message, long nowMs) {
        if (startMs < 0 || endMs >= 0) return;
        switch (eventOf(message, autoSend)) {
            case SENT:
                endMs = nowMs + AFTER_CLICK_GRACE_MS;
                return;
            case GAVE_UP:
            case FILLED:
                endMs = nowMs;
                return;
            case NONE:
            default:
        }
    }

    /** The page that ran the script is gone (renderer crash). */
    public static synchronized void clear() {
        startMs = -1L;
        endMs = -1L;
        autoSend = false;
    }

    /** A regular compose / auto-send holds the WebView at {@code nowMs}. */
    public static synchronized boolean isRunning(long nowMs) {
        return isRunning(startMs, endMs, nowMs);
    }

    /** Started, not past {@link #MAX_MS}, and not ended (or still inside the after-click grace). */
    static boolean isRunning(long startMs, long endMs, long nowMs) {
        if (startMs < 0) return false;
        long since = nowMs - startMs;
        if (since < 0 || since >= MAX_MS) return false;
        return endMs < 0 || nowMs < endMs;
    }

    /** Maps a console line of voice2sms() to its progress event. */
    static Event eventOf(String message, boolean autoSend) {
        if (message == null || !message.startsWith(PREFIX)) return Event.NONE;
        if (message.startsWith(CLICKING_SEND)) return Event.SENT;
        if (message.startsWith(NO_SEND_BUTTON) || message.startsWith(GAVE_UP)
                || message.startsWith(PAGE_NOT_READY)) {
            return Event.GAVE_UP;
        }
        // An empty body never reaches Send, auto-send or not.
        if (message.startsWith(NO_BODY)) return Event.FILLED;
        if (!autoSend && message.startsWith(FILLING_BODY)) return Event.FILLED;
        return Event.NONE;
    }

    /** Proceed when nothing runs; wait while it runs, up to {@link #MAX_LAUNCH_WAIT_MS}; then busy. */
    public static LaunchWait launchWait(boolean regularRunning, long waitedMs) {
        if (!regularRunning) return LaunchWait.PROCEED;
        return waitedMs < MAX_LAUNCH_WAIT_MS ? LaunchWait.WAIT : LaunchWait.BUSY;
    }

    /** Tests only. */
    static void reset() {
        clear();
    }
}
