package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.T;
import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SendOutcome;
import com.voice2sms.sendlater.send.SendDetails;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;

/** send-polish (OD28): a corrupt row ends as a generic reminder, never "may have already sent". */
public class SendLaterEngineSendPolishTest {

    private static final long ID = 43L;
    private EngineFakes.World w;

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        SendLaterEngine.installForTest(new EngineDeps.Builder()
                .store(w.store)
                .sources(new ArrayList<>(Collections.singletonList(new EngineFakes.FakeSource(w.j))))
                .notifier(w.notifier)
                .broadcaster(w.broadcaster)
                .alarms(w.alarms)
                .conditions(w.cond)
                .networkWaiter(w.network)
                .executor(Runnable::run)
                .marks(new EngineMarks.InMemory())
                .build());
        SendLaterEngine.setSender(w.sender);
    }

    @After public void tearDown() { SendLaterEngine.resetForTest(); }

    private void assertGenericReminder() {
        assertEquals(Status.SCHEDULED, w.store.status(ID));
        assertTrue(w.notifier.posted.toString(), w.notifier.has("remind " + ID + " LOCKED"));
        assertFalse(w.notifier.posted.toString(), w.notifier.has("mayHaveSent " + ID));
        assertTrue(w.sender.started.isEmpty());
    }

    @Test public void aRowWithNoRecipientEndsAsAGenericReminder() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).recipientE164(null).build());
        w.cond.now = T + 1_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW,
                SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "no_recipient"));
        assertGenericReminder();
    }

    @Test public void aRowWithNoTextEndsAsAGenericReminder() {
        w.store.put(message(ID).status(Status.IN_FLIGHT).text(null).build());
        w.cond.now = T + 1_000;
        SendLaterEngine.onSendOutcome(null, ID, SendOutcome.CANNOT_SHOW,
                SendDetails.forEngine(SendOutcome.CANNOT_SHOW, "no_recipient"));
        assertGenericReminder();
    }

    @Test public void theEngineHasNoFailedBranchOfItsOwn() throws IOException {
        String src = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/voice2sms/sendlater/engine/SendLaterEngine.java")), StandardCharsets.UTF_8);
        assertFalse("FAILED is not produced by the send path; no dedicated branch", src.contains("case FAILED:"));
    }
}
