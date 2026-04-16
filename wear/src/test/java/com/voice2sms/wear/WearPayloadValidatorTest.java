package com.voice2sms.wear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.voice2sms.wear.WearPayloadValidator.Request;
import com.voice2sms.wear.WearPayloadValidator.ValidationException;

/**
 * Watch-side mirror of the phone's WearPayloadValidatorTest. Both files exercise
 * the same rules — if one drifts, the two devices start accepting different
 * payloads and the protocol breaks. Intentional duplication; keep in lockstep.
 *
 * Additional coverage: {@link WearPayloadValidator#fromFields(String, String, String)}
 * which only exists on the watch side (used after intent parsing to skip JSON).
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

    private static ValidationException expectFieldsReject(String phone, String body, String id) {
        try {
            WearPayloadValidator.fromFields(phone, body, id);
            fail("expected validation error for: " + phone + "/" + body + "/" + id);
            return null;
        } catch (ValidationException e) {
            return e;
        }
    }

    // ---------- happy paths (JSON) ----------

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
    public void uuidRequestId_accepted() throws Exception {
        Request r = WearPayloadValidator.parse(bytes(
                "{\"phone\":\"+15551234567\",\"body\":\"hi\","
                        + "\"requestId\":\"a1b2c3d4-5678-90ab-cdef-1234567890ab\"}"));
        assertNotNull(r.requestId);
    }

    // ---------- rejects (JSON) ----------

    @Test
    public void emptyBytes_reject() {
        assertEquals("empty_payload", expectReject("").getMessage());
    }

    @Test
    public void notJson_reject() {
        assertEquals("malformed_json", expectReject("this is not json").getMessage());
    }

    @Test
    public void missingPhone_reject() {
        assertEquals("missing_phone",
                expectReject("{\"body\":\"hi\",\"requestId\":\"r1\"}").getMessage());
    }

    @Test
    public void alphaPhone_reject() {
        assertEquals("invalid_phone",
                expectReject("{\"phone\":\"+1call-now\",\"body\":\"hi\",\"requestId\":\"r1\"}").getMessage());
    }

    @Test
    public void missingBody_reject() {
        assertEquals("missing_body",
                expectReject("{\"phone\":\"+15551234567\",\"requestId\":\"r1\"}").getMessage());
    }

    @Test
    public void bodyOver1600_reject() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1601; i++) sb.append('a');
        String json = "{\"phone\":\"+15551234567\",\"body\":\""
                + sb + "\",\"requestId\":\"r1\"}";
        assertEquals("body_too_long", expectReject(json).getMessage());
    }

    @Test
    public void requestIdOver64Chars_reject() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65; i++) sb.append('a');
        String json = "{\"phone\":\"+15551234567\",\"body\":\"hi\",\"requestId\":\""
                + sb + "\"}";
        assertEquals("invalid_requestId", expectReject(json).getMessage());
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
        String src = "a\u2066b\u2067c\u2068d\u2069e";
        assertEquals("abcde", WearPayloadValidator.sanitizeBody(src));
    }

    @Test
    public void tabLfCrPreserved() {
        String src = "line1\nline2\ttab\rcr";
        assertEquals(src, WearPayloadValidator.sanitizeBody(src));
    }

    @Test
    public void unicodeTextPreserved() {
        String src = "héllo 👋 world";
        assertEquals(src, WearPayloadValidator.sanitizeBody(src));
    }

    // ---------- fromFields (watch-only entrypoint) ----------

    @Test
    public void fromFields_happyPath() throws Exception {
        Request r = WearPayloadValidator.fromFields(
                "+15551234567", "hello", "req-xyz-1");
        assertEquals("+15551234567", r.phone);
        assertEquals("hello", r.body);
        assertEquals("req-xyz-1", r.requestId);
    }

    @Test
    public void fromFields_trimsPhone() throws Exception {
        Request r = WearPayloadValidator.fromFields(
                "  +15551234567  ", "hi", "r1");
        assertEquals("+15551234567", r.phone);
    }

    @Test
    public void fromFields_sanitizesBody() throws Exception {
        // Bidi override must be stripped even on the fromFields path.
        Request r = WearPayloadValidator.fromFields(
                "+15551234567", "hello \u202Eworld", "r1");
        assertEquals("hello world", r.body);
    }

    @Test
    public void fromFields_nullPhone_reject() {
        assertEquals("missing_phone",
                expectFieldsReject(null, "hi", "r1").getMessage());
    }

    @Test
    public void fromFields_whitespacePhone_reject() {
        assertEquals("missing_phone",
                expectFieldsReject("   ", "hi", "r1").getMessage());
    }

    @Test
    public void fromFields_alphaPhone_reject() {
        assertEquals("invalid_phone",
                expectFieldsReject("5551234abc", "hi", "r1").getMessage());
    }

    @Test
    public void fromFields_nullBody_reject() {
        assertEquals("missing_body",
                expectFieldsReject("+15551234567", null, "r1").getMessage());
    }

    @Test
    public void fromFields_emptyBody_reject() {
        assertEquals("empty_body",
                expectFieldsReject("+15551234567", "", "r1").getMessage());
    }

    @Test
    public void fromFields_bodyOver1600_reject() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1601; i++) sb.append('a');
        assertEquals("body_too_long",
                expectFieldsReject("+15551234567", sb.toString(), "r1").getMessage());
    }

    @Test
    public void fromFields_nullRequestId_reject() {
        assertEquals("missing_requestId",
                expectFieldsReject("+15551234567", "hi", null).getMessage());
    }

    @Test
    public void fromFields_invalidRequestId_reject() {
        assertEquals("invalid_requestId",
                expectFieldsReject("+15551234567", "hi", "has space").getMessage());
    }
}
