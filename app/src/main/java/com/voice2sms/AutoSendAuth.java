package com.voice2sms;

import android.content.Intent;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Proves that a {@code force_auto_send} request came from inside this process.
 *
 * <p>SmsHandlerActivity is exported (it is the SENDTO / VIEW / SEND target), so any app could
 * put {@code force_auto_send=true} on an intent and make voice2sms send an SMS without review.
 * The app's own auto-send origins (GeminiSmsInterceptService, WearSmsListenerService,
 * RespondViaMessageService) call {@link #attach(Intent)} next to the force flag, and the two
 * consumers (SmsHandlerActivity, GVoiceWebViewActivity) honour the flag only when
 * {@link #isAuthorized(Intent)} is true. Anything else falls back to the normal review mode.
 *
 * <p>The token is a per-process random value: it never leaves the process except inside
 * explicit or package-scoped intents and FLAG_IMMUTABLE PendingIntents owned by this app, and
 * it is never logged. After a process restart an old token simply stops matching, which means
 * review mode, never a send.
 */
public final class AutoSendAuth {

    private AutoSendAuth() {}

    /** Intent extra carrying the in-process token. Never logged. */
    public static final String EXTRA_TOKEN = "com.voice2sms.extra.AUTO_SEND_TOKEN";

    private static final int TOKEN_BYTES = 32;

    private static final String TOKEN = newToken(new SecureRandom());

    /** Hex string of {@link #TOKEN_BYTES} random bytes from {@code random}. */
    static String newToken(Random random) {
        byte[] b = new byte[TOKEN_BYTES];
        random.nextBytes(b);
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(TOKEN_BYTES * 2);
        for (byte x : b) {
            sb.append(hex[(x >> 4) & 0xf]).append(hex[x & 0xf]);
        }
        return sb.toString();
    }

    /** This process's token (package-private: tests and this class only). */
    static String token() {
        return TOKEN;
    }

    /** Constant-time (for equal lengths) comparison; null or empty never matches. */
    static boolean matches(String expected, String presented) {
        if (expected == null || presented == null || expected.isEmpty()) return false;
        if (expected.length() != presented.length()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ presented.charAt(i);
        }
        return diff == 0;
    }

    /** Pure decision: auto-send only when it was requested AND the token is this process's. */
    static boolean authorized(boolean requested, String presented) {
        return requested && matches(TOKEN, presented);
    }

    /** Adds this process's token. Call right after putting force_auto_send=true on an app-owned intent. */
    public static Intent attach(Intent intent) {
        intent.putExtra(EXTRA_TOKEN, TOKEN);
        return intent;
    }

    /** True only when the intent asks for auto-send and carries this process's token. */
    public static boolean isAuthorized(Intent intent) {
        if (intent == null) return false;
        try {
            return authorized(intent.getBooleanExtra(SmsHandlerActivity.EXTRA_FORCE_AUTO_SEND, false),
                    intent.getStringExtra(EXTRA_TOKEN));
        } catch (RuntimeException e) {
            // Malformed extras from a foreign caller (BadParcelableException etc.): review mode.
            return false;
        }
    }

    /** Removes the token (and so any power it had) from an intent that has been read. */
    public static void strip(Intent intent) {
        if (intent == null) return;
        try {
            intent.removeExtra(EXTRA_TOKEN);
        } catch (RuntimeException ignored) {
            // Nothing to strip from unparcelable extras.
        }
    }
}
