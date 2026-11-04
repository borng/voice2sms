package com.voice2sms.sendlater.domain;

/**
 * One message or call seen in a conversation (R32). Immutable. {@code text} is optional and must
 * never be logged; {@code textHash} is whatever stable hash the recording source uses.
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
