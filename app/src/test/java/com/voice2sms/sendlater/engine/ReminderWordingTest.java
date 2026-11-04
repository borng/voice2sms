package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.NAME;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.TEXT;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.ThreadEventSource;
import com.voice2sms.sendlater.send.LaunchRecheck;
import com.voice2sms.sendlater.send.SendDetails;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * reminder-wording-late-launch (F4, F6, N8, N9c, OD29; I2, R27, R28): every CANNOT_SHOW detail
 * gets its own reminder title, a late launch becomes the LATE reminder, the DAILY_CAP and BUSY
 * titles name the recipient, and the hold body labels the user's own text. Fictional data only.
 */
public class ReminderWordingTest {

    private static final long ID = 41L;

    private EngineFakes.World w;
    private final List<String> causes = new ArrayList<>();

    @Before public void setUp() {
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
            @Override public void mayHaveSent(ScheduledMessage m) { base.mayHaveSent(m); }
            @Override public void remindCannotSend(ScheduledMessage m, RemindCause cause) {
                causes.add(m.getId() + " " + cause);
                base.remind(m, cause.reason());
            }
            @Override public void dismiss(long id) { base.dismiss(id); }
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

    @After public void tearDown() { SendLaterEngine.resetForTest(); }

    private void cannotShow(String engineDetail) {
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, engineDetail);
        assertEquals(Status.SCHEDULED, w.store.status(ID));
    }

    private static String title(String sendPathCause) {
        RemindCause cause = RemindCause.fromDetail(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, sendPathCause));
        return SendLaterNotifications.cannotSendTitle(message(ID).build(), cause);
    }

    // ================================================================== detail -> title (F6, N8)

    @Test public void eachNonLockCauseReadsCouldNotSend() {
        String expected = "Couldn't send automatically to " + NAME;
        for (String cause : new String[] {"compose_timeout", "timeout", "inject_failed", "launch_timeout",
                "paused", "activity_gone", "other_intent", "no_recipient", "cannot_show"}) {
            assertEquals(cause, expected, title(cause));
            assertFalse(cause, title(cause).startsWith("Unlock"));
        }
    }

    @Test public void theLockedPlannerCauseStillReadsUnlock() {
        assertEquals("Unlock to send your message to " + NAME, title(RemindCause.DETAIL_LOCKED));
        assertEquals("null keeps the LOCKED default", "Unlock to send your message to " + NAME, title(null));
    }

    @Test public void sessionCapBusyAndLateTitles() {
        assertEquals("Sign in to Google Voice in voice2sms to send to " + NAME, title("auth"));
        assertEquals("Sign in to Google Voice in voice2sms to send to " + NAME, title("no_session"));
        assertEquals("Daily scheduled-send limit reached: tap to send to " + NAME, title("daily_cap"));
        assertEquals("You were using voice2sms; tap to send to " + NAME + " now", title("user_busy"));
        assertEquals("Too late to send automatically to " + NAME, title(LaunchRecheck.DETAIL_LATE));
    }

    @Test public void dailyCapAndBusyTitlesNameTheRecipient() {
        ScheduledMessage named = message(ID).build();
        ScheduledMessage nameless = message(ID).recipientDisplayName(null).build();
        for (RemindCause c : new RemindCause[] {RemindCause.DAILY_CAP, RemindCause.BUSY}) {
            assertTrue(c.toString(), SendLaterNotifications.cannotSendTitle(named, c).contains(NAME));
            assertTrue(c.toString(), SendLaterNotifications.cannotSendTitle(nameless, c)
                    .contains(SendLaterNotifications.who(nameless)));
        }
    }

    @Test public void onlyNoSessionGetsSignIn() {
        for (RemindCause c : RemindCause.values()) {
            assertEquals(c.toString(), c == RemindCause.NO_SESSION, SendLaterNotifications.hasSignInAction(c));
        }
    }

    @Test public void newCausesAreFiledUnderPinnedReasons() {
        assertEquals(RemindCause.COULD_NOT_SEND, RemindCause.fromDetail("could_not_send"));
        assertEquals(RemindCause.LATE, RemindCause.fromDetail("late"));
        assertEquals(RemindCause.LOCKED, RemindCause.fromDetail("locked"));
        assertEquals("late_reply is not late", RemindCause.LOCKED, RemindCause.fromDetail("late_reply"));
        assertEquals(RemindReason.LOCKED, RemindCause.COULD_NOT_SEND.reason());
        assertEquals(RemindReason.LATE, RemindCause.LATE.reason());
    }

    // ================================================================== engine (F4, F6)

    @Test public void couldNotSendDetailRemindsWithTheGenericCause() {
        cannotShow(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "compose_timeout"));
        assertEquals(Collections.singletonList(ID + " COULD_NOT_SEND"), causes);
        assertTrue(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " LOCKED"));
    }

    @Test public void aLateLaunchBecomesTheLateReminderAndIsNeverRetried() {
        cannotShow(SendDetails.forEngine(SendOutcome.CANNOT_SHOW, LaunchRecheck.DETAIL_LATE));
        assertEquals(Collections.singletonList(ID + " LATE"), causes);
        assertTrue(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " LATE"));
        // Reminded: a later DUE (boot, time change, stray alarm) must not auto-send it (I6).
        w.cond.now = T + 40 * MIN;
        int before = w.sender.started.size();
        SendLaterEngine.onDue(null, ID);
        assertEquals(before, w.sender.started.size());
        assertEquals(Status.SCHEDULED, w.store.status(ID));
    }

    // ================================================================== hold body (OD29, R28)

    @Test public void theHoldBodyLabelsTheUsersOwnText() {
        ScheduledMessage m = message(ID).build();
        assertEquals("Your message: " + TEXT, SendLaterNotifications.holdBody(m, null, T));
        assertEquals("Your message: " + TEXT, SendLaterNotifications.holdBody(m, "  ", T));
        assertEquals("Your message: " + TEXT + " (3 h late)",
                SendLaterNotifications.holdBody(m, null, T + 3 * 60 * MIN));
    }

    @Test public void aKnownReplyPreviewIsShownInstead() {
        ScheduledMessage m = message(ID).build();
        String reply = "on my way";
        assertEquals(reply, SendLaterNotifications.holdBody(m, reply, T));
        assertFalse(SendLaterNotifications.holdBody(m, reply, T).contains(TEXT));
        assertEquals(reply + " (45 min late)", SendLaterNotifications.holdBody(m, reply, T + 45 * MIN));
    }
}
