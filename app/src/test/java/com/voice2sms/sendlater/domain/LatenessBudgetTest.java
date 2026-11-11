package com.voice2sms.sendlater.domain;

import static com.voice2sms.sendlater.domain.Fixtures.DAY;
import static com.voice2sms.sendlater.domain.Fixtures.HOUR;
import static com.voice2sms.sendlater.domain.Fixtures.MIN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** D10 / R27 / I2: lateness budget per how far ahead the message was scheduled, and overrides. */
public class LatenessBudgetTest {

    @Test
    public void auto_lessThanOneHourAhead_is10Minutes() {
        assertEquals(10 * MIN, LatenessBudget.budgetMs(5 * MIN, LatenessPolicy.AUTO));
        assertEquals(10 * MIN, LatenessBudget.budgetMs(59 * MIN, LatenessPolicy.AUTO));
    }

    @Test
    public void auto_oneToTwentyFourHoursAhead_is30Minutes() {
        assertEquals(30 * MIN, LatenessBudget.budgetMs(HOUR, LatenessPolicy.AUTO));
        assertEquals(30 * MIN, LatenessBudget.budgetMs(3 * HOUR, LatenessPolicy.AUTO));
        assertEquals(30 * MIN, LatenessBudget.budgetMs(23 * HOUR, LatenessPolicy.AUTO));
    }

    @Test
    public void auto_moreThanTwentyFourHoursAhead_is2Hours() {
        assertEquals(2 * HOUR, LatenessBudget.budgetMs(DAY + MIN, LatenessPolicy.AUTO));
        assertEquals(2 * HOUR, LatenessBudget.budgetMs(7 * DAY, LatenessPolicy.AUTO));
    }

    @Test
    public void within30Min_overridesRegardlessOfLeadTime() {
        assertEquals(30 * MIN, LatenessBudget.budgetMs(5 * MIN, LatenessPolicy.WITHIN_30_MIN));
        assertEquals(30 * MIN, LatenessBudget.budgetMs(7 * DAY, LatenessPolicy.WITHIN_30_MIN));
    }

    @Test
    public void never_isZero() {
        assertEquals(0L, LatenessBudget.budgetMs(5 * MIN, LatenessPolicy.NEVER));
        assertEquals(0L, LatenessBudget.budgetMs(7 * DAY, LatenessPolicy.NEVER));
    }

    @Test
    public void sendAnyway_isCappedAt24Hours() {
        // Orchestrator ruling 2026-11-04 (OD14, I2): the "Send anyway" late policy is UP_TO_24H,
        // never unbounded. A tap on "Send anyway" in a hold notification bypasses the budget separately.
        long b = LatenessBudget.budgetMs(5 * MIN, LatenessPolicy.SEND_ANYWAY);
        assertTrue("send-anyway budget must be 24 h, was " + b, b == 24 * 60 * MIN);
        assertTrue(LatenessBudget.budgetMs(5 * MIN, LatenessPolicy.UP_TO_24H) == 24 * 60 * MIN);
    }
}
