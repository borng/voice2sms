package com.voice2sms.sendlater.store;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

/**
 * Row of {@code watermark} (R20): one per message, deleted with it. The {@code orig*} columns
 * (schema v2, OD22(1)) keep the message's FIRST watermark once a snooze or edit replaced the main
 * one: the self-preemption baseline. {@code origQuality} null = never replaced.
 */
@Entity(tableName = "watermark",
        foreignKeys = @ForeignKey(entity = ScheduledMessageEntity.class,
                parentColumns = "id", childColumns = "messageId",
                onDelete = ForeignKey.CASCADE))
public class WatermarkEntity {
    @PrimaryKey
    public long messageId;
    @NonNull public String quality = "DEVICE_ONLY";
    public String newestServerId;
    public Long newestTs;
    public String bubbleHash;
    public long capturedAtDevice;
    public Long skewEstimateMs;
    // v2 (OD22(1)): the original watermark, null until the main one is replaced.
    public String origQuality;
    public String origNewestServerId;
    public Long origNewestTs;
    public String origBubbleHash;
    public Long origCapturedAtDevice;
    public Long origSkewEstimateMs;
}
