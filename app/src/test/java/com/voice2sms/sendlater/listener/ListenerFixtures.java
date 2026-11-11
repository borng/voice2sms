package com.voice2sms.sendlater.listener;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared fixtures and fakes for the reply-listener tests (R32-R34). No real numbers or names:
 * 555-01xx is the reserved fictional range and the display names are placeholders.
 *
 * <p>Seams these tests pin (the node's test-facing contract):
 * <ul>
 *   <li>{@code NotificationFields(pkg, tag, channelId, category, whenMs, conversationTitle, title,
 *       text, NotificationFields.Message[] messages)} and
 *       {@code NotificationFields.Message(text, timeMs, sender)}: plain value objects.</li>
 *   <li>{@code ListenerEventStore}: the slice of {@code SendLaterRepository} the listener uses,
 *       with the repository's signatures.</li>
 *   <li>{@code InboundSink}: the early-hold hook; production forwards to
 *       {@code SendLaterEngine.onInboundEvent(context, key, event)}.</li>
 *   <li>{@code ListenerEventRecorder(ListenerEventStore, InboundSink)}:
 *       {@code boolean onNotification(NotificationFields, long nowMs)} (true = stored) and
 *       {@code int purge(long nowMs)}.</li>
 *   <li>{@code NotificationThreadEventSource(ListenerEventStore, NotificationThreadEventSource.Availability)}.</li>
 * </ul>
 */
final class ListenerFixtures {

    static final long MIN = 60_000L;
    static final long HOUR = 60 * MIN;
    static final long DAY = 24 * HOUR;

    /** Arbitrary fixed epoch (2026-06-10T12:00Z); only differences matter. */
    static final long T0 = 1_781_092_800_000L;

    static final String GV_PKG = "com.google.android.apps.googlevoice";
    static final String V2S_PKG = "com.voice2sms";
    static final String GV_TEXT_TAG = "TEXT_MESSAGE_NOTIFICATION_TAG";
    static final String GV_TEXT_CHANNEL = "1TXT";
    static final String CATEGORY_MESSAGE = "msg";
    static final String CATEGORY_MISSED_CALL = "missed_call";
    static final String V2S_SMS_CHANNEL = "incoming_sms";

    static final String E164 = "+15555550100";
    static final String OTHER_E164 = "+15555550199";
    static final String NAME = "Test Contact";
    static final String OTHER_NAME = "Another Placeholder";
    static final String SCHEDULED_TEXT = "Running late, be there at 9";
    static final String REPLY_TEXT = "No worries see you soon";
    static final String OLDER_REPLY_TEXT = "Are you on your way";

    private ListenerFixtures() {}

    static NotificationFields.Message msg(String text, long timeMs, String sender) {
        return new NotificationFields.Message(text, timeMs, sender);
    }

    /** The S22 shape (F5): channel 1TXT, tag TEXT_MESSAGE_NOTIFICATION_TAG, category msg, MessagingStyle. */
    static NotificationFields gvText(String conversationTitle, long whenMs, String text,
                                     NotificationFields.Message... messages) {
        return new NotificationFields(GV_PKG, GV_TEXT_TAG, GV_TEXT_CHANNEL, CATEGORY_MESSAGE, whenMs,
                conversationTitle, conversationTitle, text, messages);
    }

    static NotificationFields gvReply(long timeMs) {
        return gvText(NAME, timeMs, REPLY_TEXT, msg(REPLY_TEXT, timeMs, NAME));
    }

    /** SmsReceiver's shape: title = sender address, text = body, category msg, BigTextStyle (no messages). */
    static NotificationFields v2sSms(String address, String body, long whenMs) {
        return new NotificationFields(V2S_PKG, null, V2S_SMS_CHANNEL, CATEGORY_MESSAGE, whenMs,
                null, address, body, new NotificationFields.Message[0]);
    }

    static Watermark timestampWatermark(long capturedAt) {
        return new Watermark(Watermark.Quality.TIMESTAMP, null, capturedAt - 10 * MIN,
                "bubble-hash", capturedAt, null);
    }

    /** A SCHEDULED message created at T0, due 2 h later, hold-if-replied on, TIMESTAMP watermark at T0. */
    static ScheduledMessage.Builder message(long id, String e164, String name) {
        return new ScheduledMessage.Builder()
                .id(id)
                .createdAt(T0)
                .scheduledFor(T0 + 2 * HOUR)
                .text(SCHEDULED_TEXT)
                .recipientE164(e164)
                .recipientDisplayName(name)
                .threadItemId(null)
                .source(ScheduledMessage.Source.NATIVE_GV)
                .rung(Rung.R1)
                .holdIfReplied(true)
                .latenessPolicy(LatenessPolicy.DEFAULT)
                .status(ScheduledMessage.Status.SCHEDULED)
                .lastError(null)
                .attemptCount(0)
                .watermark(timestampWatermark(T0));
    }

    /** In-memory {@link ListenerEventStore}: live messages are whatever the test seeds. */
    static final class FakeStore implements ListenerEventStore {
        final List<ScheduledMessage> live = new ArrayList<>();
        final List<Recorded> recorded = new ArrayList<>();
        final List<long[]> purgeCalls = new ArrayList<>();
        final List<Boolean> purgeOrphans = new ArrayList<>();

        static final class Recorded {
            final ConversationRef ref;
            final ThreadEvent event;
            final String source;
            final long recordedAt;

            Recorded(ConversationRef ref, ThreadEvent event, String source, long recordedAt) {
                this.ref = ref;
                this.event = event;
                this.source = source;
                this.recordedAt = recordedAt;
            }
        }

        FakeStore seed(ScheduledMessage... m) {
            live.addAll(Arrays.asList(m));
            return this;
        }

        @Override public List<ScheduledMessage> liveMessages() {
            return new ArrayList<>(live);
        }

        @Override public long recordEvent(ConversationRef ref, ThreadEvent event, String source, long recordedAt) {
            recorded.add(new Recorded(ref, event, source, recordedAt));
            return recorded.size();
        }

        @Override public List<ThreadEvent> eventsSince(String conversationKey, long sinceMs) {
            List<ThreadEvent> out = new ArrayList<>();
            for (Recorded r : recorded) {
                if (r.ref.key().equals(conversationKey) && r.event.getTimeMs() >= sinceMs) out.add(r.event);
            }
            return out;
        }

        @Override public int purgeEvents(long olderThanMs, boolean orphans) {
            purgeCalls.add(new long[] {olderThanMs});
            purgeOrphans.add(orphans);
            return 0;
        }
    }

    /** Records every early-hold call. */
    static final class FakeSink implements InboundSink {
        final List<String> keys = new ArrayList<>();
        final List<ThreadEvent> events = new ArrayList<>();

        @Override public void onInboundEvent(String e164OrConversationKey, ThreadEvent event) {
            keys.add(e164OrConversationKey);
            events.add(event);
        }
    }
}
