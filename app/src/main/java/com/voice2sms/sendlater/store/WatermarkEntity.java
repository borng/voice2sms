package com.voice2sms.sendlater.store;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

/** Row of {@code watermark} (R20): one per message, deleted with it. */
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
}
