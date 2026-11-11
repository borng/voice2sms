package com.voice2sms.sendlater.voice.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.Classification;
import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEventClassifier;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.voice.logic.VoiceBubbleParser;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/** The on-screen source honours "events newer than wm" (ThreadEventSource contract, R33, OD21). */
public class OnScreenThreadSourceTest {

    private static final String NAME = "Alex Example";
    private static final long MIN = 60_000L;
    private static final long T = 1_793_000_040_000L - (1_793_000_040_000L % MIN);

    private static ThreadEvent in(long t, String text) {
        return new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.SMS, t, ThreadEvent.hashOf(text), text);
    }

    private static ThreadEvent out(long t, String text) {
        return new ThreadEvent(ThreadEvent.Direction.OUT, ThreadEvent.Kind.SMS, t, ThreadEvent.hashOf(text), text);
    }

    private static Watermark ts(ThreadEvent newest) {
        return new Watermark(Watermark.Quality.TIMESTAMP, null, newest.getTimeMs(), newest.getTextHash(), T + 30_000L, null);
    }

    private static ScheduledMessage message(Watermark wm) {
        return new ScheduledMessage.Builder().id(1L).createdAt(T + 30_000L).scheduledFor(T + 60 * MIN)
                .text("talk tomorrow about the plan").recipientDisplayName(NAME).holdIfReplied(true)
                .watermark(wm).build();
    }

    @After public void unbind() {
        OnScreenThreadSource.INSTANCE.bind(null);
    }

    @Test public void watermarkBubbleVisibleAtDueTimeIsNotAReply() {
        // The contact's text, then "reply later": the watermark is that very bubble.
        FakeVoiceNodes n = new FakeVoiceNodes(new ArrayList<String>(), NAME, "");
        n.bubbles = new ArrayList<>(Arrays.asList("Hi there from me, 6:40 PM", "Can you call me from Alex Example, 6:45 PM"));
        long now = System.currentTimeMillis();
        Watermark wm = VoiceBubbleParser.bubbleWatermark(n.bubbles, now, TimeZone.getDefault());
        assertEquals(Watermark.Quality.TIMESTAMP, wm.getQuality());

        OnScreenThreadSource src = OnScreenThreadSource.INSTANCE;
        src.bind(n);
        src.setOpen(new OnScreenThreadSource.OpenThread(NAME, true));
        ConversationRef ref = new ConversationRef(null, NAME);
        assertTrue(src.isAvailableFor(ref));

        List<ThreadEvent> got = src.readSince(ref, wm);
        for (ThreadEvent e : got) assertTrue("no inbound at or before the watermark bubble", !e.isInbound());
        assertNotEquals(Classification.REPLIED, ThreadEventClassifier.classify(message(wm), got, false));

        // A real reply below the watermark bubble still counts.
        n.bubbles.add("Never mind from Alex Example, 6:45 PM");
        List<ThreadEvent> after = src.readSince(ref, wm);
        int inbound = 0;
        for (ThreadEvent e : after) if (e.isInbound()) inbound++;
        assertEquals("only the reply below the watermark bubble", 1, inbound);
        assertEquals(Classification.REPLIED, ThreadEventClassifier.classify(message(wm), after, false));
    }

    @Test public void inboundAtTheWatermarkIsDroppedOutboundIsKept() {
        ThreadEvent older = in(T - MIN, "hi");
        ThreadEvent mark = in(T, "see you");
        ThreadEvent mine = out(T + MIN, "talk tomorrow about the plan");
        List<ThreadEvent> got = OnScreenThreadSource.sinceWatermark(
                Arrays.asList(older, null, mark, mine), ts(mark));
        assertEquals("outbound rows stay for self-preemption (OD21)", Collections.singletonList(mine), got);
        assertEquals(Classification.SELF_PREEMPTED, ThreadEventClassifier.classify(message(ts(mark)), got, false));
    }

    @Test public void scrolledAwayWatermarkCountsOnlyStrictlyNewerInbound() {
        ThreadEvent mark = in(T, "see you");
        ThreadEvent sameMinute = in(T, "other");
        ThreadEvent later = in(T + MIN, "later");
        assertEquals(Collections.singletonList(later),
                OnScreenThreadSource.sinceWatermark(Arrays.asList(sameMinute, later), ts(mark)));
    }

    @Test public void nullWatermarkKeepsEveryRowForTheClassifier() {
        ThreadEvent a = in(T, "a");
        ThreadEvent b = out(T, "b");
        assertEquals(Arrays.asList(a, b), OnScreenThreadSource.sinceWatermark(Arrays.asList(a, null, b), null));
        assertTrue(OnScreenThreadSource.sinceWatermark(null, null).isEmpty());
    }
}
