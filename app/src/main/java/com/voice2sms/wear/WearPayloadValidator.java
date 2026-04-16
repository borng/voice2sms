package com.voice2sms.wear;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Validates SMS send requests arriving from the Voice2SMS watch companion.
 *
 * Pure JVM code — no Android dependencies — so this can be unit-tested directly
 * without Robolectric. The service layer ({@link WearSmsListenerService}) wraps
 * this with Wearable DataLayer / notification / intent-dispatch concerns.
 *
 * Expected payload: UTF-8 JSON bytes, shape:
 *   {"phone":"+15551234567","body":"hello","requestId":"uuid-or-similar"}
 *
 * All three fields are REQUIRED. phone must match E.164-ish format; body is
 * sanitized (control chars + bidi overrides stripped) and bounded; requestId
 * must be present (1-64 chars, alnum/dash/underscore) for idempotent dedup
 * in the service layer.
 */
public final class WearPayloadValidator {

    /** Max encoded payload size we will accept (defense-in-depth against junk). */
    static final int MAX_PAYLOAD_BYTES = 8192;
    /** Max body length after sanitization. SMS concat tops out around 1530 GSM chars; 1600 is safe. */
    static final int MAX_BODY_LEN = 1600;
    /** E.164-ish phone. Optional leading +, 7-15 digits. Rejects letters, spaces, separators. */
    static final Pattern PHONE_RE = Pattern.compile("^\\+?[0-9]{7,15}$");
    /** requestId must be 1-64 chars of [A-Za-z0-9_-]. UUIDs and base62 ids both fit. */
    static final Pattern REQUEST_ID_RE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private WearPayloadValidator() { /* static only */ }

    /** Thrown when a payload fails validation. Message is a short reason safe to log/ack. */
    public static final class ValidationException extends Exception {
        public ValidationException(String reason) { super(reason); }
    }

    /** Parsed, validated, sanitized request. Fields are non-null. */
    public static final class Request {
        public final String phone;
        public final String body;
        public final String requestId;
        Request(String phone, String body, String requestId) {
            this.phone = phone;
            this.body = body;
            this.requestId = requestId;
        }
    }

    public static Request parse(byte[] payload) throws ValidationException {
        if (payload == null || payload.length == 0) {
            throw new ValidationException("empty_payload");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new ValidationException("payload_too_large");
        }

        JSONObject obj;
        try {
            obj = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new ValidationException("malformed_json");
        }

        String phone = optNonEmpty(obj, "phone");
        if (phone == null) throw new ValidationException("missing_phone");
        if (!PHONE_RE.matcher(phone).matches()) throw new ValidationException("invalid_phone");

        String rawBody = obj.optString("body", null);
        if (rawBody == null) throw new ValidationException("missing_body");
        String body = sanitizeBody(rawBody);
        if (body.isEmpty()) throw new ValidationException("empty_body");
        if (body.length() > MAX_BODY_LEN) throw new ValidationException("body_too_long");

        String requestId = optNonEmpty(obj, "requestId");
        if (requestId == null) throw new ValidationException("missing_requestId");
        if (!REQUEST_ID_RE.matcher(requestId).matches()) throw new ValidationException("invalid_requestId");

        return new Request(phone, body, requestId);
    }

    private static String optNonEmpty(JSONObject obj, String key) {
        String v = obj.optString(key, null);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * Strip characters that can hide intent from a reviewer or corrupt downstream rendering:
     * ASCII control chars (except tab/LF/CR), DEL, and Unicode bidi override codepoints
     * (U+202A-202E, U+2066-2069). Everything else passes through unchanged.
     */
    public static String sanitizeBody(String body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(c);
                continue;
            }
            if (c < 0x20 || c == 0x7F) continue;
            if ((c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) continue;
            sb.append(c);
        }
        return sb.toString();
    }
}
