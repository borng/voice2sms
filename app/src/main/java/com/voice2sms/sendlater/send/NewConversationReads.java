package com.voice2sms.sendlater.send;

import com.voice2sms.sendlater.web.ThreadBubble;
import com.voice2sms.sendlater.web.ThreadUrl;

import java.util.ArrayList;
import java.util.List;

/**
 * LIVE-F10 F1 (D30, R31): the pre-send reads of one job in the new-conversation fallback flow.
 * Pure JVM, main thread.
 *
 * <p>In this flow a page that is not the job's 1:1 thread has no gv-message-list, so the check
 * counts the conversation as empty ({@link PreSendRead#classifyNoConversation}). That is only
 * true of the new-conversation draft for this job, never of the bare messages list before
 * voice2sms() has entered the recipient: GV may still open the number's (now existing)
 * conversation there. So:
 * <ul>
 *   <li>A read on the job's own thread page is a {@link Kind#THREAD} read, used as read.</li>
 *   <li>A read elsewhere counts as {@link Kind#EMPTY} only once the page confirmed our body is in
 *       the box ("typed": the recipient was entered and the draft is shown), and only while the
 *       job's thread has never been the page in this flow. Otherwise it is {@link Kind#NOT_YET}
 *       and proves nothing: the agreement starts over and the check keeps reading until its
 *       deadline (UNVERIFIED).</li>
 *   <li>Two reads agree only when they are of the same kind ({@link PreSendRead}).</li>
 *   <li>An empty agreement stands only if the page is still not the job's thread when it is
 *       taken ({@link #emptyStillHolds}); the Send gate drops it later too if the thread shows
 *       up before a click is granted ({@link ScheduledCompose#revokeEmptyClearance}).</li>
 * </ul>
 */
public final class NewConversationReads {

    /** What one read in the new-conversation flow is. */
    public enum Kind {
        /** The page is the job's own thread: the read is the read. */
        THREAD,
        /** The new-conversation draft for this job (typed, never seen the thread): empty conversation. */
        EMPTY,
        /** Neither (the messages list before the recipient, or after the thread showed up): proves nothing. */
        NOT_YET
    }

    /** An agreed read: {@link #empty} for an empty conversation, else {@link #bubbles} as read. */
    public static final class Agreed {
        public final boolean empty;
        public final List<ThreadBubble> bubbles;

        public Agreed(boolean empty, List<ThreadBubble> bubbles) {
            this.empty = empty;
            this.bubbles = bubbles;
        }
    }

    private final String e164;
    private PreSendRead reads = new PreSendRead();
    private Kind lastKind;
    private boolean sawThread;

    public NewConversationReads(String e164) {
        this.e164 = e164;
    }

    /**
     * Notes a main-frame URL of this flow. True when it is the job's own thread page; from then
     * on no read off that page ever counts as an empty conversation for this job.
     */
    public boolean onUrl(String url) {
        boolean onThread = ThreadUrl.isThreadOf(url, e164);
        if (onThread) sawThread = true;
        return onThread;
    }

    /** The job's thread was the page at some point in this flow. */
    public boolean sawThread() {
        return sawThread;
    }

    /** The kind of a read taken on {@code url}; {@code typed}: the page confirmed our body in the box. */
    public Kind kindOf(String url, boolean typed) {
        if (onUrl(url)) return Kind.THREAD;
        if (sawThread || !typed) return Kind.NOT_YET;
        return Kind.EMPTY;
    }

    /**
     * Feeds one read ({@code bubbles} from the reader, null when nothing is shown) taken on
     * {@code url}. Returns the agreed read once two reads of the same kind at least
     * {@link PreSendRead#MIN_GAP_MS} apart agree, else null (keep reading).
     */
    public Agreed onRead(String url, boolean typed, List<ThreadBubble> bubbles, long nowMs) {
        Kind kind = kindOf(url, typed);
        if (kind != lastKind) reads = new PreSendRead();
        lastKind = kind;
        if (kind == Kind.NOT_YET) {
            reads.onRead(null, nowMs);
            return null;
        }
        List<ThreadBubble> view = kind == Kind.EMPTY ? new ArrayList<ThreadBubble>() : bubbles;
        List<ThreadBubble> agreed = reads.onRead(view, nowMs);
        return agreed == null ? null : new Agreed(kind == Kind.EMPTY, agreed);
    }

    /**
     * An empty agreement may clear the send only if the page ({@code url}, now) is still not the
     * job's thread and never was. False -> the agreement is dropped and reading goes on.
     */
    public boolean emptyStillHolds(String url) {
        boolean onThread = onUrl(url);
        if (onThread || sawThread) {
            restart();
            return false;
        }
        return true;
    }

    /** Starts the agreement over (a revoked clearance, a dropped empty agreement). */
    public void restart() {
        reads = new PreSendRead();
        lastKind = null;
    }
}
