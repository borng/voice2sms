package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.E164;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Deliver M4: a send the controller verified, whose bubble GV later turns into "Not sent", is
 * downgraded SENT_VERIFIED -> HELD(UNVERIFIED) with the "may have already sent" notification,
 * once, and only within the follow-up window. Fictional data only (EngineFakes).
 */
public class SentNotDeliveredEngineTest {

    private static final long ID = 7L;
    private EngineFakes.World w;

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        SendLaterEngine.installForTest(w.deps(true));
    }

    @After public void tearDown() {
        SendLaterEngine.resetForTest();
    }

    /** Due at T, sent, verified at T + 30 s. */
    private void verified() {
        w.store.put(message(ID).build());
        SendLaterEngine.setSender(w.sender);
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        w.cond.now = T + 30_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, null);
        assertEquals(Status.SENT_VERIFIED, w.store.status(ID));
        assertTrue(w.notifier.has("sent " + ID));
        w.broadcaster.sent.clear();
    }

    @Test public void aLaterNotSentDowngradesToHeldUnverifiedWithMayHaveSent() {
        verified();
        w.cond.now = T + 45_000;
        SendLaterEngine.onSentNotDelivered(null, ID);
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.UNVERIFIED, w.store.rows.get(ID).getHoldReason());
        assertTrue("could-not-confirm notification: " + w.notifier.posted,
                w.notifier.has("mayHaveSent " + ID));
        assertTrue("state broadcast: " + w.broadcaster.sent, w.broadcaster.sent.contains(ID + " " + E164));
        assertFalse("an expiry alarm is armed like any HELD row: " + w.alarms.calls,
                w.alarms.matching("inexact " + AlarmKind.EXPIRE + " " + ID + " ").isEmpty());
        assertTrue("never re-sent (I5)", w.sender.started.size() == 1);
    }

    @Test public void appliesOnlyOnce() {
        verified();
        w.cond.now = T + 40_000;
        SendLaterEngine.onSentNotDelivered(null, ID);
        int posts = w.notifier.posted.size();
        SendLaterEngine.onSentNotDelivered(null, ID);
        assertEquals(1, w.j.count("store.unverifySent"));
        assertEquals(posts, w.notifier.posted.size());
    }

    @Test public void tooLateLeavesTheVerdict() {
        verified();
        w.cond.now = T + 30_000 + SendLaterEngine.SENT_FOLLOW_UP_MAX_MS + 1;
        SendLaterEngine.onSentNotDelivered(null, ID);
        assertEquals(Status.SENT_VERIFIED, w.store.status(ID));
        assertEquals(0, w.j.count("store.unverifySent"));
        assertFalse(w.notifier.has("mayHaveSent " + ID));
    }

    @Test public void aRowThisProcessDidNotVerifyIsIgnored() {
        w.store.put(message(ID).status(Status.SENT_VERIFIED).build());
        SendLaterEngine.onSentNotDelivered(null, ID);
        assertEquals(Status.SENT_VERIFIED, w.store.status(ID));
        assertEquals(0, w.j.count("store.unverifySent"));
    }

    @Test public void aRowNoLongerSentIsIgnored() {
        verified();
        // Something else already moved it (fake only: SENT_VERIFIED is terminal in the real store).
        w.store.put(w.store.rows.get(ID).toBuilder().status(Status.CANCELED).build());
        w.cond.now = T + 40_000;
        SendLaterEngine.onSentNotDelivered(null, ID);
        assertEquals(Status.CANCELED, w.store.status(ID));
        assertEquals(0, w.j.count("store.unverifySent"));
        assertFalse(w.notifier.has("mayHaveSent " + ID));
    }
}
