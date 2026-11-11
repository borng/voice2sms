package com.voice2sms.sendlater.web;

import com.voice2sms.sendlater.voice.logic.PhoneNumbers;

/**
 * LIVE-F14 / R10a (D46, OD36): the pairing rule behind the WebView edge tab on GV's new-message
 * page. voice2sms opened by an SMS intent composes there (a recipient chip, no thread URL), so the
 * R10 thread gate never fires. The page reports its single chip's E.164; the live activity
 * confirms it against its own intent recipient, never page text alone. Pure JVM.
 */
public final class NewComposeRecipient {

    private NewComposeRecipient() {}

    /**
     * @param mainFrameUrl    the activity's main-frame URL: must be a trusted GV page
     *                        ({@link OriginGuard#isTrustedGvUrl}) that is not a thread (a 1:1 thread
     *                        is R10's, so no second tab; a group thread is never scheduled)
     * @param intentRecipient the live activity's intent recipient as handed over (raw, e.g. from an
     *                        sms: URI), or null/empty when the activity was not opened for one
     * @param chipCount       recipient chips on the page: exactly one is accepted
     * @param chipE164        the single chip's number as the page derived it: must pass the one
     *                        E.164 rule (D38: "+", no "+0", 7-15 digits, no separators)
     * @return the confirmed E.164, or null
     */
    public static String confirm(String mainFrameUrl, String intentRecipient, int chipCount,
                                 String chipE164) {
        if (!OriginGuard.isTrustedGvUrl(mainFrameUrl)) return null;
        if (isThreadPage(mainFrameUrl)) return null;
        if (chipCount != 1) return null;
        if (!PhoneNumbers.isE164(chipE164)) return null;
        if (intentRecipient == null || intentRecipient.trim().isEmpty()) return chipE164;
        // An intent recipient that does not normalize (several numbers, a name) is not "none".
        String wanted = PhoneNumbers.toE164(intentRecipient, "US");
        return chipE164.equals(wanted) ? chipE164 : null;
    }

    /** Any GV thread page (1:1 or group: an itemId starting "t."), so not a new-message page. */
    static boolean isThreadPage(String url) {
        if (ThreadUrl.oneToOneE164(url) != null) return true;
        String id = ThreadUrl.itemId(url);
        return id != null && id.startsWith("t.");
    }
}
