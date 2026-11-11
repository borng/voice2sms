package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.Classification;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.PreSendCheck;
import com.voice2sms.sendlater.domain.PreSendCheck.Verdict;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.web.ThreadBubble;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * live-presend-failclosed fix pass 1 (R31, D30, OD22, LIVE-F10): a thread-mode (or Kind THREAD)
 * pre-send agreement on an empty bubble list (gv-message-list rendered with no item, which a slow
 * render looks like) goes through the same fail-closed empty-conversation rule as Kind EMPTY.
 * Fictional number and text only. Paths relative to app/.
 */
public class ThreadModeEmptyReadTest {

    private static final long MIN = 60_000L;
    private static final long T0 = 1_781_092_800_000L;
    private static final long NOW = T0 + 60 * MIN;
    private static final Watermark NOTHING = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, T0, null);

    private static ScheduledMessage message(Watermark wm) {
        return new ScheduledMessage.Builder()
                .id(9L).createdAt(T0 + MIN).scheduledFor(T0 + 60 * MIN).text("See you at the station")
                .recipientE164("+15555550123")
                .source(ScheduledMessage.Source.WEBVIEW).rung(Rung.R1).holdIfReplied(true)
                .latenessPolicy(LatenessPolicy.DEFAULT).status(ScheduledMessage.Status.IN_FLIGHT)
                .watermark(wm).build();
    }

    /** The controller's routing of an agreed read, as onPreSendRead does it. */
    private static Verdict agreedVerdict(PreSendCheck check, ScheduledMessage m, boolean emptyKind,
                                         List<ThreadBubble> bubbles) {
        boolean emptyRead = PreSendRead.readsAsEmpty(emptyKind, bubbles);
        Classification c = emptyRead
                ? PreSendRead.classifyNoConversation(m, ScheduledSendController.READ_LIMIT, NOW)
                : PreSendRead.classify(m, bubbles, ScheduledSendController.READ_LIMIT, NOW);
        return emptyRead ? PreSendRead.noConversationVerdict(check, m, c) : check.decide(m, c);
    }

    private static List<ThreadBubble> agreedEmptyThreadRead() {
        PreSendRead reads = new PreSendRead();
        assertEquals(null, reads.onRead(new ArrayList<ThreadBubble>(), NOW - 10_000L));
        List<ThreadBubble> agreed = reads.onRead(new ArrayList<ThreadBubble>(), NOW);
        assertNotNull("two [] reads far enough apart agree", agreed);
        assertTrue(agreed.isEmpty());
        return agreed;
    }

    private static PreSendCheck required(boolean on, boolean saw) {
        return PreSendCheck.required(OutboundPolicy.STILL_SEND).withListenerFacts(on, saw);
    }

    @Test public void anEmptyBubbleListReadsAsEmptyInEveryMode() {
        assertTrue(PreSendRead.readsAsEmpty(true, new ArrayList<ThreadBubble>()));
        assertTrue(PreSendRead.readsAsEmpty(false, new ArrayList<ThreadBubble>()));
        assertTrue(PreSendRead.readsAsEmpty(false, null));
    }

    @Test public void threadModeEmptyAgreementWithTheListenerOffIsUnverified() {
        List<ThreadBubble> agreed = agreedEmptyThreadRead();
        assertEquals(Verdict.HOLD_UNVERIFIED, agreedVerdict(required(false, false), message(NOTHING), false, agreed));
    }

    @Test public void threadModeEmptyAgreementWithAListenerMatchIsUnverified() {
        assertEquals(Verdict.HOLD_UNVERIFIED,
                agreedVerdict(required(true, true), message(NOTHING), false, agreedEmptyThreadRead()));
    }

    @Test public void threadModeEmptyAgreementWithSomethingSeenAtSchedulingIsUnverified() {
        Watermark hashed = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, "abc123", T0, null);
        Watermark id = new Watermark(Watermark.Quality.DEVICE_ONLY, "srv-1", null, null, T0, null);
        assertEquals(Verdict.HOLD_UNVERIFIED,
                agreedVerdict(required(true, false), message(hashed), false, agreedEmptyThreadRead()));
        assertEquals(Verdict.HOLD_UNVERIFIED,
                agreedVerdict(required(true, false), message(id), false, agreedEmptyThreadRead()));
    }

    @Test public void threadModeEmptyAgreementWithoutListenerFactsIsUnverified() {
        assertEquals(Verdict.HOLD_UNVERIFIED, agreedVerdict(ScheduledSendController.defaultPreSendCheck(),
                message(NOTHING), false, agreedEmptyThreadRead()));
    }

    @Test public void threadModeEmptyAgreementClearsOnlyWithAllThree() {
        assertEquals(Verdict.SEND,
                agreedVerdict(required(true, false), message(NOTHING), false, agreedEmptyThreadRead()));
    }

    @Test public void controllerRoutesEveryEmptyAgreementThroughTheFailClosedRule() throws IOException {
        String c = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/send/ScheduledSendController.java")), StandardCharsets.UTF_8);
        String read = block(c, "private void onPreSendRead\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(read.contains("boolean emptyRead = PreSendRead.readsAsEmpty(agreed.empty, agreed.bubbles)"));
        assertTrue(read.contains("Classification c = emptyRead"));
        assertTrue(read.contains("PreSendCheck.Verdict v = emptyRead"));
        assertFalse("no empty-kind-only routing left", read.contains("Classification c = agreed.empty")
                || read.contains("PreSendCheck.Verdict v = agreed.empty"));
        assertTrue("the empty-conversation gate opener stays Kind EMPTY only",
                read.contains("onPreSendVerdict(job, v, now, agreed.empty)"));
    }

    private static String block(String src, String sig) {
        Matcher m = Pattern.compile(sig).matcher(src);
        assertTrue("not found: " + sig, m.find());
        int open = src.indexOf('{', m.end() - 1);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced " + sig);
    }
}
