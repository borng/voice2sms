package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.send.NewConversationReads.Agreed;
import com.voice2sms.sendlater.send.NewConversationReads.Kind;
import com.voice2sms.sendlater.send.ScheduledCompose.ClickState;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * live-thread-compose fix pass (LIVE-F10 F1, F2; D30, R31): in the new-conversation fallback the
 * pre-send check may count the conversation as empty only on this job's draft once our body is in
 * the box, never on the bare messages list and never after GV showed the number's thread; a
 * clearance given on an empty conversation is dropped when the thread shows up before a click is
 * granted. Drives the fallback sequence through the same pure-JVM pieces the controller uses
 * ({@link NewConversationReads}, {@link ScheduledCompose}). Fictional numbers and texts only.
 */
public class LiveThreadComposeFallbackTest {

    private static final String E164 = "+15555550123";
    private static final String LIST = "https://voice.google.com/u/0/messages";
    private static final String THREAD = "https://voice.google.com/u/0/messages?itemId=t.%2B15555550123";
    private static final String OTHER_THREAD = "https://voice.google.com/u/0/messages?itemId=t.%2B15555550199";
    private static final String DRAFT = "https://voice.google.com/u/0/messages?itemId=draft";
    private static final long T0 = 1_781_092_800_000L;
    private static final long POLL = ScheduledSendController.BASELINE_POLL_MS;

    private static List<ThreadBubble> reply() {
        return new ArrayList<>(Arrays.asList(new ThreadBubble(ThreadEvent.Direction.IN, T0, "are you there", null)));
    }

    private static List<ThreadBubble> none() {
        return new ArrayList<>();
    }

    /** Reads every POLL from {@code from} for {@code ms}; the first agreement or null. */
    private static Agreed readFor(NewConversationReads r, String url, boolean typed, List<ThreadBubble> b,
                                  long from, long ms) {
        for (long t = from; t <= from + ms; t += POLL) {
            Agreed a = r.onRead(url, typed, b, t);
            if (a != null) return a;
        }
        return null;
    }

    private static ScheduledCompose gatedCompose() {
        ScheduledCompose c = new ScheduledCompose(7L);
        c.requirePreSendCheck();
        return c;
    }

    // ================================================================== read kinds

    @Test public void theBareMessagesListBeforeTheRecipientNeverAgreesOnAnEmptyConversation() {
        NewConversationReads r = new NewConversationReads(E164);
        assertEquals(Kind.NOT_YET, r.kindOf(LIST, false));
        assertNull("F1: untyped list reads prove nothing, however long", readFor(r, LIST, false, null, T0, 20_000));
        assertNull(readFor(r, DRAFT, false, none(), T0 + 21_000, 5_000));
    }

    @Test public void theTypedDraftAgreesOnAnEmptyConversation() {
        NewConversationReads r = new NewConversationReads(E164);
        assertEquals(Kind.EMPTY, r.kindOf(DRAFT, true));
        assertNull(r.onRead(DRAFT, true, null, T0));
        assertNull("one read is not an agreement", r.onRead(DRAFT, true, null, T0 + 1_000));
        Agreed a = r.onRead(DRAFT, true, null, T0 + PreSendRead.MIN_GAP_MS);
        assertNotNull(a);
        assertTrue(a.empty);
        assertEquals(0, a.bubbles.size());
        assertTrue(r.emptyStillHolds(DRAFT));
    }

    @Test public void readsOfDifferentKindsNeverAgree() {
        NewConversationReads r = new NewConversationReads(E164);
        assertNull(r.onRead(DRAFT, true, null, T0));
        // GV opened the number's conversation: an (unrendered, empty) thread read must not
        // complete the empty agreement started on the draft.
        assertNull(r.onRead(THREAD, true, none(), T0 + 2_000));
        assertNull(r.onRead(THREAD, true, none(), T0 + 2_500));
        Agreed a = r.onRead(THREAD, true, none(), T0 + 2_000 + PreSendRead.MIN_GAP_MS);
        assertNotNull(a);
        assertFalse("a thread read is used as read, never as an empty conversation", a.empty);
    }

    @Test public void onceTheThreadShowedUpNoOffThreadReadIsEmptyAgain() {
        NewConversationReads r = new NewConversationReads(E164);
        assertEquals(Kind.THREAD, r.kindOf(THREAD, true));
        assertTrue(r.sawThread());
        assertEquals(Kind.NOT_YET, r.kindOf(DRAFT, true));
        assertEquals(Kind.NOT_YET, r.kindOf(LIST, true));
        assertNull(readFor(r, DRAFT, true, null, T0, 20_000));
        assertFalse(r.emptyStillHolds(DRAFT));
    }

    @Test public void anotherThreadIsNotTheJobsThread() {
        NewConversationReads r = new NewConversationReads(E164);
        assertFalse(r.onUrl(OTHER_THREAD));
        assertFalse(r.sawThread());
        assertEquals(Kind.NOT_YET, r.kindOf(OTHER_THREAD, false));
    }

    @Test public void anEmptyAgreementDoesNotStandWhenTheThreadIsThePageAtTheVerdict() {
        NewConversationReads r = new NewConversationReads(E164);
        Agreed a = readFor(r, DRAFT, true, null, T0, 3_000);
        assertNotNull(a);
        assertTrue(a.empty);
        assertFalse("the page became the thread between the read and the verdict", r.emptyStillHolds(THREAD));
        assertTrue(r.sawThread());
        Agreed b = readFor(r, THREAD, true, reply(), T0 + 4_000, 3_000);
        assertNotNull(b);
        assertFalse(b.empty);
        assertEquals(1, b.bubbles.size());
    }

    // ================================================================== gate: dropping an empty clearance

    @Test public void anEmptyClearanceIsDroppedOnTheJobsThreadBeforeAClick() {
        ScheduledCompose c = gatedCompose();
        String t = c.nextInjection();
        assertTrue(c.onTyped(t));
        assertEquals(ClickState.WAIT, c.clickState(t, false));
        assertTrue(c.clearPreSendOnEmptyConversation());
        assertFalse(c.isPreSendPending());
        // GV opened the number's conversation before the script asked again.
        assertEquals("F1: no tap on a conversation the check never read", ClickState.WAIT, c.clickState(t, true));
        assertTrue(c.isPreSendPending());
        assertTrue("a re-read is due", c.takeRecheckDue());
        assertFalse("once", c.takeRecheckDue());
        assertEquals(ClickState.WAIT, c.clickState(t, true));
        assertFalse("nothing more to drop", c.takeRecheckDue());
        // The re-read of the thread clears the send (a thread read, not an empty one).
        assertTrue(c.clearPreSend());
        assertEquals(ClickState.YES, c.clickState(t, true));
        assertEquals("once per job", ClickState.NO, c.clickState(t, true));
    }

    @Test public void anEmptyClearanceOffTheThreadStillLetsTheJobSend() {
        ScheduledCompose c = gatedCompose();
        String t = c.nextInjection();
        c.onTyped(t);
        assertTrue(c.clearPreSendOnEmptyConversation());
        assertEquals(ClickState.YES, c.clickState(t, false));
        assertFalse("never dropped after a granted click", c.revokeEmptyClearance());
    }

    @Test public void aThreadClearanceIsNeverDropped() {
        ScheduledCompose c = gatedCompose();
        String t = c.nextInjection();
        c.onTyped(t);
        assertTrue(c.clearPreSend());
        assertFalse(c.revokeEmptyClearance());
        assertEquals(ClickState.YES, c.clickState(t, true));
    }

    @Test public void aClosedGateDropsNothingAndSaysNo() {
        ScheduledCompose c = gatedCompose();
        String t = c.nextInjection();
        c.clearPreSendOnEmptyConversation();
        assertTrue(c.close());
        assertFalse(c.revokeEmptyClearance());
        assertEquals(ClickState.NO, c.clickState(t, true));
        assertFalse(c.takeRecheckDue());
    }

    @Test public void staleTokenAsksFromTheThreadStillDropTheClearanceButNeverClick() {
        ScheduledCompose c = gatedCompose();
        String old = c.nextInjection();
        String t = c.nextInjection();
        c.onTyped(t);
        c.clearPreSendOnEmptyConversation();
        assertEquals(ClickState.NO, c.clickState(old, true));
        assertTrue("dropping only makes the gate stricter", c.isPreSendPending());
        assertEquals(ClickState.WAIT, c.clickState(t, false));
    }

    // ================================================================== the F1 fallback sequence

    /**
     * F1 scenario, driven the way the controller wires it: the thread page fell back (cold
     * WebView), the messages list loads, voice2sms() enters the recipient and GV opens the number's
     * conversation (the contact texted first). The check must read that conversation; it never
     * clears the send on the list or on a draft it left.
     */
    @Test public void fallbackThatLandsOnTheNumbersThreadReadsTheThreadBeforeAnyTap() {
        ScheduledCompose c = gatedCompose();
        NewConversationReads r = new NewConversationReads(E164);
        String t = c.nextInjection();   // injection on the messages list, mode "new"
        long now = T0;
        // The bare list before the recipient: nothing agrees, the script (not typed) never asks.
        assertNull(readFor(r, LIST, c.isTyped(), null, now, 6_000));
        now += 6_000;
        // GV opened the conversation; voice2sms() typed into its box.
        r.onUrl(THREAD);
        assertTrue(c.onTyped(t));
        assertEquals(ClickState.WAIT, c.clickState(t, true));
        assertFalse("nothing to drop yet", c.takeRecheckDue());
        Agreed a = readFor(r, THREAD, c.isTyped(), reply(), now, 3_000);
        assertNotNull(a);
        assertFalse("the conversation itself was read", a.empty);
        assertEquals(ThreadEvent.Direction.IN, a.bubbles.get(0).getDirection());
        // The reply holds the job (verdict side, LivePresendTest); the gate closes, no tap.
        assertTrue(c.close());
        assertEquals(ClickState.NO, c.clickState(t, true));
    }

    /**
     * F1 ordering: the empty agreement on the typed draft clears the send, then GV routes the
     * draft to the number's thread before the script's next ask. The clearance is dropped, the
     * reads restart on the thread, and only a thread read can clear the send again.
     */
    @Test public void threadAfterAnEmptyClearanceDropsItAndReReads() {
        ScheduledCompose c = gatedCompose();
        NewConversationReads r = new NewConversationReads(E164);
        String t = c.nextInjection();
        assertTrue(c.onTyped(t));
        Agreed a = readFor(r, DRAFT, true, null, T0, 3_000);
        assertNotNull(a);
        assertTrue(a.empty);
        assertTrue(r.emptyStillHolds(DRAFT));
        assertTrue(c.clearPreSendOnEmptyConversation());
        // doUpdateVisitedHistory: the job's thread (controller.onMainFrameUrl).
        assertTrue(r.onUrl(THREAD));
        assertTrue(c.revokeEmptyClearance());
        assertTrue(c.takeRecheckDue());
        r.restart();
        assertEquals(ClickState.WAIT, c.clickState(t, true));
        Agreed b = readFor(r, THREAD, true, reply(), T0 + 5_000, 3_000);
        assertNotNull(b);
        assertFalse(b.empty);
        // Back on a draft-like page: never an empty conversation again.
        assertNull(readFor(r, DRAFT, true, null, T0 + 9_000, 10_000));
    }

    @Test public void theScriptWaitsLongEnoughForARead() {
        assertTrue(ScheduledSendController.clickWaitMs() >= 2 * ScheduledSendController.PRESEND_TIMEOUT_MS);
        assertTrue(ScheduledSendController.clickWaitMs() < com.voice2sms.sendlater.send.LockCoverPolicy.HARD_TIMEOUT_MS);
    }

    // ================================================================== wiring (source)

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path CONTROLLER = MAIN.resolve("sendlater/send/ScheduledSendController.java");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path INJECT = Paths.get("src/main/assets/inject.js");

    private static String code(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
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

    @Test public void controllerUsesTheFallbackReadsAndTheEmptyClearance() throws IOException {
        String c = code(CONTROLLER);
        String view = block(c, "private NewConversationReads.Agreed preSendView\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(view.contains("job.fallbackReads.onRead(host.mainFrameUrl(), job.compose.isTyped()"));
        String read = block(c, "private void onPreSendRead\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(read.indexOf("emptyStillHolds(host.mainFrameUrl())") < read.indexOf("classifyNoConversation("));
        String verdict = block(c, "private void onPreSendVerdict\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(verdict.contains("clearPreSendOnEmptyConversation()"));
        String url = block(c, "public void onMainFrameUrl\\s*\\(String url\\)\\s*\\{");
        assertTrue(url.contains("revokeEmptyClearance()") && url.contains("recheckIfDue()"));
        String recheck = block(c, "private void recheckIfDue\\s*\\(\\s*\\)\\s*\\{");
        assertTrue(recheck.contains("takeRecheckDue()") && recheck.contains("PRESEND_TIMEOUT_MS")
                && recheck.contains("preSendReadOnce(job)"));
        String ask = block(c, "public static String clickState\\s*\\(String token, String pageUrl\\)\\s*\\{");
        assertTrue(ask.contains("sc.clickState(token, onJobThread)"));
        assertTrue(ask.contains("main.post("));
    }

    @Test public void activityReportsUrlsAndGuardsTheLocatedAsk() throws IOException {
        String a = code(ACTIVITY);
        String hist = block(a, "public\\s+void\\s+doUpdateVisitedHistory\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(hist.contains("scheduledSend.onMainFrameUrl(url)"));
        String b = block(a, "@JavascriptInterface\\s+public\\s+String\\s+scheduledClickStateAt\\s*\\([^)]*\\)\\s*\\{");
        int guard = b.indexOf("OriginGuard.isTrustedGvUrl(mainFrameUrl)");
        assertTrue(guard >= 0 && guard < b.indexOf("ScheduledSendController.clickState(token, pageUrl)"));
    }

    @Test public void scriptAsksWithItsOwnLocation() throws IOException {
        String js = code(INJECT);
        String entry = block(js, "(?m)^function v2sScheduledCompose\\s*\\(phone, body, autoSendDelay, token\\)\\s*\\{");
        String ask = block(entry, "function askClick\\s*\\(\\s*\\)\\s*\\{");
        int at = ask.indexOf("V2SBridge.scheduledClickStateAt(token, String(location.href))");
        assertTrue(at >= 0 && at < ask.indexOf("V2SBridge.scheduledClickState(token)"));
    }
}
