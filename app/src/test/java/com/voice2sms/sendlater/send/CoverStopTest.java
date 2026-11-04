package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.engine.RemindCause;
import com.voice2sms.sendlater.send.LockCoverPolicy.JobState;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dl-actions (OD31 PQ3; R30): on an unlocked phone the send cover shows a Stop button until
 * typing starts; Stop ends the job without sending (CANNOT_SHOW "stopped", a reminder follows).
 * Paths are relative to the app module.
 */
public class CoverStopTest {

    private static final Path SEND = Paths.get("src/main/java/com/voice2sms/sendlater/send");
    private static final Path STRINGS = Paths.get("src/main/res/values/sendlater_send.xml");

    private static String code(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static String method(String src, String sig) {
        Matcher m = Pattern.compile(sig).matcher(src);
        assertTrue("method " + sig, m.find());
        int open = src.indexOf('{', m.end());
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced " + sig);
    }

    // ------------------------------------------------------------------ policy

    @Test public void stopShowsOnlyOnAnUnlockedCoverBeforeTyping() {
        assertTrue(LockCoverPolicy.showStop(true, JobState.RUNNING, true, false, true, false));
    }

    @Test public void stopIsHiddenOnceTypingStarted() {
        assertFalse(LockCoverPolicy.showStop(true, JobState.RUNNING, true, false, true, true));
    }

    @Test public void stopIsHiddenWhenLockedOrDark() {
        assertFalse(LockCoverPolicy.showStop(true, JobState.RUNNING, true, true, true, false));
    }

    @Test public void stopIsHiddenWithoutARunningCoveredClaimedJob() {
        assertFalse(LockCoverPolicy.showStop(false, JobState.RUNNING, true, false, true, false));
        assertFalse(LockCoverPolicy.showStop(true, JobState.ENDED, true, false, true, false));
        assertFalse(LockCoverPolicy.showStop(true, JobState.IDLE, true, false, true, false));
        assertFalse(LockCoverPolicy.showStop(true, JobState.RUNNING, false, false, true, false));
        assertFalse("between jobs", LockCoverPolicy.showStop(true, JobState.RUNNING, true, false, false, false));
    }

    // ------------------------------------------------------------------ outcome and reminder

    @Test public void stopIsCannotShowWithItsOwnReminder() {
        assertEquals(RemindCause.DETAIL_STOPPED, SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "stopped"));
        assertEquals(RemindCause.STOPPED, RemindCause.fromDetail(RemindCause.DETAIL_STOPPED));
        assertEquals(com.voice2sms.sendlater.domain.RemindReason.LOCKED, RemindCause.STOPPED.reason());
        assertEquals(SendOutcome.CANNOT_SHOW, ScheduledSendController.failureOutcome(false));
    }

    // ------------------------------------------------------------------ wiring

    @Test public void controllerStopEndsWithoutSendingAndRechecksThePolicy() throws IOException {
        String c = code(SEND.resolve("ScheduledSendController.java"));
        String stop = method(c, "void onStopTapped\\s*\\(");
        int check = stop.indexOf("stopAllowed()");
        int abandon = stop.indexOf("abandonAll(RemindCause.DETAIL_STOPPED)");
        int end = stop.indexOf("endJob(true)");
        assertTrue("re-checks before acting", check >= 0 && abandon > check);
        assertTrue("then finishes", end > abandon);
        String allowed = method(c, "private boolean stopAllowed\\s*\\(");
        assertTrue(allowed.contains("LockCoverPolicy.showStop("));
        assertTrue(allowed.contains("job.injected"));
        assertTrue(allowed.contains("lockedNow"));
        String injected = method(c, "public void onComposerInjected\\s*\\(");
        assertTrue("Stop goes away as typing starts",
                injected.indexOf("updateStop()") > injected.indexOf("job.injected = true"));
        assertTrue("re-read on every lock change", method(c, "private boolean refreshLockState\\s*\\(").contains("updateStop()"));
        assertTrue("hidden at job end", method(c, "private void endJob\\s*\\(").contains("updateStop()"));
    }

    @Test public void coverStopLivesInItsOwnWindowAndIsLabelled() throws IOException {
        String l = code(SEND.resolve("LockCover.java"));
        assertTrue("the activity drops touches on its own window while covered", l.contains("new PopupWindow("));
        assertTrue(l.contains("R.string.sendlater_send_cover_stop"));
        assertTrue(l.contains("R.string.sendlater_send_cover_stop_description"));
        assertTrue("touch target at least 48dp", l.contains("dp(48)"));
        assertTrue("removed with the cover", method(l, "void hide\\s*\\(").contains("hideStop()"));
        String strings = new String(Files.readAllBytes(STRINGS), StandardCharsets.UTF_8);
        assertTrue(strings.contains("name=\"sendlater_send_cover_stop\""));
        assertTrue(strings.contains("name=\"sendlater_send_cover_stop_description\""));
    }
}
