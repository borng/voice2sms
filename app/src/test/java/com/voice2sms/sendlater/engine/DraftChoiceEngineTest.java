package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.NAME;
import static com.voice2sms.sendlater.engine.EngineFakes.TEXT;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.domain.DraftChoice;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.PreSendCheck;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * D32: a draft in the conversation's box ends the job unsent with the choice notification, and
 * the notification's three actions. Fictional data only (EngineFakes).
 */
public class DraftChoiceEngineTest {

    private static final long ID = 91L;
    private static final String DRAFT = "Can you grab milk on the way?";

    private EngineFakes.World w;
    private DraftNotifier notifier;
    private EngineMarks.InMemory marks;
    private ModeSender sender;

    /** Records the draft notice apart from every other notification. */
    static final class DraftNotifier implements EngineNotifier {
        final List<String> posted = new ArrayList<>();
        final List<String> drafts = new ArrayList<>();
        final List<Long> dismissed = new ArrayList<>();

        @Override public void lastCall(ScheduledMessage m) { posted.add("lastCall"); }
        @Override public void hold(ScheduledMessage m, HoldReason r, long nowMs) { posted.add("hold " + r); }
        @Override public void remind(ScheduledMessage m, RemindReason r) { posted.add("remind " + r); }
        @Override public void remindCannotSend(ScheduledMessage m, RemindCause c) { posted.add("cannotSend " + c); }
        @Override public void sent(ScheduledMessage m) { posted.add("sent"); }
        @Override public void repliedDuringSend(ScheduledMessage m) { posted.add("repliedDuringSend"); }
        @Override public void selfPreempted(ScheduledMessage m) { posted.add("selfPreempted"); }
        @Override public void expired(ScheduledMessage m) { posted.add("expired"); }
        @Override public void mayHaveSent(ScheduledMessage m) { posted.add("mayHaveSent"); }
        @Override public void dismiss(long id) { dismissed.add(id); }
        @Override public void draftInTheWay(ScheduledMessage m, String draft) {
            posted.add("draft");
            drafts.add(draft);
        }
    }

    /** Records the draft mode of every start, like WebViewScheduledSender. */
    static final class ModeSender implements ScheduledSender {
        final List<DraftChoice.Mode> modes = new ArrayList<>();

        @Override public boolean start(Context context, ScheduledMessage m) {
            throw new AssertionError("the engine passes the check and the draft mode");
        }

        @Override public boolean start(Context context, ScheduledMessage m, PreSendCheck check, DraftChoice.Mode mode) {
            modes.add(mode);
            return true;
        }

        @Override public boolean checksThreadBeforeSend() { return true; }
    }

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        DraftStash.clearAll();
        w = new EngineFakes.World();
        notifier = new DraftNotifier();
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
        sender = new ModeSender();
        SendLaterEngine.setSender(sender);
    }

    @After public void tearDown() {
        SendLaterEngine.resetForTest();
        DraftStash.clearAll();
    }

    /** A due message dispatched by the engine: IN_FLIGHT, started in ASK mode. */
    private void inFlight() {
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
    }

    // ------------------------------------------------------------------ the decision

    @Test public void everyScheduledSendStartsInAskMode() {
        inFlight();
        assertEquals(Collections.singletonList(DraftChoice.Mode.ASK), sender.modes);
    }

    @Test public void aDraftPresentOutcomePostsTheChoiceNotificationNotAReminder() {
        inFlight();
        DraftStash.put(ID, DRAFT);
        // The send path's detail for draft_present stays could_not_send (OD34 contract).
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, RemindCause.DETAIL_COULD_NOT_SEND);
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertEquals(Collections.singletonList("draft"), notifier.posted);
        assertEquals(Collections.singletonList(DRAFT), notifier.drafts);
        assertTrue("never sends itself later", marks.isReminded(ID));
        assertNull("the draft left memory with the notification", DraftStash.take(ID));
    }

    @Test public void withoutADraftTheGenericReminderStays() {
        inFlight();
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, RemindCause.DETAIL_COULD_NOT_SEND);
        assertEquals(Collections.singletonList("cannotSend " + RemindCause.COULD_NOT_SEND), notifier.posted);
        assertTrue(notifier.drafts.isEmpty());
    }

    @Test public void aDraftStashedForAnotherOutcomeIsDropped() {
        inFlight();
        DraftStash.put(ID, DRAFT);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.SENT_VERIFIED, null);
        assertTrue(notifier.drafts.isEmpty());
        assertNull(DraftStash.take(ID));
    }

    @Test public void aPreSendHoldWinsOverAStashedDraftAndDropsIt() {
        inFlight();
        DraftStash.put(ID, DRAFT);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, "presend_replied");
        assertEquals(Status.HELD, w.store.status(ID));
        assertTrue(notifier.drafts.isEmpty());
        assertNull(DraftStash.take(ID));
    }

    // ------------------------------------------------------------------ the actions

    /** The row after the choice notification: SCHEDULED, reminded. */
    private void choiceShown() {
        inFlight();
        DraftStash.put(ID, DRAFT);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, RemindCause.DETAIL_COULD_NOT_SEND);
        sender.modes.clear();
        notifier.posted.clear();
    }

    @Test public void replaceAndSendStartsOneUserTapSendInReplaceMode() {
        choiceShown();
        assertEquals(TapResult.DONE, SendLaterEngine.onNotificationTap(null, DraftNotice.ACTION_REPLACE, ID));
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertEquals(Collections.singletonList(DraftChoice.Mode.REPLACE), sender.modes);
    }

    @Test public void sendBothStartsOneUserTapSendInBothMode() {
        choiceShown();
        assertEquals(TapResult.DONE, SendLaterEngine.onNotificationTap(null, DraftNotice.ACTION_BOTH, ID));
        assertEquals(Status.IN_FLIGHT, w.store.status(ID));
        assertEquals(Collections.singletonList(DraftChoice.Mode.BOTH), sender.modes);
    }

    @Test public void keepDraftSendsNothingAndKeepsTheMessageWaiting() {
        choiceShown();
        assertEquals(TapResult.DONE, SendLaterEngine.onNotificationTap(null, DraftNotice.ACTION_KEEP, ID));
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(sender.modes.isEmpty());
        assertTrue(notifier.dismissed.contains(ID));
        assertTrue("still never sends itself", marks.isReminded(ID));
    }

    @Test public void sendNowAndSendAnywayStillAsk() {
        choiceShown();
        SendLaterEngine.onNotificationTap(null, SendLaterIntents.ACTION_SEND_NOW, ID);
        assertEquals(Collections.singletonList(DraftChoice.Mode.ASK), sender.modes);
    }

    @Test public void aSendNowThatHitsADraftGetsTheSameChoice() {
        choiceShown();
        SendLaterEngine.onNotificationTap(null, SendLaterIntents.ACTION_SEND_ANYWAY, ID);
        DraftStash.put(ID, DRAFT);
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW, RemindCause.DETAIL_COULD_NOT_SEND);
        assertEquals(Collections.singletonList("draft"), notifier.posted);
    }

    @Test public void aStaleDraftTapDoesNothing() {
        w.store.put(message(ID).status(Status.SENT_VERIFIED).build());
        assertNotEquals(TapResult.DONE, SendLaterEngine.onNotificationTap(null, DraftNotice.ACTION_REPLACE, ID));
        assertNotEquals(TapResult.DONE, SendLaterEngine.onNotificationTap(null, DraftNotice.ACTION_KEEP, ID));
        assertTrue(sender.modes.isEmpty());
    }

    // ------------------------------------------------------------------ the notification

    @Test public void theNoticeShowsTheDraftAndOurText() {
        ScheduledMessage m = message(ID).build();
        assertEquals("Draft in your conversation with " + NAME, DraftNotice.title(m));
        String body = DraftNotice.body(DRAFT, m);
        assertTrue(body.contains(DRAFT));
        assertTrue(body.contains("Your message: " + TEXT));
    }

    @Test public void aNamelessTitleShowsTheFormattedNumber() {
        ScheduledMessage m = message(ID).recipientDisplayName(null).recipientE164("+15555550100").build();
        assertEquals("Draft in your conversation with (555) 555-0100", DraftNotice.title(m));
    }

    @Test public void thePublicVersionCarriesNoNameNumberOrText() {
        String pub = DraftNotice.PUBLIC_TITLE;
        assertEquals("Draft in a conversation", pub);
        for (String secret : new String[] {NAME, "555", TEXT, DRAFT, "Your message"}) {
            assertFalse(secret, pub.contains(secret));
            assertFalse(secret, DraftNotice.NOT_RESTORED_PUBLIC_TITLE.contains(secret));
        }
    }

    @Test public void aNoticeThatCouldNotPutTheDraftBackKeepsIt() {
        ScheduledMessage m = message(ID).build();
        assertTrue(DraftNotice.notRestoredBody(DRAFT).contains(DRAFT));
        assertTrue(DraftNotice.notRestoredTitle(m).contains(NAME));
    }

    @Test public void draftActionsGoThroughTheUnlockTrampolineExceptKeep() {
        assertTrue(SendLaterUnlockActivity.isUnlockAction(DraftNotice.ACTION_REPLACE));
        assertTrue(SendLaterUnlockActivity.isUnlockAction(DraftNotice.ACTION_BOTH));
        assertFalse(SendLaterUnlockActivity.isUnlockAction(DraftNotice.ACTION_KEEP));
        assertTrue(DraftNotice.isDraftAction(DraftNotice.ACTION_KEEP));
        assertFalse(DraftNotice.isDraftAction(SendLaterIntents.ACTION_SEND_NOW));
    }

    @Test public void requestCodesStayDistinctWithTheDraftActions() {
        String[] actions = {SendLaterIntents.ACTION_SEND_NOW, SendLaterIntents.ACTION_SEND_ANYWAY,
                SendLaterIntents.ACTION_SNOOZE, SendLaterIntents.ACTION_CANCEL,
                DraftNotice.ACTION_REPLACE, DraftNotice.ACTION_BOTH, DraftNotice.ACTION_KEEP};
        Set<Integer> seen = new HashSet<>();
        for (long id = 1; id <= 50; id++) {
            for (String a : actions) assertTrue(id + " " + a, seen.add(SendLaterNotifications.requestCode(a, id)));
        }
    }
}
