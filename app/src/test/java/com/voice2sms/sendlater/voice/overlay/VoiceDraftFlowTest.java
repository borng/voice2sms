package com.voice2sms.sendlater.voice.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * Snapshot -> ACTION_SCHEDULED -> clear -> Undo, with a fake node accessor and fake records
 * (R5, R6, R7 per OD7, D13, T1, I1, I4).
 *
 * <p>Pinned API: {@code new VoiceDraftFlow(VoiceNodeAccessor nodes, VoiceDraftFlow.Records records)};
 * {@code Records { boolean isPersisted(long id); boolean cancel(long id); }};
 * {@code ScheduledOutcome onScheduled(VoiceDraftSnapshot snapshot, long messageId)} with
 * {@code ScheduledOutcome {CLEARED, CLEAR_FAILED, CONVERSATION_CHANGED, NOT_PERSISTED}};
 * {@code UndoOutcome undo(VoiceDraftSnapshot snapshot, long messageId)} with
 * {@code UndoOutcome {RESTORED, NOT_RESTORED}}.
 * CLEARED -> "Scheduled for … · Undo"; CLEAR_FAILED -> "Scheduled, but the draft couldn't be cleared";
 * CONVERSATION_CHANGED -> "Saved to Upcoming — the conversation changed, draft left as is".
 */
public class VoiceDraftFlowTest {

    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");
    private static final String NAME = "Alex Example";
    private static final String DRAFT = "Dinner at 7? 🍝  ";
    private static final long ID = 42L;
    private static final long NOW = 1_793_000_000_000L;

    private List<String> log;
    private FakeVoiceNodes nodes;
    private FakeRecords records;
    private VoiceDraftFlow flow;
    private VoiceDraftSnapshot snap;

    @Before public void setUp() {
        log = new ArrayList<>();
        nodes = new FakeVoiceNodes(log, NAME, DRAFT);
        records = new FakeRecords(log);
        records.persisted.add(ID);
        snap = VoiceDraftSnapshot.capture(nodes, NOW, ZONE,
                Collections.<String>emptyList(), Collections.singletonList("+14355550101"));
        assertNotNull(snap);
        log.clear();
        flow = new VoiceDraftFlow(nodes, records);
    }

    // ---------------------------------------------------------------- clear (R5, I1, D13)

    @Test public void clearsDraftWithEmptyTextAfterRecordIsConfirmed() {
        assertEquals(VoiceDraftFlow.ScheduledOutcome.CLEARED, flow.onScheduled(snap, ID));
        assertEquals("D13: ACTION_SET_TEXT('')", Collections.singletonList(""), nodes.setDraftCalls);
        assertEquals("", nodes.draft);
        int persisted = log.indexOf("isPersisted");
        int cleared = log.indexOf("setDraft");
        assertTrue("I1: the record is verified before the draft is touched", persisted >= 0 && persisted < cleared);
        assertTrue("record kept", records.canceled.isEmpty());
    }

    @Test public void recordNotPersistedLeavesDraftAlone() {
        records.persisted.clear();
        assertEquals(VoiceDraftFlow.ScheduledOutcome.NOT_PERSISTED, flow.onScheduled(snap, ID));
        assertTrue("I1: never clear before the record is verified", nodes.setDraftCalls.isEmpty());
        assertEquals(DRAFT, nodes.draft);
    }

    @Test public void clearFailureKeepsRecordAndReportsIt() {
        nodes.setTextResult = false;
        assertEquals("T1/R5: a false ACTION_SET_TEXT return is reported",
                VoiceDraftFlow.ScheduledOutcome.CLEAR_FAILED, flow.onScheduled(snap, ID));
        assertTrue("R5: the schedule is kept", records.canceled.isEmpty());
        assertTrue(records.persisted.contains(ID));
        assertEquals(DRAFT, nodes.draft);
    }

    @Test public void missingDraftFieldAtScheduleTimeIsNotCleared() {
        nodes.draft = null;
        VoiceDraftFlow.ScheduledOutcome o = flow.onScheduled(snap, ID);
        assertNotEquals(VoiceDraftFlow.ScheduledOutcome.CLEARED, o);
        assertTrue(nodes.setDraftCalls.isEmpty());
        assertTrue("the record is kept", records.canceled.isEmpty());
    }

    // ---------------------------------------------------------------- conversation changed (R7, OD7)

    @Test public void titleChangedBetweenSnapshotAndScheduledLeavesDraft() {
        nodes.title = "Sam Example";
        assertEquals(VoiceDraftFlow.ScheduledOutcome.CONVERSATION_CHANGED, flow.onScheduled(snap, ID));
        assertTrue("draft left alone", nodes.setDraftCalls.isEmpty());
        assertEquals(DRAFT, nodes.draft);
        assertTrue("OD7: the record is kept (Saved to Upcoming)", records.canceled.isEmpty());
    }

    @Test public void draftEditedBetweenSnapshotAndScheduledLeavesDraft() {
        nodes.draft = DRAFT + " and more";
        assertEquals(VoiceDraftFlow.ScheduledOutcome.CONVERSATION_CHANGED, flow.onScheduled(snap, ID));
        assertTrue(nodes.setDraftCalls.isEmpty());
        assertEquals(DRAFT + " and more", nodes.draft);
        assertTrue(records.canceled.isEmpty());
    }

    @Test public void draftComparedAsExactUnicode() {
        nodes.draft = DRAFT.trim();
        assertEquals("trailing whitespace counts as a change",
                VoiceDraftFlow.ScheduledOutcome.CONVERSATION_CHANGED, flow.onScheduled(snap, ID));
        assertTrue(nodes.setDraftCalls.isEmpty());
    }

    @Test public void titleNodeMissingCountsAsChanged() {
        nodes.title = null;
        assertEquals(VoiceDraftFlow.ScheduledOutcome.CONVERSATION_CHANGED, flow.onScheduled(snap, ID));
        assertTrue(nodes.setDraftCalls.isEmpty());
    }

    @Test public void noSnapshotNeverTouchesDraft() {
        VoiceDraftFlow.ScheduledOutcome o = flow.onScheduled(null, ID);
        assertNotEquals(VoiceDraftFlow.ScheduledOutcome.CLEARED, o);
        assertTrue(nodes.setDraftCalls.isEmpty());
        assertTrue(records.canceled.isEmpty());
    }

    // ---------------------------------------------------------------- undo (R6, I4)

    @Test public void undoRestoresExactTextWhenFieldEmptyAndTitleSame() {
        assertEquals(VoiceDraftFlow.ScheduledOutcome.CLEARED, flow.onScheduled(snap, ID));
        nodes.setDraftCalls.clear();
        assertEquals(VoiceDraftFlow.UndoOutcome.RESTORED, flow.undo(snap, ID));
        assertEquals("exact original text restored", Collections.singletonList(DRAFT), nodes.setDraftCalls);
        assertEquals(DRAFT, nodes.draft);
        assertEquals("R6: the record is cancelled", Collections.singletonList(ID), records.canceled);
    }

    @Test public void undoNeverOverwritesTextTypedAfterScheduling() {
        assertEquals(VoiceDraftFlow.ScheduledOutcome.CLEARED, flow.onScheduled(snap, ID));
        nodes.draft = "something new";
        nodes.setDraftCalls.clear();
        assertEquals(VoiceDraftFlow.UndoOutcome.NOT_RESTORED, flow.undo(snap, ID));
        assertTrue("I4: typed text is not overwritten", nodes.setDraftCalls.isEmpty());
        assertEquals("something new", nodes.draft);
        assertEquals("R6: the record is still cancelled", Collections.singletonList(ID), records.canceled);
    }

    @Test public void undoInAnotherThreadDoesNotRestore() {
        assertEquals(VoiceDraftFlow.ScheduledOutcome.CLEARED, flow.onScheduled(snap, ID));
        nodes.title = "Sam Example";
        nodes.setDraftCalls.clear();
        assertEquals(VoiceDraftFlow.UndoOutcome.NOT_RESTORED, flow.undo(snap, ID));
        assertTrue("the other thread's composer is not written", nodes.setDraftCalls.isEmpty());
        assertEquals(Collections.singletonList(ID), records.canceled);
    }

    @Test public void undoWithFieldMissingDoesNotRestore() {
        assertEquals(VoiceDraftFlow.ScheduledOutcome.CLEARED, flow.onScheduled(snap, ID));
        nodes.draft = null;
        nodes.setDraftCalls.clear();
        assertEquals(VoiceDraftFlow.UndoOutcome.NOT_RESTORED, flow.undo(snap, ID));
        assertTrue(nodes.setDraftCalls.isEmpty());
        assertEquals(Collections.singletonList(ID), records.canceled);
    }

    @Test public void undoWithTitleMissingDoesNotRestore() {
        assertEquals(VoiceDraftFlow.ScheduledOutcome.CLEARED, flow.onScheduled(snap, ID));
        nodes.title = null;
        nodes.setDraftCalls.clear();
        assertEquals(VoiceDraftFlow.UndoOutcome.NOT_RESTORED, flow.undo(snap, ID));
        assertFalse(nodes.setDraftCalls.contains(DRAFT));
    }
}
