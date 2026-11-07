package com.voice2sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * R44 / D29: Enter in the GV WebView composer inserts a newline; only the Send button sends.
 * Source-level checks of inject.js v2sInstallEnterNewline() (no JS engine on the JVM test
 * classpath), its install from GVoiceWebViewActivity, and the EditorInfo flags set by
 * RichContentWebView. Gradle runs :app tests with cwd = app/.
 */
public class EnterNewlineTest {

    private static final String INJECT_JS = "src/main/assets/inject.js";
    private static final String ACTIVITY = "src/main/java/com/voice2sms/GVoiceWebViewActivity.java";
    private static final String WEBVIEW = "src/main/java/com/voice2sms/RichContentWebView.java";

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** Body (between the outer braces) of a top-level function, by brace counting. */
    private static String functionBody(String src, String name) {
        Matcher m = Pattern.compile("(?m)^function " + Pattern.quote(name) + "\\s*\\(").matcher(src);
        assertTrue("inject.js does not define " + name, m.find());
        int open = src.indexOf('{', m.end());
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open + 1, i);
        }
        throw new AssertionError("unbalanced braces in " + name);
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    // ---------------------------------------------------------------- inject.js listener

    @Test
    public void installIsIdempotentAndUsesCapturePhaseOnDocument() throws IOException {
        String body = stripComments(functionBody(read(INJECT_JS), "v2sInstallEnterNewline"));
        assertTrue("once per document", body.contains("if (window._v2sEnterNewline) return;"));
        assertTrue("keydown capture listener on document",
                Pattern.compile("document\\.addEventListener\\('keydown',[\\s\\S]*?\\}, true\\);")
                        .matcher(body).find());
        assertTrue("beforeinput capture listener on document",
                Pattern.compile("document\\.addEventListener\\('beforeinput',[\\s\\S]*?\\}, true\\);")
                        .matcher(body).find());
        assertTrue("keypress swallowed in capture", body.contains("document.addEventListener('keypress', swallow, true);"));
        assertTrue("keyup swallowed in capture", body.contains("document.addEventListener('keyup', swallow, true);"));
        assertFalse("never intercepts clicks (Send button, clickSend)", body.contains("'click'"));
        assertFalse("never intercepts pointer/touch", body.matches("(?s).*'(pointer|touch|mouse)\\w*'.*"));
    }

    @Test
    public void keydownIsComposerOnlyAndBlocksGvHandlers() throws IOException {
        String body = stripComments(functionBody(read(INJECT_JS), "v2sInstallEnterNewline"));
        assertTrue(body.contains("if (!_v2sIsComposer(e.target) || !_v2sIsPlainEnter(e)) return;"));
        assertTrue(body.contains("e.preventDefault();"));
        assertTrue(body.contains("e.stopImmediatePropagation();"));
        assertTrue(body.contains("_v2sInsertNewline(e.target);"));
        assertTrue("beforeinput covers insertLineBreak", body.contains("'insertLineBreak'"));
        assertTrue("beforeinput covers insertParagraph", body.contains("'insertParagraph'"));
        assertTrue("beforeinput leaves composition alone", body.contains("if (e.isComposing) return;"));
    }

    @Test
    public void composerSelectorsMatchFillBody() throws IOException {
        String src = read(INJECT_JS);
        String fill = functionBody(src, "voice2sms");
        int at = fill.indexOf("function fillBody(");
        assertTrue(at >= 0);
        String[] selectors = {
            "textarea[placeholder=\"Type a message\"]",
            "textarea[placeholder*=\"message\"]",
            "textarea[placeholder*=\"text\"]",
            "textarea[aria-label*=\"message\"]",
            "[contenteditable=\"true\"][aria-label*=\"message\"]"
        };
        int decl = src.indexOf("var V2S_COMPOSER_SELECTOR");
        assertTrue("V2S_COMPOSER_SELECTOR declared", decl >= 0);
        String selectorDecl = src.substring(decl, src.indexOf(';', decl));
        for (String sel : selectors) {
            assertTrue("fillBody uses " + sel, fill.indexOf(sel, at) >= 0);
            assertTrue("composer selector includes " + sel, selectorDecl.contains(sel));
        }
        String isComposer = functionBody(src, "_v2sIsComposer");
        assertTrue(isComposer.contains("el.matches(V2S_COMPOSER_SELECTOR)"));
    }

    @Test
    public void imeCompositionIsUntouched() throws IOException {
        String body = stripComments(functionBody(read(INJECT_JS), "_v2sIsPlainEnter"));
        assertTrue(body.contains("if (e.isComposing || e.keyCode === 229) return false;"));
        assertTrue("Enter with or without Shift (no shiftKey test)", !body.contains("shiftKey"));
        assertTrue(body.contains("e.key === 'Enter' || e.keyCode === 13"));
    }

    @Test
    public void newlineGoesThroughAngularSafeSetter() throws IOException {
        String body = stripComments(functionBody(read(INJECT_JS), "_v2sInsertNewline"));
        assertTrue("native textarea value setter",
                body.contains("Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value')"));
        assertTrue(body.contains("nativeSetter.set.call(el, next);"));
        assertTrue("newline replaces the selection",
                body.contains("value.slice(0, start) + '\\n' + value.slice(end)"));
        assertTrue("caret after the newline", body.contains("var caret = start + 1;")
                && body.contains("el.setSelectionRange(caret, caret);"));
        assertTrue("input event in the Angular zone", body.contains("_v2sGetAngularZone(el)")
                && body.contains("angularZone.run(function() { el.dispatchEvent(inputEvent); });"));
        assertTrue(body.contains("inputType: 'insertLineBreak'"));
        assertFalse("never logs composer text", body.contains("console.log"));
    }

    @Test
    public void composeAndAutoSendFlowUntouched() throws IOException {
        String src = read(INJECT_JS);
        String compose = functionBody(src, "voice2sms");
        assertFalse("voice2sms() does not depend on the Enter module",
                compose.contains("v2sInstallEnterNewline") || compose.contains("_v2sInsertNewline"));
        assertTrue("auto-send still clicks Send", compose.contains("setTimeout(function() { clickSend(5); }, jitteredDelay);"));
        String sl = functionBody(src, "v2sSendLaterInit");
        assertFalse("independent of Send Later", sl.contains("v2sInstallEnterNewline"));
    }

    // ---------------------------------------------------------------- activity install

    @Test
    public void activityInstallsOncePerPageLoadIndependentOfSendLater() throws IOException {
        String src = read(ACTIVITY);
        assertTrue("onPageFinished installs on GV pages regardless of Send Later",
                src.contains("if (trustedGv) installEnterNewline();")
                        && src.contains("final boolean trustedGv = OriginGuard.isTrustedGvUrl(url);"));
        int at = src.indexOf("private void installEnterNewline()");
        assertTrue(at >= 0);
        String method = src.substring(at, src.indexOf("\n    }\n", at));
        assertTrue("guarded by isActivityAlive", method.contains("if (!isActivityAlive()) return;"));
        assertTrue(method.contains("\"\\nv2sInstallEnterNewline();\""));
        assertFalse(method.contains("sendLaterHooks"));
    }

    // ---------------------------------------------------------------- EditorInfo flags

    @Test
    public void webViewAppliesNoEnterActionAndKeepsStickers() throws IOException {
        String src = read(WEBVIEW);
        assertTrue(src.contains(
                "outAttrs.imeOptions = imeOptionsWithoutEnterAction(outAttrs.imeOptions, outAttrs.inputType);"));
        assertTrue("sticker MIME types kept",
                src.contains("EditorInfoCompat.setContentMimeTypes(outAttrs, IMAGE_MIME_TYPES);"));
        assertTrue(src.contains("InputConnectionCompat.createWrapper(this, ic, outAttrs)"));
        assertTrue("flags set before the wrapper is built",
                src.indexOf("imeOptionsWithoutEnterAction(outAttrs.imeOptions")
                        < src.indexOf("InputConnectionCompat.createWrapper"));
    }

    private static final int TEXTAREA = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;

    @Test
    public void multiLineSendBecomesReturnKey() {
        int in = EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        int out = RichContentWebView.imeOptionsWithoutEnterAction(in, TEXTAREA);
        assertEquals(EditorInfo.IME_ACTION_NONE, out & EditorInfo.IME_MASK_ACTION);
        assertTrue((out & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0);
        assertTrue("other flags kept", (out & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0);
    }

    @Test
    public void multiLineWithoutActionGetsNoEnterAction() {
        int out = RichContentWebView.imeOptionsWithoutEnterAction(EditorInfo.IME_ACTION_UNSPECIFIED, TEXTAREA);
        assertEquals(EditorInfo.IME_ACTION_NONE, out & EditorInfo.IME_MASK_ACTION);
        assertTrue((out & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0);
    }

    @Test
    public void singleLineSendAlsoLosesSend() {
        int out = RichContentWebView.imeOptionsWithoutEnterAction(EditorInfo.IME_ACTION_SEND,
                InputType.TYPE_CLASS_TEXT);
        assertEquals(EditorInfo.IME_ACTION_NONE, out & EditorInfo.IME_MASK_ACTION);
        assertTrue((out & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0);
    }

    @Test
    public void singleLineSearchAndGoUnchanged() {
        int text = InputType.TYPE_CLASS_TEXT;
        assertEquals(EditorInfo.IME_ACTION_SEARCH,
                RichContentWebView.imeOptionsWithoutEnterAction(EditorInfo.IME_ACTION_SEARCH, text));
        assertEquals(EditorInfo.IME_ACTION_GO,
                RichContentWebView.imeOptionsWithoutEnterAction(EditorInfo.IME_ACTION_GO, text));
        assertEquals(EditorInfo.IME_ACTION_NEXT,
                RichContentWebView.imeOptionsWithoutEnterAction(EditorInfo.IME_ACTION_NEXT,
                        InputType.TYPE_CLASS_PHONE));
    }
}
