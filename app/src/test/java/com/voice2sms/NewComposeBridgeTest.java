package com.voice2sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.voice2sms.sendlater.web.ChipState;
import com.voice2sms.sendlater.web.LiveDelegate;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LIVE-F14 / R10a (D46, OD36): the bridge method the new-message page uses to have the live
 * activity confirm its single recipient chip.
 *
 * <p>Interface asserted: {@code @JavascriptInterface public String
 * sendLaterNewComposeState(String nonce, int chipCount, String e164)} on V2SBridge and its
 * PageBridge forwarder. It answers chip-state JSON (the {@code sendLaterChipState} shape):
 * {@code ChipState.disabled()} on a bad nonce, an untrusted main frame, or with no Activity
 * attached; it checks the page's E.164 against the live activity's intent recipient
 * ({@code NewComposeRecipient.confirm}). The PageBridge half is called by reflection, so this file
 * compiles before the method exists; the V2SBridge half is read as source like SourceSafetyTest.
 */
public class NewComposeBridgeTest {

    private static final String NAME = "sendLaterNewComposeState";
    private static final Path ACTIVITY = Paths.get("src/main/java/com/voice2sms/GVoiceWebViewActivity.java");
    private static final Path INJECT = Paths.get("src/main/assets/inject.js");

    // ------------------------------------------------------------------ source helpers

    private static String read(Path p) throws IOException {
        assertTrue("missing: " + p, Files.exists(p));
        String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)^\\s*//[^\\n]*", "").replaceAll("\\s//[^\\n\"']*", "");
    }

    private static String body(String src, String signature) {
        Matcher m = Pattern.compile(signature).matcher(src);
        if (!m.find()) return null;
        int open = src.indexOf('{', m.end() - 1);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(m.start(), i + 1);
        }
        return null;
    }

    private static String bridgeMethod(String cls) throws IOException {
        String klass = body(read(ACTIVITY), cls);
        assertNotNull("class not found: " + cls, klass);
        String b = body(klass, "@JavascriptInterface\\s+public\\s+(final\\s+)?String\\s+" + NAME
                + "\\s*\\(\\s*String\\s+nonce\\s*,\\s*int\\s+chipCount\\s*,\\s*String\\s+e164\\s*\\)\\s*\\{");
        assertNotNull(cls + " must declare @JavascriptInterface public String " + NAME
                + "(String nonce, int chipCount, String e164) (R10a)", b);
        return b;
    }

    // ------------------------------------------------------------------ V2SBridge (source)

    @Test
    public void v2sBridgeMethod_checksNonceAndOriginFirst_andRefusesWithTheDisabledChip() throws IOException {
        String b = bridgeMethod("private\\s+class\\s+V2SBridge\\s*\\{");
        String inner = b.substring(b.indexOf('{') + 1).trim();
        assertTrue(NAME + ": the first statement checks the nonce and the origin together",
                Pattern.compile("^(final\\s+String\\s+url\\s*=\\s*mainFrameUrl\\s*;\\s*)?"
                        + "if\\s*\\(\\s*!BRIDGE_NONCE\\.accepts\\(nonce\\)\\s*\\|\\|\\s*"
                        + "!OriginGuard\\.isTrustedGvUrl\\((mainFrameUrl|url)\\)\\s*\\)\\s*(\\{\\s*)?"
                        + "return\\s+ChipState\\.disabled\\(\\)")
                        .matcher(inner).find());
    }

    @Test
    public void v2sBridgeMethod_pairsAgainstTheLiveActivitysRecipient() throws IOException {
        String b = bridgeMethod("private\\s+class\\s+V2SBridge\\s*\\{");
        String inner = b.substring(b.indexOf('{') + 1);
        // Not page text alone: the activity's own intent recipient goes into the decision.
        assertTrue(NAME + " must read the activity's intent recipient",
                Pattern.compile("\\brecipient\\b").matcher(inner).find());
        assertTrue(NAME + " must pass chipCount on to the pairing rule",
                Pattern.compile("\\bchipCount\\b").matcher(inner).find());
    }

    @Test
    public void pageBridge_forwardsTheNewMethod() throws IOException {
        String b = bridgeMethod("static\\s+final\\s+class\\s+PageBridge\\s*\\{");
        assertTrue("PageBridge." + NAME + " must forward to the live V2SBridge",
                Pattern.compile("\\." + NAME + "\\s*\\(\\s*nonce\\s*,\\s*chipCount\\s*,\\s*e164\\s*\\)")
                        .matcher(b).find());
    }

    // ------------------------------------------------------------------ PageBridge (behaviour)

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void noLiveActivity_answersTheDisabledChip() throws Exception {
        GVoiceWebViewActivity.PageBridge pb = new GVoiceWebViewActivity.PageBridge(new LiveDelegate());
        Method m;
        try {
            m = GVoiceWebViewActivity.PageBridge.class.getDeclaredMethod(NAME, String.class, int.class, String.class);
        } catch (NoSuchMethodException e) {
            fail("missing PageBridge." + NAME + "(String, int, String) (R10a)");
            return;
        }
        m.setAccessible(true);
        try {
            assertEquals(ChipState.disabled(), m.invoke(pb, "n", 1, "+12025550123"));
            assertEquals(ChipState.disabled(), m.invoke(pb, null, 1, null));
        } catch (InvocationTargetException e) {
            throw new AssertionError(NAME + " must not throw with no Activity", e.getCause());
        }
    }

    // ------------------------------------------------------------------ page side

    @Test
    public void injectJs_asksTheNewBridgeMethodWithTheNonce_andKeepsTheThreadPath() throws IOException {
        String js = read(INJECT);
        assertTrue("inject.js must ask V2SBridge." + NAME + " with the page nonce on the new-message page",
                Pattern.compile("V2SBridge\\." + NAME + "\\(\\s*_v2sBridgeNonce\\s*,").matcher(js).find());
        // R10 unchanged: a 1:1 thread still asks sendLaterChipState for the URL's E.164.
        assertTrue(Pattern.compile("V2SBridge\\.sendLaterChipState\\(\\s*_v2sBridgeNonce\\s*,").matcher(js).find());
        assertTrue(js.contains("function _v2sSlThreadE164()"));
    }
}
