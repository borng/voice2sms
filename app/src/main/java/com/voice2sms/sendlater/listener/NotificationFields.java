package com.voice2sms.sendlater.listener;

/**
 * The subset of a posted notification the reply listener looks at (R32), as a plain value object
 * so {@link NotificationParser} stays pure JVM. Any field may be null. Holds notification content:
 * never log it ({@link #toString()} redacts).
 */
public final class NotificationFields {

    /** One MessagingStyle message. {@code sender} null means the device user (MessagingStyle rule). */
    public static final class Message {
        private final String text;
        private final long timeMs;
        private final String sender;

        public Message(String text, long timeMs, String sender) {
            this.text = text;
            this.timeMs = timeMs;
            this.sender = sender;
        }

        public String getText() { return text; }
        public long getTimeMs() { return timeMs; }
        public String getSender() { return sender; }

        @Override public String toString() { return "Message{redacted}"; }
    }

    private final String pkg;
    private final String tag;
    private final String channelId;
    private final String category;
    private final long whenMs;
    private final String conversationTitle;
    private final String title;
    private final String text;
    private final Message[] messages;

    public NotificationFields(String pkg, String tag, String channelId, String category, long whenMs,
                              String conversationTitle, String title, String text, Message[] messages) {
        this.pkg = pkg;
        this.tag = tag;
        this.channelId = channelId;
        this.category = category;
        this.whenMs = whenMs;
        this.conversationTitle = conversationTitle;
        this.title = title;
        this.text = text;
        this.messages = messages != null ? messages.clone() : new Message[0];
    }

    public String getPkg() { return pkg; }
    public String getTag() { return tag; }
    public String getChannelId() { return channelId; }
    public String getCategory() { return category; }
    public long getWhenMs() { return whenMs; }
    public String getConversationTitle() { return conversationTitle; }
    public String getTitle() { return title; }
    public String getText() { return text; }
    public Message[] getMessages() { return messages.clone(); }

    /** Never includes titles, text or senders. */
    @Override public String toString() {
        return "NotificationFields{pkg=" + pkg + ", channel=" + channelId + ", category=" + category
                + ", messages=" + messages.length + "}";
    }
}
