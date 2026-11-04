package com.voice2sms.sendlater;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pinned action strings (OD8/OD14/OD15): other nodes and adb scripts depend on these values. */
public class SendLaterIntentsContractTest {

    @Test
    public void actionsUseThePinnedPrefix() {
        String p = "com.voice2sms.sendlater.action.";
        assertEquals(p + "CAPTURE", SendLaterIntents.ACTION_CAPTURE);
        assertEquals(p + "EDIT", SendLaterIntents.ACTION_EDIT);
        assertEquals(p + "UPCOMING", SendLaterIntents.ACTION_UPCOMING);
        assertEquals(p + "SCHEDULED", SendLaterIntents.ACTION_SCHEDULED);
        assertEquals(p + "STATE_CHANGED", SendLaterIntents.ACTION_STATE_CHANGED);
        assertEquals(p + "SEND_NOW", SendLaterIntents.ACTION_SEND_NOW);
        assertEquals(p + "SEND_ANYWAY", SendLaterIntents.ACTION_SEND_ANYWAY);
        assertEquals(p + "SNOOZE", SendLaterIntents.ACTION_SNOOZE);
        assertEquals(p + "CANCEL", SendLaterIntents.ACTION_CANCEL);
        assertEquals(p + "EXPIRE", SendLaterIntents.ACTION_EXPIRE);
        assertEquals(p + "DEBUG_SEED", SendLaterIntents.ACTION_DEBUG_SEED);
        assertEquals(p + "DEBUG_DUMP", SendLaterIntents.ACTION_DEBUG_DUMP);
        assertEquals(p + "DEBUG_EVALUATE", SendLaterIntents.ACTION_DEBUG_EVALUATE);
        assertEquals(p + "DEBUG_FIRE", SendLaterIntents.ACTION_DEBUG_FIRE);
        assertEquals(p + "DEBUG_INBOUND", SendLaterIntents.ACTION_DEBUG_INBOUND);
    }
}
