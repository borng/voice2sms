package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.HOUR;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.domain.DraftChoice;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LadderEvaluator;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

/**
 * D42: a scheduled send that stops on the user's own draft ends HELD with {@link HoldReason#DRAFT}
 * (not SCHEDULED + reminded), Keep draft leaves it there, and it never sends without a user tap.
 * Send anyway / Replace and send still go through the D32 draft check. Fictional data only.
 */
public class HeldDraftEngineTest {

    private static final long ID = 92L;
    private static final String DRAFT = "Can you grab milk on the way?";

    private EngineFakes.World w;
    private DraftChoiceEngineTest.DraftNotifier notifier;
    private EngineMarks.InMemory marks;
    private DraftChoiceEngineTest.ModeSender sender;

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        DraftStash.clearAll();
        w = new EngineFakes.World();
        notifier = new DraftChoiceEngineTest.DraftNotifier();
        marks = new EngineMarks.InMemory();
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store)
                .sources(Collections.<com.voice2sms.sendlater.domain.ThreadEventSource>singletonList(w.source))
                .notifier(notifier)
                .broadcaster(w.broadcaster)
                .alarms(w.alarms)
                .conditions(w.cond)
                .networkWaiter(w.network)
                .executor(Runnable::run)
                .marks(marks)
                .build());
        sender = new DraftChoiceEngineTest.ModeSender();
        SendLaterEngine.setSender(sender);
    }

    @After public void tearDown() {
        SendLaterEngine.resetForTest();
        DraftStash.clearAll();
    }

    /** A due message dispatched by the engine whose job found the user's draft in the box. */
    private void draftOutcome() {
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        DraftStash.put(ID, DRAFT);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, RemindCause.DETAIL_COULD_NOT_SEND);
        sender.modes.clear();
    }

    private void assertHeldDraft() {
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.DRAFT, w.store.get(ID).getHoldReason());
    }

    // ------------------------------------------------------------------ the outcome (no choice yet)

    @Test public void theDraftOutcomeHoldsTheRecordWithReasonDraft() {
        draftOutcome();
        assertHeldDraft();
    }

    @Test public void theDraftOutcomeStillPostsTheChoiceNotificationAndNoReminder() {
        draftOutcome();
        assertEquals(Collections.singletonList(DRAFT), notifier.drafts);
        for (String p : notifier.posted) {
            assertFalse(p, p.startsWith("remind") || p.startsWith("cannotSend"));
        }
    }

    @Test public void withoutADraftTheCannotShowOutcomeIsNotHeldAsDraft() {
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, RemindCause.DETAIL_COULD_NOT_SEND);
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(notifier.drafts.isEmpty());
    }

    @Test public void aPreSendHoldWinsOverAStashedDraft() {
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        DraftStash.put(ID, DRAFT);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, "presend_replied");
        assertEquals(Status.HELD, w.store.status(ID));
        assertEquals(HoldReason.REPLIED, w.store.get(ID).getHoldReason());
    }

    // ------------------------------------------------------------------ Keep draft

    @Test public void keepDraftLeavesTheRecordHeldWithReasonDraft() {
        draftOutcome();
        assertEquals(TapResult.DONE, SendLaterEngine.onNotificationTap(null, DraftNotice.ACTION_KEEP, ID));
        assertHeldDraft();
        assertTrue("nothing typed or sent", sender.modes.isEmpty());
        assertTrue(notifier.dismissed.contains(ID));
    }

    @Test public void keepDraftOnAScheduledRowHoldsItWithReasonDraft() {
        // A row still SCHEDULED + reminded (e.g. a choice notification posted before D42).
        w.store.put(message(ID).build());
        marks.setReminded(ID, true);
        assertEquals(TapResult.DONE, SendLaterEngine.onNotificationTap(null, DraftNotice.ACTION_KEEP, ID));
        assertHeldDraft();
        assertTrue(sender.modes.isEmpty());
    }

    // ------------------------------------------------------------------ never sends without a tap

    @Test public void aHeldDraftNeverSendsItselfOnDueLastCallOrRestart() {
        draftOutcome();
        SendLaterEngine.onNotificationTap(null, DraftNotice.ACTION_KEEP, ID);
        w.cond.now = T + HOUR;
        SendLaterEngine.onDue(null, ID);
        SendLaterEngine.onLastCall(null, ID);
        SendLaterEngine.onProcessStart(null);
        SendLaterEngine.onDue(null, ID);
        assertTrue("no send started without a user tap", sender.modes.isEmpty());
        assertHeldDraft();
    }

    @Test public void sendAnywayFromUpcomingStillGoesThroughTheDraftCheck() {
        draftOutcome();
        assertEquals(TapResult.DONE, SendLaterEngine.sendAnyway(null, ID));
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertEquals(Collections.singletonList(DraftChoice.Mode.ASK), sender.modes);
    }

    @Test public void sendNowTapOnAHeldDraftAsks() {
        draftOutcome();
        SendLaterEngine.onNotificationTap(null, SendLaterIntents.ACTION_SEND_NOW, ID);
        assertEquals(Collections.singletonList(DraftChoice.Mode.ASK), sender.modes);
    }

    @Test public void replaceAndSendFromAHeldDraftStartsOneReplaceSend() {
        draftOutcome();
        assertEquals(TapResult.DONE, SendLaterEngine.onNotificationTap(null, DraftNotice.ACTION_REPLACE, ID));
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertEquals(Collections.singletonList(DraftChoice.Mode.REPLACE), sender.modes);
    }

    @Test public void aSendAnywayThatHitsTheDraftAgainHoldsAsDraftAgain() {
        draftOutcome();
        SendLaterEngine.sendAnyway(null, ID);
        DraftStash.put(ID, DRAFT);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, RemindCause.DETAIL_COULD_NOT_SEND);
        assertHeldDraft();
    }

    // ------------------------------------------------------------------ expiry (same as other holds)

    @Test public void aHeldDraftArmsTheExpiryAndExpires24hPastItsTime() {
        draftOutcome();
        assertFalse("expiry alarm armed", w.alarms.registered(AlarmKind.EXPIRE, ID).isEmpty());
        w.cond.now = T + LadderEvaluator.EXPIRY_MS;
        SendLaterEngine.onExpireAlarm(null, ID);
        assertEquals(Status.EXPIRED, w.store.status(ID));
        assertTrue(sender.modes.isEmpty());
    }

    @Test public void anEarlyExpiryAlarmLeavesTheHeldDraftAlone() {
        draftOutcome();
        w.cond.now = T + LadderEvaluator.EXPIRY_MS - HOUR;
        SendLaterEngine.onExpireAlarm(null, ID);
        assertHeldDraft();
    }
}
