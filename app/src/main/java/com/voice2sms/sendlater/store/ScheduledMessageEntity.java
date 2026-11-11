package com.voice2sms.sendlater.store;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Row of {@code scheduled_message} (R20). Enums are stored by {@code name()}. {@code text} is
 * message content: never log it. {@code conversationKey} is {@code ConversationRef.key()} of the
 * message, kept so the event purge (R34) can find conversations with live messages in SQL.
 * {@code inFlightBodyHash} is the R31 body hash recorded by {@code markInFlight};
 * {@code updatedAt} is the time of the last status change (drives {@code recent}).
 * {@code rungReason} (schema v2) is {@code RemindReason.name()} of why the message sits below R1,
 * e.g. NO_SESSION for a D23 save (OD22(4)); null otherwise.
 */
@Entity(tableName = "scheduled_message",
        indices = {@Index("status"), @Index("recipientE164"), @Index("conversationKey")})
public class ScheduledMessageEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long createdAt;
    public long scheduledFor;
    public String text;
    public String recipientE164;
    public String recipientDisplayName;
    public String threadItemId;
    @NonNull public String source = "NATIVE_GV";
    @NonNull public String rung = "R1";
    public boolean holdIfReplied;
    @NonNull public String latenessPolicy = "DEFAULT";
    @NonNull public String status = "SCHEDULED";
    public String holdReason;
    public String lastError;
    public int attemptCount;
    @NonNull public String conversationKey = "";
    public String inFlightBodyHash;
    @ColumnInfo(defaultValue = "0")
    public long updatedAt;
    // v2 (OD22(4))
    public String rungReason;
}
