package com.voice2sms.sendlater.listener;

import android.content.Context;
import android.content.SharedPreferences;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.store.SendLaterRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** {@link ListenerEventStore} over the Room-backed {@link SendLaterRepository}. Off the main thread only. */
final class RepositoryListenerStore implements ListenerEventStore {

    /** Prefs file shared with {@link SendLaterListenerStatus}. */
    private static final String PREFS = "sendlater_listener";
    /** SHA-256 of conversation keys matched by number (OD22(5)); never the numbers themselves. */
    private static final String KEY_NUMBER_MATCHES = "number_matches";
    /** Bound on the durable set; past it the set restarts (it only ever errs toward UNVERIFIED). */
    static final int MAX_NUMBER_MATCHES = 500;

    private final Context app;
    private final SendLaterRepository repo;

    RepositoryListenerStore(Context context) {
        this.app = context.getApplicationContext();
        this.repo = SendLaterRepository.get(context);
    }

    @Override public void noteNumberMatch(String conversationKey) {
        String h = hash(conversationKey);
        if (h == null) return;
        try {
            SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> cur = p.getStringSet(KEY_NUMBER_MATCHES, null);
            if (cur != null && cur.contains(h)) return;
            Set<String> next = cur == null || cur.size() >= MAX_NUMBER_MATCHES
                    ? new HashSet<String>() : new HashSet<>(cur);
            next.add(h);
            p.edit().putStringSet(KEY_NUMBER_MATCHES, next).apply();
        } catch (RuntimeException ignored) {
            // The stored events still back hasNumberMatch.
        }
    }

    @Override public boolean hasNumberMatch(String conversationKey) {
        String h = hash(conversationKey);
        try {
            Set<String> cur = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getStringSet(KEY_NUMBER_MATCHES, null);
            if (h != null && cur != null && cur.contains(h)) return true;
        } catch (RuntimeException ignored) {
            // fall back to the events
        }
        List<ThreadEvent> e = repo.eventsSince(conversationKey, 0L);
        return e != null && !e.isEmpty();
    }

    /** Lowercase hex SHA-256 of {@code key}, or null. */
    static String hash(String key) {
        if (key == null) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override public List<ScheduledMessage> liveMessages() {
        List<ScheduledMessage> out = new ArrayList<>();
        out.addAll(repo.allPending());
        out.addAll(repo.allHeld());
        out.addAll(repo.inFlight());
        return out;
    }

    @Override public long recordEvent(ConversationRef ref, ThreadEvent event, String source, long recordedAt) {
        return repo.recordEvent(ref, event, source, recordedAt);
    }

    @Override public List<ThreadEvent> eventsSince(String conversationKey, long sinceMs) {
        return repo.eventsSince(conversationKey, sinceMs);
    }

    @Override public int purgeEvents(long olderThanMs, boolean orphans) {
        return repo.purgeEvents(olderThanMs, orphans);
    }
}
