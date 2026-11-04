package com.voice2sms.sendlater.listener;

import com.voice2sms.sendlater.voice.logic.PhoneNumbers;
import com.voice2sms.sendlater.voice.logic.RecipientResolver;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-JVM mapping from a posted notification to an inbound conversation event (R32, D14, D22).
 *
 * <p>Sources:
 * <ul>
 *   <li>Google Voice (primary, F5). Texts: channel {@code 1TXT}, tag
 *       {@code TEXT_MESSAGE_NOTIFICATION_TAG}, category {@code msg}, MessagingStyle with
 *       {@code android.conversationTitle}. Calls and voicemails (T4): their channel ids are not
 *       known, so they are classified only on explicit evidence (category {@code missed_call}, or a
 *       title or text naming a missed call or a voicemail). Anything else from Voice is
 *       {@link ParsedEvent.Kind#UNKNOWN}, which the recorder ignores.</li>
 *   <li>voice2sms {@code SmsReceiver} (secondary; only posts while voice2sms is the default SMS
 *       app): channel {@code incoming_sms}, title = sender address, text = body.</li>
 * </ul>
 * Every other package, and every other voice2sms channel (Send Later's own notifications), gives
 * null.
 */
public final class NotificationParser {

    public static final String VOICE_PACKAGE = "com.google.android.apps.googlevoice";
    public static final String V2S_PACKAGE = "com.voice2sms";
    public static final String VOICE_TEXT_CHANNEL = "1TXT";
    public static final String VOICE_TEXT_TAG = "TEXT_MESSAGE_NOTIFICATION_TAG";
    public static final String V2S_SMS_CHANNEL = "incoming_sms";

    static final String CATEGORY_MESSAGE = "msg";
    static final String CATEGORY_MISSED_CALL = "missed_call";

    private static final Pattern VOICEMAIL = Pattern.compile("(?i)\\bvoice\\s?mail\\b");
    private static final Pattern MISSED_CALL = Pattern.compile("(?i)\\bmissed\\s+(video\\s+)?call\\b");
    /** "Missed call from X", "New voicemail from X", "Voicemail from X" -> X. */
    private static final Pattern FROM_PREFIX = Pattern.compile(
            "(?i)^\\s*(?:new\\s+)?(?:missed\\s+(?:video\\s+)?call|voice\\s?mail)\\s+from\\s+(.+)$");

    private NotificationParser() {}

    /** The event this notification announces, or null when it is not from a supported source. */
    public static ParsedEvent parse(NotificationFields f) {
        if (f == null || f.getPkg() == null) return null;
        if (VOICE_PACKAGE.equals(f.getPkg())) return parseVoice(f);
        if (V2S_PACKAGE.equals(f.getPkg()) && V2S_SMS_CHANNEL.equals(f.getChannelId())) return parseV2s(f);
        return null;
    }

    private static ParsedEvent parseVoice(NotificationFields f) {
        String title = firstNonEmpty(f.getConversationTitle(), f.getTitle());
        String category = lower(f.getCategory());

        // Calls and voicemails: explicit evidence only (T4).
        boolean voicemail = matches(VOICEMAIL, f.getTitle()) || matches(VOICEMAIL, f.getConversationTitle())
                || (f.getMessages().length == 0 && matches(VOICEMAIL, f.getText()));
        boolean missedCall = CATEGORY_MISSED_CALL.equals(category)
                || matches(MISSED_CALL, f.getTitle()) || matches(MISSED_CALL, f.getConversationTitle())
                || (f.getMessages().length == 0 && matches(MISSED_CALL, f.getText()));
        if (voicemail || missedCall) {
            String who = stripFromPrefix(title);
            return new ParsedEvent(who, PhoneNumbers.toE164(who, REGION), voicemail
                    ? ParsedEvent.Kind.VOICEMAIL : ParsedEvent.Kind.CALL, f.getWhenMs(), null);
        }

        boolean textShape = f.getMessages().length > 0
                || VOICE_TEXT_CHANNEL.equals(f.getChannelId())
                || VOICE_TEXT_TAG.equals(f.getTag())
                || CATEGORY_MESSAGE.equals(category);
        if (!textShape) {
            return new ParsedEvent(title, null, ParsedEvent.Kind.UNKNOWN, f.getWhenMs(), null);
        }

        String text = f.getText();
        long time = f.getWhenMs();
        NotificationFields.Message[] msgs = f.getMessages();
        if (msgs.length > 0) {
            NotificationFields.Message newest = null;
            for (NotificationFields.Message m : msgs) {
                if (m == null || m.getSender() == null) continue; // null sender = the device user
                if (newest == null || m.getTimeMs() >= newest.getTimeMs()) newest = m;
            }
            if (newest == null) {
                // Only the user's own messages (e.g. a reply sent from the notification): not inbound.
                return null;
            }
            text = newest.getText();
            if (newest.getTimeMs() > 0) time = newest.getTimeMs();
        }
        return new ParsedEvent(title, PhoneNumbers.toE164(title, REGION),
                ParsedEvent.Kind.SMS, time, text);
    }

    private static ParsedEvent parseV2s(NotificationFields f) {
        String address = firstNonEmpty(f.getTitle(), f.getConversationTitle());
        return new ParsedEvent(address, PhoneNumbers.toE164(address, REGION),
                ParsedEvent.Kind.SMS, f.getWhenMs(), f.getText());
    }

    // ------------------------------------------------------------------ helpers

    private static final String REGION = RecipientResolver.DEFAULT_REGION;

    private static String stripFromPrefix(String title) {
        if (title == null) return null;
        Matcher m = FROM_PREFIX.matcher(title);
        return m.matches() ? m.group(1).trim() : title;
    }

    private static boolean matches(Pattern p, String s) {
        return s != null && p.matcher(s).find();
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        return null;
    }
}
