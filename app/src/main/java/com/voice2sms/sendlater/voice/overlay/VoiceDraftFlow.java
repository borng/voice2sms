package com.voice2sms.sendlater.voice.overlay;

import com.voice2sms.sendlater.voice.logic.ConversationChanged;
import com.voice2sms.sendlater.voice.logic.UndoGuard;

/**
 * After the sheet scheduled a native Voice capture: verify the record, re-check the live screen
 * against the snapshot, clear the draft, and Undo (R5, R6, R7/OD7, D13, T1, I1, I4).
 *
 * <p>Pure: the screen is a {@link VoiceNodeAccessor} and the store is {@link Records}. Callers run
 * it off the main thread (the records are a database).
 */
public final class VoiceDraftFlow {

    /** The repository checks this flow needs. */
    public interface Records {
        /** True when the record is stored and still SCHEDULED or HELD. */
        boolean isPersisted(long messageId);

        /** Cancels the record; true when it is now cancelled. */
        boolean cancel(long messageId);
    }

    public enum ScheduledOutcome {
        /** Draft cleared: "Scheduled for … · Undo". */
        CLEARED,
        /** Record kept, ACTION_SET_TEXT failed: "Scheduled, but the draft couldn't be cleared". */
        CLEAR_FAILED,
        /** Record kept, draft untouched: "Saved to Upcoming — the conversation changed, draft left as is". */
        CONVERSATION_CHANGED,
        /** The record could not be verified: nothing is touched. */
        NOT_PERSISTED
    }

    public enum UndoOutcome { RESTORED, NOT_RESTORED }

    private final VoiceNodeAccessor nodes;
    private final Records records;

    public VoiceDraftFlow(VoiceNodeAccessor nodes, Records records) {
        this.nodes = nodes;
        this.records = records;
    }

    /**
     * ACTION_SCHEDULED for this capture. The draft is cleared only when the record is verified
     * (I1) and the live title and draft still equal the snapshot (R7/OD7, exact Unicode).
     */
    public ScheduledOutcome onScheduled(VoiceDraftSnapshot snapshot, long messageId) {
        if (messageId <= 0 || !records.isPersisted(messageId)) return ScheduledOutcome.NOT_PERSISTED;
        if (snapshot == null) return ScheduledOutcome.CONVERSATION_CHANGED;
        String liveTitle = nodes.readTitle();
        String liveDraft = nodes.readDraft();
        if (liveTitle == null || liveDraft == null) return ScheduledOutcome.CONVERSATION_CHANGED;
        if (ConversationChanged.check(snapshot.getDisplayName(), snapshot.getDraftText(), liveTitle, liveDraft)
                .isChanged()) {
            return ScheduledOutcome.CONVERSATION_CHANGED;
        }
        return nodes.setDraft("") ? ScheduledOutcome.CLEARED : ScheduledOutcome.CLEAR_FAILED;
    }

    /**
     * Undo: cancels the record, then restores the exact draft only when the field is present and
     * empty and the title is unchanged (R6, I4). Nothing is restored when the cancel failed (the
     * message may be on its way), so the draft can never be sent twice.
     */
    public UndoOutcome undo(VoiceDraftSnapshot snapshot, long messageId) {
        if (!records.cancel(messageId)) return UndoOutcome.NOT_RESTORED;
        if (snapshot == null) return UndoOutcome.NOT_RESTORED;
        String liveTitle = nodes.readTitle();
        String liveDraft = nodes.readDraft();
        if (liveTitle == null || liveDraft == null) return UndoOutcome.NOT_RESTORED;
        if (!UndoGuard.canRestore(snapshot.getDisplayName(), liveTitle, liveDraft)) {
            return UndoOutcome.NOT_RESTORED;
        }
        return nodes.setDraft(snapshot.getDraftText()) ? UndoOutcome.RESTORED : UndoOutcome.NOT_RESTORED;
    }

    /**
     * Whether the snapshot's draft is still in the composer of the same thread (exact text). Used
     * after an Undo that did not restore: the draft was never cleared (clear failed, or clearing is
     * off in Settings), so the message says it is still there rather than that it was not restored.
     */
    public boolean draftStillInComposer(VoiceDraftSnapshot snapshot) {
        if (snapshot == null) return false;
        String liveTitle = nodes.readTitle();
        String liveDraft = nodes.readDraft();
        return liveTitle != null && liveDraft != null
                && liveTitle.equals(snapshot.getDisplayName()) && liveDraft.equals(snapshot.getDraftText());
    }
}
