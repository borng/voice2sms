package com.voice2sms.sendlater.store;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * Thin Room DAO. Status guards live in the WHERE clauses so every transition is a single atomic
 * statement; each update returns the number of rows changed (0 = transition refused). Use through
 * {@link SendLaterRepository}, never on the main thread.
 */
@Dao
public interface SendLaterDao {

    // ---- scheduled_message ----

    @Insert
    long insertMessage(ScheduledMessageEntity e);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertWatermark(WatermarkEntity w);

    @Query("SELECT * FROM scheduled_message WHERE id = :id")
    ScheduledMessageEntity message(long id);

    @Query("SELECT * FROM watermark WHERE messageId = :id")
    WatermarkEntity watermark(long id);

    @Query("SELECT * FROM scheduled_message WHERE status = :status AND recipientE164 = :e164 ORDER BY scheduledFor, id")
    List<ScheduledMessageEntity> byStatusForThread(String status, String e164);

    @Query("SELECT * FROM scheduled_message WHERE status = :status ORDER BY scheduledFor, id")
    List<ScheduledMessageEntity> byStatus(String status);

    @Query("SELECT * FROM scheduled_message WHERE status IN (:statuses) AND updatedAt >= :sinceMs ORDER BY updatedAt DESC, id DESC")
    List<ScheduledMessageEntity> byStatusesUpdatedSince(List<String> statuses, long sinceMs);

    @Query("UPDATE scheduled_message SET status = :to, inFlightBodyHash = :bodyHash, updatedAt = :now "
            + "WHERE id = :id AND status IN (:from)")
    int toInFlight(long id, String to, String bodyHash, List<String> from, long now);

    @Query("UPDATE scheduled_message SET status = :to, holdReason = COALESCE(:holdReason, holdReason), updatedAt = :now "
            + "WHERE id = :id AND status IN (:from)")
    int transition(long id, String to, String holdReason, List<String> from, long now);

    @Query("UPDATE scheduled_message SET status = :to, lastError = :lastError, attemptCount = attemptCount + 1, "
            + "updatedAt = :now WHERE id = :id AND status IN (:from)")
    int transitionWithAttempt(long id, String to, String lastError, List<String> from, long now);

    @Query("UPDATE scheduled_message SET status = 'SCHEDULED', scheduledFor = :scheduledFor, "
            + "holdIfReplied = :holdIfReplied, latenessPolicy = :latenessPolicy, holdReason = NULL, "
            + "updatedAt = :now WHERE id = :id AND status IN (:from)")
    int reschedule(long id, long scheduledFor, boolean holdIfReplied, String latenessPolicy,
                   List<String> from, long now);

    // ---- thread_event ----

    @Insert
    long insertEvent(ThreadEventEntity e);

    @Query("SELECT * FROM thread_event WHERE conversationKey = :key AND timeMs >= :sinceMs ORDER BY timeMs, id")
    List<ThreadEventEntity> eventsSince(String key, long sinceMs);

    @Query("DELETE FROM thread_event WHERE timeMs < :olderThanMs")
    int deleteEventsOlderThan(long olderThanMs);

    @Query("DELETE FROM thread_event WHERE conversationKey NOT IN "
            + "(SELECT conversationKey FROM scheduled_message WHERE status IN (:live))")
    int deleteOrphanEvents(List<String> live);

    @Query("SELECT DISTINCT conversationKey FROM scheduled_message WHERE status IN (:live)")
    List<String> liveConversationKeys(List<String> live);

    // ---- thread_directory ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertDirectory(ThreadDirectoryEntity e);

    @Query("SELECT DISTINCT e164 FROM thread_directory WHERE nameKey = :nameKey ORDER BY lastSeenMs DESC")
    List<String> directoryNumbers(String nameKey);
}
