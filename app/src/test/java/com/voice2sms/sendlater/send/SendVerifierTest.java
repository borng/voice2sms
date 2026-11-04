package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.ThreadEvent.Direction;
import com.voice2sms.sendlater.domain.ThreadEvent.Kind;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Acceptance tests for {@code com.voice2sms.sendlater.send.SendVerifier} (R31 Schrodinger rule,
 * I5): {@code verify(bodyHash, threadEvents, checkTimeMs, sendTimeMs)} is pure JVM and returns
 * VERIFIED | UNVERIFIED | VERIFIED_WITH_LATE_REPLY. Called by reflection so the rest of the suite
 * still compiles before the class exists. Message texts are fictional.
 */
public class SendVerifierTest {

    private static final String CLASS = "com.voice2sms.sendlater.send.SendVerifier";

    private static final long CHECK = 1_800_000_000_000L;   // thread check at due time
    private static final long SEND = CHECK + 30_000L;       // send 30 s later

    private static final String BODY = "Running ten minutes late, save me a seat please";
    private static final String HASH = ThreadEvent.hashOf(BODY);
    private static final String OTHER = "Did you remember to bring the charger";

    private static Method verifyMethod() throws Exception {
        Class<?> c = Class.forName(CLASS, false, SendVerifierTest.class.getClassLoader());
        Method found = null;
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals("verify") && m.getParameterCount() == 4) found = m;
        }
        assertNotNull("SendVerifier.verify(bodyHash, threadEvents, checkTimeMs, sendTimeMs) missing", found);
        assertTrue("verify must be static", Modifier.isStatic(found.getModifiers()));
        assertTrue("verify must be public", Modifier.isPublic(found.getModifiers()));
        found.setAccessible(true);
        return found;
    }

    private static String verify(String bodyHash, List<ThreadEvent> events, long check, long send) throws Exception {
        Method m = verifyMethod();
        Object r;
        try {
            r = m.invoke(null, bodyHash, events, check, send);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
        assertNotNull("verify never returns null", r);
        assertTrue("verify returns an enum constant", r instanceof Enum);
        return ((Enum<?>) r).name();
    }

    private static ThreadEvent out(long t, String text) {
        return new ThreadEvent(Direction.OUT, Kind.SMS, t, ThreadEvent.hashOf(text), text);
    }

    private static ThreadEvent in(long t, String text) {
        return new ThreadEvent(Direction.IN, Kind.SMS, t, ThreadEvent.hashOf(text), text);
    }

    private static List<ThreadEvent> list(ThreadEvent... e) {
        return new ArrayList<>(Arrays.asList(e));
    }

    // ------------------------------------------------------------------ result set

    @Test public void resultTypeHasExactlyTheThreePinnedValues() throws Exception {
        Class<?> rt = verifyMethod().getReturnType();
        assertTrue("verify returns an enum", rt.isEnum());
        Set<String> names = new HashSet<>();
        for (Object c : rt.getEnumConstants()) names.add(((Enum<?>) c).name());
        assertEquals(new HashSet<>(Arrays.asList("VERIFIED", "UNVERIFIED", "VERIFIED_WITH_LATE_REPLY")), names);
    }

    @Test public void verifierIsPureJvm() throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get("src/main/java/com/voice2sms/sendlater/send/SendVerifier.java");
        assertTrue("missing " + p, java.nio.file.Files.exists(p));
        String src = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse("SendVerifier must not import android.*", src.contains("import android."));
        assertFalse("SendVerifier must not import androidx.*", src.contains("import androidx."));
    }

    // ------------------------------------------------------------------ VERIFIED

    @Test public void outboundBubbleMatchingBodyHashIsVerified() throws Exception {
        assertEquals("VERIFIED", verify(HASH, list(out(SEND, BODY)), CHECK, SEND));
    }

    @Test public void matchingOutboundAmongOtherBubblesIsVerified() throws Exception {
        assertEquals("VERIFIED", verify(HASH,
                list(in(CHECK - 600_000L, OTHER), out(CHECK - 300_000L, OTHER), out(SEND, BODY)), CHECK, SEND));
    }

    @Test public void inboundBeforeTheCheckDoesNotMakeALateReply() throws Exception {
        assertEquals("an inbound already seen by the thread check is not a late reply", "VERIFIED",
                verify(HASH, list(in(CHECK - 60_000L, OTHER), out(SEND, BODY)), CHECK, SEND));
    }

    @Test public void hashOnlyOutboundEventStillMatches() throws Exception {
        ThreadEvent hashOnly = new ThreadEvent(Direction.OUT, Kind.SMS, SEND, HASH, null);
        assertEquals("VERIFIED", verify(HASH, list(hashOnly), CHECK, SEND));
    }

    // ------------------------------------------------------------------ VERIFIED_WITH_LATE_REPLY

    @Test public void inboundBetweenCheckAndSendIsVerifiedWithLateReply() throws Exception {
        assertEquals("VERIFIED_WITH_LATE_REPLY",
                verify(HASH, list(in(CHECK + 10_000L, OTHER), out(SEND, BODY)), CHECK, SEND));
    }

    @Test public void lateReplyIsDetectedWhicheverOrderTheEventsArrive() throws Exception {
        assertEquals("VERIFIED_WITH_LATE_REPLY",
                verify(HASH, list(out(SEND, BODY), in(CHECK + 20_000L, OTHER)), CHECK, SEND));
    }

    @Test public void inboundBetweenCheckAndSendWithoutOwnBubbleIsUnverified() throws Exception {
        assertEquals("a late reply alone is no proof the message went out", "UNVERIFIED",
                verify(HASH, list(in(CHECK + 10_000L, OTHER)), CHECK, SEND));
    }

    // ------------------------------------------------------------------ UNVERIFIED

    @Test public void nothingInTheThreadIsUnverified() throws Exception {
        assertEquals("UNVERIFIED", verify(HASH, new ArrayList<ThreadEvent>(), CHECK, SEND));
        assertEquals("UNVERIFIED", verify(HASH, Collections.<ThreadEvent>emptyList(), CHECK, SEND));
    }

    @Test public void nullEventsAreUnverified() throws Exception {
        assertEquals("no thread read at all is UNVERIFIED", "UNVERIFIED", verify(HASH, null, CHECK, SEND));
    }

    @Test public void outboundWithDifferentTextIsUnverified() throws Exception {
        assertEquals("UNVERIFIED", verify(HASH, list(out(SEND, OTHER)), CHECK, SEND));
    }

    // ------------------------------------------------------------------ never VERIFIED on inbound

    @Test public void inboundWithTheSameTextIsNeverVerified() throws Exception {
        // The other side echoing the exact text is not our bubble.
        String r = verify(HASH, list(in(SEND, BODY)), CHECK, SEND);
        assertEquals("UNVERIFIED", r);
    }

    @Test public void inboundWithTheSameTextBetweenCheckAndSendIsNeverVerified() throws Exception {
        String r = verify(HASH, list(in(CHECK + 10_000L, BODY)), CHECK, SEND);
        assertNotEquals("VERIFIED", r);
        assertNotEquals("an inbound match is not a sent bubble", "VERIFIED_WITH_LATE_REPLY", r);
        assertEquals("UNVERIFIED", r);
    }

    @Test public void hashOnlyInboundMatchIsNeverVerified() throws Exception {
        ThreadEvent hashOnly = new ThreadEvent(Direction.IN, Kind.SMS, SEND, HASH, null);
        assertEquals("UNVERIFIED", verify(HASH, list(hashOnly, out(SEND, OTHER)), CHECK, SEND));
    }
}
