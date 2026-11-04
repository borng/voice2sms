package com.voice2sms.sendlater.store;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/**
 * A (display name, number) pair scraped from the GV web thread list (R4b). {@code nameKey} is the
 * normalized name used for lookup ({@link ThreadDirectory#normalizeName}). Personal data: never log.
 */
@Entity(tableName = "thread_directory", primaryKeys = {"nameKey", "e164"},
        indices = {@Index("e164")})
public class ThreadDirectoryEntity {
    @NonNull public String nameKey = "";
    @NonNull public String e164 = "";
    public String displayName;
    public long lastSeenMs;
}
