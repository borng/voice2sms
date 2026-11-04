package com.voice2sms.sendlater.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.SendLaterIntents;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dl-actions (whole-branch L1, OD31; UI M4, M8): Send now / Send anyway never run from the lock
 * screen without an unlock (an authenticated broadcast on API 31+, else an activity that asks for
 * the unlock first); the action receiver and the edit sheet report stale taps; the last call
 * title is computed. Paths are relative to the app module.
 */
public class UnlockActionsSourceTest {

    private static final Path ENGINE = Paths.get("src/main/java/com/voice2sms/sendlater/engine");
    private static final Path SHEET = Paths.get("src/main/java/com/voice2sms/sendlater/ui/SendLaterSheetActivity.java");
    private static final Path MANIFEST = Paths.get("src/main/AndroidManifest.xml");

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String code(Path p) throws IOException {
        String s = read(p).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
        return s;
    }

    /** The body of the first method whose signature matches {@code sig}. */
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

    // ------------------------------------------------------------------ L1 / OD31

    @Test public void apiLevelPicksTheUnlockPath() {
        assertTrue(SendLaterNotifications.usesAuthenticatedBroadcast(31));
        assertTrue(SendLaterNotifications.usesAuthenticatedBroadcast(34));
        assertFalse(SendLaterNotifications.usesAuthenticatedBroadcast(30));
        assertFalse(SendLaterNotifications.usesAuthenticatedBroadcast(24));
        assertTrue(SendLaterUnlockActivity.isUnlockAction(SendLaterIntents.ACTION_SEND_NOW));
        assertTrue(SendLaterUnlockActivity.isUnlockAction(SendLaterIntents.ACTION_SEND_ANYWAY));
        assertFalse(SendLaterUnlockActivity.isUnlockAction(SendLaterIntents.ACTION_CANCEL));
        assertFalse(SendLaterUnlockActivity.isUnlockAction(null));
    }

    @Test public void sendNowAndSendAnywayAreNeverPlainBroadcastActions() throws IOException {
        String n = code(ENGINE.resolve("SendLaterNotifications.java"));
        assertFalse("Send now as a plain broadcast",
                Pattern.compile("broadcast\\s*\\(\\s*c\\s*,\\s*SendLaterIntents\\.ACTION_SEND_NOW").matcher(n).find());
        assertFalse("Send anyway as a plain broadcast",
                Pattern.compile("broadcast\\s*\\(\\s*c\\s*,\\s*SendLaterIntents\\.ACTION_SEND_ANYWAY").matcher(n).find());
        // last call, hold and may-have-sent: all three go through unlockedAction.
        Matcher m = Pattern.compile("unlockedAction\\s*\\([^;]*ACTION_SEND_(NOW|ANYWAY)").matcher(n);
        int count = 0;
        while (m.find()) count++;
        assertEquals(3, count);
        String unlocked = method(n, "NotificationCompat\\.Action\\s+unlockedAction\\s*\\(");
        assertTrue(unlocked.contains("usesAuthenticatedBroadcast(Build.VERSION.SDK_INT)"));
        assertTrue(unlocked.contains("setAuthenticationRequired(true)"));
        assertTrue(unlocked.contains("SendLaterUnlockActivity.pendingIntent("));
    }

    @Test public void unlockActivityActsOnlyWhenUnlockedAndNeverShowsOverTheKeyguard() throws IOException {
        String a = code(ENGINE.resolve("SendLaterUnlockActivity.java"));
        assertTrue(a.contains("isKeyguardLocked()"));
        assertTrue(a.contains("requestDismissKeyguard("));
        String success = a.substring(a.indexOf("onDismissSucceeded"), a.indexOf("onDismissCancelled"));
        assertTrue("acts on unlock success", success.contains("act(action, id)"));
        String cancelled = a.substring(a.indexOf("onDismissCancelled"), a.indexOf("onDismissError") + 60);
        assertFalse("never acts on a cancelled or failed unlock", cancelled.contains("act("));
        for (String f : new String[] {"setShowWhenLocked", "FLAG_SHOW_WHEN_LOCKED", "FLAG_DISMISS_KEYGUARD",
                "setTurnScreenOn"}) {
            assertFalse(f, a.contains(f));
        }
        assertTrue("same engine path as the receiver", a.contains("SendLaterEngine.onNotificationTap("));
        assertTrue(a.contains("PendingIntent.FLAG_IMMUTABLE"));

        String m = read(MANIFEST);
        int start = m.indexOf("<!-- sendlater:dl-actions start -->");
        int end = m.indexOf("<!-- sendlater:dl-actions end -->");
        assertTrue(start >= 0 && end > start);
        String block = m.substring(start, end);
        assertTrue(block.contains(".sendlater.engine.SendLaterUnlockActivity"));
        assertTrue(block.contains("android:exported=\"false\""));
        assertFalse(block.contains("showWhenLocked"));
    }

    // ------------------------------------------------------------------ UI M4 / M8 wiring

    @Test public void receiverRoutesTapsThroughTheReportingEnginePath() throws IOException {
        String r = code(ENGINE.resolve("SendLaterActionReceiver.java"));
        assertTrue(r.contains("SendLaterEngine.onNotificationTap(app, action, id)"));
        String n = code(ENGINE.resolve("AndroidEngineDeps.java"));
        assertTrue(n.contains("SendLaterNotifications.staleTap("));
        assertTrue(n.contains("SendLaterNotifications.sending("));
    }

    @Test public void lastCallTitleIsComputedAndSendingHasNoActions() throws IOException {
        String n = code(ENGINE.resolve("SendLaterNotifications.java"));
        assertFalse("no hard-coded 5 min title", n.contains("\" in 5 min\""));
        assertTrue(method(n, "public static void lastCall\\s*\\(").contains("lastCallTitle(m, now)"));
        String sending = method(n, "public static void sending\\s*\\(");
        assertFalse("no Cancel / Send now once sending", sending.contains("addAction"));
        assertFalse(sending.contains("setUsesChronometer"));
        assertTrue("replaces only a showing last call", sending.contains("KIND_LAST_CALL.equals(activeKind("));
    }

    @Test public void sheetCancelWaitsForTheResultOffTheMainThread() throws IOException {
        String s = code(SHEET);
        String secondary = method(s, "private void onSecondary\\s*\\(");
        assertTrue(secondary.contains("cancelMessage("));
        assertFalse("no fire-and-forget cancel", secondary.contains("onCancel("));
        String cancel = method(s, "private void cancelMessage\\s*\\(");
        int io = cancel.indexOf("io.execute(");
        int call = cancel.indexOf("SendLaterEngine.cancel(app, id)");
        assertTrue("engine cancel on the io executor", io >= 0 && call > io);
        int ui = cancel.indexOf("runOnUiThread(");
        assertTrue(ui > call);
        String onUi = cancel.substring(ui);
        assertTrue(onUi.contains("isFinishing() || isDestroyed()"));
        int done = onUi.indexOf("result == TapResult.DONE");
        assertTrue("closes only when the cancel took", done >= 0 && onUi.indexOf("finish()") > done);
        assertTrue("says what happened otherwise", onUi.contains("TapResult.feedback(result, true)"));
    }
}
