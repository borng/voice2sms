package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * deliver sec-M1: the per-document bridge nonce. Issued only for a trusted GV URL, kept for the
 * document, dropped on invalidate (onPageStarted, renderer gone), and a call without the current
 * value is refused.
 */
public class BridgeNonceTest {

    private static final String GV = "https://voice.google.com/u/0/messages";
    private static final String[] UNTRUSTED = {
            null, "", "about:blank", "http://voice.google.com/u/0/messages",
            "https://storage.googleapis.com/some-bucket/page.html", "https://sites.google.com/view/x",
            "https://voice.google.com.example.net/", "https://user@voice.google.com/",
            "https://voice.google.com:8443/", "javascript:alert(1)",
    };

    /** Deterministic generator: "abcdef" plus a counter, 32 hex characters. */
    private static BridgeNonce counting() {
        return new BridgeNonce(new BridgeNonce.Generator() {
            int i;

            @Override
            public String next() {
                return "abcdef" + String.format("%026x", ++i);
            }
        });
    }

    @Test
    public void noNonceAcceptedBeforeOneIsIssued() {
        BridgeNonce n = counting();
        assertNull(n.current());
        assertFalse(n.accepts(null));
        assertFalse(n.accepts(""));
        assertFalse(n.accepts("null"));
        assertFalse(n.accepts("undefined"));
    }

    @Test
    public void trustedGvPageGetsANonceThatIsAcceptedExactly() {
        BridgeNonce n = counting();
        String v = n.issueFor(GV);
        assertEquals(32, v.length());
        assertEquals(v, n.current());
        assertTrue(n.accepts(v));
        assertFalse(n.accepts(v.toUpperCase()));
        assertFalse(n.accepts(v + "0"));
        assertFalse(n.accepts(v.substring(1)));
        assertFalse(n.accepts(null));
    }

    @Test
    public void untrustedPagesNeverGetANonceAndDropTheCurrentOne() {
        for (String url : UNTRUSTED) {
            BridgeNonce n = counting();
            String v = n.issueFor(GV);
            assertNull("no nonce for " + url, n.issueFor(url));
            assertNull(n.current());
            assertFalse("the previous document's nonce is gone after " + url, n.accepts(v));
        }
    }

    @Test
    public void repeatedFinishForOneDocumentKeepsTheSameValue() {
        BridgeNonce n = counting();
        String v = n.issueFor(GV);
        assertEquals(v, n.issueFor(GV + "?itemId=t.%2B10000000000"));
        assertTrue(n.accepts(v));
    }

    @Test
    public void invalidateRotatesOnTheNextTrustedDocument() {
        BridgeNonce n = counting();
        String first = n.issueFor(GV);
        n.invalidate();
        assertNull(n.current());
        assertFalse("a call between onPageStarted and the next finish is refused", n.accepts(first));
        String second = n.issueFor(GV);
        assertNotEquals(first, second);
        assertFalse("the old document's nonce stays dead", n.accepts(first));
        assertTrue(n.accepts(second));
    }

    @Test
    public void secureGeneratorGivesDistinct128BitHexValues() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String v = BridgeNonce.SECURE.next();
            assertTrue(v, v.matches("[0-9a-f]{32}"));
            assertTrue("repeated value", seen.add(v));
        }
    }

    @Test
    public void pageStatementCarriesOnlyAHexNonce() {
        String v = String.format("%032x", 7);
        assertEquals("\n;_v2sBridgeNonce = '" + v + "';", BridgeNonce.pageStatement(v));
        String cleared = "\n;_v2sBridgeNonce = null;";
        assertEquals(cleared, BridgeNonce.pageStatement(null));
        assertEquals(cleared, BridgeNonce.pageStatement("abc"));
        assertEquals(cleared, BridgeNonce.pageStatement("'; alert(1); '0123456789abcdef"));
        assertEquals(cleared, BridgeNonce.pageStatement(v.toUpperCase().replace('0', 'G')));
    }
}
