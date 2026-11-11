package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Acceptance (webview-capture, R10/F7): the 1:1 thread-URL parser shared by the Java side
 * (readThread "is the WebView showing this thread", directory/bridge checks). Mirrors the inject.js
 * gate: itemId read as a query parameter, decoded, accepted only when it matches
 * {@code ^t\.\+\d{7,15}$}; anything else (group t.g.*, no itemId) is not 1:1. Never guess.
 *
 * Contract used here: {@code ThreadUrl.oneToOneE164(String url)} returns the E.164 ("+" + digits)
 * of a 1:1 thread URL, else null. Numbers are fictional 555-01xx.
 */
public class ThreadUrlTest {

    private static final String BASE = "https://voice.google.com/u/0/messages";

    @Test
    public void encodedPlus_accepted() {
        assertEquals("+15555550123", ThreadUrl.oneToOneE164(BASE + "?itemId=t.%2B15555550123"));
    }

    @Test
    public void decodedPlus_accepted() {
        // A literal '+' in a query string decodes to a space under form decoding; the parser must
        // still read it as the E.164 plus.
        assertEquals("+15555550123", ThreadUrl.oneToOneE164(BASE + "?itemId=t.+15555550123"));
    }

    @Test
    public void itemIdAmongOtherParams_accepted() {
        assertEquals("+15555550123",
                ThreadUrl.oneToOneE164(BASE + "?foo=1&itemId=t.%2B15555550123&bar=2"));
    }

    @Test
    public void digitBounds_sevenToFifteen() {
        assertEquals("+5550123", ThreadUrl.oneToOneE164(BASE + "?itemId=t.%2B5550123"));
        assertEquals("+123456789012345", ThreadUrl.oneToOneE164(BASE + "?itemId=t.%2B123456789012345"));
        assertNull(ThreadUrl.oneToOneE164(BASE + "?itemId=t.%2B555012"));
        assertNull(ThreadUrl.oneToOneE164(BASE + "?itemId=t.%2B1234567890123456"));
    }

    @Test
    public void groupThread_rejected() {
        assertNull(ThreadUrl.oneToOneE164(BASE + "?itemId=t.g.Ab12Cd34Ef"));
        assertNull(ThreadUrl.oneToOneE164(BASE + "?itemId=t.g.%2B15555550123"));
    }

    @Test
    public void missingItemId_rejected() {
        assertNull(ThreadUrl.oneToOneE164(BASE));
        assertNull(ThreadUrl.oneToOneE164(BASE + "?foo=bar"));
        assertNull(ThreadUrl.oneToOneE164(BASE + "?itemId="));
        assertNull(ThreadUrl.oneToOneE164("https://voice.google.com/u/0/calls"));
    }

    @Test
    public void malformedIds_rejected() {
        assertNull(ThreadUrl.oneToOneE164(BASE + "?itemId=t.15555550123"));          // no plus
        assertNull(ThreadUrl.oneToOneE164(BASE + "?itemId=t.%2B1555abc0123"));       // letters
        assertNull(ThreadUrl.oneToOneE164(BASE + "?itemId=t.%2B15555550123x"));      // trailing junk
        assertNull(ThreadUrl.oneToOneE164(BASE + "?itemId=t.%2B15555550123,%2B15555550124")); // several
        assertNull(ThreadUrl.oneToOneE164(BASE + "?itemId=%2B15555550123"));         // no t. prefix
    }

    @Test
    public void nullAndEmpty_rejected() {
        assertNull(ThreadUrl.oneToOneE164(null));
        assertNull(ThreadUrl.oneToOneE164(""));
        assertNull(ThreadUrl.oneToOneE164("not a url"));
    }
}
