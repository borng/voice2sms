package com.voice2sms.sendlater.voice.logic;

/**
 * R7: before Schedule, compare the live Voice screen with the tap-time snapshot. A changed title or
 * draft means "The conversation changed. Review before scheduling." and a re-snapshot on confirm.
 */
public final class ConversationChanged {

    /** The two values R7 compares. Draft is exact Unicode; null draft is treated as "". */
    public static final class Screen {
        private final String title;
        private final String draft;

        public Screen(String title, String draft) {
            this.title = title;
            this.draft = draft == null ? "" : draft;
        }

        public String getTitle() { return title; }
        public String getDraft() { return draft; }

        /** Never includes the draft or the title. */
        @Override public String toString() { return "Screen{}"; }
    }

    public enum Result {
        UNCHANGED, TITLE_CHANGED, DRAFT_CHANGED, BOTH_CHANGED;

        public boolean isChanged() { return this != UNCHANGED; }
    }

    private ConversationChanged() {}

    /** A null live screen or null title on either side counts as a title change (unknown = changed). */
    public static Result check(Screen snapshot, Screen live) {
        if (snapshot == null || live == null) return Result.BOTH_CHANGED;
        boolean title = snapshot.getTitle() == null || live.getTitle() == null
                || !snapshot.getTitle().equals(live.getTitle());
        boolean draft = !snapshot.getDraft().equals(live.getDraft());
        if (title && draft) return Result.BOTH_CHANGED;
        if (title) return Result.TITLE_CHANGED;
        if (draft) return Result.DRAFT_CHANGED;
        return Result.UNCHANGED;
    }

    public static Result check(String snapshotTitle, String snapshotDraft, String liveTitle, String liveDraft) {
        return check(new Screen(snapshotTitle, snapshotDraft), new Screen(liveTitle, liveDraft));
    }
}
