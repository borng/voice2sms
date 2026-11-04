package com.voice2sms.sendlater.voice.overlay;

import android.content.Context;
import android.util.Log;

import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.engine.SendLaterEngine;
import com.voice2sms.sendlater.store.SendLaterRepository;

/**
 * {@link VoiceDraftFlow.Records} over the repository. Off the main thread only: the engine's
 * cancel then runs inline (alarms, notifications and the state broadcast included).
 */
final class RepositoryRecords implements VoiceDraftFlow.Records {

    private static final String TAG = "Voice2SMS-SLV";

    private final Context app;
    private volatile boolean lastCancelApplied;

    RepositoryRecords(Context app) {
        this.app = app.getApplicationContext();
    }

    private static boolean isOpen(ScheduledMessage m) {
        return m != null && (m.getStatus() == ScheduledMessage.Status.SCHEDULED
                || m.getStatus() == ScheduledMessage.Status.HELD);
    }

    @Override public boolean isPersisted(long id) {
        try {
            return isOpen(SendLaterRepository.get(app).get(id));
        } catch (RuntimeException e) {
            Log.w(TAG, "record check failed: " + e.getClass());
            return false;
        }
    }

    @Override public boolean cancel(long id) {
        lastCancelApplied = false;
        try {
            SendLaterRepository repo = SendLaterRepository.get(app);
            if (!isOpen(repo.get(id))) return false;
            SendLaterEngine.onCancel(app, id);
            ScheduledMessage after = repo.get(id);
            lastCancelApplied = after != null && after.getStatus() == ScheduledMessage.Status.CANCELED;
            return lastCancelApplied;
        } catch (RuntimeException e) {
            Log.w(TAG, "cancel failed: " + e.getClass());
            return false;
        }
    }

    /** Whether the last {@link #cancel} took effect. */
    boolean lastCancelApplied() {
        return lastCancelApplied;
    }
}
