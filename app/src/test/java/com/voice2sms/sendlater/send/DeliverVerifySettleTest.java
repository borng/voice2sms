package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEvent.Direction;
import com.voice2sms.sendlater.web.ThreadBubble;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Deliver M4: a bubble GV still shows as sending never settles a verification, and after
 * SENT_VERIFIED the same bubble is watched for {@link SentFollowUp#WATCH_MS}; a later "Not sent"
 * downgrades the row through the engine. Fictional text only.
 */
public class DeliverVerifySettleTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long CHECK = NOW - 20_000L;
    private static final String BODY = "Placeholder scheduled text for the settle test";
    private static final String HASH = ThreadEvent.hashOf(BODY);
    private static final Path SEND = Paths.get("src/main/java/com/voice2sms/sendlater/send");

    private static ThreadBubble out(Long t, String text, boolean failed, boolean sending) {
        return new ThreadBubble(Direction.OUT, t, text, null, false, failed, sending);
    }
    private static ThreadBubble sent(String text) { return out(null, text, false, false); }
    private static ThreadBubble sending(Long t, String text) { return out(t, text, false, true); }
    private static ThreadBubble failed(String text) { return out(null, text, true, false); }
    private static ThreadBubble in(Long t, String text) { return new ThreadBubble(Direction.IN, t, text, null); }
    private static List<ThreadBubble> list(ThreadBubble... b) { return new ArrayList<>(Arrays.asList(b)); }

    private static final List<ThreadBubble> BASE = list(in(NOW - 3_600_000L, "earlier reply"),
            sent("an older message"));

    private static List<ThreadBubble> withOwn(ThreadBubble own) {
        List<ThreadBubble> l = new ArrayList<>(BASE);
        l.add(own);
        return l;
    }

    private static SendVerifier.Result poll(SendWatch w, List<ThreadBubble> bubbles, boolean pending, long now) {
        List<ThreadEvent> ev = w.onPoll(bubbles, pending, now);
        SendVerifier.Result v = ev == null ? null : SendVerifier.verify(HASH, ev, CHECK, now);
        return w.settle(v, pending, now);
    }

    // ------------------------------------------------------------------ settle gate

    @Test public void aSendingBubbleNeverSettlesHoweverLongItStays() {
        SendWatch w = new SendWatch(HASH);
        assertNull("baseline", poll(w, BASE, true, NOW - 5_000L));
        for (long t = 0; t <= 20_000L; t += 1_000L) {
            assertNull("still sending at +" + t, poll(w, withOwn(sending(null, BODY)), false, NOW + t));
        }
    }

    @Test public void theSettleClockStartsWhenSendingEnds() {
        SendWatch w = new SendWatch(HASH);
        poll(w, BASE, true, NOW - 5_000L);
        assertNull(poll(w, withOwn(sending(null, BODY)), false, NOW));
        assertNull(poll(w, withOwn(sending(null, BODY)), false, NOW + 10_000L));
        assertNull("first settled sighting", poll(w, withOwn(sent(BODY)), false, NOW + 11_000L));
        assertNull("inside the settle period", poll(w, withOwn(sent(BODY)), false, NOW + 13_999L));
        assertEquals(SendVerifier.Result.VERIFIED,
                poll(w, withOwn(sent(BODY)), false, NOW + 11_000L + SendWatch.SETTLE_MS));
    }

    @Test public void goingBackToSendingRestartsTheSettleClock() {
        SendWatch w = new SendWatch(HASH);
        poll(w, BASE, true, NOW - 5_000L);
        assertNull(poll(w, withOwn(sent(BODY)), false, NOW));
        assertNull(poll(w, withOwn(sending(null, BODY)), false, NOW + 2_000L));
        assertNull("the earlier sighting no longer counts",
                poll(w, withOwn(sent(BODY)), false, NOW + SendWatch.SETTLE_MS));
        assertEquals(SendVerifier.Result.VERIFIED,
                poll(w, withOwn(sent(BODY)), false, NOW + 2 * SendWatch.SETTLE_MS));
    }

    @Test public void aServerTimeDoesNotConfirmWhileSending() {
        SendWatch w = new SendWatch(HASH);
        poll(w, BASE, true, NOW - 5_000L);
        assertNull("a timed bubble that is still sending is not proof",
                poll(w, withOwn(sending(NOW - 1_000L, BODY)), false, NOW));
        assertEquals("the same timed bubble once sent confirms at once", SendVerifier.Result.VERIFIED,
                poll(w, withOwn(out(NOW - 1_000L, BODY, false, false)), false, NOW + 1_000L));
    }

    @Test public void withoutABaselineASendingBubbleDoesNotSettleEither() {
        SendWatch w = new SendWatch(HASH);
        assertNull("unreadable while pending", poll(w, null, true, NOW - 5_000L));
        List<ThreadBubble> newConv = list(sending(NOW - 1_000L, BODY));
        assertNull(poll(w, newConv, false, NOW));
        assertNull(poll(w, newConv, false, NOW + 10_000L));
        assertEquals(SendVerifier.Result.VERIFIED,
                poll(w, list(out(NOW - 1_000L, BODY, false, false)), false, NOW + 11_000L));
    }

    // ------------------------------------------------------------------ follow-up after SENT_VERIFIED

    private static SentFollowUp verifiedFollowUp() {
        SendWatch w = new SendWatch(HASH);
        poll(w, BASE, true, NOW - 5_000L);
        poll(w, withOwn(sent(BODY)), false, NOW);
        assertEquals(SendVerifier.Result.VERIFIED, poll(w, withOwn(sent(BODY)), false, NOW + SendWatch.SETTLE_MS));
        return w.followUp(NOW + SendWatch.SETTLE_MS);
    }

    @Test public void aLaterNotSentIsReportedOnce() {
        SentFollowUp f = verifiedFollowUp();
        long t = NOW + SendWatch.SETTLE_MS;
        assertEquals(SentFollowUp.Step.KEEP, f.onRead(withOwn(sent(BODY)), true, t + 1_000L));
        assertEquals(SentFollowUp.Step.FAILED, f.onRead(withOwn(failed(BODY)), true, t + 8_000L));
        assertTrue(f.isOver());
        assertEquals(SentFollowUp.Step.DONE, f.onRead(withOwn(failed(BODY)), true, t + 9_000L));
    }

    @Test public void theWatchEndsAfterItsWindow() {
        SentFollowUp f = verifiedFollowUp();
        long t = NOW + SendWatch.SETTLE_MS;
        assertEquals(SentFollowUp.Step.DONE,
                f.onRead(withOwn(failed(BODY)), true, t + SentFollowUp.WATCH_MS));
        assertEquals(30_000L, SentFollowUp.WATCH_MS);
    }

    @Test public void theWatchEndsWhenThePageLeavesTheThread() {
        SentFollowUp f = verifiedFollowUp();
        assertEquals(SentFollowUp.Step.DONE, f.onRead(null, false, NOW + 5_000L));
        assertEquals(SentFollowUp.Step.DONE, f.onRead(withOwn(failed(BODY)), true, NOW + 6_000L));
    }

    @Test public void anUnreadableOrUnalignedReadOnTheThreadKeepsWatching() {
        SentFollowUp f = verifiedFollowUp();
        assertEquals(SentFollowUp.Step.KEEP, f.onRead(null, true, NOW + 5_000L));
        assertEquals("cannot be aligned: decide nothing",
                SentFollowUp.Step.KEEP, f.onRead(list(failed(BODY)), true, NOW + 6_000L));
        assertEquals("our bubble gone from view: the verdict stands",
                SentFollowUp.Step.KEEP, f.onRead(BASE, true, NOW + 7_000L));
    }

    @Test public void anOldFailedCopyBeforeTheBaselineIsNotOurs() {
        SendWatch w = new SendWatch(HASH);
        List<ThreadBubble> base = list(in(NOW - 3_600_000L, "earlier reply"), failed(BODY));
        poll(w, base, true, NOW - 5_000L);
        List<ThreadBubble> after = new ArrayList<>(base);
        after.add(sent(BODY));
        poll(w, after, false, NOW);
        assertEquals(SendVerifier.Result.VERIFIED, poll(w, after, false, NOW + SendWatch.SETTLE_MS));
        SentFollowUp f = w.followUp(NOW + SendWatch.SETTLE_MS);
        assertEquals(SentFollowUp.Step.KEEP, f.onRead(after, true, NOW + 5_000L));
    }

    @Test public void withoutABaselineTheNewestOwnOutboundIsWatched() {
        SentFollowUp f = new SentFollowUp(HASH, null, NOW);
        assertEquals(SentFollowUp.Step.KEEP, f.onRead(list(sent(BODY)), true, NOW + 1_000L));
        assertEquals("a newer other outbound: ours cannot be told apart, keep",
                SentFollowUp.Step.KEEP, f.onRead(list(failed(BODY), sent("something else")), true, NOW + 2_000L));
        assertEquals(SentFollowUp.Step.FAILED, f.onRead(list(failed(BODY)), true, NOW + 3_000L));
    }

    // ------------------------------------------------------------------ page report

    @Test public void theReportCarriesSendingAndFailedWins() {
        ThreadBubble.Report r = ThreadBubble.parseReport("{\"e164\":\"+15555550100\",\"bubbles\":["
                + "{\"direction\":\"OUT\",\"text\":\"a\",\"sending\":true},"
                + "{\"direction\":\"OUT\",\"text\":\"b\",\"sending\":true,\"failed\":true},"
                + "{\"direction\":\"OUT\",\"text\":\"c\"}]}");
        assertTrue(r.bubbles.get(0).isSending());
        assertFalse(r.bubbles.get(1).isSending());
        assertTrue(r.bubbles.get(1).isFailed());
        assertFalse(r.bubbles.get(2).isSending());
    }

    // ------------------------------------------------------------------ controller wiring

    private static String code(String file) throws IOException {
        Path p = SEND.resolve(file);
        if (!Files.exists(p)) p = Paths.get("app").resolve(p);
        return SendPathSourceTest.stripComments(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    @Test public void everySentVerifiedReportStartsTheFollowUp() throws IOException {
        String c = code("ScheduledSendController.java");
        assertEquals(2, count(c, "report(job, SendOutcome.SENT_VERIFIED"));
        assertEquals(2, count(c, "startFollowUp(job, now);"));
        int a = c.indexOf("report(job, SendOutcome.SENT_VERIFIED, null);");
        assertTrue(c.substring(a, a + 120).contains("startFollowUp(job, now);"));
        int b = c.indexOf("report(job, SendOutcome.SENT_VERIFIED, DETAIL_LATE_REPLY);");
        assertTrue(c.substring(b, b + 120).contains("startFollowUp(job, now);"));
    }

    @Test public void theFollowUpIsGuardedReadsOnlyAndDowngradesThroughTheEngine() throws IOException {
        String c = code("ScheduledSendController.java");
        int at = c.indexOf("private void followUpOnce(");
        String body = c.substring(at, c.indexOf("\n    }\n", at));
        assertTrue(body, body.contains("if (follow.isOver() || !host.isAlive()) return;"));
        assertEquals("guard before the read and in its callback", 2, count(body, "!host.isAlive()"));
        assertTrue(body.contains("ThreadUrl.isThreadOf(host.mainFrameUrl(), e164)"));
        assertTrue(body.contains("SendLaterEngine.onSentNotDelivered(app, id)"));
        assertFalse("never touches the composer", body.contains("clearComposer") || body.contains("click"));
        assertFalse("never reports a second outcome", body.contains("report(") || body.contains("toEngine("));
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }
}
