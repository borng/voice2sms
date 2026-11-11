package com.voice2sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.voice2sms.sendlater.web.ChipState;
import com.voice2sms.sendlater.web.LiveDelegate;

import org.junit.Test;

/**
 * LIVE-F13: with no GVoiceWebViewActivity attached, the page's stable V2SBridge gives every
 * method's existing refusal answer and never throws.
 */
public class PageBridgeTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static GVoiceWebViewActivity.PageBridge detached() {
        return new GVoiceWebViewActivity.PageBridge(new LiveDelegate());
    }

    @Test
    public void noActivity_answersAreTheSafeRefusals() {
        GVoiceWebViewActivity.PageBridge b = detached();
        assertEquals(ChipState.disabled(), b.sendLaterChipState("n", "+12025550123"));
        assertFalse(b.requestSendLaterUndo("n", 7L));
        assertEquals("no", b.scheduledClickState("n", "t"));
        assertEquals("no", b.scheduledClickStateAt("n", "t", "https://voice.google.com/u/0/messages"));
        assertEquals("no", b.scheduledClickStateIn("n", "t", "https://voice.google.com/u/0/messages", 1, "2025550123"));
        assertEquals("stop", b.scheduledDraftStep("n", "t", "draft"));
    }

    @Test
    public void noActivity_voidCallsAreNoOps() {
        GVoiceWebViewActivity.PageBridge b = detached();
        b.requestTapAndType(1f, 2f, "x");
        b.requestType("x");
        b.requestShowKeyboard();
        b.requestPageReload();
        b.requestFocusAndKeyboard(1f, 2f);
        b.requestSendLater("n", "{}");
        b.requestSendLaterChipTap("n", "+12025550123");
        b.onThreadRead("n", "{}");
        b.onScheduledTyped("n", "t");
        b.onScheduledNoThread("n", "t");
        b.onScheduledWrongPage("n", "t");
        b.onScheduledDraftPresent("n", "t");
        b.onThreadDirectory("n", "{}");
    }
}
