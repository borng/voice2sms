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

    @Test public void noLockFlagsBeforeASuccessfulClaim() {
        for (JobState s : JobState.values()) {
            assertFalse("unclaimed / stale intent: " + s, LockCoverPolicy.applyLockFlags(false, s));
        }
        assertTrue(LockCoverPolicy.applyLockFlags(true, JobState.RUNNING));
        assertFalse("cleared once the job ended", LockCoverPolicy.applyLockFlags(true, JobState.ENDED));
        assertFalse(LockCoverPolicy.applyLockFlags(true, JobState.IDLE));
    }

    @Test public void coverShownWheneverOverTheKeyguardAndTheJobRuns() {
        assertTrue(LockCoverPolicy.showCover(true, true, JobState.RUNNING, true));
        assertTrue("unlocked mid-send: the cover stays until the job ends",
                LockCoverPolicy.showCover(true, true, JobState.RUNNING, false));
        assertTrue("ended, still locked: stays until the finish", LockCoverPolicy.showCover(true, true, JobState.ENDED, true));
        assertFalse("ended after an unlock: the user sees the thread", LockCoverPolicy.showCover(true, true, JobState.ENDED, false));
        assertFalse("never for an unclaimed launch", LockCoverPolicy.showCover(false, true, JobState.RUNNING, true));
    }

    @Test public void unlockedPathHasNoCoverNoNavigationRuleAndNoForcedFinish() {
        for (JobState s : JobState.values()) {
            assertFalse(LockCoverPolicy.showCover(true, false, s, false));
        }
        assertTrue(LockCoverPolicy.allowNavigation(false, E164, "https://voice.google.com/u/0/calls"));
        for (Event e : Event.values()) {
            assertFalse(LockCoverPolicy.finishNow(e, false, false));
        }
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

    @Test public void finishNowOnJobEndTimeoutAndPauseWhileOverTheKeyguard() {
        assertTrue(LockCoverPolicy.finishNow(Event.JOB_ENDED, true, true));
        assertTrue(LockCoverPolicy.finishNow(Event.TIMEOUT, true, true));
        assertTrue(LockCoverPolicy.finishNow(Event.PAUSED, true, true));
        assertTrue(LockCoverPolicy.finishNow(Event.STOPPED, true, true));
        assertFalse("unlocked during the send: keep the thread for the user",
                LockCoverPolicy.finishNow(Event.JOB_ENDED, true, false));
        assertFalse(LockCoverPolicy.finishNow(null, true, true));
    }

    @Test public void hardTimeoutIsSixtySeconds() {
        assertEquals(60_000L, LockCoverPolicy.HARD_TIMEOUT_MS);
    }

    @Test public void theCoverShowsOnlyTheLastFourDigits() {
        assertEquals("••• 0000", LockCoverPolicy.maskedNumber(E164));
        assertEquals("", LockCoverPolicy.maskedNumber(null));
        assertEquals("", LockCoverPolicy.maskedNumber("+12"));
        assertFalse(LockCoverPolicy.maskedNumber(E164).contains("555"));
    }
}
