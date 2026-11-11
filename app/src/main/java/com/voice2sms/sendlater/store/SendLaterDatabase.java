package com.voice2sms.sendlater.store;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * Room database {@code sendlater.db} (R20, R23). Main-thread queries stay disallowed: call the
 * repository from a background thread.
 *
 * <p>Schema history. Every version step has an explicit {@link Migration}; destructive fallback
 * is never enabled (a scheduled message must survive an app update, R23/I1).
 * <ul>
 *   <li>v1: scheduled_message, watermark, thread_event, thread_directory.</li>
 *   <li>v2 (OD22): watermark.orig* (the original watermark kept across snooze/edit, the
 *       self-preemption baseline) and scheduled_message.rungReason (why a message sits below R1;
 *       existing R3 rows are D23 saves, so they get NO_SESSION).</li>
 * </ul>
 */
@Database(entities = {ScheduledMessageEntity.class, WatermarkEntity.class,
        ThreadEventEntity.class, ThreadDirectoryEntity.class},
        version = 2, exportSchema = false)
public abstract class SendLaterDatabase extends RoomDatabase {

    public static final String NAME = "sendlater.db";

    /**
     * v1 -> v2, additive only: nullable columns, no table rebuilt, no row dropped. Pinned as
     * plain SQL so a JVM test can check it (no DROP / DELETE).
     */
    static final String[] MIGRATION_1_2_SQL = {
            "ALTER TABLE `watermark` ADD COLUMN `origQuality` TEXT",
            "ALTER TABLE `watermark` ADD COLUMN `origNewestServerId` TEXT",
            "ALTER TABLE `watermark` ADD COLUMN `origNewestTs` INTEGER",
            "ALTER TABLE `watermark` ADD COLUMN `origBubbleHash` TEXT",
            "ALTER TABLE `watermark` ADD COLUMN `origCapturedAtDevice` INTEGER",
            "ALTER TABLE `watermark` ADD COLUMN `origSkewEstimateMs` INTEGER",
            "ALTER TABLE `scheduled_message` ADD COLUMN `rungReason` TEXT",
            // Before v2 the only producer of R3 was the sheet's no-session save (R9/D23).
            "UPDATE `scheduled_message` SET `rungReason` = 'NO_SESSION' WHERE `rung` = 'R3'",
    };

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            for (String sql : MIGRATION_1_2_SQL) db.execSQL(sql);
        }
    };

    private static volatile SendLaterDatabase instance;

    public abstract SendLaterDao dao();

    public static SendLaterDatabase get(Context context) {
        SendLaterDatabase db = instance;
        if (db == null) {
            synchronized (SendLaterDatabase.class) {
                db = instance;
                if (db == null) {
                    db = Room.databaseBuilder(context.getApplicationContext(),
                            SendLaterDatabase.class, NAME)
                            .addMigrations(MIGRATION_1_2)
                            .build();
                    instance = db;
                }
            }
        }
        return db;
    }
}
