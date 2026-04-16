package com.voice2sms.wear;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Watch-side mirror of the phone's {@code WearPayloadValidator}. Same rules,
 * same error strings — the phone's validator is the canonical contract. Kept
 * as a duplicate (not a shared module) while the wear/ surface is tiny; if a
 * third consumer appears, extract to a shared: module.
 *
 * SourceSafetyTest guards the duplication so the two files can't drift on
 * things that matter: regex, error codes, size limits, and sanitizer behavior.
 *
 * Watch side adds {@link #fromFields(String, String, String)} for the
 * post-intent-parse path that never needs JSON decode.
 */
public final class WearPayloadValidator {

    static final int MAX_PAYLOAD_BYTES = 8192;
    static final int MAX_BODY_LEN = 1600;
    static final Pattern PHONE_RE = Pattern.compile("^\\+?[0-9]{7,15}$");
    static final Pattern REQUEST_ID_RE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private WearPayloadValidator() { /* static only */ }

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

    /** JSON-payload entrypoint (parity with the phone-side validator, used by tests). */
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

    /**
     * Builds a Request from already-separated fields. Used on the watch side
     * after the intent has been parsed — we already have phone/body/requestId
     * and want the same validation rules without the JSON decode step.
     */
    public static Request fromFields(String phone, String body, String requestId)
            throws ValidationException {
        if (phone == null || phone.trim().isEmpty()) throw new ValidationException("missing_phone");
        String p = phone.trim();
        if (!PHONE_RE.matcher(p).matches()) throw new ValidationException("invalid_phone");

        if (body == null) throw new ValidationException("missing_body");
        String sanitized = sanitizeBody(body);
        if (sanitized.isEmpty()) throw new ValidationException("empty_body");
        if (sanitized.length() > MAX_BODY_LEN) throw new ValidationException("body_too_long");

        if (requestId == null) throw new ValidationException("missing_requestId");
        if (!REQUEST_ID_RE.matcher(requestId).matches()) throw new ValidationException("invalid_requestId");

        return new Request(p, sanitized, requestId);
    }

    private static String optNonEmpty(JSONObject obj, String key) {
        String v = obj.optString(key, null);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

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
