package com.voice2sms.wear;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Deliberate duplicate of the phone-side {@code com.voice2sms.wear.WearPayloadValidator}.
 * The two files must stay lockstep — SourceSafetyTest guards the shared constants,
 * regexes, and error codes.
 */
public final class WearPayloadValidator {

    static final int MAX_PAYLOAD_BYTES = 8192;
    /** SMS concat tops out around 1530 GSM chars; 1600 leaves headroom. */
    static final int MAX_BODY_LEN = 1600;
    static final Pattern PHONE_RE = Pattern.compile("^\\+?[0-9]{7,15}$");
    static final Pattern REQUEST_ID_RE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private WearPayloadValidator() {}

    public static final class ValidationException extends Exception {
        public ValidationException(String reason) { super(reason); }
    }

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

    /** JSON-payload entrypoint, kept for parity with the phone-side validator. */
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

        return validateFields(
                optNonEmpty(obj, "phone"),
                obj.optString("body", null),
                optNonEmpty(obj, "requestId"));
    }

    /** Watch-side entrypoint: fields are already separated, skip the JSON decode. */
    public static Request fromFields(String phone, String body, String requestId)
            throws ValidationException {
        return validateFields(
                phone == null ? null : phone.trim(),
                body,
                requestId);
    }

    private static Request validateFields(String phone, String body, String requestId)
            throws ValidationException {
        if (phone == null || phone.isEmpty()) throw new ValidationException("missing_phone");
        if (!PHONE_RE.matcher(phone).matches()) throw new ValidationException("invalid_phone");

        if (body == null) throw new ValidationException("missing_body");
        String sanitized = sanitizeBody(body);
        if (sanitized.isEmpty()) throw new ValidationException("empty_body");
        if (sanitized.length() > MAX_BODY_LEN) throw new ValidationException("body_too_long");

        if (requestId == null || requestId.isEmpty()) throw new ValidationException("missing_requestId");
        if (!REQUEST_ID_RE.matcher(requestId).matches()) throw new ValidationException("invalid_requestId");

        return new Request(phone, sanitized, requestId);
    }

    private static String optNonEmpty(JSONObject obj, String key) {
        String v = obj.optString(key, null);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * Strips ASCII control chars (except tab/LF/CR), DEL, and Unicode bidi
     * override codepoints (U+202A-202E, U+2066-2069) that could hide intent
     * from a human reviewer.
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
