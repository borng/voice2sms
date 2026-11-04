package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.HOUR;
import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.inboundSms;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Engine paths beyond the acceptance suite: taps, outcomes, retry cap, labels, cookie parsing. */
public class SendLaterEngineExtrasTest {

    private static final long ID = 7L;
    private EngineFakes.World w;

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        SendLaterEngine.installForTest(w.deps(true));
    }

    @After public void tearDown() { SendLaterEngine.resetForTest(); }

    @Test public void snoozeReschedulesOneHourFromNowWithAFreshWatermarkAndRegistersAlarms() {
        ScheduledMessage held = message(ID).status(Status.HELD).holdReason(HoldReason.REPLIED).build();
        w.store.put(held);
        w.cond.now = T + HOUR;
        SendLaterEngine.onSnooze(null, ID);
        ScheduledMessage m = w.store.rows.get(ID);
        assertEquals(Status.SCHEDULED, m.getStatus());
        assertEquals(T + 2 * HOUR, m.getScheduledFor());
        // OD21 (2) superseded "keeps the watermark": snooze takes a fresh DEVICE_ONLY one at now.
        assertEquals(Watermark.Quality.DEVICE_ONLY, m.getWatermark().getQuality());
        assertEquals(T + HOUR, m.getWatermark().getCapturedAtDevice());
        assertEquals(T + 2 * HOUR, w.alarms.timeOf("exact " + AlarmKind.DUE + " " + ID + " "));
        assertEquals(T + 2 * HOUR - 5 * MIN, w.alarms.timeOf("exact " + AlarmKind.LAST_CALL + " " + ID + " "));
        assertFalse(w.alarms.matching("cancel " + AlarmKind.EXPIRE + " " + ID).isEmpty());
    }

    @Test public void cancelTapCancelsEveryAlarmAndBroadcasts() {
        w.store.put(message(ID).build());
        SendLaterEngine.onCancel(null, ID);
        assertEquals(Status.CANCELED, w.store.status(ID));
        for (AlarmKind k : AlarmKind.values()) {
            assertFalse(k + " cancelled: " + w.alarms.calls, w.alarms.matching("cancel " + k + " " + ID).isEmpty());
        }
        assertEquals(1, w.broadcaster.sent.size());
    }

    @Test public void staleSnoozeAndCancelTapsAreDropped() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).build());
        w.store.put(message(8).status(Status.SENT_VERIFIED).build());
        SendLaterEngine.onSnooze(null, ID);
        SendLaterEngine.onCancel(null, ID);
        SendLaterEngine.onSnooze(null, 8);
        SendLaterEngine.onCancel(null, 8);
        SendLaterEngine.onCancel(null, 999);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertEquals(Status.SENT_VERIFIED, w.store.status(8));
        assertTrue(w.broadcaster.sent.isEmpty());
    }

    @Test public void sentUnverifiedWithoutBubbleHoldsAndNeverRetries() {
        w.store.put(message(ID).build());
        SendLaterEngine.setSender(w.sender);
        SendLaterEngine.onDue(null, ID);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_UNVERIFIED, null);
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue(w.notifier.has("mayHaveSent " + ID));
        assertEquals(1, w.sender.started.size());
        assertEquals(0, w.j.count("store.releaseForRetry"));
    }

    @Test public void cannotShowReleasesToScheduledAndRemindsLocked() {
        w.store.put(message(ID).build());
        SendLaterEngine.setSender(w.sender);
        SendLaterEngine.onDue(null, ID);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, null);
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " LOCKED"));
    }

    @Test public void senderThatDoesNotStartIsTreatedAsCannotShow() {
        w.store.put(message(ID).build());
        w.sender.result = false;
        SendLaterEngine.setSender(w.sender);
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " LOCKED"));
    }

    @Test public void secondFailureIsNotRetriedAgain() {
        w.store.put(message(ID).build());
        SendLaterEngine.setSender(w.sender);
        SendLaterEngine.onDue(null, ID);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.FAILED, "x");
        assertEquals("one retry", 2, w.sender.started.size());
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.FAILED, "x");
        assertEquals("no second retry", 2, w.sender.started.size());
        assertEquals(Status.FAILED, w.store.status(ID));
        assertFalse(w.notifier.posted.isEmpty());
    }

    @Test public void earlyHoldMatchesByConversationKeyWithoutANumber() {
        ScheduledMessage m = message(ID).recipientE164(null).build();
        w.store.put(m);
        w.cond.now = T - HOUR;
        SendLaterEngine.onInboundEvent(null, ConversationRef.of(m).key(), inboundSms(T - HOUR));
        assertEquals(Status.HELD, w.store.status(ID));
    }

    @Test public void lateLabelsUseMinutesUnderAnHour() {
        assertEquals("Send anyway (45 min late)",
                SendLaterNotifications.sendAnywayLabel(message(1).build(), T + 45 * MIN));
        assertEquals("Send anyway", SendLaterNotifications.sendAnywayLabel(message(1).build(), T + 30 * MIN));
    }

    @Test public void sidCookieMustBeNamedExactlySid() {
        assertTrue(GvSession.hasSidCookie("NID=1; SID=abc; HSID=2"));
        assertTrue(GvSession.hasSidCookie("SID=abc"));
        assertFalse(GvSession.hasSidCookie("__Secure-3PSID=abc; HSID=2"));
        assertFalse(GvSession.hasSidCookie("SID="));
        assertFalse(GvSession.hasSidCookie(null));
    }
}
