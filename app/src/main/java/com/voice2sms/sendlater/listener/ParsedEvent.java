package com.voice2sms.sendlater.listener;

/**
 * What {@link NotificationParser} read from one notification: always inbound. {@link Kind#UNKNOWN}
 * is a Voice notification that could not be classified (T4); it is never stored. Holds
 * notification content: never log it ({@link #toString()} redacts).
 */
public final class ParsedEvent {

    public enum Kind { SMS, MMS, CALL, VOICEMAIL, UNKNOWN }

    public enum Direction { IN }

    private final String conversationTitle;
    private final String number;
    private final Kind kind;
    private final long timeMs;
    private final String text;

    public ParsedEvent(String conversationTitle, String number, Kind kind, long timeMs, String text) {
        if (kind == null) throw new IllegalArgumentException("kind");
        this.conversationTitle = conversationTitle;
        this.number = number;
        this.kind = kind;
        this.timeMs = timeMs;
        this.text = text;
    }

    /** The conversation's display title (contact name or formatted number); may be null. */
    public String getConversationTitle() { return conversationTitle; }
    /** E.164 when the notification identifies the other side by number, else null. */
    public String getNumber() { return number; }
    public Kind getKind() { return kind; }
    public Direction getDirection() { return Direction.IN; }
    /** Event time from the notification; 0 when it carried none. */
    public long getTimeMs() { return timeMs; }
    public String getText() { return text; }

    @Override public String toString() {
        return "ParsedEvent{kind=" + kind + ", hasNumber=" + (number != null) + "}";
    }
}
