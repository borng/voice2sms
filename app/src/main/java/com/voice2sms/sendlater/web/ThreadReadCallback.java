package com.voice2sms.sendlater.web;

import java.util.List;

/**
 * Result of {@code GVoiceWebViewActivity.readThread(e164, limit, cb)}. Called on the main thread.
 * {@code bubbles} is null when the WebView is not showing that 1:1 thread, the page did not answer
 * in time, or the bubbles could not be read; otherwise oldest first, newest last (may be empty).
 */
public interface ThreadReadCallback {
    void onThreadRead(List<ThreadBubble> bubbles);
}
