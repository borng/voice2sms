package com.voice2sms.sendlater.domain;

import static com.voice2sms.sendlater.domain.Fixtures.HOUR;
import static com.voice2sms.sendlater.domain.Fixtures.MIN;
import static com.voice2sms.sendlater.domain.Fixtures.T0;
import static com.voice2sms.sendlater.domain.Fixtures.deviceOnlyWatermark;
import static com.voice2sms.sendlater.domain.Fixtures.events;
import static com.voice2sms.sendlater.domain.Fixtures.inbound;
import static com.voice2sms.sendlater.domain.Fixtures.inboundSms;
import static com.voice2sms.sendlater.domain.Fixtures.message;
import static com.voice2sms.sendlater.domain.Fixtures.none;
import static com.voice2sms.sendlater.domain.Fixtures.outboundSms;
import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

/** R26 similarity rule and R32 source semantics for the thread check. */
public class ThreadEventClassifierTest {

    private static final long BETWEEN = T0 + HOUR;

    private static Classification classify(String scheduledText, List<ThreadEvent> events) {
        return ThreadEventClassifier.classify(message(3 * HOUR).text(scheduledText).build(), events, true);
    }

    private static Classification outboundVs(String scheduled, String sent) {
        return classify(scheduled, events(outboundSms(BETWEEN, sent)));
    }

    // ---- similarity: normalized containment ---------------------------------------------

    @Test
    public void similarity_sameTextDifferentCaseAndPunctuation_isSelfPreempted() {
        assertEquals(Classification.SELF_PREEMPTED,
                outboundVs("Running late, be there at 9", "running late be there at 9!!"));
    }

    @Test
    public void similarity_sentTextContainsScheduledText_isSelfPreempted() {
        assertEquals(Classification.SELF_PREEMPTED,
                outboundVs("Running late, be there at 9", "Hey - running late, be there at 9. Sorry!"));
    }

    @Test
    public void similarity_scheduledTextContainsSentText_isSelfPreempted() {
        assertEquals(Classification.SELF_PREEMPTED,
                outboundVs("Hey, running late, be there at 9. Sorry!", "Running late, be there at 9"));
    }

    @Test
    public void similarity_extraWhitespace_isSelfPreempted() {
        assertEquals(Classification.SELF_PREEMPTED,
                outboundVs("Call me after work", "  call   me\nafter work "));
    }

    // ---- similarity: token Jaccard >= 0.6 -----------------------------------------------

    @Test
    public void jaccardAboveThreshold_isSelfPreempted() {
        // {see,you,at,the,park,tomorrow} vs {see,you,at,the,park,later}: 5/7 = 0.71
        assertEquals(Classification.SELF_PREEMPTED,
                outboundVs("See you at the park tomorrow", "see you at the park later"));
    }

    @Test
    public void jaccardExactlyAtThreshold_isSelfPreempted() {
        // {call,me,after,work} vs {call,me,after,lunch}: 3/5 = 0.6
        assertEquals(Classification.SELF_PREEMPTED,
                outboundVs("Call me after work", "call me after lunch"));
    }

    @Test
    public void jaccardBelowThreshold_isOutboundOnly() {
        // {pick,up,milk,and,eggs,please} vs {pick,up,bread,and,butter,please}: 4/8 = 0.5
        assertEquals(Classification.OUTBOUND_ONLY,
                outboundVs("Pick up milk and eggs please", "pick up bread and butter please"));
    }

    @Test
    public void unrelatedOutbound_isOutboundOnly() {
        assertEquals(Classification.OUTBOUND_ONLY,
                outboundVs("Running late, be there at 9", "What time is dinner"));
    }

    // ---- direction / kind -----------------------------------------------------------------

    @Test
    public void inboundText_isReplied() {
        assertEquals(Classification.REPLIED, classify("Running late", events(inboundSms(BETWEEN, "where are you"))));
    }

    @Test
    public void inboundMms_isReplied() {
        assertEquals(Classification.REPLIED,
                classify("Running late", events(inbound(ThreadEvent.Kind.MMS, BETWEEN, null))));
    }

    @Test
    public void inboundCall_isReachedOut() {
        assertEquals(Classification.REACHED_OUT,
                classify("Running late", events(inbound(ThreadEvent.Kind.CALL, BETWEEN, null))));
    }

    @Test
    public void inboundVoicemail_isReachedOut() {
        assertEquals(Classification.REACHED_OUT,
                classify("Running late", events(inbound(ThreadEvent.Kind.VOICEMAIL, BETWEEN, null))));
    }

    // ---- DEVICE_ONLY margin rules (+-90 s around capture) -------------------------------

    private static Classification deviceOnly(List<ThreadEvent> events) {
        ScheduledMessage m = message(3 * HOUR).watermark(deviceOnlyWatermark(T0)).build();
        return ThreadEventClassifier.classify(m, events, true);
    }

    @Test
    public void deviceOnly_inboundJustBeforeCapture_counts() {
        assertEquals(Classification.REPLIED, deviceOnly(events(inboundSms(T0 - 60_000L, "you there?"))));
    }

    @Test
    public void deviceOnly_inboundJustAfterCapture_counts() {
        assertEquals(Classification.REPLIED, deviceOnly(events(inboundSms(T0 + 60_000L, "you there?"))));
    }

    @Test
    public void deviceOnly_inboundWellBeforeMargin_isIgnored() {
        assertEquals(Classification.UNCHANGED, deviceOnly(events(inboundSms(T0 - 10 * MIN, "you there?"))));
    }

    @Test
    public void deviceOnly_outboundInsideMargin_isIgnored() {
        assertEquals(Classification.UNCHANGED, deviceOnly(events(outboundSms(T0 + 60_000L, "What time is dinner"))));
    }

    @Test
    public void deviceOnly_similarOutboundInsideMargin_isIgnoredNotSelfPreempted() {
        assertEquals(Classification.UNCHANGED,
                deviceOnly(events(outboundSms(T0 - 30_000L, Fixtures.TEXT))));
    }

    @Test
    public void deviceOnly_outboundAfterMargin_counts() {
        assertEquals(Classification.OUTBOUND_ONLY, deviceOnly(events(outboundSms(T0 + 5 * MIN, "What time is dinner"))));
    }

    // ---- R32 source semantics -----------------------------------------------------------

    @Test
    public void listenerEnabled_noEvents_isUnchanged() {
        assertEquals(Classification.UNCHANGED,
                ThreadEventClassifier.classify(message(3 * HOUR).build(), none(), true));
    }

    @Test
    public void noAvailableSource_isUnverified() {
        ThreadEventSource source = new UnavailableSource();
        ScheduledMessage m = message(3 * HOUR).build();
        List<ThreadEvent> read = source.isAvailable()
                ? source.readSince(new ConversationRef(m.getRecipientE164(), m.getRecipientDisplayName()), m.getWatermark())
                : null;
        assertEquals(Classification.UNVERIFIED, ThreadEventClassifier.classify(m, read, false));
    }

    @Test
    public void listenerDisabled_emptyEvents_isUnverified() {
        assertEquals(Classification.UNVERIFIED,
                ThreadEventClassifier.classify(message(3 * HOUR).build(), none(), false));
    }

    @Test
    public void nullEvents_noSourceRead_isUnverifiedEvenWithListenerFlag() {
        assertEquals(Classification.UNVERIFIED,
                ThreadEventClassifier.classify(message(3 * HOUR).build(), null, true));
    }

    private static final class UnavailableSource implements ThreadEventSource {
        @Override public boolean isAvailable() { return false; }
        @Override public List<ThreadEvent> readSince(ConversationRef ref, Watermark wm) {
            throw new AssertionError("readSince must not be called on an unavailable source");
        }
    }
}
