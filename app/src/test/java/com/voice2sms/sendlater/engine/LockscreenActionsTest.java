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
 * Deliver sec-L1 (dlv-lockscreen-actions): Cancel, Snooze 1 h and Keep draft never run from the
 * lock screen without an unlock. API 31+: authentication-required broadcasts; below 31: through
 * {@link SendLaterUnlockActivity}, which grants no foreground send for them. Paths are relative
 * to the app module.
 */
public class LockscreenActionsTest {

    private static final Path ENGINE = Paths.get("src/main/java/com/voice2sms/sendlater/engine");

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

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test public void cancelSnoozeAndKeepAreUnlockOnlyActions() {
        assertTrue(SendLaterUnlockActivity.isUnlockOnlyAction(SendLaterIntents.ACTION_CANCEL));
        assertTrue(SendLaterUnlockActivity.isUnlockOnlyAction(SendLaterIntents.ACTION_SNOOZE));
        assertTrue(SendLaterUnlockActivity.isUnlockOnlyAction(DraftNotice.ACTION_KEEP));
        assertFalse("sends are granted actions, not unlock-only",
                SendLaterUnlockActivity.isUnlockOnlyAction(SendLaterIntents.ACTION_SEND_NOW));
        assertFalse(SendLaterUnlockActivity.isUnlockOnlyAction(DraftNotice.ACTION_REPLACE));
        assertFalse(SendLaterUnlockActivity.isUnlockOnlyAction(SendLaterIntents.ACTION_EDIT));
        assertFalse(SendLaterUnlockActivity.isUnlockOnlyAction(null));
    }

    @Test public void theActivityAcceptsAllProtectedActionsAndNothingElse() {
        for (String a : new String[] {SendLaterIntents.ACTION_SEND_NOW, SendLaterIntents.ACTION_SEND_ANYWAY,
                DraftNotice.ACTION_REPLACE, DraftNotice.ACTION_BOTH, SendLaterIntents.ACTION_CANCEL,
                SendLaterIntents.ACTION_SNOOZE, DraftNotice.ACTION_KEEP}) {
            assertTrue(a, SendLaterUnlockActivity.accepts(a));
        }
        assertFalse(SendLaterUnlockActivity.accepts(SendLaterIntents.ACTION_EDIT));
        assertFalse(SendLaterUnlockActivity.accepts("com.voice2sms.sendlater.action.OTHER"));
        assertFalse(SendLaterUnlockActivity.accepts(null));
    }

    @Test public void apiLevelPicksActivityBelow31AndAuthenticatedBroadcastFrom31() {
        assertTrue(SendLaterNotifications.lockedActionUsesActivity(24));
        assertTrue(SendLaterNotifications.lockedActionUsesActivity(26));
        assertTrue(SendLaterNotifications.lockedActionUsesActivity(30));
        assertFalse(SendLaterNotifications.lockedActionUsesActivity(31));
        assertFalse(SendLaterNotifications.lockedActionUsesActivity(34));
    }

    @Test public void lockedActionIsAuthenticatedBroadcastOrUnlockActivity() throws IOException {
        String n = code(ENGINE.resolve("SendLaterNotifications.java"));
        String locked = method(n, "NotificationCompat\\.Action\\s+lockedAction\\s*\\(");
        assertTrue(locked.contains("lockedActionUsesActivity(Build.VERSION.SDK_INT)"));
        assertTrue(locked.contains("SendLaterUnlockActivity.pendingIntent(c, action, id)"));
        assertTrue(locked.contains("broadcast(c, action, id)"));
        assertTrue(locked.contains("setAuthenticationRequired(true)"));
        // the broadcast branch (after the activity branch returns) is the authenticated one
        int act = locked.indexOf("SendLaterUnlockActivity.pendingIntent(");
        int bc = locked.indexOf("broadcast(c, action, id)");
        int auth = locked.indexOf("setAuthenticationRequired(true)");
        assertTrue(act < bc && bc < auth);
        String b = method(n, "private static PendingIntent broadcast\\s*\\(");
        assertTrue(b.contains("PendingIntent.FLAG_IMMUTABLE"));
        assertTrue(b.contains("setPackage(c.getPackageName())"));
    }

    @Test public void noCancelSnoozeOrKeepButtonIsAPlainBroadcast() throws IOException {
        String n = code(ENGINE.resolve("SendLaterNotifications.java"));
        for (String a : new String[] {"SendLaterIntents\\.ACTION_CANCEL", "SendLaterIntents\\.ACTION_SNOOZE",
                "DraftNotice\\.ACTION_KEEP"}) {
            assertFalse(a + " as a plain broadcast",
                    Pattern.compile("broadcast\\s*\\(\\s*c\\s*,\\s*" + a).matcher(n).find());
        }
        // lastCall Cancel, hold Snooze + Cancel, may-have-sent Cancel, draft Keep.
        assertEquals(3, count(Pattern.compile(
                "lockedAction\\s*\\(\\s*c\\s*,\\s*\"Cancel\"\\s*,\\s*SendLaterIntents\\.ACTION_CANCEL"), n));
        assertEquals(1, count(Pattern.compile(
                "lockedAction\\s*\\(\\s*c\\s*,\\s*\"Snooze 1 h\"\\s*,\\s*SendLaterIntents\\.ACTION_SNOOZE"), n));
        assertEquals(1, count(Pattern.compile(
                "lockedAction\\s*\\(\\s*c\\s*,\\s*\"Keep draft\"\\s*,\\s*DraftNotice\\.ACTION_KEEP"), n));
        // no other way to add these buttons
        assertFalse(Pattern.compile("addAction\\s*\\(\\s*0\\s*,\\s*\"(Cancel|Snooze 1 h|Keep draft)\"").matcher(n).find());
    }

    @Test public void unlockOnlyActionsGetNoForegroundSendGrant() throws IOException {
        String a = code(ENGINE.resolve("SendLaterUnlockActivity.java"));
        String onCreate = method(a, "protected void onCreate\\s*\\(");
        assertTrue(onCreate.contains("!accepts(action)"));
        assertTrue("keyguard rule unchanged", onCreate.contains("isKeyguardLocked()"));
        String act = method(a, "private void act\\s*\\(");
        int branch = act.indexOf("if (!isUnlockAction(action))");
        int plain = act.indexOf("actWithoutGrant(action, id)");
        int grant = act.indexOf("UserTapGrant.grant(");
        assertTrue("non-send actions branch off before any grant", branch >= 0 && plain > branch && grant > plain);
        String noGrant = method(a, "private void actWithoutGrant\\s*\\(");
        assertFalse(noGrant.contains("UserTapGrant"));
        assertTrue("same engine path as the receiver", noGrant.contains("SendLaterEngine.onNotificationTap(app, action, id)"));
        assertTrue(noGrant.contains("release()"));
    }

    @Test public void receiverStillHandlesTheAuthenticatedBroadcasts() throws IOException {
        String r = code(ENGINE.resolve("SendLaterActionReceiver.java"));
        for (String s : new String[] {"SendLaterIntents.ACTION_SNOOZE", "SendLaterIntents.ACTION_CANCEL",
                "DraftNotice.ACTION_KEEP"}) {
            assertTrue(s, r.contains(s));
        }
    }
}
