package com.voice2sms.wear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.voice2sms.wear.SendToIntentParser.Parsed;

/**
 * Edge-case coverage for {@link SendToIntentParser}. These map the ways
 * Google Assistant / resolver pickers can shape ACTION_SENDTO intents the
 * Wear activity will see in the wild.
 *
 * Pure JVM — no Android runtime dependency.
 */
public class SendToIntentParseTest {

    @Test
    public void plainPhone_withBodyExtra() {
        Parsed p = SendToIntentParser.parse("+15551234567", "testing 1 2 3");
        assertEquals("+15551234567", p.phone);
        assertEquals("testing 1 2 3", p.body);
    }

    @Test
    public void domesticPhone_withBodyExtra() {
        Parsed p = SendToIntentParser.parse("5551234567", "hi");
        assertEquals("5551234567", p.phone);
        assertEquals("hi", p.body);
    }

    @Test
    public void opaqueUri_bodyInQueryString() {
        // Assistant occasionally writes body into the opaque URI instead of an extra.
        Parsed p = SendToIntentParser.parse("+15551234567?body=hi%20there", null);
        assertEquals("+15551234567", p.phone);
        assertEquals("hi there", p.body);
    }

    @Test
    public void bodyExtraWinsOverQueryString() {
        // If both are present the extra is the authoritative source.
        Parsed p = SendToIntentParser.parse(
                "+15551234567?body=from-query", "from-extra");
        assertEquals("+15551234567", p.phone);
        assertEquals("from-extra", p.body);
    }

    @Test
    public void queryStringBody_urlDecoded() {
        Parsed p = SendToIntentParser.parse(
                "+15551234567?body=hello%20%F0%9F%91%8B%20world", null);
        assertEquals("hello 👋 world", p.body);
    }

    @Test
    public void queryStringBody_trailingParamsIgnored() {
        // Only the "body" key matters; trailing params shouldn't pollute it.
        Parsed p = SendToIntentParser.parse(
                "+15551234567?body=hi&foo=bar", null);
        assertEquals("hi", p.body);
    }

    @Test
    public void queryStringBody_withAmpersandInValue_splitsAtAmpersand() {
        // URL spec: ampersand ends the value. Senders should encode & as %26.
        Parsed p = SendToIntentParser.parse(
                "+15551234567?body=tea%20%26%20crumpets", null);
        assertEquals("tea & crumpets", p.body);
    }

    @Test
    public void phone_trimsWhitespace() {
        Parsed p = SendToIntentParser.parse("  +15551234567  ", "hi");
        assertEquals("+15551234567", p.phone);
    }

    @Test
    public void missingBody_returnsNull() {
        // Validator turns this into "missing_body"; parser just doesn't invent one.
        Parsed p = SendToIntentParser.parse("+15551234567", null);
        assertEquals("+15551234567", p.phone);
        assertNull(p.body);
    }

    @Test
    public void nullSsp_phoneNull() {
        Parsed p = SendToIntentParser.parse(null, "body");
        assertNull(p.phone);
        assertEquals("body", p.body);
    }

    @Test
    public void emptySsp_phoneNull() {
        Parsed p = SendToIntentParser.parse("", "body");
        assertNull(p.phone);
    }

    @Test
    public void sspWithOnlyQuery_phoneNull() {
        // "?body=hi" with no phone — parser shouldn't invent an empty phone.
        Parsed p = SendToIntentParser.parse("?body=hi", null);
        assertNull(p.phone);
        assertEquals("hi", p.body);
    }

    @Test
    public void queryWithoutBodyKey_returnsNull() {
        Parsed p = SendToIntentParser.parse("+15551234567?foo=bar", null);
        assertEquals("+15551234567", p.phone);
        assertNull(p.body);
    }

    @Test
    public void ampersandSeparator_phoneCutCorrectly() {
        // Rare: some senders use & as first separator instead of ?.
        Parsed p = SendToIntentParser.parse("+15551234567&body=hi", null);
        assertEquals("+15551234567", p.phone);
    }
}
