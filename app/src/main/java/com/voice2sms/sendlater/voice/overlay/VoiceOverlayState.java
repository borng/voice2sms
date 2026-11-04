package com.voice2sms.sendlater.voice.overlay;

import com.voice2sms.sendlater.voice.logic.PhoneNumbers;
import com.voice2sms.sendlater.voice.logic.RecipientResolver;
import com.voice2sms.sendlater.voice.logic.VoiceScreenSignature;

import java.util.Locale;

/**
 * What the native Voice overlay shows for one screen reading (R1, R17, R19, D12/OD13):
 * the edge tab only on a HIGH-confidence 1:1 thread with a non-empty draft while Send Later is
 * enabled; the chip on a HIGH 1:1 thread with pending or held messages, whatever the draft.
 */
public final class VoiceOverlayState {

    private final boolean tabVisible;
    private final boolean chipVisible;

    private VoiceOverlayState(boolean tabVisible, boolean chipVisible) {
        this.tabVisible = tabVisible;
        this.chipVisible = chipVisible;
    }

    public static VoiceOverlayState compute(VoiceScreenSignature.Level signature, boolean draftNonEmpty,
                                            boolean isGroup, boolean enabled, int pendingCount) {
        boolean base = signature == VoiceScreenSignature.Level.HIGH && !isGroup && enabled;
        return new VoiceOverlayState(base && draftNonEmpty, base && pendingCount > 0);
    }

    public boolean isTabVisible() { return tabVisible; }

    public boolean isChipVisible() { return chipVisible; }

    public boolean isNothingVisible() { return !tabVisible && !chipVisible; }

    /**
     * OD13 group heuristic on {@code toolbar_title}; true means "treat as a group" (no tab, no chip).
     * A title that parses as a phone number is 1:1. Otherwise a group when it contains ',', '&' or
     * the word "and", or more than two capitalised words. Null or blank: unsure, so group (D12).
     */
    public static boolean isGroupTitle(String title) {
        if (title == null) return true;
        String t = title.trim();
        if (t.isEmpty()) return true;
        if (PhoneNumbers.isPhoneNumber(t, RecipientResolver.DEFAULT_REGION)) return false;
        if (t.indexOf(',') >= 0 || t.indexOf('&') >= 0) return true;
        String padded = " " + t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + " ";
        if (padded.contains(" and ")) return true;
        int capitalised = 0;
        for (String w : t.split("\\s+")) {
            if (!w.isEmpty() && Character.isUpperCase(w.codePointAt(0))) capitalised++;
        }
        return capitalised > 2;
    }

    @Override public String toString() {
        return "VoiceOverlayState{tab=" + tabVisible + ", chip=" + chipVisible + "}";
    }
}
