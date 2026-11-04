package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.domain.ThreadEventSource;
import com.voice2sms.sendlater.send.SendDetails;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * gate-residuals (R27): a sender that reports a corrupt row (CANNOT_SHOW "no_recipient") before
 * start() returns false gets the generic COULD_NOT_SEND reminder; a bare false (the old corrupt
 * branch) would have fallen to the LOCKED "Unlock to send" default. Fictional data only.
 */
public class CorruptRowReminderTest {

    private static final long ID = 43L;
    /** ScheduledSendController.DETAIL_NO_RECIPIENT (package-private in send). */
    private static final String NO_RECIPIENT = "no_recipient";

    private EngineFakes.World w;
    private final List<String> causes = new ArrayList<>();

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        final EngineFakes.FakeNotifier base = w.notifier;
        EngineNotifier notifier = new EngineNotifier() {
            @Override public void lastCall(ScheduledMessage m) { base.lastCall(m); }
            @Override public void hold(ScheduledMessage m, HoldReason r, long nowMs) { base.hold(m, r, nowMs); }
            @Override public void remind(ScheduledMessage m, RemindReason r) { base.remind(m, r); }
            @Override public void sent(ScheduledMessage m) { base.sent(m); }
            @Override public void repliedDuringSend(ScheduledMessage m) { base.repliedDuringSend(m); }
            @Override public void selfPreempted(ScheduledMessage m) { base.selfPreempted(m); }
            @Override public void expired(ScheduledMessage m) { base.expired(m); }
            @Override public void mayHaveSent(ScheduledMessage m) { base.mayHaveSent(m); }
            @Override public void remindCannotSend(ScheduledMessage m, RemindCause cause) {
                causes.add(m.getId() + " " + cause);
                base.remind(m, cause.reason());
            }
            @Override public void dismiss(long id) { base.dismiss(id); }
        };
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store)
                .sources(new ArrayList<ThreadEventSource>(Collections.singletonList(w.source)))
                .notifier(notifier)
                .broadcaster(w.broadcaster)
                .alarms(w.alarms)
                .conditions(w.cond)
                .networkWaiter(w.network)
                .executor(Runnable::run)
                .build());
    }

    @After public void tearDown() { SendLaterEngine.resetForTest(); }

    @Test public void aReportedCorruptRowGetsTheCouldNotSendReminder() {
        SendLaterEngine.setSender(new ScheduledSender() {
            @Override public boolean start(Context context, ScheduledMessage m) {
                // What WebViewScheduledSender.start now does for a row with no recipient or text.
                SendLaterEngine.onSendOutcome(context, m.getId(), SendOutcome.CANNOT_SHOW,
                        SendDetails.forEngine(SendOutcome.CANNOT_SHOW, NO_RECIPIENT));
                return false;
            }
        });
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Collections.singletonList(ID + " COULD_NOT_SEND"), causes);
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " LOCKED"));
        assertEquals("could_not_send", SendDetails.forEngine(SendOutcome.CANNOT_SHOW, NO_RECIPIENT));
    }

    /** The behaviour the fix removes: a bare false reaches the engine as the LOCKED default. */
    @Test public void aBareFalseWouldHaveSaidUnlock() {
        SendLaterEngine.setSender(new ScheduledSender() {
            @Override public boolean start(Context context, ScheduledMessage m) { return false; }
        });
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(Collections.singletonList(ID + " LOCKED"), causes);
    }
}
