package com.voice2sms.sendlater.store;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One recorded conversation event (R32, R34) from the notification listener or the on-screen
 * bubbles. {@code conversationKey} is {@code ConversationRef.key()}; {@code recipientE164} is kept
 * as well when known. {@code text} is optional message content: never log it. {@code textHash} must
 * be {@code ThreadEvent.hashOf(text)}.
 */
@Entity(tableName = "thread_event",
        indices = {@Index({"conversationKey", "timeMs"}), @Index("timeMs")})
public class ThreadEventEntity {
    public static final String SOURCE_LISTENER = "LISTENER";
    public static final String SOURCE_ON_SCREEN = "ON_SCREEN";

    @PrimaryKey(autoGenerate = true)
    public long id;
    @NonNull public String conversationKey = "";
    public String recipientE164;
    @NonNull public String direction = "IN";
    @NonNull public String kind = "SMS";
    public long timeMs;
    public String textHash;
    public String text;
    @NonNull public String source = SOURCE_LISTENER;
    public long recordedAt;
}
