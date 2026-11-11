package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/** Deliver M4: inject.js reports a bubble's sending state and reads status lines without logging. */
public class SendingStateJsSourceTest {

    private static String body(JsSource js, String name) {
        JsSource.Fn f = js.first(name);
        assertNotNull(name + " missing", f);
        return f.body;
    }

    @Test public void readerReturnsSending() throws IOException {
        String read = body(JsSource.injectJs(), "_v2sSlReadBubbles");
        assertTrue(read.contains("sending: _v2sSlBubbleSending(n, full)"));
    }

    @Test public void sendingLooksAtClassesBusyStateSpinnersAndTheStatusLine() throws IOException {
        String s = body(JsSource.injectJs(), "_v2sSlBubbleSending");
        assertTrue(s.contains("sending") && s.contains("pending"));
        assertTrue(s.contains("aria-busy"));
        assertTrue(s.contains("progressbar"));
        assertTrue(s.contains("_v2sSlBubbleStatus(n)"));
        assertTrue("failed still checks the status line too",
                body(JsSource.injectJs(), "_v2sSlBubbleFailed").contains("_v2sSlBubbleStatus(n)"));
    }

    @Test public void theStatusLineNeverComesFromTheMessageTextOrLabel() throws IOException {
        JsSource js = JsSource.injectJs();
        String st = body(js, "_v2sSlBubbleStatus");
        assertTrue(st.contains("_v2sSlInMessageText(els[i])"));
        assertTrue(st.contains("_v2sSlHoldsMessageText(els[i])"));
        assertTrue("short status lines only", st.contains("t.length <= 40"));
        for (String n : new String[]{"_v2sSlInMessageText", "_v2sSlHoldsMessageText"}) {
            String b = body(js, n);
            assertTrue(n, b.contains("gv-annotation") && b.contains(".cdk-visually-hidden"));
        }
    }

    @Test public void noneOfItLogs() throws IOException {
        JsSource js = JsSource.injectJs();
        for (String n : new String[]{"_v2sSlBubbleSending", "_v2sSlBubbleStatus", "_v2sSlInMessageText",
                "_v2sSlHoldsMessageText", "_v2sSlBubbleFailed"}) {
            String b = body(js, n);
            assertFalse(n + " must not log", b.contains("console.") || b.contains("V2SBridge."));
        }
    }
}
