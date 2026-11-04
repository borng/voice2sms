package com.voice2sms.sendlater.debug;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.NetworkState;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.SessionState;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import java.util.List;

/**
 * What the debug commands talk to. Production: {@link SendLaterDebugReceiver}'s Android backend
 * over the repository, scheduler and engine. Tests: a fake.
 *
 * <p>{@link #engineOnDue} is the only method that can reach the scheduled sender.
 * {@link SendLaterDebugCommands} calls it only from {@code fire} after the send guard passed.
 */
interface DebugBackend {

    long nowMs();

    // ---- reads (EVALUATE, DUMP)
    ScheduledMessage get(long id);
    List<ScheduledMessage> allRecords();
    List<DebugDump.KeyedEvent> allEvents();
    /** The thread events the ladder would see for {@code m}, or null when no source is available. */
    List<ThreadEvent> eventsFor(ScheduledMessage m);
    SessionState session();
    NetworkState network();
    boolean listenerEnabled();
    OutboundPolicy outboundPolicy();

    // ---- writes that never send
    long schedule(ScheduledMessage m, Watermark wm) throws Exception;
    void registerAlarms(ScheduledMessage m);
    boolean hold(long id, HoldReason reason);
    void cancelAlarms(long id);
    void recordInbound(String e164, ThreadEvent event);
    void engineOnInbound(String e164, ThreadEvent event);
    void engineOnLastCall(long id);
    void engineOnCancel(long id);

    // ---- the send-capable path (guarded)
    /** Moves a SCHEDULED row's time to {@code nowMs} so the engine treats it as due. */
    boolean makeDueNow(long id, long nowMs);
    /** {@code SendLaterEngine.onDue}: may start a real send. */
    void engineOnDue(long id);
}
