package com.voice2sms.sendlater.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * deliver sec-M1: a per-document secret for the Send Later {@code V2SBridge} methods. Pure JVM.
 *
 * <p>A {@code @JavascriptInterface} is callable from every document and frame the WebView shows,
 * and the main-frame URL Java sees can lag behind the document that is really committed. So the
 * Send Later bridge methods also require this nonce as their first argument:
 * <ul>
 *   <li>{@link #issueFor} (main thread, onPageFinished) creates it only when the finished page
 *       is a trusted GV URL ({@link OriginGuard#isTrustedGvUrl}); the same value is kept for the
 *       rest of that document (onPageFinished can repeat for one load).</li>
 *   <li>Java injects it into the page together with the Send Later module (and the scheduled
 *       composer); inject.js keeps it in a top-frame global that no other origin can read.</li>
 *   <li>{@link #invalidate} (onPageStarted, renderer gone) drops it before the next document
 *       runs, so a page that was never trusted never learns a valid value.</li>
 *   <li>{@link #accepts} (binder thread) compares in constant time; null or stale is refused.</li>
 * </ul>
 * The value is random hex, never logged.
 */
public final class BridgeNonce {

    /** Source of fresh values; injectable for tests. */
    public interface Generator {
        String next();
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 128 random bits as 32 lowercase hex characters. */
    static final Generator SECURE = new Generator() {
        @Override
        public String next() {
            byte[] b = new byte[16];
            RANDOM.nextBytes(b);
            char[] out = new char[32];
            for (int i = 0; i < b.length; i++) {
                out[2 * i] = HEX[(b[i] >> 4) & 0xf];
                out[2 * i + 1] = HEX[b[i] & 0xf];
            }
            return new String(out);
        }
    };

    private final Generator generator;
    private volatile String current;

    public BridgeNonce() {
        this(SECURE);
    }

    public BridgeNonce(Generator generator) {
        if (generator == null) throw new IllegalArgumentException("generator");
        this.generator = generator;
    }

    /**
     * Main thread, onPageFinished: the nonce for the finished document at {@code url}. A trusted
     * GV URL keeps the current value or creates one; any other URL drops it and answers null.
     */
    public synchronized String issueFor(String url) {
        if (!OriginGuard.isTrustedGvUrl(url)) {
            current = null;
            return null;
        }
        String n = current;
        if (n == null) {
            n = generator.next();
            current = n;
        }
        return n;
    }

    /** The value issued for the current document, or null (none issued since the last invalidate). */
    public String current() {
        return current;
    }

    /** Main thread, onPageStarted / renderer gone: no call is accepted until the next issue. */
    public synchronized void invalidate() {
        current = null;
    }

    /** Binder thread: true only for exactly the current value (constant-time compare). */
    public boolean accepts(String candidate) {
        String n = current;
        if (n == null || candidate == null) return false;
        return MessageDigest.isEqual(n.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The JS statement that hands {@code nonce} to inject.js ({@code _v2sBridgeNonce}); a null
     * nonce clears it. The value is hex only, so it needs no escaping beyond the check here.
     */
    public static String pageStatement(String nonce) {
        if (nonce == null || !nonce.matches("[0-9a-f]{16,64}")) return "\n;_v2sBridgeNonce = null;";
        return "\n;_v2sBridgeNonce = '" + nonce + "';";
    }
}
