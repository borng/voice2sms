package com.voice2sms.sendlater.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * One message or call seen in a conversation (R32). Immutable. {@code text} is optional and must
 * never be logged; {@code textHash} is {@link #hashOf(String)} of the text (OD20(5)): lowercase hex
 * SHA-256 of the classifier's normalized text, so a hash-only event can still be matched.
 */
public final class ThreadEvent {

    public enum Direction { IN, OUT }

    public enum Kind { SMS, MMS, CALL, VOICEMAIL }

    private final Direction direction;
    private final Kind kind;
    private final long timeMs;
    private final String textHash;
    private final String text;

    public ThreadEvent(Direction direction, Kind kind, long timeMs, String textHash, String text) {
        if (direction == null) throw new IllegalArgumentException("direction");
        if (kind == null) throw new IllegalArgumentException("kind");
        this.direction = direction;
        this.kind = kind;
        this.timeMs = timeMs;
        this.textHash = textHash;
        this.text = text;
    }

    public Direction getDirection() { return direction; }
    public Kind getKind() { return kind; }
    public long getTimeMs() { return timeMs; }
    public String getTextHash() { return textHash; }
    public String getText() { return text; }

    /**
     * Lowercase hex SHA-256 of {@code text} normalized as the classifier does (case-folded,
     * diacritics stripped, split into letter/digit tokens, joined by single spaces, UTF-8).
     * Null for null text.
     */
    public static String hashOf(String text) {
        if (text == null) return null;
        StringBuilder norm = new StringBuilder();
        for (String t : TextNorm.tokens(text)) {
            if (norm.length() > 0) norm.append(' ');
            norm.append(t);
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(norm.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    public boolean isInbound() { return direction == Direction.IN; }

    /** Calls and voicemails (D7): the other side reached out without texting. */
    public boolean isCallLike() { return kind == Kind.CALL || kind == Kind.VOICEMAIL; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ThreadEvent)) return false;
        ThreadEvent e = (ThreadEvent) o;
        return direction == e.direction && kind == e.kind && timeMs == e.timeMs
                && Objects2.eq(textHash, e.textHash) && Objects2.eq(text, e.text);
    }

    @Override public int hashCode() {
        return Objects2.hash(direction, kind, timeMs, textHash, text);
    }

    /** Never includes the text. */
    @Override public String toString() {
        return "ThreadEvent{" + direction + " " + kind + " @" + timeMs + "}";
    }
}
