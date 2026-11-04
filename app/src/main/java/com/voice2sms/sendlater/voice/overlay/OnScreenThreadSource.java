package com.voice2sms.sendlater.voice.overlay;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.engine.OutboundCapableSource;
import com.voice2sms.sendlater.engine.SendLaterEngine;
import com.voice2sms.sendlater.voice.logic.VoiceBubbleParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread source for the native Voice thread that is on screen right now (OD21): its visible
 * bubbles, both directions. Available only while a HIGH 1:1 thread is open, its list shows the
 * newest rows, and the conversation asked for is that thread (same number as a number title, or
 * the same display name). Registered once per process with {@link SendLaterEngine#registerSource}.
 */
final class OnScreenThreadSource implements OutboundCapableSource {

    /** The open thread as of the last screen reading. */
    static final class OpenThread {
        final String title;
        final String key;
        final boolean atNewest;

        OpenThread(String title, boolean atNewest) {
            this.title = title;
            this.key = PendingIndex.keyForTitle(title);
            this.atNewest = atNewest;
        }
    }

    static final OnScreenThreadSource INSTANCE = new OnScreenThreadSource();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private volatile VoiceNodeAccessor nodes;
    private volatile OpenThread open;

    private OnScreenThreadSource() {}

    static void registerOnce() {
        if (REGISTERED.compareAndSet(false, true)) SendLaterEngine.registerSource(INSTANCE);
    }

    void bind(VoiceNodeAccessor accessor) {
        nodes = accessor;
        if (accessor == null) open = null;
    }

    void setOpen(OpenThread t) {
        open = t;
    }

    @Override public boolean isAvailable() {
        OpenThread t = open;
        return nodes != null && t != null && t.key != null && t.atNewest;
    }

    @Override public boolean isAvailableFor(ConversationRef ref) {
        if (!isAvailable() || ref == null) return false;
        OpenThread t = open;
        if (t == null) return false;
        if (t.key.equals(ref.key())) return true;
        String name = ref.getDisplayName();
        return name != null && t.key.equals(PendingIndex.keyForTitle(name));
    }

    /**
     * The visible bubbles of the open thread newer than {@code wm}; throws when that thread is no
     * longer on screen. See {@link #sinceWatermark}.
     */
    @Override public List<ThreadEvent> readSince(ConversationRef ref, Watermark wm) {
        VoiceNodeAccessor n = nodes;
        OpenThread t = open;
        if (n == null || t == null || !isAvailableFor(ref)) throw new IllegalStateException("not on screen");
        String live = n.readTitle();
        if (live == null || !live.equals(t.title)) throw new IllegalStateException("thread changed");
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        List<ThreadEvent> rows = new ArrayList<>();
        for (String d : n.readBubbleDescriptions()) rows.add(VoiceBubbleParser.parse(d, now, zone));
        return sinceWatermark(rows, wm);
    }

    /**
     * Filters parsed visible rows (screen order, null = not a bubble) to the ThreadEventSource
     * contract "events newer than {@code wm}". Inbound rows use {@link EarlyHoldFilter}'s anchor
     * logic: bubble times have minute precision, so the watermark bubble itself parses to exactly
     * the watermark time and would otherwise count as a reply; only rows below the visible
     * watermark bubble count, or rows strictly newer when it has scrolled away. Outbound rows are
     * all kept: the classifier bounds them itself (strictly newer than the watermark), and they are
     * what lets this source vouch for an interrupted send (self-preemption, OD21). A null watermark
     * keeps every inbound row (the classifier then applies its createdAt margin). Pure JVM.
     */
    static List<ThreadEvent> sinceWatermark(List<ThreadEvent> rows, Watermark wm) {
        List<ThreadEvent> out = new ArrayList<>();
        if (rows == null) return out;
        Set<ThreadEvent> inbound = null;
        if (wm != null) {
            inbound = Collections.newSetFromMap(new IdentityHashMap<ThreadEvent, Boolean>());
            inbound.addAll(EarlyHoldFilter.newInbound(rows, wm, wm.getCapturedAtDevice()));
        }
        for (ThreadEvent e : rows) {
            if (e == null) continue;
            if (!e.isInbound() || inbound == null || inbound.contains(e)) out.add(e);
        }
        return out;
    }
}
