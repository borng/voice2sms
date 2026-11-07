package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.DraftChoice;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D32 / D35 source guards: the draft reaches only the choice notification (never a log, Room or
 * preferences), the bridge is origin-guarded, the job modes are wired through, and the settle
 * time is 15 s inside the hard timeout.
 */
public class DraftChoiceSourceTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path SL = MAIN.resolve("sendlater");
    private static final Path CONTROLLER = SL.resolve("send/ScheduledSendController.java");
    private static final Path SENDER = SL.resolve("send/WebViewScheduledSender.java");
    private static final Path ENGINE = SL.resolve("engine/SendLaterEngine.java");
    private static final Path NOTIFS = SL.resolve("engine/SendLaterNotifications.java");
    private static final Path STASH = SL.resolve("engine/DraftStash.java");
    private static final Path NOTICE = SL.resolve("engine/DraftNotice.java");
    private static final Path CHOICE = SL.resolve("domain/DraftChoice.java");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path INJECT = Paths.get("src/main/assets/inject.js");

    private static String code(Path p) throws IOException {
        Path f = Files.exists(p) ? p : Paths.get("app").resolve(p);
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }

    /** The brace block that follows the first match of {@code header} (header included). */
    private static String block(String src, String header) {
        Matcher m = Pattern.compile(header).matcher(src);
        assertTrue("not found: " + header, m.find());
        int open = src.indexOf('{', m.end() - 1);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(m.start(), i + 1);
        }
        throw new AssertionError("unbalanced: " + header);
    }

    /** Every Log.x(...) / console.log(...) call (up to its closing ");") in {@code src}. */
    private static List<String> logCalls(String src) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\b(Log\\.[a-z]+|console\\.log)\\s*\\(").matcher(src);
        while (m.find()) {
            int end = src.indexOf(");", m.end());
            out.add(src.substring(m.start(), end < 0 ? src.length() : end));
        }
        return out;
    }

    private static final Pattern DRAFT_IDENT = Pattern.compile(
            "\\+\\s*(draft|savedDraft|boxText|current|text|body)\\b|\\b(draft|savedDraft|boxText)\\s*\\+");

    // ------------------------------------------------------------------ the draft never leaks

    @Test public void noLogCallPrintsADraft() throws IOException {
        for (Path p : new Path[] {CONTROLLER, SENDER, ENGINE, NOTIFS, STASH, NOTICE, CHOICE}) {
            for (String call : logCalls(code(p))) {
                assertFalse(p + ": " + call, DRAFT_IDENT.matcher(call).find());
                assertFalse(p + ": " + call, call.toLowerCase().contains("draft"));
            }
        }
        String a = code(ACTIVITY);
        for (String name : new String[] {"public String scheduledDraftStep\\s*\\(", "public void restoreDraft\\s*\\("}) {
            assertTrue(name, logCalls(block(a, name)).isEmpty());
        }
        String js = code(INJECT);
        String restore = block(js, "(?m)^function v2sScheduledRestoreDraft\\s*\\(draft, text\\)\\s*\\{");
        String entry = block(js, "(?m)^function v2sScheduledCompose\\s*\\(phone, body, autoSendDelay, token\\)\\s*\\{");
        String step = block(entry, "function draftStep\\s*\\(text\\)\\s*\\{");
        String fill = block(entry, "function fillThread\\s*\\(polls\\)\\s*\\{");
        for (String part : new String[] {restore, step, fill}) {
            for (String call : logCalls(part)) assertFalse(call, DRAFT_IDENT.matcher(call).find());
        }
    }

    @Test public void theDraftIsNeverStoredInRoomOrPreferences() throws IOException {
        for (Path p : new Path[] {STASH, NOTICE, CHOICE}) {
            String c = code(p);
            for (String banned : new String[] {"SendLaterRepository", "SendLaterDao", "SharedPreferences",
                    "EngineStore", "EngineMarks", "store."}) {
                assertFalse(p + " uses " + banned, c.contains(banned));
            }
        }
        String e = code(ENGINE);
        assertFalse("no store write carries the draft",
                Pattern.compile("d\\.(store|marks)\\.\\w+\\([^;]*\\bdraft\\b").matcher(e).find());
        String remind = block(e, "private static void remindDraft\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(remind.contains("d.notifier.draftInTheWay(m, draft)"));
        String c = code(CONTROLLER);
        assertFalse("the controller writes no draft to the store",
                Pattern.compile("SendLaterRepository[^;]*\\bdraft\\b").matcher(c).find());
        assertFalse(c.contains("getSharedPreferences"));
    }

    @Test public void theChoiceNotificationIsPrivateWithATextFreePublicVersion() throws IOException {
        String n = code(NOTIFS);
        String draft = block(n, "public static void draftInTheWay\\s*\\([^)]*\\)\\s*\\{");
        assertTrue("base() sets VISIBILITY_PRIVATE", draft.contains("base(c, CHANNEL_HOLD, m)"));
        assertTrue(block(n, "private static NotificationCompat.Builder base\\s*\\([^)]*\\)\\s*\\{")
                .contains("VISIBILITY_PRIVATE"));
        assertTrue(draft.contains("setPublicVersion(draftPublicVersion(c, DraftNotice.PUBLIC_TITLE))"));
        assertTrue(draft.contains("unlockedAction(c, \"Replace and send\", DraftNotice.ACTION_REPLACE, m.getId())"));
        assertTrue(draft.contains("broadcast(c, DraftNotice.ACTION_KEEP, m.getId())"));
        assertTrue(draft.contains("unlockedAction(c, \"Send both\", DraftNotice.ACTION_BOTH, m.getId())"));
        String pub = block(n, "private static Notification draftPublicVersion\\s*\\([^)]*\\)\\s*\\{");
        assertFalse(pub.contains("setContentText"));
        assertFalse(pub.contains("setStyle"));
        assertFalse(pub.contains("ScheduledMessage"));
        String lost = block(n, "public static void draftNotRestored\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(lost.contains("setPublicVersion(draftPublicVersion("));
        assertTrue("its own slot, not the message's", lost.contains("nm.notify(TAG_DRAFT, slot(m.getId())"));
    }

    // ------------------------------------------------------------------ wiring

    @Test public void theBridgeIsOriginGuardedAndHopsToTheMainThread() throws IOException {
        String b = block(code(ACTIVITY), "@JavascriptInterface\\s+public\\s+String\\s+scheduledDraftStep\\s*\\([^)]*\\)\\s*\\{");
        int guard = b.indexOf("OriginGuard.isTrustedGvUrl(mainFrameUrl)) return \"stop\"");
        assertTrue(guard >= 0 && guard < b.indexOf("ScheduledSendController.draftStep("));
        assertTrue(b.indexOf("runOnUiThread") < b.indexOf("isActivityAlive()"));
        assertTrue(b.indexOf("isActivityAlive()") < b.indexOf("scheduledSend.onDraftStep("));
        String r = block(code(ACTIVITY), "public void restoreDraft\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(r.indexOf("isActivityAlive()") < r.indexOf("evaluateJavascript("));
        assertTrue(r.indexOf("OriginGuard.isTrustedGvUrl(webView.getUrl())") < r.indexOf("evaluateJavascript("));
    }

    @Test public void theStopStepHandsTheDraftToTheEngineBeforeTheOutcome() throws IOException {
        String on = block(code(CONTROLLER), "public void onDraftStep\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(on.contains("job.compose.isCurrent(token)"));
        int put = on.indexOf("DraftStash.put(job.id, draft)");
        int stop = on.indexOf("stopUnsent(job, DETAIL_DRAFT_PRESENT)");
        assertTrue(put >= 0 && put < stop);
        assertTrue(on.contains("job.savedDraft = draft"));
    }

    @Test public void eachJobTakesItsOwnDraftModeAndEveryStartRecordsOne() throws IOException {
        String loaded = block(code(CONTROLLER), "private void onLoaded\\s*\\([^)]*\\)\\s*\\{");
        assertTrue(loaded.contains("job.draftMode = takeDraftMode(job.id)"));
        String s = code(SENDER);
        String three = block(s, "public boolean start\\s*\\(Context context, ScheduledMessage message, PreSendCheck check\\)\\s*\\{");
        assertTrue(three.contains("DraftChoice.Mode.ASK"));
        String four = block(s, "public boolean start\\s*\\(Context context, ScheduledMessage message, PreSendCheck check, DraftChoice.Mode draftMode\\)\\s*\\{");
        assertTrue(four.contains("ScheduledSendController.noteDraftMode(message.getId(), draftMode)"));
    }

    @Test public void draftModesAreOneShotAndDefaultToAsk() {
        assertEquals(DraftChoice.Mode.ASK, ScheduledSendController.takeDraftMode(4242L));
        ScheduledSendController.noteDraftMode(4242L, DraftChoice.Mode.BOTH);
        assertEquals(DraftChoice.Mode.BOTH, ScheduledSendController.takeDraftMode(4242L));
        assertEquals(DraftChoice.Mode.ASK, ScheduledSendController.takeDraftMode(4242L));
        ScheduledSendController.noteDraftMode(4242L, null);
        assertEquals(DraftChoice.Mode.ASK, ScheduledSendController.takeDraftMode(4242L));
    }

    @Test public void withNoRunningJobTheBridgeAnswersStop() {
        assertEquals("stop", ScheduledSendController.draftStep("stale-token", "anything"));
    }

    @Test public void theEndOfAJobPutsASavedDraftBack() throws IOException {
        String c = code(CONTROLLER);
        String close = block(c, "private void closeCompose\\s*\\(Job job\\)\\s*\\{");
        assertTrue("after our text was cleared", close.indexOf("host.clearComposer(") < close.indexOf("restoreSavedDraft(job)"));
        String r = block(c, "private void restoreSavedDraft\\s*\\(Job job\\)\\s*\\{");
        assertTrue(r.contains("job.savedDraft = null"));
        assertTrue(r.contains("host.restoreDraft(draft, body,"));
        assertTrue("not lost when it cannot go back", r.contains("SendLaterNotifications.draftNotRestored(app, m, draft)"));
    }

    @Test public void injectJsAsksJavaAndTypesOnlyOnReplaceOrBoth() throws IOException {
        String js = code(INJECT);
        String entry = block(js, "(?m)^function v2sScheduledCompose\\s*\\(phone, body, autoSendDelay, token\\)\\s*\\{");
        String fill = block(entry, "function fillThread\\s*\\(polls\\)\\s*\\{");
        String draftBranch = block(fill, "if \\(typeof current === 'string' && current.trim\\(\\).length > 0\\)\\s*\\{");
        assertTrue(draftBranch.contains("var step = draftStep(current);"));
        assertTrue(draftBranch.contains("if (step !== 'replace' && step !== 'both') {"));
        String step = block(entry, "function draftStep\\s*\\(text\\)\\s*\\{");
        assertTrue(step.contains("V2SBridge.scheduledDraftStep(token, String(text))"));
        String restore = block(js, "(?m)^function v2sScheduledRestoreDraft\\s*\\(draft, text\\)\\s*\\{");
        assertTrue("never over other text",
                restore.indexOf("!_v2sScheduledHolds(el, text)") < restore.indexOf("_v2sScheduledSet(el, draft, false)"));
    }

    // ------------------------------------------------------------------ D35

    @Test public void threadSettleIsFifteenSecondsInsideTheHardTimeout() throws IOException {
        assertEquals(15_000L, ScheduledCompose.THREAD_SETTLE_MS);
        assertTrue(ScheduledSendController.RELOAD_COMPOSE_GRACE_MS > ScheduledCompose.THREAD_SETTLE_MS);
        assertTrue(ScheduledSendController.COMPOSE_TIMEOUT_MS + ScheduledSendController.RELOAD_COMPOSE_GRACE_MS
                < LockCoverPolicy.HARD_TIMEOUT_MS);
        assertTrue(code(INJECT).contains("var SETTLE_MS = opts.settleMs > 0 ? opts.settleMs : 15000;"));
    }
}
