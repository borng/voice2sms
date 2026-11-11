package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/** LIVE-F9: inject.js reads the real GV web thread DOM and never logs message content. */
public class LiveReaderJsSourceTest {

    private static String body(JsSource js, String name) {
        JsSource.Fn f = js.first(name);
        assertNotNull(name + " missing", f);
        return f.body;
    }

    @Test public void readerLooksForTheRealDom() throws IOException {
        JsSource js = JsSource.injectJs();
        String read = body(js, "_v2sSlReadBubbles");
        assertTrue(read.contains("gv-message-item"));
        assertTrue(read.contains("gv-message-list"));
        assertTrue("direction from a descendant .full-container",
                read.contains("n.querySelector('.full-container')"));
        assertTrue(read.contains("gv-annotation.content"));
        assertTrue(read.contains(".cdk-visually-hidden"));
        assertTrue("label returned", read.contains("label:"));
        assertTrue("failed returned", read.contains("failed:"));
        assertTrue("an unclear direction skips the item", read.contains("if (!direction) continue;"));
        assertTrue("[] for a rendered thread without items", read.contains("? [] : null"));
        String dir = body(js, "_v2sSlBubbleDirection");
        assertTrue(dir.contains("outgoing") && dir.contains("incoming"));
        assertTrue("old selectors kept as fallback", dir.contains("_v2sSlLegacyDirection("));
        String failed = body(js, "_v2sSlBubbleFailed");
        assertTrue(failed.contains("fail") && failed.contains("error") && failed.contains("not-sent"));
    }

    @Test public void captureSendsTheNewestLabel() throws IOException {
        String cap = body(JsSource.injectJs(), "_v2sSlCapture");
        assertTrue(cap.contains("newestMessageLabel"));
    }

    @Test public void readerNeverLogs() throws IOException {
        JsSource js = JsSource.injectJs();
        for (String n : new String[]{"_v2sSlReadBubbles", "_v2sSlBubbleDirection", "_v2sSlBubbleFailed",
                "_v2sSlLegacyDirection", "_v2sSlBubbleTime", "_v2sSlClassWords", "_v2sSlCapture"}) {
            String b = body(js, n);
            assertFalse(n + " must not log", b.contains("console.") || b.contains("V2SBridge.log"));
        }
        String rt = js.code.substring(js.code.indexOf("window.v2sReadThread = function"));
        rt = rt.substring(0, rt.indexOf("\n};"));
        assertFalse(rt.contains("console."));
    }
}
