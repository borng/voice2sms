package com.voice2sms.sendlater.web;

import java.util.concurrent.atomic.AtomicReference;

/**
 * LIVE-F13: the one target a long-lived object (the WebView's stable V2SBridge) forwards to.
 *
 * <p>The singleton WebView outlives every GVoiceWebViewActivity, and an object added with
 * addJavascriptInterface only reaches the page on its next load. So the page keeps one bridge
 * object for its whole life, and that object forwards each call to whatever activity is attached
 * here right now. {@link #attach} is called from onCreate, {@link #detach} from onDestroy; a
 * detach only clears the target it was given, so an old activity's late onDestroy (after the
 * next activity's onCreate) never cuts the new one off. With nothing attached {@link #current}
 * is null and the caller answers its safe/disabled value.
 *
 * <p>Pure JVM, thread-safe: attach/detach run on the main thread, {@link #current} on the
 * WebView's binder thread. Identity (==) comparison, never equals().
 */
public final class LiveDelegate<T> {

    private final AtomicReference<T> target = new AtomicReference<>();

    /** Makes {@code t} the live target (replacing any previous one). Null is ignored. */
    public void attach(T t) {
        if (t != null) target.set(t);
    }

    /**
     * Clears the live target only if it is still {@code t}. Returns true when it was cleared;
     * false when another target had already been attached (or nothing was).
     */
    public boolean detach(T t) {
        return t != null && target.compareAndSet(t, null);
    }

    /** The live target, or null when none is attached. */
    public T current() {
        return target.get();
    }
}
