package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.SendLaterSettings.Parse;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.PresetSlots;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SendLaterSettingsTest {

    @Test public void keysArePinned() {
        assertEquals("sendlater_enabled", SendLaterSettings.KEY_ENABLED);
        assertEquals("sendlater_slot_morning", SendLaterSettings.KEY_SLOT_MORNING);
        assertEquals("sendlater_slot_afternoon", SendLaterSettings.KEY_SLOT_AFTERNOON);
        assertEquals("sendlater_slot_evening", SendLaterSettings.KEY_SLOT_EVENING);
        assertEquals("sendlater_slot_night", SendLaterSettings.KEY_SLOT_NIGHT);
        assertEquals("sendlater_outbound_policy", SendLaterSettings.KEY_OUTBOUND_POLICY);
        assertEquals("sendlater_hold_default", SendLaterSettings.KEY_HOLD_DEFAULT);
        assertEquals("sendlater_clear_draft", SendLaterSettings.KEY_CLEAR_DRAFT);
    }

    @Test public void defaultsFromAnEmptyStore() {
        Map<String, Object> m = Collections.emptyMap();
        assertArrayEquals(new int[] {8 * 60, 13 * 60, 18 * 60, 21 * 60}, Parse.slotMinutes(m));
        assertArrayEquals(PresetSlots.DEFAULT_SLOT_MINUTES, Parse.slotMinutes(null));
        assertEquals(OutboundPolicy.STILL_SEND, Parse.outboundPolicy(m));
        assertTrue(Parse.holdDefault(m));
        assertTrue(Parse.clearDraft(m));
        assertFalse(Parse.enabled(m));
        assertFalse(Parse.enabled(null));
    }

    @Test public void storedValuesWin() {
        Map<String, Object> m = new HashMap<>();
        m.put(SendLaterSettings.KEY_ENABLED, true);
        m.put(SendLaterSettings.KEY_SLOT_MORNING, 450);
        m.put(SendLaterSettings.KEY_SLOT_AFTERNOON, "720");
        m.put(SendLaterSettings.KEY_SLOT_EVENING, 1140L);
        m.put(SendLaterSettings.KEY_OUTBOUND_POLICY, "HOLD_AND_ASK");
        m.put(SendLaterSettings.KEY_HOLD_DEFAULT, false);
        m.put(SendLaterSettings.KEY_CLEAR_DRAFT, "false");
        assertTrue(Parse.enabled(m));
        assertArrayEquals(new int[] {450, 720, 1140, 1260}, Parse.slotMinutes(m));
        assertEquals(OutboundPolicy.HOLD_AND_ASK, Parse.outboundPolicy(m));
        assertFalse(Parse.holdDefault(m));
        assertFalse(Parse.clearDraft(m));
    }

    @Test public void garbageFallsBackToDefaults() {
        Map<String, Object> m = new HashMap<>();
        m.put(SendLaterSettings.KEY_ENABLED, "yes");
        m.put(SendLaterSettings.KEY_SLOT_MORNING, "8:00");
        m.put(SendLaterSettings.KEY_SLOT_AFTERNOON, 1440);
        m.put(SendLaterSettings.KEY_SLOT_EVENING, -1);
        m.put(SendLaterSettings.KEY_SLOT_NIGHT, 3.5f);
        m.put(SendLaterSettings.KEY_OUTBOUND_POLICY, "sometimes");
        m.put(SendLaterSettings.KEY_HOLD_DEFAULT, 1);
        assertFalse(Parse.enabled(m));
        assertArrayEquals(new int[] {480, 780, 1080, 1260}, Parse.slotMinutes(m));
        assertEquals(OutboundPolicy.STILL_SEND, Parse.outboundPolicy(m));
        assertTrue(Parse.holdDefault(m));
    }
}
