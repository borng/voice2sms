package com.voice2sms.sendlater.listener;

import android.content.Context;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.store.SendLaterRepository;

import java.util.ArrayList;
import java.util.List;

/** {@link ListenerEventStore} over the Room-backed {@link SendLaterRepository}. Off the main thread only. */
final class RepositoryListenerStore implements ListenerEventStore {

    private final SendLaterRepository repo;

    RepositoryListenerStore(Context context) {
        this.repo = SendLaterRepository.get(context);
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
