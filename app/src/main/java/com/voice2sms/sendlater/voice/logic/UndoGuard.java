package com.voice2sms.sendlater.voice.logic;

/**
 * R6 / I4: Undo may put the scheduled draft back into {@code send_message_text} only when the field
 * is still empty and the thread title is unchanged. Otherwise the caller cancels the record and says
 * the draft is in Upcoming; it never overwrites text typed after scheduling.
 */
public final class UndoGuard {

    private UndoGuard() {}

    /**
     * @param snapshotTitle {@code toolbar_title} captured when the tab was tapped
     * @param liveTitle     {@code toolbar_title} now; null (unknown / node missing) refuses
     * @param liveDraft     the live {@code send_message_text} text; null or "" means empty. Callers
     *                      pass the text with hint text already excluded, and do not call this at all
     *                      when the field node itself is missing (R2 is then NONE).
     */
    public static boolean canRestore(String snapshotTitle, String liveTitle, String liveDraft) {
        if (snapshotTitle == null || liveTitle == null) return false;
        if (!snapshotTitle.equals(liveTitle)) return false;
        return liveDraft == null || liveDraft.isEmpty();
    }
}
