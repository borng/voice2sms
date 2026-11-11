package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;

import com.voice2sms.sendlater.send.LaunchPlanner.Result;

import org.junit.Test;

/**
 * LIVE-F4: a send started by the user's own tap while voice2sms's tap activity is in the
 * foreground is DIRECT on an unlocked, lit screen, accessibility service or not. A locked or dark
 * screen keeps the usual rules (OD31 L1). Without the tap the planner is unchanged.
 */
public class LaunchPlannerUserTapTest {

    @Test public void userTapOnAnUnlockedLitScreenIsDirectWithoutAccessibility() {
        assertEquals(Result.DIRECT, LaunchPlanner.plan(false, false, true, false, true));
        assertEquals(Result.DIRECT, LaunchPlanner.plan(false, false, true, true, true));
    }

    @Test public void withoutTheTapAnUnlockedLitScreenStillCannotShow() {
        assertEquals(Result.CANNOT_SHOW, LaunchPlanner.plan(false, false, true, true, false));
        assertEquals(Result.CANNOT_SHOW, LaunchPlanner.plan(false, false, true, true));
    }

    @Test public void userTapNeverActsOverTheKeyguardOrADarkScreen() {
        assertEquals(Result.FULL_SCREEN_INTENT, LaunchPlanner.plan(false, true, true, true, true));
        assertEquals(Result.CANNOT_SHOW, LaunchPlanner.plan(false, true, true, false, true));
        assertEquals(Result.FULL_SCREEN_INTENT, LaunchPlanner.plan(false, false, false, true, true));
        assertEquals(Result.CANNOT_SHOW, LaunchPlanner.plan(false, false, false, false, true));
    }

    @Test public void accessibilityStaysDirectEitherWay() {
        assertEquals(Result.DIRECT, LaunchPlanner.plan(true, true, false, false, false));
        assertEquals(Result.DIRECT, LaunchPlanner.plan(true, false, true, false, true));
    }

    @Test public void fourArgumentPlanEqualsNoTap() {
        boolean[] b = {false, true};
        for (boolean a : b) for (boolean l : b) for (boolean s : b) for (boolean f : b) {
            assertEquals(LaunchPlanner.plan(a, l, s, f, false), LaunchPlanner.plan(a, l, s, f));
        }
    }
}
