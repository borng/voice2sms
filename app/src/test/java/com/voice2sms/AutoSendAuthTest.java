package com.voice2sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

/**
 * sec-main-high: force_auto_send from an outside caller must be ignored; only this process's
 * AutoSendAuth token makes it count. Pure JVM (the decision functions take no Android types).
 */
public class AutoSendAuthTest {

    @Test public void externalForceAutoSendWithoutTokenIsIgnored() {
        assertFalse(AutoSendAuth.authorized(true, null));
    }

    @Test public void matchingTokenIsHonoured() {
        assertTrue(AutoSendAuth.authorized(true, AutoSendAuth.token()));
        // A fresh String with the same content (as after an intent round trip through a Parcel).
        assertTrue(AutoSendAuth.authorized(true, new String(AutoSendAuth.token().toCharArray())));
    }

    @Test public void wrongTokenIsIgnored() {
        String t = AutoSendAuth.token();
        char last = t.charAt(t.length() - 1);
        String flipped = t.substring(0, t.length() - 1) + (last == '0' ? '1' : '0');
        assertFalse(AutoSendAuth.authorized(true, flipped));
        assertFalse(AutoSendAuth.authorized(true, AutoSendAuth.newToken(new Random(7))));
        assertFalse("prefix", AutoSendAuth.authorized(true, t.substring(0, t.length() - 1)));
        assertFalse("longer", AutoSendAuth.authorized(true, t + "0"));
        String upper = t.toUpperCase(java.util.Locale.ROOT);
        if (!upper.equals(t)) assertFalse("case-sensitive", AutoSendAuth.authorized(true, upper));
    }

    @Test public void missingOrEmptyTokenIsIgnored() {
        assertFalse(AutoSendAuth.authorized(true, ""));
        assertFalse(AutoSendAuth.authorized(true, null));
    }

    @Test public void tokenAloneWithoutTheFlagDoesNotAutoSend() {
        assertFalse(AutoSendAuth.authorized(false, AutoSendAuth.token()));
        assertFalse(AutoSendAuth.authorized(false, null));
    }

    @Test public void matchesHandlesNullsAndEmptyExpected() {
        assertFalse(AutoSendAuth.matches(null, "a"));
        assertFalse(AutoSendAuth.matches("a", null));
        assertFalse(AutoSendAuth.matches("", ""));
        assertTrue(AutoSendAuth.matches("abc", "abc"));
        assertFalse(AutoSendAuth.matches("abc", "abd"));
    }

    @Test public void tokenIsLongRandomHex() {
        String t = AutoSendAuth.token();
        assertEquals(64, t.length());
        assertTrue(t.matches("[0-9a-f]{64}"));
        assertEquals("stable within a process", t, AutoSendAuth.token());
        assertNotEquals(AutoSendAuth.newToken(new Random(1)), AutoSendAuth.newToken(new Random(2)));
        assertEquals("deterministic for a seeded source",
                AutoSendAuth.newToken(new Random(3)), AutoSendAuth.newToken(new Random(3)));
    }
}
