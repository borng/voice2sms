package com.voice2sms.sendlater.engine;

import static com.voice2sms.sendlater.engine.EngineFakes.message;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.NetworkState;
import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.domain.PreSendCheck;
import com.voice2sms.sendlater.domain.SessionState;
import com.voice2sms.sendlater.domain.ThreadEventSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * live-presend-failclosed (D30, R31, OD22(5)): the engine passes the listener facts the
 * empty-conversation rule needs with the dispatch's PreSendCheck; the send controller never
 * reads the stores itself. Fictional data only (EngineFakes).
 */
public class LivePresendFailClosedEngineTest {

    private static final long ID = 84L;

    private EngineFakes.World w;
    private LivePresendEngineTest.CheckingSender webView;
    /** What the listener store says about the conversation; null = the interface default. */
    private Boolean saw;
    private boolean sawThrows;
    private final List<String> askedKeys = new ArrayList<>();

    private final EngineConditions conditions = new EngineConditions() {
        @Override public long nowMs() { return w.cond.nowMs(); }
        @Override public SessionState session() { return w.cond.session(); }
        @Override public NetworkState network() { return w.cond.network(); }
        @Override public boolean listenerEnabled() { return w.cond.listenerEnabled(); }
        @Override public OutboundPolicy outboundPolicy() { return w.cond.outboundPolicy(); }
        @Override public boolean listenerSawConversation(ConversationRef ref) {
            askedKeys.add(ref.key());
            if (sawThrows) throw new IllegalStateException("store");
            return saw == null ? EngineConditions.super.listenerSawConversation(ref) : saw;
        }
    };

    @Before public void setUp() {
        SendLaterEngine.resetForTest();
        w = new EngineFakes.World();
        List<ThreadEventSource> sources = new ArrayList<>(Collections.<ThreadEventSource>singletonList(w.source));
        SendLaterEngine.installForTest(new EngineDeps.Builder().store(w.store).sources(sources)
                .notifier(w.notifier).broadcaster(w.broadcaster).alarms(w.alarms).conditions(conditions)
                .networkWaiter(w.network).executor(Runnable::run).build());
        webView = new LivePresendEngineTest.CheckingSender(w.store);
        SendLaterEngine.setSender(webView);
    }

    @After public void tearDown() {
        SendLaterEngine.resetForTest();
    }

    private PreSendCheck dispatch() {
        w.store.put(message(ID).build());
        SendLaterEngine.onDue(null, ID);
        assertEquals(1, webView.checks.size());
        return webView.checks.get(0);
    }

    @Test public void theRequiredCheckCarriesTheListenerFacts() {
        w.source.available = false;
        saw = false;
        PreSendCheck c = dispatch();
        assertEquals(PreSendCheck.Mode.REQUIRED, c.getMode());
        assertTrue(c.hasListenerFacts());
        assertTrue(c.isListenerEnabled());
        assertFalse(c.listenerSawConversation());
        assertEquals("asked about this message's conversation",
                Collections.singletonList(new ConversationRef(message(ID).build().getRecipientE164(), null).key()),
                askedKeys);
    }

    @Test public void listenerOffAtDispatchIsPassedAsOff() {
        w.source.available = false;
        w.cond.listener = false;
        saw = false;
        PreSendCheck c = dispatch();
        assertTrue(c.hasListenerFacts());
        assertFalse(c.isListenerEnabled());
    }

    @Test public void aMatchedConversationIsPassedAsSeen() {
        saw = true;
        PreSendCheck c = dispatch();
        assertEquals(PreSendCheck.Mode.CHECK, c.getMode());
        assertTrue(c.listenerSawConversation());
    }

    @Test public void theDefaultAndAThrowingStoreCountAsSeen() {
        w.source.available = false;
        assertTrue("interface default fails closed", dispatch().listenerSawConversation());
        SendLaterEngine.resetForTest();
        setUp();
        w.source.available = false;
        sawThrows = true;
        assertTrue("unreadable store fails closed", dispatch().listenerSawConversation());
    }

    @Test public void theUsersOwnTapStaysOffWithoutFacts() {
        assertFalse(PreSendCheck.off().withListenerFacts(true, false).hasListenerFacts());
        assertTrue(PreSendCheck.off().withListenerFacts(true, false).isOff());
    }
}
