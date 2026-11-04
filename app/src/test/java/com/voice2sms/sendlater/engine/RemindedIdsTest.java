package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/** Deliver fix UI M5: the read-only reminded-mark accessor the chip and Upcoming use. */
public class RemindedIdsTest {

    private EngineFakes.World w;
    private EngineMarks marks;

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        marks = new EngineMarks.InMemory();
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store)
                .sources(new ArrayList<>(Collections.singletonList(w.source)))
                .notifier(w.notifier)
                .broadcaster(w.broadcaster)
                .alarms(w.alarms)
                .conditions(w.cond)
                .networkWaiter(w.network)
                .executor(Runnable::run)
                .marks(marks)
                .build());
    }

    @After public void tearDown() { SendLaterEngine.resetForTest(); }

    @Test public void onlyMarkedScheduledRows() {
        ScheduledMessage a = message(1).build();
        ScheduledMessage b = message(2).build();
        ScheduledMessage held = message(3).status(Status.HELD).holdReason(HoldReason.REPLIED).build();
        marks.setReminded(1, true);
        marks.setReminded(3, true);
        Set<Long> ids = SendLaterEngine.remindedIds(null, Arrays.asList(a, b, held, null));
        assertEquals(Collections.singleton(1L), ids);
    }

    @Test public void emptyAndNullAreSafe() {
        assertTrue(SendLaterEngine.remindedIds(null, null).isEmpty());
        assertTrue(SendLaterEngine.remindedIds(null, new ArrayList<ScheduledMessage>()).isEmpty());
    }

    @Test public void noEngineMeansNothingReminded() {
        SendLaterEngine.resetForTest();
        assertTrue("deps(null) throws inside; the label falls back to Scheduled",
                SendLaterEngine.remindedIds(null, Collections.singletonList(message(1).build())).isEmpty());
    }
}
