package com.voice2sms.sendlater.listener;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * In-memory gate of the notification listener (R40, F3): the E.164 values and display-name keys of
 * every SCHEDULED, HELD and IN_FLIGHT message. A parsed notification reaches the store only when
 * {@link #mayMatch} says it could belong to one of them, so a Voice notification for any other
 * conversation costs no database work.
 *
 * <p>Matching mirrors {@link ListenerEventRecorder}: by E.164 when the notification carries a
 * number, otherwise by the {@link ConversationRef} name key of its conversation title. The snapshot
 * is immutable; the process-wide copy is replaced off the main thread on listener connect and on
 * ACTION_STATE_CHANGED / ACTION_SCHEDULED. A null current copy means "unknown" and the recorder
 * falls back to the store (fail toward detection). Pure JVM; never log its contents.
 */
public final class LiveConversationKeys {

    private static volatile LiveConversationKeys current;

    private final Set<String> e164s;
    private final Set<String> nameKeys;

    private LiveConversationKeys(Set<String> e164s, Set<String> nameKeys) {
        this.e164s = Collections.unmodifiableSet(e164s);
        this.nameKeys = Collections.unmodifiableSet(nameKeys);
    }

    /** Snapshot of the non-terminal messages in {@code live}; a null list gives an empty gate. */
    public static LiveConversationKeys of(List<ScheduledMessage> live) {
        Set<String> e164s = new HashSet<>();
        Set<String> names = new HashSet<>();
        if (live != null) {
            for (ScheduledMessage m : live) {
                if (m == null || m.getStatus() == null || m.getStatus().isTerminal()) continue;
                if (m.getRecipientE164() != null) e164s.add(m.getRecipientE164());
                String nk = nameKey(m.getRecipientDisplayName());
                if (nk != null) names.add(nk);
            }
        }
        return new LiveConversationKeys(e164s, names);
    }

    /**
     * Rebuilds the process-wide gate from {@code store} (one store read). On a store failure the
     * gate becomes unknown (null), so notifications go to the store rather than being dropped.
     * Call off the main thread, on the listener's worker so it orders with recording.
     */
    public static LiveConversationKeys rebuild(ListenerEventStore store) {
        LiveConversationKeys next;
        try {
            next = of(store.liveMessages());
        } catch (RuntimeException e) {
            next = null;
        }
        current = next;
        return next;
    }

    /** The process-wide gate, or null when it has not been built (or the last build failed). */
    public static LiveConversationKeys current() {
        return current;
    }

    /** Forgets the process-wide gate; the next notification goes to the store until a rebuild. */
    public static void invalidate() {
        current = null;
    }

    /** Whether {@code p} could belong to a live message; false means the store need not be asked. */
    public boolean mayMatch(ParsedEvent p) {
        if (p == null) return false;
        if (p.getNumber() != null) return e164s.contains(p.getNumber());
        String nk = nameKey(p.getConversationTitle());
        return nk != null && nameKeys.contains(nk);
    }

    public boolean isEmpty() {
        return e164s.isEmpty() && nameKeys.isEmpty();
    }

    /** ConversationRef's normalized name key, or null for a blank name (as the recorder does). */
    static String nameKey(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        return new ConversationRef(null, name).key();
    }

    @Override public String toString() {
        return "LiveConversationKeys{numbers=" + e164s.size() + ", names=" + nameKeys.size() + "}";
    }
}
