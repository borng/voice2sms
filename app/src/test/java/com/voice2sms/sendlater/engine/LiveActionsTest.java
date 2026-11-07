package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.ThreadEventSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * live-actions (LIVE-F4, LIVE-F8; OD27, OD31 L1): Send now / Send anyway are activity
 * PendingIntents on every API level (the send starts from the foreground, so it never depends on
 * the accessibility service); Snooze / Cancel stay broadcasts; request codes are distinct per
 * message and action; the tap grant is bounded; "may be a duplicate" only after SENT_UNVERIFIED.
 * Fictional data only.
 */
public class LiveActionsTest {

    private static final long ID = 71L;
    private static final Path ENGINE = Paths.get("src/main/java/com/voice2sms/sendlater/engine");
    private static final Path SEND = Paths.get("src/main/java/com/voice2sms/sendlater/send");

    private EngineFakes.World w;
    private final List<String> mayHaveSent = new ArrayList<>();

    @Before public void setUp() {
        UserTapGrant.clearAll();
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        final EngineFakes.FakeNotifier base = w.notifier;
        EngineNotifier notifier = new EngineNotifier() {
            @Override public void lastCall(ScheduledMessage m) { base.lastCall(m); }
            @Override public void hold(ScheduledMessage m, HoldReason r, long nowMs) { base.hold(m, r, nowMs); }
            @Override public void remind(ScheduledMessage m, RemindReason r) { base.remind(m, r); }
            @Override public void sent(ScheduledMessage m) { base.sent(m); }
            @Override public void repliedDuringSend(ScheduledMessage m) { base.repliedDuringSend(m); }
            @Override public void selfPreempted(ScheduledMessage m) { base.selfPreempted(m); }
            @Override public void expired(ScheduledMessage m) { base.expired(m); }
            @Override public void mayHaveSent(ScheduledMessage m) { mayHaveSent.add(m.getId() + " legacy"); }
            @Override public void mayHaveSent(ScheduledMessage m, boolean duplicateRisk) {
                mayHaveSent.add(m.getId() + " " + duplicateRisk);
            }
        };
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store)
                .sources(new ArrayList<ThreadEventSource>(Collections.singletonList(w.source)))
                .notifier(notifier)
                .broadcaster(w.broadcaster)
                .alarms(w.alarms)
                .conditions(w.cond)
                .networkWaiter(w.network)
                .executor(Runnable::run)
                .build());
        SendLaterEngine.setSender(w.sender);
    }

    @After public void tearDown() {
        SendLaterEngine.resetForTest();
        UserTapGrant.clearAll();
    }

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

    // ------------------------------------------------------------------ LIVE-F8 wording

    @Test public void onlySentUnverifiedWarnsOfADuplicate() {
        assertTrue(SendLaterNotifications.duplicateRisk(SendOutcome.SENT_UNVERIFIED));
        assertFalse(SendLaterNotifications.duplicateRisk(SendOutcome.CANNOT_SHOW));
        assertFalse(SendLaterNotifications.duplicateRisk(SendOutcome.SENT_VERIFIED));
        assertFalse(SendLaterNotifications.duplicateRisk(SendOutcome.FAILED));
        assertFalse("interrupted IN_FLIGHT row: progress unknown", SendLaterNotifications.duplicateRisk(null));
    }

    @Test public void wordingFollowsTheDuplicateRisk() {
        ScheduledMessage m = message(ID).build();
        long now = m.getScheduledFor() + 60_000L;
        assertEquals("Send anyway (may be a duplicate)", SendLaterNotifications.sendAnywayLabel(m, now,
                SendLaterNotifications.duplicateRisk(SendOutcome.SENT_UNVERIFIED)));
        assertEquals("Send anyway", SendLaterNotifications.sendAnywayLabel(m, now,
                SendLaterNotifications.duplicateRisk(SendOutcome.CANNOT_SHOW)));
        assertEquals("Send anyway", SendLaterNotifications.sendAnywayLabel(m, now,
                SendLaterNotifications.duplicateRisk(null)));
    }

    @Test public void sentUnverifiedHoldPassesTheDuplicateRisk() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_UNVERIFIED, "no_match");
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(Collections.singletonList(ID + " true"), mayHaveSent);
    }

    @Test public void inFlightAtRestartIsMayHaveSentWithThePlainLabel() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        SendLaterEngine.resolveInFlight(null);
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(Collections.singletonList(ID + " false"), mayHaveSent);
    }

    @Test public void cannotShowIsAReminderNotAMayHaveSent() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, "cannot_show");
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(mayHaveSent.isEmpty());
    }

    @Test public void notificationPostsTheDuplicateLabelOnlyWhenAsked() throws IOException {
        String n = code(ENGINE.resolve("SendLaterNotifications.java"));
        String two = method(n, "public static void mayHaveSent\\s*\\(Context c, ScheduledMessage m, boolean duplicateRisk\\)");
        assertTrue(two.contains("sendAnywayLabel(m, System.currentTimeMillis(), duplicateRisk)"));
        String deps = code(ENGINE.resolve("AndroidEngineDeps.java"));
        assertTrue(deps.contains("SendLaterNotifications.mayHaveSent(app, m, duplicateRisk)"));
        String eng = code(ENGINE.resolve("SendLaterEngine.java"));
        assertTrue(eng.contains("d.notifier.mayHaveSent(held, SendLaterNotifications.duplicateRisk(lastOutcome))"));
    }

    // ------------------------------------------------------------------ LIVE-F4 PendingIntent kinds

    @Test public void sendNowAndSendAnywayAreActivitiesOnEveryApiLevel() throws IOException {
        String n = code(ENGINE.resolve("SendLaterNotifications.java"));
        String unlocked = method(n, "NotificationCompat\\.Action\\s+unlockedAction\\s*\\(");
        assertTrue(unlocked.contains("SendLaterUnlockActivity.pendingIntent(c, action, id)"));
        assertFalse("never a broadcast, on any API level", unlocked.contains("broadcast("));
        assertFalse(unlocked.contains("getBroadcast"));
        String a = code(ENGINE.resolve("SendLaterUnlockActivity.java"));
        String pi = method(a, "static PendingIntent pendingIntent\\s*\\(");
        assertTrue(pi.contains("PendingIntent.getActivity("));
        assertTrue(pi.contains("PendingIntent.FLAG_IMMUTABLE"));
        assertTrue(pi.contains("SendLaterNotifications.requestCode(action, id)"));
        assertTrue(pi.contains("FLAG_ACTIVITY_NO_HISTORY"));
        assertTrue(pi.contains("FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS"));
    }

    @Test public void snoozeAndCancelStayBroadcasts() throws IOException {
        String n = code(ENGINE.resolve("SendLaterNotifications.java"));
        // Deliver sec-L1 (dlv-lockscreen-actions): Snooze / Cancel are broadcasts on API 31+ only
        // (authentication-required); below 31 they go through SendLaterUnlockActivity.
        assertTrue(Pattern.compile("lockedAction\\s*\\(\\s*c\\s*,[^;]*SendLaterIntents\\.ACTION_SNOOZE").matcher(n).find());
        assertTrue(Pattern.compile("lockedAction\\s*\\(\\s*c\\s*,[^;]*SendLaterIntents\\.ACTION_CANCEL").matcher(n).find());
        assertTrue(method(n, "NotificationCompat\\.Action\\s+lockedAction\\s*\\(").contains("broadcast(c, action, id)"));
        String b = method(n, "private static PendingIntent broadcast\\s*\\(");
        assertTrue(b.contains("PendingIntent.getBroadcast(c, requestCode(action, id)"));
        assertTrue(b.contains("PendingIntent.FLAG_IMMUTABLE"));
        assertTrue(b.contains("setPackage(c.getPackageName())"));
    }

    @Test public void requestCodesAreDistinctPerMessageAndAction() {
        String[] actions = {SendLaterIntents.ACTION_SEND_NOW, SendLaterIntents.ACTION_SEND_ANYWAY,
                SendLaterIntents.ACTION_SNOOZE, SendLaterIntents.ACTION_CANCEL};
        Set<Integer> seen = new HashSet<>();
        for (long id = 1; id <= 50; id++) {
            for (String a : actions) assertTrue(id + " " + a, seen.add(SendLaterNotifications.requestCode(a, id)));
        }
        assertNotEquals(SendLaterNotifications.requestCode(SendLaterIntents.ACTION_SEND_NOW, ID),
                SendLaterNotifications.requestCode(SendLaterIntents.ACTION_SEND_ANYWAY, ID));
    }

    @Test public void tapActivityGrantsTheForegroundTapAndKeepsTheUnlockRule() throws IOException {
        String a = code(ENGINE.resolve("SendLaterUnlockActivity.java"));
        String act = method(a, "private void act\\s*\\(");
        int grant = act.indexOf("UserTapGrant.grant(");
        int tap = act.indexOf("SendLaterEngine.onNotificationTap(app, action, id)");
        assertTrue("grant before the engine plans the send", grant >= 0 && tap > grant);
        assertTrue(method(a, "private void release\\s*\\(").contains("UserTapGrant.revoke("));
        assertTrue(method(a, "protected void onDestroy\\s*\\(").contains("UserTapGrant.revoke("));
        // OD31 L1 unchanged: unlocked check + requestDismissKeyguard; never over the keyguard.
        assertTrue(a.contains("isKeyguardLocked()"));
        assertTrue(a.contains("requestDismissKeyguard("));
        assertFalse(a.contains("setShowWhenLocked"));
    }

    @Test public void senderPlansWithTheGrantAndReleasesItAtEveryLaunchDecision() throws IOException {
        String s = code(SEND.resolve("WebViewScheduledSender.java"));
        assertTrue(method(s, "private static LaunchPlanner\\.Result planFor\\s*\\(").contains("isUserTapForeground(id)"));
        assertTrue(method(s, "static boolean isUserTapForeground\\s*\\(").contains("UserTapGrant.isActive("));
        String launch = method(s, "private static void launch\\s*\\(");
        assertTrue(launch.contains("finally"));
        assertTrue(launch.contains("UserTapGrant.consume("));
        assertFalse("never auto-send flags from Send Later", s.contains("EXTRA_FORCE_AUTO_SEND"));
    }

    // ------------------------------------------------------------------ UserTapGrant

    @Test public void grantIsPerMessageAndBounded() {
        UserTapGrant.grant(ID, 1_000L, null);
        assertTrue(UserTapGrant.isActive(ID, 1_000L));
        assertFalse("other messages are not user taps", UserTapGrant.isActive(ID + 1, 1_000L));
        assertTrue(UserTapGrant.isActive(ID, 1_000L + UserTapGrant.MAX_AGE_MS));
        assertFalse("never outlives its ceiling", UserTapGrant.isActive(ID, 1_001L + UserTapGrant.MAX_AGE_MS));
        assertFalse("expired grants are dropped", UserTapGrant.isActive(ID, 1_000L));
    }

    @Test public void consumeEndsTheGrantAndRunsTheCallbackOnce() {
        AtomicInteger calls = new AtomicInteger();
        UserTapGrant.grant(ID, 0L, calls::incrementAndGet);
        assertTrue(UserTapGrant.consume(ID, 10L));
        assertFalse(UserTapGrant.consume(ID, 20L));
        assertEquals(1, calls.get());
        assertFalse(UserTapGrant.isActive(ID, 20L));
    }

    @Test public void revokeEndsTheGrantWithoutTheCallback() {
        AtomicInteger calls = new AtomicInteger();
        UserTapGrant.grant(ID, 0L, calls::incrementAndGet);
        UserTapGrant.revoke(ID);
        assertFalse(UserTapGrant.isActive(ID, 1L));
        assertFalse(UserTapGrant.consume(ID, 1L));
        assertEquals(0, calls.get());
    }

    @Test public void aClockGoingBackwardsNeverKeepsAGrant() {
        UserTapGrant.grant(ID, 5_000L, null);
        assertFalse(UserTapGrant.isActive(ID, 4_000L));
    }
}
