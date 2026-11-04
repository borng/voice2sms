package com.voice2sms.sendlater.store;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Room database {@code sendlater.db} (R20, R23). Main-thread queries stay disallowed: call the
 * repository from a background thread.
 */
@Database(entities = {ScheduledMessageEntity.class, WatermarkEntity.class,
        ThreadEventEntity.class, ThreadDirectoryEntity.class},
        version = 1, exportSchema = false)
public abstract class SendLaterDatabase extends RoomDatabase {

    public static final String NAME = "sendlater.db";

    private static volatile SendLaterDatabase instance;

    public abstract SendLaterDao dao();

    public static SendLaterDatabase get(Context context) {
        SendLaterDatabase db = instance;
        if (db == null) {
            synchronized (SendLaterDatabase.class) {
                db = instance;
                if (db == null) {
                    db = Room.databaseBuilder(context.getApplicationContext(),
                            SendLaterDatabase.class, NAME).build();
                    instance = db;
                }
            }
        }
        return db;
    }
}
