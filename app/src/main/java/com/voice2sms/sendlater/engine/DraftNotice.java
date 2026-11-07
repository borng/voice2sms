package com.voice2sms.sendlater.engine;

import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.ScheduledMessage;

/**
 * D32: the "draft in the way" notification's actions and words. Pure JVM (the posting lives in
 * {@link SendLaterNotifications#draftInTheWay}).
 *
 * <p>A scheduled send (or the user's Send now / Send anyway) found the conversation's box holding
 * the user's own draft, so nothing was typed. The notification shows the draft and the scheduled
 * text and offers:
 * <ul>
 *   <li>{@link #ACTION_REPLACE} "Replace and send": a user-tap send (through
 *       {@link SendLaterUnlockActivity}, so it needs an unlock, OD31 L1) whose compose clears the
 *       box and types ours;</li>
 *   <li>{@link #ACTION_KEEP} "Keep draft": the notification goes; the message stays in Upcoming
 *       waiting for the user (nothing typed, nothing sent);</li>
 *   <li>{@link #ACTION_BOTH} "Send both": a user-tap send that keeps the draft in memory, sends
 *       ours, then puts the draft back in the box.</li>
 * </ul>
 * Lock screen: {@code VISIBILITY_PRIVATE} with a public version that says only
 * {@link #PUBLIC_TITLE} (no name, number or text).
 */
public final class DraftNotice {

    private static final String P = "com.voice2sms.sendlater.action.";
    /** "Replace and send" (activity trampoline, unlock required). */
    public static final String ACTION_REPLACE = P + "DRAFT_REPLACE";
    /** "Send both" (activity trampoline, unlock required). */
    public static final String ACTION_BOTH = P + "DRAFT_BOTH";
    /** "Keep draft" (broadcast to {@link SendLaterActionReceiver}). */
    public static final String ACTION_KEEP = P + "DRAFT_KEEP";

    /** The only words the lock screen shows. */
    public static final String PUBLIC_TITLE = "Draft in a conversation";
    /** Title of the notice posted when Send both could not put the draft back. */
    static final String NOT_RESTORED_PUBLIC_TITLE = "A draft was not put back";

    /** Longest draft / message shown in the notification body. */
    static final int MAX_SHOWN = 400;

    private DraftNotice() {}

    /** Whether {@code action} is one of the three draft actions. */
    public static boolean isDraftAction(String action) {
        return ACTION_REPLACE.equals(action) || ACTION_BOTH.equals(action) || ACTION_KEEP.equals(action);
    }

    /** "Draft in your conversation with <name or formatted number>". */
    public static String title(ScheduledMessage m) {
        return "Draft in your conversation with " + who(m);
    }

    /** The private body: the current draft, then "Your message: <scheduled text>". */
    public static String body(String draft, ScheduledMessage m) {
        return "Draft: " + clip(draft) + "\nYour message: " + clip(m != null ? m.getText() : null);
    }

    /** One-line summary for the collapsed notification. */
    public static String summary() {
        return "Your scheduled message was not sent";
    }

    /** Title of the notice that holds a draft Send both could not put back. */
    public static String notRestoredTitle(ScheduledMessage m) {
        return "Your draft to " + who(m) + " was not put back";
    }

    /** Body of that notice: the draft, so it is not lost. */
    public static String notRestoredBody(String draft) {
        return "Your draft: " + clip(draft);
    }

    /** Name, else the formatted number (D39), else "your contact". */
    static String who(ScheduledMessage m) {
        if (m == null) return "your contact";
        return ChipLabel.recipient(m.getRecipientDisplayName(), m.getRecipientE164(), "your contact");
    }

    static String clip(String t) {
        if (t == null) return "";
        return t.length() <= MAX_SHOWN ? t : t.substring(0, MAX_SHOWN - 1) + "…";
    }
}
