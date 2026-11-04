package com.voice2sms.sendlater.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.NetworkState;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.SessionState;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** D5 / I9: the debug hooks never reach the sender unless explicitly allowed for the allowed number. */
public class SendLaterDebugCommandsTest {

    // Fictional 555-01xx numbers, assembled so no 10-digit literal sits in this public repo.
    private static final String EXCHANGE = "+1202" + "555";
    private static final String ALLOWED = EXCHANGE + "0142";
    private static final String OTHER = EXCHANGE + "0199";
    private static final long NOW = 1_800_000_000_000L;

    private FakeBackend b;
    private SendLaterDebugCommands c;

    @Before public void setUp() {
        b = new FakeBackend();
        c = new SendLaterDebugCommands(b);
    }

    private long seed(String e164, boolean allow, String allowed) {
        String r = c.seed(e164, "see you at five", 3, true, null, allow, allowed);
        assertTrue(r, r.startsWith("id="));
        return Long.parseLong(r.substring(3, r.indexOf(' ')));
    }

    // ---------------------------------------------------------------- guard

    @Test public void sendAllowed_requiresFlagAndEqualNumbers() {
        assertFalse(SendLaterDebugCommands.sendAllowed(false, ALLOWED, ALLOWED));
        assertFalse(SendLaterDebugCommands.sendAllowed(true, null, ALLOWED));
        assertFalse(SendLaterDebugCommands.sendAllowed(true, "", ALLOWED));
        assertFalse(SendLaterDebugCommands.sendAllowed(true, ALLOWED, OTHER));
        assertFalse(SendLaterDebugCommands.sendAllowed(true, "garbage", "garbage"));
        assertTrue(SendLaterDebugCommands.sendAllowed(true, ALLOWED, ALLOWED));
        assertTrue(SendLaterDebugCommands.sendAllowed(true, "(202) 555-" + "0142", ALLOWED));
    }

    // ---------------------------------------------------------------- seed

    @Test public void seed_withoutAllow_storesR2AndRegistersAlarms() {
        long id = seed(ALLOWED, false, null);
        ScheduledMessage m = b.get(id);
        assertEquals(Rung.R2, m.getRung());
        assertEquals(Status.SCHEDULED, m.getStatus());
        assertEquals(NOW + 3 * 60_000L, m.getScheduledFor());
        assertEquals(ALLOWED, m.getRecipientE164());
        assertEquals(Watermark.Quality.DEVICE_ONLY, m.getWatermark().getQuality());
        assertEquals(1, b.alarmsRegistered);
    }

    @Test public void seed_allowedButOtherRecipient_storesR2() {
        long id = seed(OTHER, true, ALLOWED);
        assertEquals(Rung.R2, b.get(id).getRung());
    }

    @Test public void seed_allowedRecipient_storesR1() {
        long id = seed(ALLOWED, true, ALLOWED);
        assertEquals(Rung.R1, b.get(id).getRung());
    }

    @Test public void seed_rejectsBadInput() {
        assertEquals("error=bad_e164", c.seed("not a number", "x y z", 3, true, null, false, null));
        assertEquals("error=no_text", c.seed(ALLOWED, " ", 3, true, null, false, null));
        assertEquals("error=bad_source", c.seed(ALLOWED, "x", 3, true, "PIGEON", false, null));
        assertEquals(0, b.records.size());
    }

    // ---------------------------------------------------------------- evaluate applies nothing

    @Test public void evaluate_appliesNothing() {
        long id = seed(ALLOWED, true, ALLOWED);
        b.resetCounters();
        String r = c.evaluate(id);
        assertTrue(r, r.contains("decision="));
        assertTrue(r, r.contains("applied=none"));
        assertEquals(0, b.mutations());
        assertEquals(Status.SCHEDULED, b.get(id).getStatus());
    }

    @Test public void evaluate_r2ReportsRemindAndR1Decision() {
        long id = seed(ALLOWED, false, null);
        b.listener = true;
        b.resetCounters();
        String r = c.evaluate(id);
        assertTrue(r, r.contains("decision=REMIND"));
        assertTrue(r, r.contains("asR1=SEND"));
        assertEquals(0, b.mutations());
    }

    // ---------------------------------------------------------------- fire guard

    @Test public void fire_withoutAllowSend_downgradesToHold_neverReachesEngine() {
        long id = seed(ALLOWED, true, ALLOWED);
        String r = c.fire(id, false, ALLOWED);
        assertEquals(0, b.onDueCalls);
        assertEquals(0, b.makeDueCalls);
        assertEquals(Status.HELD, b.get(id).getStatus());
        assertEquals(HoldReason.UNVERIFIED, b.get(id).getHoldReason());
        assertEquals(1, b.alarmsCancelled);
        assertTrue(r, r.contains("applied=HOLD(UNVERIFIED)"));
    }

    @Test public void fire_withNonAllowedRecipient_downgradesToHold() {
        long id = seed(OTHER, false, null);
        c.fire(id, true, ALLOWED);
        assertEquals(0, b.onDueCalls);
        assertEquals(Status.HELD, b.get(id).getStatus());
    }

    @Test public void fire_withoutAllowedRecipientExtra_downgradesToHold() {
        long id = seed(ALLOWED, false, null);
        c.fire(id, true, null);
        assertEquals(0, b.onDueCalls);
        assertEquals(Status.HELD, b.get(id).getStatus());
    }

    @Test public void fire_allowed_makesDueAndCallsEngineOnce() {
        long id = seed(ALLOWED, true, ALLOWED);
        String r = c.fire(id, true, ALLOWED);
        assertEquals(1, b.makeDueCalls);
        assertEquals(1, b.onDueCalls);
        assertEquals(0, b.holds);
        assertTrue(r, r.contains("applied=engine.onDue"));
    }

    @Test public void fire_nonScheduled_doesNothing() {
        long id = seed(ALLOWED, true, ALLOWED);
        b.hold(id, HoldReason.REPLIED);
        b.resetCounters();
        String r = c.fire(id, true, ALLOWED);
        assertEquals(0, b.mutations());
        assertTrue(r, r.contains("applied=none"));
    }

    @Test public void fire_unknownId() {
        assertTrue(c.fire(99, true, ALLOWED).contains("not_found"));
        assertEquals(0, b.onDueCalls);
    }

    // ---------------------------------------------------------------- inbound, last call, cancel

    @Test public void inbound_recordsAndForwardsHashedEvent() {
        String r = c.inbound("(202) 555-" + "0142", "running late");
        assertEquals(1, b.inbound.size());
        ThreadEvent ev = b.inbound.get(0);
        assertTrue(ev.isInbound());
        assertEquals(ThreadEvent.hashOf("running late"), ev.getTextHash());
        assertEquals(1, b.engineInbound);
        assertFalse("result must not carry the text", r.contains("running late"));
        assertFalse("result must not carry the full number", r.contains("555" + "0142"));
    }

    @Test public void lastCallAndCancel_delegateToEngine() {
        long id = seed(ALLOWED, false, null);
        c.lastCall(id);
        assertEquals(1, b.lastCalls);
        String r = c.cancel(id);
        assertEquals(1, b.cancels);
        assertTrue(r, r.contains("status=CANCELED"));
        assertEquals(0, b.onDueCalls);
    }

    @Test public void dump_isOneLineWithoutText() {
        seed(ALLOWED, false, null);
        String json = c.dump();
        assertNotNull(json);
        assertFalse(json.contains("\n"));
        assertFalse(json.contains("see you at five"));
        assertTrue(json, json.contains("\"id\":1,\"status\":\"SCHEDULED\""));
    }

    // ---------------------------------------------------------------- fake

    static final class FakeBackend implements DebugBackend {
        final Map<Long, ScheduledMessage> records = new LinkedHashMap<>();
        final List<ThreadEvent> inbound = new ArrayList<>();
        long nextId = 1;
        boolean listener;
        int alarmsRegistered, alarmsCancelled, holds, onDueCalls, makeDueCalls, lastCalls, cancels, engineInbound;

        void resetCounters() {
            alarmsRegistered = alarmsCancelled = holds = onDueCalls = makeDueCalls = lastCalls = cancels = engineInbound = 0;
        }

        int mutations() {
            return alarmsRegistered + alarmsCancelled + holds + onDueCalls + makeDueCalls + lastCalls + cancels
                    + engineInbound + inbound.size();
        }

        @Override public long nowMs() { return NOW; }
        @Override public ScheduledMessage get(long id) { return records.get(id); }
        @Override public List<ScheduledMessage> allRecords() { return new ArrayList<>(records.values()); }
        @Override public List<DebugDump.KeyedEvent> allEvents() { return new ArrayList<>(); }
        @Override public List<ThreadEvent> eventsFor(ScheduledMessage m) {
            return listener ? new ArrayList<ThreadEvent>() : null;
        }
        @Override public SessionState session() { return SessionState.READY; }
        @Override public NetworkState network() { return NetworkState.CONNECTED; }
        @Override public boolean listenerEnabled() { return listener; }
        @Override public OutboundPolicy outboundPolicy() { return OutboundPolicy.STILL_SEND; }

        @Override public long schedule(ScheduledMessage m, Watermark wm) {
            long id = nextId++;
            records.put(id, m.toBuilder().id(id).watermark(wm).build());
            return id;
        }

        @Override public void registerAlarms(ScheduledMessage m) { alarmsRegistered++; }

        @Override public boolean hold(long id, HoldReason reason) {
            holds++;
            ScheduledMessage m = records.get(id);
            if (m == null || m.getStatus() != Status.SCHEDULED) return false;
            records.put(id, m.toBuilder().status(Status.HELD).holdReason(reason).build());
            return true;
        }

        @Override public void cancelAlarms(long id) { alarmsCancelled++; }
        @Override public void recordInbound(String e164, ThreadEvent event) { inbound.add(event); }
        @Override public void engineOnInbound(String e164, ThreadEvent event) { engineInbound++; }
        @Override public void engineOnLastCall(long id) { lastCalls++; }

        @Override public void engineOnCancel(long id) {
            cancels++;
            ScheduledMessage m = records.get(id);
            if (m != null) records.put(id, m.toBuilder().status(Status.CANCELED).build());
        }

        @Override public boolean makeDueNow(long id, long nowMs) {
            makeDueCalls++;
            ScheduledMessage m = records.get(id);
            if (m == null) return false;
            records.put(id, m.toBuilder().scheduledFor(nowMs).build());
            return true;
        }

        @Override public void engineOnDue(long id) { onDueCalls++; }

        // gate-residuals: master switch + ACTION_STATE_CHANGED (recorded in order).
        boolean enabled;
        final List<String> calls = new ArrayList<>();
        @Override public boolean sendLaterEnabled() { calls.add("read"); return enabled; }
        @Override public void setSendLaterEnabled(boolean on) { calls.add("set:" + on); enabled = on; }
        @Override public void applySwitch() { calls.add("applySwitch:" + enabled); }
        @Override public void broadcastStateChanged() { calls.add("stateChanged"); }
    }
}
