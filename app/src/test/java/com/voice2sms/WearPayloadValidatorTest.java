package com.voice2sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.voice2sms.wear.WearPayloadValidator;
import com.voice2sms.wear.WearPayloadValidator.Request;
import com.voice2sms.wear.WearPayloadValidator.ValidationException;

/**
 * Pure-JVM validation tests for Wearable SMS send payloads.
 *
 * The validator is intentionally Android-free so it can be exercised without
 * Robolectric. Every branch that produces a user-visible ack status has a
 * corresponding test here.
 */
public class WearPayloadValidatorTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ValidationException expectReject(String json) {
        try {
            WearPayloadValidator.parse(bytes(json));
            fail("expected validation error for: " + json);
            return null;
        } catch (ValidationException e) {
            return e;
        }
    }

    // ---------- happy paths ----------

    @Test
    public void validE164Phone_parses() throws Exception {
        Request r = WearPayloadValidator.parse(bytes(
                "{\"phone\":\"+15551234567\",\"body\":\"hi\",\"requestId\":\"abc-123\"}"));
        assertEquals("+15551234567", r.phone);
        assertEquals("hi", r.body);
        assertEquals("abc-123", r.requestId);
    }

    @Test
    public void validDomesticPhone_parses() throws Exception {
        Request r = WearPayloadValidator.parse(bytes(
                "{\"phone\":\"5551234567\",\"body\":\"yo\",\"requestId\":\"r1\"}"));
        assertEquals("5551234567", r.phone);
    }

    @Test
    public void bodyAt1600Chars_allowed() throws Exception {
        StringBuilder sb = new StringBuilder(1600);
        for (int i = 0; i < 1600; i++) sb.append('a');
        String json = "{\"phone\":\"+15551234567\",\"body\":\""
                + sb + "\",\"requestId\":\"r1\"}";
        Request r = WearPayloadValidator.parse(bytes(json));
        assertEquals(1600, r.body.length());
    }

    @Test
    public void uuidRequestId_accepted() throws Exception {
        Request r = WearPayloadValidator.parse(bytes(
                "{\"phone\":\"+15551234567\",\"body\":\"hi\","
                        + "\"requestId\":\"a1b2c3d4-5678-90ab-cdef-1234567890ab\"}"));
        assertNotNull(r.requestId);
    }

    // ---------- empty / malformed ----------

    @Test
    public void emptyBytes_reject() {
        assertEquals("empty_payload", expectReject("").getMessage());
    }

    @Test
    public void nullBytes_reject() {
        // WearableListenerService callbacks can hand us null if a data item has no
        // payload set. The validator's null guard must handle this without NPE.
        try {
            WearPayloadValidator.parse(null);
            fail("expected ValidationException for null payload");
        } catch (ValidationException e) {
            assertEquals("empty_payload", e.getMessage());
        }
    }

    @Test
    public void notJson_reject() {
        assertEquals("malformed_json", expectReject("this is not json").getMessage());
    }

    @Test
    public void jsonArrayInsteadOfObject_reject() {
        assertEquals("malformed_json", expectReject("[1,2,3]").getMessage());
    }

    @Test
    public void oversizedPayload_reject() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"phone\":\"+15551234567\",\"body\":\"");
        for (int i = 0; i < 9000; i++) sb.append('x');
        sb.append("\",\"requestId\":\"r1\"}");
        assertEquals("payload_too_large", expectReject(sb.toString()).getMessage());
    }

    // ---------- phone field ----------

    @Test
    public void missingPhone_reject() {
        assertEquals("missing_phone",
                expectReject("{\"body\":\"hi\",\"requestId\":\"r1\"}").getMessage());
    }

    @Test
    public void emptyPhone_reject() {
        assertEquals("missing_phone",
                expectReject("{\"phone\":\"\",\"body\":\"hi\",\"requestId\":\"r1\"}").getMessage());
    }

    @Test
    public void whitespacePhone_reject() {
        assertEquals("missing_phone",
                expectReject("{\"phone\":\"   \",\"body\":\"hi\",\"requestId\":\"r1\"}").getMessage());
    }

    @Test
    public void alphaPhone_reject() {
        assertEquals("invalid_phone",
                expectReject("{\"phone\":\"+1call-now\",\"body\":\"hi\",\"requestId\":\"r1\"}").getMessage());
    }

    @Test
    public void phoneTooShort_reject() {
        assertEquals("invalid_phone",
                expectReject("{\"phone\":\"12345\",\"body\":\"hi\",\"requestId\":\"r1\"}").getMessage());
    }

    @Test
    public void phoneTooLong_reject() {
        assertEquals("invalid_phone",
                expectReject("{\"phone\":\"+1234567890123456\",\"body\":\"hi\",\"requestId\":\"r1\"}").getMessage());
    }

    @Test
    public void phoneWithSpaces_reject() {
        assertEquals("invalid_phone",
                expectReject("{\"phone\":\"+1 555 123 4567\",\"body\":\"hi\",\"requestId\":\"r1\"}").getMessage());
    }

    // ---------- body field ----------

    @Test
    public void missingBody_reject() {
        assertEquals("missing_body",
                expectReject("{\"phone\":\"+15551234567\",\"requestId\":\"r1\"}").getMessage());
    }

    @Test
    public void emptyBody_reject() {
        assertEquals("empty_body",
                expectReject("{\"phone\":\"+15551234567\",\"body\":\"\",\"requestId\":\"r1\"}").getMessage());
    }

    @Test
    public void bodyOnlyControlChars_rejectAsEmpty() {
        // Body of only stripped control chars becomes empty post-sanitize.
        assertEquals("empty_body",
                expectReject("{\"phone\":\"+15551234567\",\"body\":\"\\u0001\\u0002\",\"requestId\":\"r1\"}").getMessage());
    }

    @Test
    public void bodyOver1600_reject() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1601; i++) sb.append('a');
        String json = "{\"phone\":\"+15551234567\",\"body\":\""
                + sb + "\",\"requestId\":\"r1\"}";
        assertEquals("body_too_long", expectReject(json).getMessage());
    }

    // ---------- body sanitization ----------

    @Test
    public void bidiOverrideCharsStripped() {
        // U+202E = RIGHT-TO-LEFT OVERRIDE. Must be stripped.
        String src = "hello \u202Eworld";
        assertEquals("hello world", WearPayloadValidator.sanitizeBody(src));
    }

    @Test
    public void bidiIsolateCharsStripped() {
        // U+2066-U+2069 isolates must also be stripped.
        String src = "a\u2066b\u2067c\u2068d\u2069e";
        assertEquals("abcde", WearPayloadValidator.sanitizeBody(src));
    }

    @Test
    public void tabLfCrPreserved() {
        String src = "line1\nline2\ttab\rcr";
        assertEquals(src, WearPayloadValidator.sanitizeBody(src));
    }

    @Test
    public void delCharStripped() {
        String src = "hi\u007Fthere";
        assertEquals("hithere", WearPayloadValidator.sanitizeBody(src));
    }

    @Test
    public void unicodeTextPreserved() {
        String src = "héllo 👋 world";
        assertEquals(src, WearPayloadValidator.sanitizeBody(src));
    }

    // ---------- requestId field ----------

    @Test
    public void missingRequestId_reject() {
        assertEquals("missing_requestId",
                expectReject("{\"phone\":\"+15551234567\",\"body\":\"hi\"}").getMessage());
    }

    @Test
    public void emptyRequestId_reject() {
        assertEquals("missing_requestId",
                expectReject("{\"phone\":\"+15551234567\",\"body\":\"hi\",\"requestId\":\"\"}").getMessage());
    }

    @Test
    public void requestIdWithSpaces_reject() {
        assertEquals("invalid_requestId",
                expectReject("{\"phone\":\"+15551234567\",\"body\":\"hi\",\"requestId\":\"has space\"}").getMessage());
    }

    @Test
    public void requestIdWithSpecialChars_reject() {
        // Only [A-Za-z0-9_-] allowed.
        assertEquals("invalid_requestId",
                expectReject("{\"phone\":\"+15551234567\",\"body\":\"hi\",\"requestId\":\"a$b\"}").getMessage());
    }

    @Test
    public void requestIdOver64Chars_reject() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65; i++) sb.append('a');
        String json = "{\"phone\":\"+15551234567\",\"body\":\"hi\",\"requestId\":\""
                + sb + "\"}";
        assertEquals("invalid_requestId", expectReject(json).getMessage());
    }
}
