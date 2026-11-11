package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.send.LockCoverPolicy.Event;
import com.voice2sms.sendlater.send.LockCoverPolicy.JobState;

import org.junit.Test;

/** send-lock-cover acceptance: the pure policy. */
public class LockCoverPolicyTest {

    private static final String E164 = "+15550100000";
    private static final String THREAD = "https://voice.google.com/u/0/messages?itemId=t.%2B15550100000";

    @Test public void noLockFlagsBeforeASuccessfulClaimOrWithoutTheCover() {
        for (JobState s : JobState.values()) {
            assertFalse("unclaimed / stale intent: " + s, LockCoverPolicy.applyLockFlags(false, s, true));
            assertFalse("OD26: never without the cover: " + s, LockCoverPolicy.applyLockFlags(true, s, false));
        }
        assertTrue(LockCoverPolicy.applyLockFlags(true, JobState.RUNNING, true));
        assertFalse("cleared once the job ended", LockCoverPolicy.applyLockFlags(true, JobState.ENDED, true));
        assertFalse(LockCoverPolicy.applyLockFlags(true, JobState.IDLE, true));
    }

    @Test public void coverShownForEveryClaimedRunningJobLockedOrNot() {
        assertTrue(LockCoverPolicy.showCover(true, JobState.RUNNING, true));
        assertTrue("OD26: unlocked too", LockCoverPolicy.showCover(true, JobState.RUNNING, false));
        assertTrue("ended, still locked: stays until the finish", LockCoverPolicy.showCover(true, JobState.ENDED, true));
        assertFalse("ended while unlocked: the user sees the thread", LockCoverPolicy.showCover(true, JobState.ENDED, false));
        for (JobState s : JobState.values()) {
            assertFalse("never for an unclaimed launch", LockCoverPolicy.showCover(false, s, true));
            assertFalse(LockCoverPolicy.showCover(false, s, false));
        }
        assertFalse(LockCoverPolicy.showCover(true, JobState.IDLE, true));
    }

    @Test public void timeoutAndPauseEndEveryRunningScheduledJob() {
        for (Event e : Event.values()) {
            assertTrue(e + " ends a running job", LockCoverPolicy.endsJob(e, true, JobState.RUNNING));
            assertFalse(LockCoverPolicy.endsJob(e, false, JobState.RUNNING));
            assertFalse(LockCoverPolicy.endsJob(e, true, JobState.ENDED));
            assertFalse(LockCoverPolicy.endsJob(e, true, JobState.IDLE));
        }
        assertFalse(LockCoverPolicy.endsJob(null, true, JobState.RUNNING));
    }

    @Test public void uncoveredPagesHaveNoNavigationRule() {
        assertTrue(LockCoverPolicy.allowNavigation(false, E164, "https://voice.google.com/u/0/calls"));
    }

    @Test public void navigationAllowedOnlyToTheTargetThreadWhileCovered() {
        assertTrue(LockCoverPolicy.allowNavigation(true, E164, THREAD));
        assertTrue(LockCoverPolicy.allowNavigation(true, E164, "https://voice.google.com/u/0/messages?itemId=t.+15550100000"));
        assertFalse("another thread", LockCoverPolicy.allowNavigation(true, E164,
                "https://voice.google.com/u/0/messages?itemId=t.%2B15550100001"));
        assertFalse("the inbox", LockCoverPolicy.allowNavigation(true, E164, "https://voice.google.com/u/0/messages"));
        assertFalse("sign-in", LockCoverPolicy.allowNavigation(true, E164, "https://accounts.google.com/ServiceLogin"));
        assertFalse("no target yet", LockCoverPolicy.allowNavigation(true, null, THREAD));
        assertFalse(LockCoverPolicy.allowNavigation(true, E164, null));
    }

    @Test public void finishNowOnPauseAlwaysAndOnJobEndOrTimeoutWhileLocked() {
        assertTrue(LockCoverPolicy.finishNow(Event.JOB_ENDED, true));
        assertTrue(LockCoverPolicy.finishNow(Event.TIMEOUT, true));
        assertTrue(LockCoverPolicy.finishNow(Event.PAUSED, true));
        assertTrue(LockCoverPolicy.finishNow(Event.STOPPED, true));
        assertTrue("the user left: the job ended, nothing to keep", LockCoverPolicy.finishNow(Event.PAUSED, false));
        assertTrue(LockCoverPolicy.finishNow(Event.STOPPED, false));
        assertFalse("unlocked at job end: keep the thread for the user",
                LockCoverPolicy.finishNow(Event.JOB_ENDED, false));
        assertFalse(LockCoverPolicy.finishNow(Event.TIMEOUT, false));
        assertFalse(LockCoverPolicy.finishNow(null, true));
    }

    @Test public void hardTimeoutIsSeventyFiveSeconds() {
        // D43: 75 s so the reload path (compose + reload grace) fits with room to spare.
        assertEquals(75_000L, LockCoverPolicy.HARD_TIMEOUT_MS);
    }

    @Test public void theCoverShowsOnlyTheLastFourDigits() {
        assertEquals("••• 0000", LockCoverPolicy.maskedNumber(E164));
        assertEquals("", LockCoverPolicy.maskedNumber(null));
        assertEquals("", LockCoverPolicy.maskedNumber("+12"));
        assertFalse(LockCoverPolicy.maskedNumber(E164).contains("555"));
    }
}
