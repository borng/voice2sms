package com.voice2sms.sendlater.send;

import android.os.Looper;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.engine.OutboundCapableSource;
import com.voice2sms.sendlater.engine.SendLaterEngine;
import com.voice2sms.sendlater.web.SendLaterWebHooks;
import com.voice2sms.sendlater.web.ThreadBubble;
import com.voice2sms.sendlater.web.ThreadUrl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The open GV web thread as a thread source (R32, OD21). It sees the user's own outbound bubbles,
 * so it is an {@link OutboundCapableSource}: an empty-of-own-bubble read from it lets the engine
 * retry an interrupted send. It is available for a conversation only while a live
 * GVoiceWebViewActivity (its {@link SendLaterWebHooks#active()}) shows that 1:1 thread.
 *
 * <p>{@link #readSince} blocks the calling engine thread on the page read (main thread). It never
 * returns an empty list for a read that did not happen: on the main thread, with no live
 * activity, on timeout or when the page cannot read the bubbles it throws, which the engine
 * treats as "this source was not read" (no retry on its say-so).
 */
public final class WebViewThreadEventSource implements OutboundCapableSource {

    /** Newest bubbles asked from the page per read. */
    static final int READ_LIMIT = 50;
    /** Longer than the page read timeout (5 s) so the page's own null answer arrives first. */
    static final long WAIT_MS = 7_000L;
    /** Read from this long before the watermark (the classifier applies the exact window). */
    static final long READ_SLACK_MS = 15 * 60_000L;

    /** Production wiring, from Voice2SmsApplication.onCreate before the engine's process start. */
    public static void register() {
        SendLaterEngine.registerSource(new WebViewThreadEventSource());
    }

    @Override public boolean isAvailable() {
        return SendLaterWebHooks.active() != null && ScheduledSendController.liveMainFrameUrl() != null;
    }

    @Override public boolean isAvailableFor(ConversationRef ref) {
        if (ref == null || ref.getE164() == null) return false;
        if (SendLaterWebHooks.active() == null) return false;
        return ThreadUrl.isThreadOf(ScheduledSendController.liveMainFrameUrl(), ref.getE164());
    }

    @Override public List<ThreadEvent> readSince(ConversationRef ref, Watermark wm) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("web thread read on the main thread");
        }
        SendLaterWebHooks hooks = SendLaterWebHooks.active();
        if (ref == null || ref.getE164() == null || hooks == null) {
            throw new IllegalStateException("no open web thread");
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<List<ThreadBubble>> got = new AtomicReference<>();
        hooks.readThread(ref.getE164(), READ_LIMIT, bubbles -> {
            got.set(bubbles);
            done.countDown();
        });
        try {
            if (!done.await(WAIT_MS, TimeUnit.MILLISECONDS)) throw new IllegalStateException("web thread read timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("web thread read interrupted");
        }
        List<ThreadBubble> bubbles = got.get();
        if (bubbles == null) throw new IllegalStateException("web thread not readable");
        long since = sinceMs(wm);
        List<ThreadEvent> out = new ArrayList<>();
        for (ThreadEvent e : toEvents(bubbles, System.currentTimeMillis())) {
            if (e.getTimeMs() >= since) out.add(e);
        }
        return out;
    }

    /**
     * Bubbles (oldest first) to SMS events with textHash = {@link ThreadEvent#hashOf}. A bubble
     * whose time the page could not read takes the time of the next timed bubble after it, or
     * {@code nowMs} when none follows: the latest it can be. That errs towards "recent", so a
     * just-sent bubble still showing no time is never missed (which could allow a duplicate
     * send) and an untimed reply is never read as old (which could skip a hold).
     */
    static List<ThreadEvent> toEvents(List<ThreadBubble> bubbles, long nowMs) {
        List<ThreadEvent> out = new ArrayList<>();
        if (bubbles == null) return out;
        long upper = nowMs;
        long[] times = new long[bubbles.size()];
        for (int i = bubbles.size() - 1; i >= 0; i--) {
            ThreadBubble b = bubbles.get(i);
            Long t = b != null ? b.getTimeMs() : null;
            if (t != null) upper = t;
            times[i] = upper;
        }
        for (int i = 0; i < bubbles.size(); i++) {
            ThreadBubble b = bubbles.get(i);
            if (b == null || b.getDirection() == null) continue;
            ThreadEvent e = b.toThreadEvent();
            if (e == null) {
                e = new ThreadEvent(b.getDirection(), ThreadEvent.Kind.SMS, times[i],
                        ThreadEvent.hashOf(b.getText()), b.getText());
            }
            out.add(e);
        }
        return out;
    }

    static long sinceMs(Watermark wm) {
        if (wm == null) return 0L;
        long floor = wm.getCapturedAtDevice();
        if (wm.getNewestTs() != null) floor = Math.min(floor, wm.getNewestTs());
        long skew = wm.getSkewEstimateMs() != null ? Math.abs(wm.getSkewEstimateMs()) : 0L;
        return Math.max(0L, floor - READ_SLACK_MS - skew);
    }
}
