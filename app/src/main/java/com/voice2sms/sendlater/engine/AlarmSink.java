package com.voice2sms.sendlater.engine;

/** AlarmManager seam. Production: {@link AndroidAlarmSink}. */
public interface AlarmSink {
    /** {@code AlarmManager.canScheduleExactAlarms()} on API 31+, true below. */
    boolean canScheduleExact();

    /** {@code setExactAndAllowWhileIdle}; may throw SecurityException if the permission was revoked. */
    void setExact(AlarmKind kind, long id, long atMs);

    /** {@code setAndAllowWhileIdle}. */
    void setInexact(AlarmKind kind, long id, long atMs);

    void cancel(AlarmKind kind, long id);
}
