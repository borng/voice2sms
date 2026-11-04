package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Acceptance (webview-capture, R10/R11/R17/R18/R19/I4/I8): source-level checks of the Send Later
 * module in {@code app/src/main/assets/inject.js}. There is no JS engine on the JVM test
 * classpath, so these read the asset as text: they find the module's entry points, follow the
 * named functions they call, and assert the pinned bridge calls, URL gate, Angular-safe clear
 * and guarded Undo.
 */
public class InjectJsSendLaterTest {

    /** Any helper whose name contains setInputValue is the Angular-safe setter (hoisted or copied). */
    private static final Pattern SET_INPUT_VALUE = Pattern.compile("(?i)setInputValue");

    /** Top-level functions of the pre-existing compose/attach flows; never part of the module. */
    private static final Set<String> LEGACY_ROOTS =
            new HashSet<>(Arrays.asList("voice2sms", "sendImageToGV"));

    private static final String[] ENTRY_POINTS = {
            "v2sSendLaterInit", "v2sSendLaterRefresh", "v2sSendLaterClear", "v2sReadThread"};

    /**
     * SHA-256 of voice2sms()'s body on main @ 7fa8184, comments blanked, any setInputValue helper
     * removed (so hoisting it out of voice2sms is allowed), whitespace collapsed.
     */
    private static final String VOICE2SMS_BODY_SHA256 =
            "21e09c1c0d0cba1422b746e9166d71fc0756994bddb8f16a8e4c4a91fac0b588";

    private static JsSource js() throws Exception {
        return JsSource.injectJs();
    }

    private static List<JsSource.Fn> fns(JsSource js, String name) {
        List<JsSource.Fn> l = js.named(name);
        assertFalse("inject.js does not define " + name, l.isEmpty());
        return l;
    }

    /** All Send Later module code reachable from the four entry points (legacy flows excluded). */
    private static String moduleCode(JsSource js) {
        List<JsSource.Fn> roots = new ArrayList<>();
        for (String e : ENTRY_POINTS) roots.addAll(fns(js, e));
        return js.closure(roots, LEGACY_ROOTS, SET_INPUT_VALUE);
    }

    private static String closureOf(JsSource js, String name) {
        return js.closure(fns(js, name), LEGACY_ROOTS, null);
    }

    private static boolean callsBridge(String code, String method) {
        return Pattern.compile("V2SBridge\\s*\\.\\s*" + method + "\\s*\\(").matcher(code).find();
    }

    // ---------------------------------------------------------------- entry points

    @Test
    public void v2sSendLaterInit_isATopLevelFunctionOutsideVoice2sms() throws Exception {
        JsSource js = js();
        boolean declared = Pattern.compile("(?m)^function\\s+v2sSendLaterInit\\s*\\(").matcher(js.code).find()
                || Pattern.compile("(?m)^\\s*window\\.v2sSendLaterInit\\s*=").matcher(js.code).find();
        assertTrue("v2sSendLaterInit() must be a top-level entry (function or window.v2sSendLaterInit =)",
                declared);
        JsSource.Fn voice2sms = js.first("voice2sms");
        assertNotNull(voice2sms);
        for (JsSource.Fn f : fns(js, "v2sSendLaterInit")) {
            assertFalse("v2sSendLaterInit must be independent of voice2sms(), not nested in it",
                    f.start > voice2sms.start && f.end < voice2sms.end);
        }
    }

    @Test
    public void windowEntryPoints_areAssignedWithPinnedNames() throws Exception {
        JsSource js = js();
        for (String n : new String[]{"v2sSendLaterRefresh", "v2sSendLaterClear", "v2sReadThread"}) {
            assertTrue("inject.js must assign window." + n,
                    Pattern.compile("window\\s*\\.\\s*" + n + "\\s*=").matcher(js.code).find());
            fns(js, n);
        }
    }

    @Test
    public void v2sSendLaterClear_takesMessageIdExpectedDraftAndLabel() throws Exception {
        JsSource.Fn clear = fns(js(), "v2sSendLaterClear").get(0);
        assertEquals("window.v2sSendLaterClear(messageId, expectedDraft, scheduledLabel)",
                3, clear.params.size());
    }

    @Test
    public void v2sReadThread_takesE164AndLimit() throws Exception {
        JsSource.Fn read = fns(js(), "v2sReadThread").get(0);
        assertEquals("window.v2sReadThread(e164, limit)", 2, read.params.size());
    }

    // ---------------------------------------------------------------- bridge calls

    @Test
    public void module_callsEveryPinnedBridgeMethod() throws Exception {
        String module = moduleCode(js());
        for (String m : new String[]{"requestSendLater", "sendLaterChipState", "requestSendLaterChipTap",
                "requestSendLaterUndo", "onThreadRead", "onThreadDirectory"}) {
            assertTrue("Send Later module never calls V2SBridge." + m + "(...)", callsBridge(module, m));
        }
    }

    @Test
    public void refresh_readsChipStateFromTheBridge() throws Exception {
        // R17: chip text comes from V2SBridge.sendLaterChipState(e164); v2sSendLaterRefresh re-reads it.
        assertTrue("window.v2sSendLaterRefresh must (re)read V2SBridge.sendLaterChipState",
                callsBridge(closureOf(js(), "v2sSendLaterRefresh"), "sendLaterChipState"));
    }

    @Test
    public void readThread_reportsBubblesThroughOnThreadRead() throws Exception {
        String read = closureOf(js(), "v2sReadThread");
        assertTrue("v2sReadThread must answer via V2SBridge.onThreadRead(json)", callsBridge(read, "onThreadRead"));
        assertTrue("onThreadRead payload must be JSON", read.contains("JSON.stringify"));
    }

    @Test
    public void capturePayload_carriesThePinnedFieldNames() throws Exception {
        String module = moduleCode(js());
        for (String field : new String[]{"e164", "displayName", "draftText", "newestMessageId",
                "newestMessageTimeMs", "watermarkQuality"}) {
            assertTrue("capture payload field missing from the module: " + field, module.contains(field));
        }
        assertTrue("capture payload must be sent as JSON", module.contains("JSON.stringify"));
    }

    // ---------------------------------------------------------------- 1:1 URL gate (R10, F7)

    @Test
    public void threadGate_parsesItemIdWithUrlSearchParamsAndThePinnedPattern() throws Exception {
        String module = moduleCode(js());
        assertTrue("thread detection must parse the query with URLSearchParams", module.contains("URLSearchParams"));
        assertTrue("thread detection must read the itemId parameter", module.contains("itemId"));
        assertTrue("1:1 pattern ^t\\.\\+\\d{7,15}$ must be the gate",
                module.contains("^t\\.\\+\\d{7,15}$"));
    }

    @Test
    public void tabAndChip_useTheSharedAccentTokens() throws Exception {
        JsSource js = js();
        fns(js, "v2sSendLaterInit");
        String module = js.code.toUpperCase(); // tokens may live in module-level constants
        // OD9 / A4: light-blue accent #8AB4F8 with #0B1D3A text, same token as the sheet.
        assertTrue("accent #8AB4F8 missing", module.contains("#8AB4F8"));
        assertTrue("accent text colour #0B1D3A missing", module.contains("#0B1D3A"));
    }

    // ---------------------------------------------------------------- clear (R11)

    @Test
    public void clear_usesSetInputValueAndNoRawValueAssignments() throws Exception {
        JsSource js = js();
        String clear = js.closure(fns(js, "v2sSendLaterClear"), LEGACY_ROOTS, null);
        assertTrue("v2sSendLaterClear must clear through setInputValue (Angular-safe)",
                Pattern.compile("(?i)setInputValue\\w*\\s*\\(").matcher(clear).find());

        String module = moduleCode(js);
        Matcher raw = Pattern.compile("\\.value\\s*=(?!=)").matcher(module);
        if (raw.find()) {
            throw new AssertionError("Send Later module assigns .value directly outside the "
                    + "setInputValue helper: ..." + snippet(module, raw.start()) + "...");
        }
    }

    @Test
    public void clear_comparesTheLiveComposerWithExpectedDraft() throws Exception {
        JsSource js = js();
        JsSource.Fn clear = fns(js, "v2sSendLaterClear").get(0);
        String expectedDraft = clear.params.get(1);
        String body = js.closure(Collections.singletonList(clear), LEGACY_ROOTS, null);
        assertTrue("v2sSendLaterClear never compares against its expectedDraft parameter",
                Pattern.compile("\\b" + Pattern.quote(expectedDraft) + "\\b").matcher(clear.body).find());
        assertTrue("v2sSendLaterClear must check the thread URL before clearing", mentionsLocation(body));
    }

    @Test
    public void clear_showsPinnedConfirmationAndFallbackTexts() throws Exception {
        String clear = closureOf(js(), "v2sSendLaterClear");
        assertTrue("confirmation bar must offer Undo", clear.contains("Undo"));
        assertTrue("fallback text 'Saved to Upcoming \u2014 draft left as is' missing",
                clear.contains("Saved to Upcoming \u2014 draft left as is")
                        || clear.contains("Saved to Upcoming \\u2014 draft left as is"));
        assertTrue("Undo bar must last 10 s",
                Pattern.compile("\\b10000\\b|\\b10\\s*\\*\\s*1000\\b|\\b1000\\s*\\*\\s*10\\b").matcher(clear).find());
    }

    // ---------------------------------------------------------------- undo (R11, I4)

    @Test
    public void undo_isGuardedByEmptyFieldAndSameUrl() throws Exception {
        JsSource js = js();
        Matcher call = Pattern.compile("V2SBridge\\s*\\.\\s*requestSendLaterUndo\\s*\\(").matcher(js.code);
        assertTrue("no V2SBridge.requestSendLaterUndo(...) call in inject.js", call.find());
        do {
            JsSource.Fn handler = js.enclosing(call.start());
            assertNotNull("requestSendLaterUndo must be called from inside a function", handler);
            String before = js.code.substring(handler.start, call.start());
            String reach = before + "\n" + js.closure(Collections.singletonList(handler), LEGACY_ROOTS, null);
            assertTrue("Undo handler calls requestSendLaterUndo without a preceding if-guard",
                    Pattern.compile("\\bif\\s*\\(").matcher(before).find());
            assertTrue("Undo must check the composer is still empty (I4)", checksEmpty(reach));
            assertTrue("Undo must check the thread URL is unchanged", mentionsLocation(reach));
        } while (call.find());
    }

    // ---------------------------------------------------------------- unchanged compose flow (I8)

    @Test
    public void voice2smsComposeFlow_isUnchanged() throws Exception {
        JsSource js = js();
        JsSource stripped = JsSource.of(js.withoutNamed(SET_INPUT_VALUE));
        JsSource.Fn v = stripped.first("voice2sms");
        assertNotNull("voice2sms() missing", v);
        assertEquals("voice2sms(phone, body, autoSend, autoSendDelay) signature changed",
                Arrays.asList("phone", "body", "autoSend", "autoSendDelay"), v.params);
        String norm = v.body.replaceAll("\\s+", " ").trim();
        assertEquals("voice2sms() body changed - the compose/auto-send flow must stay as on main",
                VOICE2SMS_BODY_SHA256, sha256(norm));
    }

    @Test
    public void setInputValueHelper_stillExists() throws Exception {
        JsSource js = js();
        boolean found = false;
        for (JsSource.Fn f : js.allFunctions()) {
            if (f.name != null && SET_INPUT_VALUE.matcher(f.name).find()
                    && f.body.contains("InputEvent")) {
                found = true;
            }
        }
        assertTrue("the Angular-safe setInputValue helper (InputEvent dispatch) is gone", found);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean mentionsLocation(String code) {
        return Pattern.compile("\\blocation\\s*\\.\\s*(href|search)\\b|\\blocation\\b").matcher(code).find();
    }

    private static boolean checksEmpty(String code) {
        return Pattern.compile(
                "(===?|!==?)\\s*(''|\"\")"
                        + "|(''|\"\")\\s*(===?|!==?)"
                        + "|\\.length\\s*(===?|!==?|>|<=?)\\s*0\\b"
                        + "|!\\s*[\\w$.]*\\.value\\b"
                        + "|!\\s*\\(?\\s*[\\w$.]*\\.value\\s*\\|\\|").matcher(code).find();
    }

    private static String snippet(String s, int at) {
        return s.substring(Math.max(0, at - 40), Math.min(s.length(), at + 40)).replaceAll("\\s+", " ");
    }

    private static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
