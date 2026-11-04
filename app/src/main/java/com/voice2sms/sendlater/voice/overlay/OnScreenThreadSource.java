package com.voice2sms.sendlater.voice.overlay;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.engine.OutboundCapableSource;
import com.voice2sms.sendlater.engine.SendLaterEngine;
import com.voice2sms.sendlater.voice.logic.VoiceBubbleParser;

import java.util.ArrayList;
import java.util.List;
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

    /** The visible bubbles of the open thread; throws when that thread is no longer on screen. */
    @Override public List<ThreadEvent> readSince(ConversationRef ref, Watermark wm) {
        VoiceNodeAccessor n = nodes;
        OpenThread t = open;
        if (n == null || t == null || !isAvailableFor(ref)) throw new IllegalStateException("not on screen");
        String live = n.readTitle();
        if (live == null || !live.equals(t.title)) throw new IllegalStateException("thread changed");
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        List<ThreadEvent> out = new ArrayList<>();
        for (String d : n.readBubbleDescriptions()) {
            ThreadEvent e = VoiceBubbleParser.parse(d, now, zone);
            if (e != null) out.add(e);
        }
        return out;
    }
}
