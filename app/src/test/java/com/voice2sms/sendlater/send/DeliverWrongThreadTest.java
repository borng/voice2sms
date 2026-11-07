package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.send.NewConversationReads.Kind;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * deliver-correctness H1, L2, M3, L1: a scheduled job never types or taps Send in another
 * person's conversation, never reports "may have already sent" without a granted click, and
 * never leaves its recipient and body behind for a later regular injection. Fictional numbers
 * and texts only.
 */
public class DeliverWrongThreadTest {

    private static final String E164 = "+15555550123";
    private static final String LIST = "https://voice.google.com/u/0/messages";
    private static final String THREAD = "https://voice.google.com/u/0/messages?itemId=t.%2B15555550123";
    private static final String OTHER_THREAD = "https://voice.google.com/u/0/messages?itemId=t.%2B15555550199";
    private static final String GROUP = "https://voice.google.com/u/0/messages?itemId=t.g.abc";
    private static final String DRAFT = "https://voice.google.com/u/0/messages?itemId=draft";
    private static final String EVIL = "https://example.com/u/0/messages?itemId=t.%2B15555550123";

    // ================================================================== H1: kindOf

    @Test public void anotherNumbersThreadIsNeverTheJobsEmptyConversation() {
        NewConversationReads r = new NewConversationReads(E164);
        assertEquals("typed, but on someone else's thread", Kind.NOT_YET, r.kindOf(OTHER_THREAD, true));
        assertEquals(Kind.NOT_YET, r.kindOf(OTHER_THREAD, false));
        assertFalse(r.sawThread());
        for (long t = 0; t <= 20_000L; t += 300L) {
            assertNull("never agrees on an empty conversation there", r.onRead(OTHER_THREAD, true, null, t));
        }
        assertFalse(r.emptyStillHolds(OTHER_THREAD));
        // The job's own new-message draft still counts as empty once typed.
        NewConversationReads d = new NewConversationReads(E164);
        assertEquals(Kind.EMPTY, d.kindOf(DRAFT, true));
        assertTrue(d.emptyStillHolds(DRAFT));
    }

    // ================================================================== H1/L2: the page gate

    @Test public void theJobsOwnThreadMayTakeTheTap() {
        assertTrue(ScheduledCompose.pageAllowsClick(THREAD, THREAD, E164, -1, null));
        assertTrue("main frame not yet updated", ScheduledCompose.pageAllowsClick(THREAD, LIST, E164, 0, ""));
        assertTrue("no page location: the main frame", ScheduledCompose.pageAllowsClick(null, THREAD, E164, -1, null));
    }

    @Test public void anotherPersonsThreadNeverTakesTheTap() {
        assertFalse(ScheduledCompose.pageAllowsClick(OTHER_THREAD, OTHER_THREAD, E164, -1, null));
        assertFalse("even with a chip naming the number",
                ScheduledCompose.pageAllowsClick(OTHER_THREAD, LIST, E164, 1, "15555550123"));
        assertFalse("the main frame shows someone else's thread",
                ScheduledCompose.pageAllowsClick(THREAD, OTHER_THREAD, E164, -1, null));
        assertFalse("a chip view while the main frame is another thread",
                ScheduledCompose.pageAllowsClick(DRAFT, OTHER_THREAD, E164, 1, "15555550123"));
        assertFalse(ScheduledCompose.pageAllowsClick(null, OTHER_THREAD, E164, -1, null));
    }

    @Test public void aNewMessageViewNeedsOneChipThatIsTheJobsNumber() {
        assertTrue(ScheduledCompose.pageAllowsClick(LIST, LIST, E164, 1, "15555550123,,"));
        assertTrue("national format", ScheduledCompose.pageAllowsClick(DRAFT, LIST, E164, 1, "5555550123,,"));
        assertTrue("aria-label carries it", ScheduledCompose.pageAllowsClick(LIST, LIST, E164, 1, ",15555550123,"));
        assertFalse("no chip", ScheduledCompose.pageAllowsClick(LIST, LIST, E164, 0, ""));
        assertFalse("chips not reported (older script)", ScheduledCompose.pageAllowsClick(LIST, LIST, E164, -1, null));
        assertFalse("two chips", ScheduledCompose.pageAllowsClick(LIST, LIST, E164, 2, ""));
        assertFalse("a name only", ScheduledCompose.pageAllowsClick(LIST, LIST, E164, 1, ",,"));
        assertFalse("another number", ScheduledCompose.pageAllowsClick(LIST, LIST, E164, 1, "15555550199"));
        assertFalse("a partial number", ScheduledCompose.pageAllowsClick(LIST, LIST, E164, 1, "5550123"));
        assertFalse("a group thread", ScheduledCompose.pageAllowsClick(GROUP, GROUP, E164, 0, ""));
        assertFalse("not a GV page", ScheduledCompose.pageAllowsClick(EVIL, LIST, E164, 1, "15555550123"));
        assertFalse("no job number", ScheduledCompose.pageAllowsClick(THREAD, THREAD, null, 1, "15555550123"));
    }

    @Test public void chipDigitsMatchTheWholeNumberOrItsNationalPartOnly() {
        assertTrue(ScheduledCompose.chipNamesNumber("15555550123", E164));
        assertTrue(ScheduledCompose.chipNamesNumber("5555550123", E164));
        assertFalse(ScheduledCompose.chipNamesNumber("555550123", E164));
        assertFalse(ScheduledCompose.chipNamesNumber("995555550123", "+5555550123"));
        assertFalse(ScheduledCompose.chipNamesNumber("", E164));
        assertFalse(ScheduledCompose.chipNamesNumber(null, E164));
        assertFalse(ScheduledCompose.chipNamesNumber("15555550123", "15555550123"));
    }

    // ================================================================== M3

    @Test public void typedButNeverGrantedIsCannotShowInEveryEnding() {
        assertEquals(SendOutcome.CANNOT_SHOW, ScheduledCompose.outcomeOnFailure(true, false));
        assertEquals(SendOutcome.SENT_UNVERIFIED, ScheduledCompose.outcomeOnFailure(true, true));
        assertEquals(SendOutcome.CANNOT_SHOW, ScheduledSendController.failureOutcome(false));
        assertEquals(SendOutcome.SENT_UNVERIFIED, ScheduledSendController.failureOutcome(true));

        ScheduledCompose checkOff = new ScheduledCompose(1L);
        assertTrue(checkOff.onTyped(checkOff.nextInjection()));
        assertEquals(SendOutcome.CANNOT_SHOW, checkOff.closeForFailure());

        ScheduledCompose cleared = new ScheduledCompose(2L);
        cleared.requirePreSendCheck();
        String t = cleared.nextInjection();
        assertTrue(cleared.onTyped(t));
        assertTrue(cleared.clearPreSend());
        assertEquals("cleared but no tap asked yet", SendOutcome.CANNOT_SHOW, cleared.closeForFailure());

        ScheduledCompose granted = new ScheduledCompose(3L);
        String g = granted.nextInjection();
        assertTrue(granted.onTyped(g));
        assertEquals(ScheduledCompose.ClickState.YES, granted.clickState(g, true));
        assertEquals(SendOutcome.SENT_UNVERIFIED, granted.closeForFailure());
    }

    @Test public void everyFailurePathAsksTheGate() throws IOException {
        String c = code(CONTROLLER);
        String read = block(c, "private void onThreadRead\\s*\\([^)]*\\)\\s*\\{");
        assertFalse("no typed shortcut at the verify deadline", read.contains("job.injected ? SendOutcome.SENT_UNVERIFIED"));
        assertTrue(read.contains("report(job, failureOutcome(job), \"no_match\")"));
        String hard = block(c, "private void onHardTimeout\\s*\\(\\s*\\)\\s*\\{");
        assertTrue(hard.contains("failureOutcome(job)"));
        String abandon = block(c, "private void abandonAll\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(abandon.contains("failureOutcome(job)"));
        String close = block(code(COMPOSE), "public synchronized SendOutcome closeForFailure\\s*\\(\\s*\\)\\s*\\{");
        assertTrue(close.indexOf("closed = true;") < close.indexOf("outcomeOnFailure("));
    }

    // ================================================================== H1/L2 wiring

    @Test public void controllerRefusesAnyOtherPageBeforeTheGateAndEndsTheJob() throws IOException {
        String c = code(CONTROLLER);
        String ask = block(c, "public static String clickState\\s*\\(String token, String pageUrl, int chipCount, String chipDigits\\)\\s*\\{");
        int gate = ask.indexOf("ScheduledCompose.pageAllowsClick(pageUrl, c.host.mainFrameUrl(), e164, chipCount, chipDigits)");
        int grant = ask.indexOf("sc.clickState(token, onJobThread)");
        assertTrue(gate >= 0 && grant > gate);
        assertTrue(ask.substring(gate, grant).contains("ClickState.NO.wire()"));
        assertTrue(ask.contains("c.onWrongThreadAsk(token)"));
        String two = block(c, "public static String clickState\\s*\\(String token, String pageUrl\\)\\s*\\{");
        assertTrue("the located ask without chips goes through the same gate", two.contains("clickState(token, pageUrl, -1, null)"));
        String wrong = block(c, "private void onWrongThreadAsk\\s*\\(String token\\)\\s*\\{");
        assertTrue(wrong.contains("job.compose.isCurrent(token)") && wrong.contains("stopUnsent(job, DETAIL_WRONG_THREAD)"));
        assertEquals("could_not_send", SendDetails.forEngine(SendOutcome.CANNOT_SHOW, ScheduledSendController.DETAIL_WRONG_THREAD));
    }

    @Test public void activityBridgeIsGuardedAndPassesTheChips() throws IOException {
        String a = code(ACTIVITY);
        String b = block(a, "@JavascriptInterface\\s+public\\s+String\\s+scheduledClickStateIn\\s*\\([^)]*\\)\\s*\\{");
        int guard = b.indexOf("OriginGuard.isTrustedGvUrl(mainFrameUrl)");
        assertTrue(guard >= 0 && guard < b.indexOf("ScheduledSendController.clickState(token, pageUrl, chipCount, chipDigits)"));
    }

    @Test public void scheduledNewConversationMatchesOnlyTheThreadIdAndRunsTheDraftStep() throws IOException {
        String js = code(INJECT);
        String entry = block(js, "(?m)^function v2sScheduledCompose\\s*\\(phone, body, autoSendDelay, token\\)\\s*\\{");
        assertFalse("never voice2sms() and its text match", entry.contains("voice2sms(phone") || entry.contains("findExistingConversation"));
        assertTrue(entry.contains("_v2sScheduledNewMessage(jobE164, function() { return cancelled() || expired(); }, fillNew)"));

        String own = block(js, "(?m)^function _v2sScheduledOwnThreadItem\\s*\\(e164\\)\\s*\\{");
        assertTrue(own.contains("_v2sSlItemE164(items[i]) === e164"));
        assertFalse("never a text match", own.contains("textContent") || own.contains("indexOf("));
        String nav = block(js, "(?m)^function _v2sScheduledNewMessage\\s*\\(e164, stop, ready\\)\\s*\\{");
        assertTrue(nav.contains("_v2sScheduledOwnThreadItem(e164)"));
        assertFalse("navigation never types into the message box or taps Send",
                nav.contains("V2S_COMPOSER_SELECTOR") || nav.contains("textarea") || nav.contains("aria-label*=\"Send\"]"));
        assertFalse("never logs the number", Pattern.compile("console\\.log\\([^;]*\\be164\\b").matcher(nav).find());

        String fill = block(entry, "function fillNew\\s*\\(\\s*\\)\\s*\\{");
        int gate = fill.indexOf("if (!draftGate(ta)) return;");
        assertTrue("the draft step before any typing", gate >= 0 && gate < fill.indexOf("_v2sScheduledSet(ta, body, true)"));
        String draft = block(entry, "function draftGate\\s*\\(ta\\)\\s*\\{");
        assertTrue(draft.contains("var step = draftStep(current);"));
        assertTrue(draft.contains("if (step !== 'replace' && step !== 'both') {"));
        assertFalse("never touches the draft", draft.contains("_v2sScheduledSet") || draft.contains("v2sScheduledClear"));
        assertFalse("never logs the text", Pattern.compile("console\\.log\\([^;]*\\b(current|body|text)\\b").matcher(draft).find());

        String ask = block(entry, "function askClick\\s*\\(\\s*\\)\\s*\\{");
        int in = ask.indexOf("V2SBridge.scheduledClickStateIn(token, String(location.href), chips.count, chips.digits)");
        assertTrue("the chip-aware ask comes first", in >= 0 && in < ask.indexOf("V2SBridge.scheduledClickStateAt("));
        String chips = block(entry, "function recipientChips\\s*\\(\\s*\\)\\s*\\{");
        assertTrue("digits only, never a name", chips.contains(".replace(/\\D/g, '')"));
    }

    // ================================================================== L1

    @Test public void everyJobEndMakesTheActivityForgetTheScheduledRecipientAndBody() throws IOException {
        String c = code(CONTROLLER);
        String close = block(c, "private void closeCompose\\s*\\(Job job\\)\\s*\\{");
        int forget = close.indexOf("host.forgetCompose(job.e164, job.message != null ? job.message.getText() : null)");
        assertTrue(forget >= 0);
        assertFalse("for every outcome, not only unsent", close.substring(close.indexOf("if (!job.composeForgotten)"), forget).contains("unsent"));
        assertTrue("report and toEngine both close", block(c, "private void report\\s*\\([^)]*\\)\\s*\\{").contains("closeCompose(job)")
                && block(c, "private void toEngine\\s*\\([^)]*\\)\\s*\\{").contains("closeCompose(job)"));
        String a = code(ACTIVITY);
        String f = block(a, "public void forgetCompose\\s*\\(String e164, String text\\)\\s*\\{");
        assertTrue(f.contains("recipient = null;") && f.contains("body = null;"));
        assertTrue("only the values beginCompose set", f.contains("!e164.equals(recipient)"));
        assertFalse("touches no WebView", f.contains("webView"));
    }

    @Test public void forgetComposeIsADefaultNoOpForOtherHosts() throws IOException {
        String c = code(CONTROLLER);
        assertTrue(Pattern.compile("default void forgetCompose\\(String e164, String text\\)\\s*\\{\\s*\\}").matcher(c).find());
    }

    // ================================================================== helpers

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path CONTROLLER = MAIN.resolve("sendlater/send/ScheduledSendController.java");
    private static final Path COMPOSE = MAIN.resolve("sendlater/send/ScheduledCompose.java");
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
}
