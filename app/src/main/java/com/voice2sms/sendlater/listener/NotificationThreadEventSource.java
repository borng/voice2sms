package com.voice2sms.sendlater.listener;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEventSource;
import com.voice2sms.sendlater.domain.Watermark;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread source over the listener's stored events (R32). Available only while the notification
 * listener is enabled and bound (an empty read means "no reply" only then). Reads from a little
 * before the watermark; the classifier applies the exact window. Pure JVM; the production
 * {@link Availability} is {@link SendLaterListenerStatus#isAvailable(android.content.Context)}.
 */
public final class NotificationThreadEventSource implements ThreadEventSource {

    /** Whether the listener is enabled in Settings and bound recently. */
    public interface Availability {
        boolean isAvailable();
    }

    /** Read this much before the watermark (covers the classifier's 90 s margin plus skew). */
    public static final long READ_SLACK_MS = 15 * 60_000L;

    private final ListenerEventStore store;
    private final Availability availability;

    public NotificationThreadEventSource(ListenerEventStore store, Availability availability) {
        this.store = store;
        this.availability = availability;
    }

    /** Production wiring: the Room store and the live listener status. */
    public static NotificationThreadEventSource create(final android.content.Context context) {
        final android.content.Context app = context.getApplicationContext();
        return new NotificationThreadEventSource(new RepositoryListenerStore(app),
                () -> SendLaterListenerStatus.isAvailable(app));
    }

    @Override public boolean isAvailable() {
        try {
            return availability != null && availability.isAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override public List<ThreadEvent> readSince(ConversationRef ref, Watermark wm) {
        if (ref == null) return new ArrayList<>();
        List<ThreadEvent> got = store.eventsSince(ref.key(), sinceMs(wm));
        return got != null ? got : new ArrayList<ThreadEvent>();
    }

    static long sinceMs(Watermark wm) {
        if (wm == null) return 0L;
        long floor = wm.getCapturedAtDevice();
        if (wm.getNewestTs() != null) floor = Math.min(floor, wm.getNewestTs());
        long skew = wm.getSkewEstimateMs() != null ? Math.abs(wm.getSkewEstimateMs()) : 0L;
        return Math.max(0L, floor - READ_SLACK_MS - skew);
    }
}
