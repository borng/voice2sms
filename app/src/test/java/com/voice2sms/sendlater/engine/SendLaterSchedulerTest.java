package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.MIN;
import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.T0;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;

import org.junit.Before;
import org.junit.Test;

/** SendLaterScheduler over a fake AlarmManager seam (R22, R24). */
public class SendLaterSchedulerTest {

    private static final long ID = 7L;
    private EngineFakes.World w;
    private SendLaterScheduler scheduler;

    @Before public void setUp() {
        w = new EngineFakes.World();
        scheduler = w.scheduler();
        w.cond.now = T0; // scheduled 2 h ahead
    }

    private ScheduledMessage msg() { return message(ID).build(); }

    @Test public void exactAlarmsAtLastCallAndDueWhenPermitted() {
        scheduler.registerAlarms(msg());
        assertEquals("T-5 last call via setExactAndAllowWhileIdle: " + w.alarms.calls,
                T - 5 * MIN, w.alarms.timeOf("exact " + AlarmKind.LAST_CALL + " " + ID + " "));
        assertEquals("T due via setExactAndAllowWhileIdle: " + w.alarms.calls,
                T, w.alarms.timeOf("exact " + AlarmKind.DUE + " " + ID + " "));
        assertTrue("no inexact alarms when exact is permitted: " + w.alarms.calls,
                w.alarms.matching("inexact").isEmpty());
        assertFalse("last call is not posted early", w.notifier.has("lastCall " + ID));
    }

    @Test public void inexactAlarmsWhenExactNotPermitted() {
        w.alarms.exactAllowed = false;
        scheduler.registerAlarms(msg());
        assertTrue("no exact alarm without the permission: " + w.alarms.calls,
                w.alarms.matching("exact").isEmpty());
        assertEquals(T - 5 * MIN, w.alarms.timeOf("inexact " + AlarmKind.LAST_CALL + " " + ID + " "));
        assertEquals(T, w.alarms.timeOf("inexact " + AlarmKind.DUE + " " + ID + " "));
    }

    @Test public void securityExceptionFromExactFallsBackToInexact() {
        w.alarms.exactThrows = true; // permission revoked between the check and the call
        scheduler.registerAlarms(msg()); // must not throw
        assertEquals(T, w.alarms.timeOf("inexact " + AlarmKind.DUE + " " + ID + " "));
        assertEquals(T - 5 * MIN, w.alarms.timeOf("inexact " + AlarmKind.LAST_CALL + " " + ID + " "));
    }

    @Test public void lessThanFiveMinutesAheadPostsLastCallNowAndRegistersOnlyDue() {
        w.cond.now = T - 3 * MIN;
        scheduler.registerAlarms(msg());
        assertTrue("last call fires immediately (R24): " + w.notifier.posted, w.notifier.has("lastCall " + ID));
        assertEquals(T, w.alarms.timeOf("exact " + AlarmKind.DUE + " " + ID + " "));
        assertTrue("no T-5 alarm: " + w.alarms.calls, w.alarms.registered(AlarmKind.LAST_CALL, ID).isEmpty());
    }

    @Test public void cancelAlarmsCancelsLastCallAndDue() {
        scheduler.registerAlarms(msg());
        scheduler.cancelAlarms(ID);
        assertFalse(w.alarms.calls.toString(), w.alarms.matching("cancel " + AlarmKind.LAST_CALL + " " + ID).isEmpty());
        assertFalse(w.alarms.calls.toString(), w.alarms.matching("cancel " + AlarmKind.DUE + " " + ID).isEmpty());
    }

    @Test public void reregisterAllRegistersEveryPendingMessageOnly() {
        w.store.put(message(1).build());
        w.store.put(message(2).scheduledFor(T + 60 * MIN).build());
        w.store.put(message(3).status(Status.CANCELED).build());
        w.store.put(message(4).status(Status.SENT_VERIFIED).build());
        w.store.put(message(5).status(Status.HELD).holdReason(HoldReason.REPLIED).build());
        scheduler.reregisterAll(null);
        assertEquals(T, w.alarms.timeOf("exact " + AlarmKind.DUE + " 1 "));
        assertEquals(T + 60 * MIN, w.alarms.timeOf("exact " + AlarmKind.DUE + " 2 "));
        assertTrue(w.alarms.registered(AlarmKind.DUE, 3).isEmpty());
        assertTrue(w.alarms.registered(AlarmKind.DUE, 4).isEmpty());
        assertTrue("a held message gets no due alarm", w.alarms.registered(AlarmKind.DUE, 5).isEmpty());
    }
}
