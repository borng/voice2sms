package com.voice2sms.sendlater.voice.logic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Conservative E.164 normalizer, default region "US" (R4a). Fictional 555-01xx numbers only. */
public class PhoneNumbersTest {

    @Test
    public void usFormatsNormalize() {
        assertEquals("+14355550142", PhoneNumbers.toE164("(435) 555-0142", "US"));
        assertEquals("+14355550142", PhoneNumbers.toE164("435-555-0142", "US"));
        assertEquals("+14355550142", PhoneNumbers.toE164("4355550142", "US"));
        assertEquals("+14355550142", PhoneNumbers.toE164("1 (435) 555-0142", "US"));
        assertEquals("+14355550142", PhoneNumbers.toE164("+1 435-555-0142", "US"));
        assertEquals("+14355550142", PhoneNumbers.toE164("+14355550142", "US"));
    }

    @Test
    public void namesAndJunkAreNotNumbers() {
        assertNull(PhoneNumbers.toE164("Test Contact", "US"));
        assertNull(PhoneNumbers.toE164("Pharmacy", "US"));
        assertNull(PhoneNumbers.toE164("", "US"));
        assertNull(PhoneNumbers.toE164(null, "US"));
    }

    @Test
    public void incompleteNumbersAreRejected() {
        assertNull(PhoneNumbers.toE164("555-0142", "US"));
        assertNull(PhoneNumbers.toE164("435-555-014", "US"));
    }
}
