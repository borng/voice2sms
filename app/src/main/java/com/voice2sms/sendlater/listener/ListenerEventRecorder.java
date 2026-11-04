package com.voice2sms.sendlater.listener;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.store.SendLaterRepository;
import com.voice2sms.sendlater.store.ThreadEventEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Store policy of the notification listener (R32-R34). Pure JVM over {@link ListenerEventStore}.
 *
 * <ul>
 *   <li>A parsed event is stored only when a SCHEDULED, HELD or IN_FLIGHT message matches its
 *       conversation (R34): by E.164 when the notification carries a number, otherwise by display
 *       name ({@code ConversationRef} name normalization) against {@code recipientDisplayName}.</li>
 *   <li>It is stored under the matched message's {@code ConversationRef.of(m).key()}, so the thread
 *       check finds it with {@code readSince(ConversationRef.of(m), wm)}; then the early-hold sink
 *       gets that key (R33).</li>
 *   <li>Stored fields are what R32 needs: direction, kind, time and textHash. The text itself is not
 *       kept (inbound events need no text to classify).</li>
 *   <li>UNKNOWN kinds (T4) are dropped. A repost of an event already stored is not stored again.</li>
 *   <li>A match by number is noted ({@link ListenerEventStore#noteNumberMatch}) so the thread
 *       source can vouch for a message that has no display name (OD22(5)).</li>
 *   <li>With a {@link LiveConversationKeys} gate, an event no live key matches returns before any
 *       store call (R40). Without one (null: not built yet) the store decides, as before.</li>
 * </ul>
 */
public final class ListenerEventRecorder {

    private final ListenerEventStore store;
    private final InboundSink sink;
    private final LiveConversationKeys gate;

    /** Ungated: every parsed event asks the store for live messages. */
    public ListenerEventRecorder(ListenerEventStore store, InboundSink sink) {
        this(store, sink, null);
    }

    /** {@code gate} null = ungated; otherwise a miss makes no store call (R40). */
    public ListenerEventRecorder(ListenerEventStore store, InboundSink sink, LiveConversationKeys gate) {
        this.store = store;
        this.sink = sink;
        this.gate = gate;
    }

    /** Parses and, when the policy allows, stores the event and fires the early hold. True = stored. */
    public boolean onNotification(NotificationFields fields, long nowMs) {
        ParsedEvent p = NotificationParser.parse(fields);
        if (p == null) return false;
        ThreadEvent.Kind kind = toKind(p.getKind());
        if (kind == null) return false;
        if (gate != null && !gate.mayMatch(p)) return false;

        Map<String, ConversationRef> matched = matchLive(p);
        if (matched.isEmpty()) return false;

        long time = p.getTimeMs() > 0 ? p.getTimeMs() : nowMs;
        ThreadEvent event = new ThreadEvent(ThreadEvent.Direction.IN, kind, time,
                ThreadEvent.hashOf(p.getText()), null);

        boolean stored = false;
        for (Map.Entry<String, ConversationRef> e : matched.entrySet()) {
            // OD22(5): the listener has now seen this conversation by its number.
            if (p.getNumber() != null) store.noteNumberMatch(e.getKey());
            if (alreadyStored(e.getKey(), event)) continue;
            store.recordEvent(e.getValue(), event, ThreadEventEntity.SOURCE_LISTENER, nowMs);
            stored = true;
            if (sink != null) sink.onInboundEvent(e.getKey(), event);
        }
        return stored;
    }

    /** Drops events older than the retention window and events of conversations with no live message. */
    public int purge(long nowMs) {
        return store.purgeEvents(nowMs - SendLaterRepository.EVENT_RETENTION_MS, true);
    }

    // ------------------------------------------------------------------ helpers

    /** Conversation key -> the ref to store under, for every live message this event belongs to. */
    private Map<String, ConversationRef> matchLive(ParsedEvent p) {
        Map<String, ConversationRef> out = new LinkedHashMap<>();
        List<ScheduledMessage> live = store.liveMessages();
        if (live == null) return out;
        String number = p.getNumber();
        String nameKey = nameKey(p.getConversationTitle());
        for (ScheduledMessage m : live) {
            if (m == null || m.getStatus() == null || m.getStatus().isTerminal()) continue;
            boolean match;
            if (number != null) {
                match = number.equals(m.getRecipientE164());
            } else {
                String mk = nameKey(m.getRecipientDisplayName());
                match = nameKey != null && nameKey.equals(mk);
            }
            if (!match) continue;
            ConversationRef ref = ConversationRef.of(m);
            if (!out.containsKey(ref.key())) out.put(ref.key(), ref);
        }
        return out;
    }

    private boolean alreadyStored(String key, ThreadEvent event) {
        List<ThreadEvent> same = store.eventsSince(key, event.getTimeMs());
        if (same == null) return false;
        for (ThreadEvent e : same) {
            if (e.getTimeMs() == event.getTimeMs() && e.getKind() == event.getKind()
                    && e.getDirection() == event.getDirection()
                    && eq(e.getTextHash(), event.getTextHash())) {
                return true;
            }
        }
        return false;
    }

    /** ConversationRef's normalized name key, or null for a blank name. */
    private static String nameKey(String name) {
        return LiveConversationKeys.nameKey(name);
    }

    static ThreadEvent.Kind toKind(ParsedEvent.Kind k) {
        switch (k) {
            case SMS: return ThreadEvent.Kind.SMS;
            case MMS: return ThreadEvent.Kind.MMS;
            case CALL: return ThreadEvent.Kind.CALL;
            case VOICEMAIL: return ThreadEvent.Kind.VOICEMAIL;
            default: return null;
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
