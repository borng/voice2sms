package com.voice2sms.sendlater.debug;

import com.voice2sms.sendlater.domain.Decision;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LadderEvaluator;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Source;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.voice.logic.PhoneNumbers;

import java.util.List;

/**
 * The debug-build Send Later commands behind {@link SendLaterDebugReceiver} (adb only). Pure JVM
 * over a {@link DebugBackend}. Every method returns a one-line result for the broadcast's result
 * data; none of them contains message text.
 *
 * <p>Send safety (D5, I9):
 * <ul>
 *   <li>{@code DEBUG_EVALUATE} (the default the smoke script uses) runs the ladder and returns the
 *       Decision without applying anything.</li>
 *   <li>{@code DEBUG_FIRE} reaches the engine (and so the sender) only when {@code allow_send} is
 *       true AND the record's recipient equals {@code allowed_recipient}. Otherwise it downgrades to
 *       HOLD(UNVERIFIED) with the alarms cancelled, without calling the engine.</li>
 *   <li>{@code DEBUG_SEED} stores rung R1 only when the same guard passes; any other seed is stored
 *       as rung R2, which the ladder never sends on its own (REMIND), so a stray DUE alarm or a
 *       process-start re-arm cannot send it either.</li>
 * </ul>
 */
public final class SendLaterDebugCommands {

    public static final String ACTION_PREFIX = "com.voice2sms.sendlater.DEBUG_";
    public static final String ACTION_SEED = ACTION_PREFIX + "SEED";
    public static final String ACTION_DUMP = ACTION_PREFIX + "DUMP";
    public static final String ACTION_EVALUATE = ACTION_PREFIX + "EVALUATE";
    public static final String ACTION_FIRE = ACTION_PREFIX + "FIRE";
    public static final String ACTION_INBOUND = ACTION_PREFIX + "INBOUND";
    public static final String ACTION_LAST_CALL = ACTION_PREFIX + "LAST_CALL";
    public static final String ACTION_CANCEL = ACTION_PREFIX + "CANCEL";

    public static final String EXTRA_E164 = "e164";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_OFFSET_MIN = "offset_min";
    public static final String EXTRA_HOLD = "hold";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_ALLOW_SEND = "allow_send";
    public static final String EXTRA_ALLOWED_RECIPIENT = "allowed_recipient";

    public static final int DEFAULT_OFFSET_MIN = 3;
    static final String REGION = "US";

    private final DebugBackend b;

    SendLaterDebugCommands(DebugBackend backend) {
        if (backend == null) throw new IllegalArgumentException("backend");
        this.b = backend;
    }

    /**
     * The I9 guard: true only when sending was explicitly allowed and both numbers normalize to the
     * same E.164. Blank or unparseable numbers never pass.
     */
    public static boolean sendAllowed(boolean allowSend, String allowedRecipient, String recipient) {
        if (!allowSend) return false;
        String a = PhoneNumbers.toE164(allowedRecipient, REGION);
        String r = PhoneNumbers.toE164(recipient, REGION);
        return a != null && a.equals(r);
    }

    // ------------------------------------------------------------------ SEED

    String seed(String e164Raw, String text, int offsetMin, boolean holdIfReplied, String sourceName,
                boolean allowSend, String allowedRecipient) {
        String e164 = PhoneNumbers.toE164(e164Raw, REGION);
        if (e164 == null) return "error=bad_e164";
        if (text == null || text.trim().isEmpty()) return "error=no_text";
        Source source;
        try {
            source = sourceName == null || sourceName.isEmpty() ? Source.NATIVE_GV : Source.valueOf(sourceName);
        } catch (IllegalArgumentException e) {
            return "error=bad_source";
        }
        boolean mayAutoSend = sendAllowed(allowSend, allowedRecipient, e164);
        long now = b.nowMs();
        ScheduledMessage m = new ScheduledMessage.Builder()
                .createdAt(now)
                .scheduledFor(now + offsetMin * 60_000L)
                .text(text)
                .recipientE164(e164)
                .threadItemId("t." + e164)
                .source(source)
                .rung(mayAutoSend ? Rung.R1 : Rung.R2)
                .holdIfReplied(holdIfReplied)
                .status(Status.SCHEDULED)
                .build();
        Watermark wm = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, now, null);
        long id;
        try {
            id = b.schedule(m, wm);
        } catch (Exception e) {
            return "error=persist:" + e.getClass().getSimpleName();
        }
        ScheduledMessage saved = b.get(id);
        if (saved != null) b.registerAlarms(saved);
        return "id=" + id + " rung=" + m.getRung().name() + " scheduledFor=" + m.getScheduledFor();
    }

    // ------------------------------------------------------------------ EVALUATE (applies nothing)

    String evaluate(long id) {
        ScheduledMessage m = b.get(id);
        if (m == null) return "id=" + id + " error=not_found";
        if (m.getStatus().isTerminal()) return "id=" + id + " status=" + m.getStatus().name() + " decision=none";
        long now = b.nowMs();
        long at = Math.max(now, m.getScheduledFor());
        Decision stored = decide(m, m.getRung(), at);
        Decision asR1 = m.getRung() == Rung.R1 ? stored : decide(m, Rung.R1, at);
        return "id=" + id + " status=" + m.getStatus().name() + " rung=" + m.getRung().name()
                + " decision=" + describe(stored) + " asR1=" + describe(asR1) + " applied=none";
    }

    // ------------------------------------------------------------------ FIRE (guarded)

    String fire(long id, boolean allowSend, String allowedRecipient) {
        ScheduledMessage m = b.get(id);
        if (m == null) return "id=" + id + " error=not_found";
        if (m.getStatus() != Status.SCHEDULED) {
            return "id=" + id + " status=" + m.getStatus().name() + " applied=none";
        }
        long now = b.nowMs();
        Decision d = decide(m, m.getRung(), Math.max(now, m.getScheduledFor()));
        if (!sendAllowed(allowSend, allowedRecipient, m.getRecipientE164())) {
            boolean held = b.hold(id, HoldReason.UNVERIFIED);
            b.cancelAlarms(id);
            return "id=" + id + " decision=" + describe(d) + " applied=" + (held ? "HOLD(UNVERIFIED)" : "none")
                    + " reason=send_not_allowed";
        }
        if (m.getScheduledFor() > now) b.makeDueNow(id, now);
        b.engineOnDue(id);
        ScheduledMessage after = b.get(id);
        return "id=" + id + " decision=" + describe(d) + " applied=engine.onDue status="
                + (after == null ? "unknown" : after.getStatus().name());
    }

    // ------------------------------------------------------------------ INBOUND / LAST_CALL / CANCEL

    String inbound(String e164Raw, String text) {
        String e164 = PhoneNumbers.toE164(e164Raw, REGION);
        if (e164 == null) return "error=bad_e164";
        if (text == null || text.trim().isEmpty()) return "error=no_text";
        ThreadEvent ev = new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.SMS, b.nowMs(),
                ThreadEvent.hashOf(text), text);
        b.recordInbound(e164, ev);
        b.engineOnInbound(e164, ev);
        return "inbound=" + DebugDump.mask(e164) + " textHash=" + ev.getTextHash();
    }

    String lastCall(long id) {
        ScheduledMessage m = b.get(id);
        if (m == null) return "id=" + id + " error=not_found";
        b.engineOnLastCall(id);
        return "id=" + id + " lastCall=requested status=" + m.getStatus().name();
    }

    String cancel(long id) {
        if (b.get(id) == null) return "id=" + id + " error=not_found";
        b.engineOnCancel(id);
        ScheduledMessage after = b.get(id);
        return "id=" + id + " status=" + (after == null ? "unknown" : after.getStatus().name());
    }

    // ------------------------------------------------------------------ DUMP

    String dump() {
        return DebugDump.toJson(b.nowMs(), b.allRecords(), b.allEvents());
    }

    // ------------------------------------------------------------------ helpers

    private Decision decide(ScheduledMessage m, Rung rung, long atMs) {
        try {
            List<ThreadEvent> events = b.eventsFor(m);
            return LadderEvaluator.evaluate(m, rung, events, atMs, b.session(), b.network(),
                    b.listenerEnabled(), b.outboundPolicy());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String describe(Decision d) {
        if (d == null) return "none";
        StringBuilder sb = new StringBuilder(d.getKind().name());
        if (d.getReason() != null) sb.append('(').append(d.getReason().name()).append(')');
        return sb.toString();
    }
}
