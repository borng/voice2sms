package com.voice2sms.sendlater.engine;

import android.content.Context;

import androidx.core.app.NotificationManagerCompat;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEventSource;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.store.SendLaterRepository;

import java.util.List;

/**
 * Store-backed thread source: the ThreadEventEntity rows the notification listener (and the
 * on-screen reader) record. Available only while the notification listener is enabled, since an
 * empty read is evidence of "no reply" only then. Reads a little before the watermark; the
 * classifier applies the exact window.
 */
final class NotificationEventSource implements ThreadEventSource {

    /** Read this much before the watermark (covers the classifier's 90 s margin plus skew). */
    static final long READ_SLACK_MS = 15 * 60_000L;

    private final Context context;

    NotificationEventSource(Context context) {
        this.context = context;
    }

    static boolean listenerEnabled(Context context) {
        try {
            return NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override public boolean isAvailable() {
        return listenerEnabled(context);
    }

    @Override public List<ThreadEvent> readSince(ConversationRef ref, Watermark wm) {
        return SendLaterRepository.get(context).eventsSince(ref.key(), sinceMs(wm));
    }

    static long sinceMs(Watermark wm) {
        if (wm == null) return 0L;
        long floor = wm.getCapturedAtDevice();
        if (wm.getNewestTs() != null) floor = Math.min(floor, wm.getNewestTs());
        long skew = wm.getSkewEstimateMs() != null ? Math.abs(wm.getSkewEstimateMs()) : 0L;
        return Math.max(0L, floor - READ_SLACK_MS - skew);
    }
}
