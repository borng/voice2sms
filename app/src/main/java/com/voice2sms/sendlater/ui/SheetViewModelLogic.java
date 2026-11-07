package com.voice2sms.sendlater.ui;

import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.LatenessBudget;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.PresetSlots;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.voice.logic.PhoneNumbers;
import com.voice2sms.sendlater.voice.logic.RecipientResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * Pure model behind {@link SendLaterSheetActivity} (R4, R12-R16, I3). No Android types, so it is
 * JVM-tested ({@code SheetViewModelLogicTest}).
 *
 * <p>Recipient: an E.164 given by the capture wins and is never re-asked (R15). Otherwise the
 * candidate list is normalized (entries that are not numbers are dropped, so a display name is
 * never a recipient, I3): one candidate is chosen silently, several need a choice, none need a
 * typed number (R4c/d). Schedule stays disabled until a number is resolved.
 *
 * <p>Time: a preset row or a picked date/time strictly in the future (R13, R14). The primary
 * button reads "Schedule for &lt;time&gt;" in capture mode and "Update" in edit mode (R16).
 */
public final class SheetViewModelLogic {

    public enum RecipientState { RESOLVED, NEEDS_CHOICE, NEEDS_ENTRY }

    private final boolean editMode;
    private final long createdAtNowMs;
    private final TimeZone zone;
    private final List<PresetSlots.Preset> presets;
    private final List<String> candidates;
    /** How the recipient was (or must be) found; fixed at construction. */
    private final RecipientState initialRecipientState;

    private RecipientState recipientState;
    private String e164;
    private long selectedTimeMs;
    private int selectedPresetIndex;
    private boolean holdIfReplied;
    private LatenessPolicy latenessPolicy = LatenessPolicy.DEFAULT;
    /** Edit mode: the stored createdAt, the base of the lateness budget (never changes on edit). */
    private long storedCreatedAtMs = -1L;

    private SheetViewModelLogic(boolean editMode, long nowMs, TimeZone zone, int[] slotMinutes,
                                RecipientState state, String e164, List<String> candidates) {
        this.editMode = editMode;
        this.createdAtNowMs = nowMs;
        this.zone = zone != null ? zone : TimeZone.getDefault();
        this.presets = Collections.unmodifiableList(
                new ArrayList<>(PresetSlots.compute(nowMs, this.zone, slotMinutes)));
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.initialRecipientState = state;
        this.recipientState = state;
        this.e164 = state == RecipientState.RESOLVED ? e164 : null;
    }

    // ------------------------------------------------------------------ factories

    /**
     * Capture mode. {@code e164OrNull} wins when it normalizes to E.164; otherwise
     * {@code candidatesOrNull} is normalized and resolved per R4c/d.
     */
    public static SheetViewModelLogic forCapture(String e164OrNull, String[] candidatesOrNull,
                                                 boolean holdDefault, long nowMs, TimeZone zone,
                                                 int[] slotMinutes) {
        String given = normalize(e164OrNull);
        SheetViewModelLogic m;
        if (given != null) {
            m = new SheetViewModelLogic(false, nowMs, zone, slotMinutes, RecipientState.RESOLVED,
                    given, Collections.singletonList(given));
        } else {
            List<String> raw = candidatesOrNull == null
                    ? new ArrayList<String>() : Arrays.asList(candidatesOrNull);
            RecipientResolver.Resolution r = RecipientResolver.resolve(null, raw, null);
            RecipientState s;
            switch (r.getKind()) {
                case RESOLVED: s = RecipientState.RESOLVED; break;
                case NEEDS_CHOICE: s = RecipientState.NEEDS_CHOICE; break;
                default: s = RecipientState.NEEDS_ENTRY; break;
            }
            m = new SheetViewModelLogic(false, nowMs, zone, slotMinutes, s, r.getE164(), r.getCandidates());
        }
        m.holdIfReplied = holdDefault;
        m.selectPreset(0);
        return m;
    }

    /** Edit mode (R16): prefilled from the stored record; the recipient is fixed. */
    public static SheetViewModelLogic forEdit(ScheduledMessage existing, long nowMs, TimeZone zone,
                                              int[] slotMinutes) {
        if (existing == null) throw new IllegalArgumentException("existing");
        String number = normalize(existing.getRecipientE164());
        RecipientState s = number != null ? RecipientState.RESOLVED : RecipientState.NEEDS_ENTRY;
        List<String> c = number != null ? Collections.singletonList(number) : new ArrayList<String>();
        SheetViewModelLogic m = new SheetViewModelLogic(true, nowMs, zone, slotMinutes, s, number, c);
        m.holdIfReplied = existing.isHoldIfReplied();
        m.latenessPolicy = existing.getLatenessPolicy() != null
                ? existing.getLatenessPolicy() : LatenessPolicy.DEFAULT;
        m.storedCreatedAtMs = existing.getCreatedAt();
        m.selectedTimeMs = existing.getScheduledFor();
        m.selectedPresetIndex = m.indexOfPreset(existing.getScheduledFor());
        return m;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        return PhoneNumbers.toE164(raw, RecipientResolver.DEFAULT_REGION);
    }

    // ------------------------------------------------------------------ recipient (R4, I3)

    public boolean isEditMode() { return editMode; }

    public RecipientState getRecipientState() { return recipientState; }

    /** E.164 when RESOLVED, else null. */
    public String getE164() { return recipientState == RecipientState.RESOLVED ? e164 : null; }

    /** E.164 choices for "Which number?"; never null. */
    public List<String> getCandidates() { return candidates; }

    /** Whether the sheet shows the "Which number?" choices (several candidates). */
    public boolean showsCandidateChoice() {
        return !editMode && initialRecipientState == RecipientState.NEEDS_CHOICE;
    }

    /** Whether the sheet shows the number entry (no candidate at all). */
    public boolean showsNumberEntry() {
        return !editMode && initialRecipientState == RecipientState.NEEDS_ENTRY;
    }

    /** Only a listed candidate is accepted. */
    public boolean chooseCandidate(String choice) {
        if (!showsCandidateChoice() || choice == null || !candidates.contains(choice)) return false;
        e164 = choice;
        recipientState = RecipientState.RESOLVED;
        return true;
    }

    /**
     * Manual entry (zero candidates). A value that does not normalize to E.164 is rejected and
     * leaves the recipient unresolved, so editing the field to something invalid disables Schedule.
     */
    public boolean enterNumber(String raw) {
        if (!showsNumberEntry()) return false;
        String n = normalize(raw);
        if (n == null) {
            e164 = null;
            recipientState = RecipientState.NEEDS_ENTRY;
            return false;
        }
        e164 = n;
        recipientState = RecipientState.RESOLVED;
        return true;
    }

    // ------------------------------------------------------------------ time (R13, R14)

    /** Exactly {@code PresetSlots.compute(now, zone, slots)}. */
    public List<PresetSlots.Preset> getPresets() { return presets; }

    public void selectPreset(int index) {
        if (index < 0 || index >= presets.size()) return;
        selectedPresetIndex = index;
        selectedTimeMs = presets.get(index).getTimeMs();
    }

    /** Index of the selected preset, or -1 when the time came from the picker or the record. */
    public int getSelectedPresetIndex() { return selectedPresetIndex; }

    /** False, and nothing changes, unless {@code timeMs > nowMs}. */
    public boolean pickDateTime(long timeMs, long nowMs) {
        if (timeMs <= nowMs) return false;
        selectedTimeMs = timeMs;
        selectedPresetIndex = indexOfPreset(timeMs);
        return true;
    }

    public long getSelectedTimeMs() { return selectedTimeMs; }

    /** Whether the selected time is still ahead of {@code nowMs} (the sheet may sit open). */
    public boolean isSelectedTimeInFuture(long nowMs) { return selectedTimeMs > nowMs; }

    private int indexOfPreset(long t) {
        for (int i = 0; i < presets.size(); i++) if (presets.get(i).getTimeMs() == t) return i;
        return -1;
    }

    // ------------------------------------------------------------------ buttons

    public boolean isScheduleEnabled() {
        return recipientState == RecipientState.RESOLVED && e164 != null && selectedTimeMs > 0;
    }

    /** "Schedule for &lt;time&gt;" (capture) / "Update" (edit). English; the activity uses resources. */
    public String getPrimaryButtonLabel() {
        return editMode ? "Update" : "Schedule for " + getSelectedTimeLabel();
    }

    /** "Cancel" (capture) / "Cancel message" (edit). */
    public String getSecondaryButtonLabel() { return editMode ? "Cancel message" : "Cancel"; }

    /** Human label of the selected time, e.g. "Sat 9:00 AM" (EXTRA_LABEL, the button). */
    public String getSelectedTimeLabel() { return timeLabel(selectedTimeMs, createdAtNowMs, zone); }

    /**
     * "Wed 1:30 PM" within the coming week, "Wed, Jun 24, 1:30 PM" further out. The day name keeps
     * "today" and "tomorrow" unambiguous without locale-specific words. Same rule as the chip.
     */
    static String timeLabel(long t, long nowMs, TimeZone zone) {
        return ChipLabel.timeLabel(t, nowMs, zone);
    }

    // ------------------------------------------------------------------ hold + If late (R12, D10)

    public boolean isHoldIfReplied() { return holdIfReplied; }
    public void setHoldIfReplied(boolean v) { holdIfReplied = v; }

    /** Every policy, in declaration order (DEFAULT first). */
    public List<LatenessPolicy> getIfLateOptions() {
        return Collections.unmodifiableList(Arrays.asList(LatenessPolicy.values()));
    }

    public LatenessPolicy getLatenessPolicy() { return latenessPolicy; }

    public void setLatenessPolicy(LatenessPolicy p) { if (p != null) latenessPolicy = p; }

    /**
     * OD31 PQ5: the "Automatic" budget for this message at the selected time. Capture mode measures
     * the lead time from {@code nowMs} (the record's createdAt is the Schedule tap); edit mode from
     * the stored createdAt, which a reschedule keeps (same rule as {@link LatenessBudget#budgetMs}).
     */
    public long automaticBudgetMs(long nowMs) {
        long base = editMode && storedCreatedAtMs > 0 ? storedCreatedAtMs : nowMs;
        return LatenessBudget.budgetMs(selectedTimeMs - base, LatenessPolicy.DEFAULT);
    }

    /** "10 min", "30 min", "2 h" (whole hours as hours, else minutes; never below 1 min). */
    public static String budgetPhrase(long budgetMs) {
        long min = Math.max(1L, (budgetMs + LatenessBudget.MINUTE_MS / 2) / LatenessBudget.MINUTE_MS);
        if (min >= 60 && min % 60 == 0) return (min / 60) + " h";
        return min + " min";
    }

    // ------------------------------------------------------------------ edit baseline (OD31 P2)

    /**
     * Whole-branch M2 / OD31 P2: every time edit (SCHEDULED or HELD) takes a fresh reply baseline,
     * DEVICE_ONLY at {@code nowMs}, exactly like Snooze. The repository keeps the original
     * watermark as the self-preemption baseline (OD22(1)).
     */
    public static Watermark editBaseline(long nowMs) {
        return new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, nowMs, null);
    }
}
