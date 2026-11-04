package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEvent.Direction;
import com.voice2sms.sendlater.web.ThreadBubble;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Implementer's unit tests for LaunchClaims, bubble timing and the verifier's own-bubble window. */
public class SendPathLogicTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String BODY = "Running ten minutes late, save me a seat please";

    // ------------------------------------------------------------------ LaunchClaims

    @Test public void aLaunchTokenCanBeTakenExactlyOnce() {
        LaunchClaims c = new LaunchClaims();
        String t = c.issue(7L);
        assertTrue(c.isPending(7L));
        assertTrue(c.take(7L, t));
        assertFalse("second take (watchdog after the activity, or a replayed intent) fails", c.take(7L, t));
        assertFalse(c.isPending(7L));
    }

    @Test public void aStaleOrUnknownTokenNeverClaims() {
        LaunchClaims c = new LaunchClaims();
        String old = c.issue(7L);
        String fresh = c.issue(7L);
        assertNotEquals(old, fresh);
        assertFalse("an older launch of the same id is stale", c.take(7L, old));
        assertFalse(c.take(7L, null));
        assertFalse(c.take(8L, fresh));
        assertTrue(c.take(7L, fresh));
    }

    // ------------------------------------------------------------------ bubble timing

    // ------------------------------------------------------------------ verifier window

    @Test public void anOldOutboundWithTheSameTextDoesNotVerify() {
        long check = NOW;
        List<ThreadEvent> e = new ArrayList<>(Arrays.asList(new ThreadEvent(Direction.OUT, ThreadEvent.Kind.SMS,
                check - 24 * 3_600_000L, ThreadEvent.hashOf(BODY), BODY)));
        assertEquals("yesterday's identical text is not this send", SendVerifier.Result.UNVERIFIED,
                SendVerifier.verify(ThreadEvent.hashOf(BODY), e, check, check + 30_000L));
    }

    @Test public void inboundAfterOwnBubbleIsNotALateReply() {
        long check = NOW;
        long sent = check + 20_000L;
        List<ThreadEvent> e = new ArrayList<>(Arrays.asList(
                new ThreadEvent(Direction.OUT, ThreadEvent.Kind.SMS, sent, ThreadEvent.hashOf(BODY), BODY),
                new ThreadEvent(Direction.IN, ThreadEvent.Kind.SMS, sent + 5_000L, ThreadEvent.hashOf("ok"), "ok")));
        assertEquals("a reply to the sent message is not 'replied just as it sent'", SendVerifier.Result.VERIFIED,
                SendVerifier.verify(ThreadEvent.hashOf(BODY), e, check, sent + 10_000L));
    }

    @Test public void launchPlannerPrefersDirectThenFsi() {
        assertEquals(LaunchPlanner.Result.DIRECT, LaunchPlanner.plan(true, false, true, false));
        assertEquals(LaunchPlanner.Result.FULL_SCREEN_INTENT, LaunchPlanner.plan(false, true, false, true));
        assertEquals(LaunchPlanner.Result.CANNOT_SHOW, LaunchPlanner.plan(false, false, true, true));
    }
}
