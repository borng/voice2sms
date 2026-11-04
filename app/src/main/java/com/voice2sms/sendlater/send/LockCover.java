package com.voice2sms.sendlater.send;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import com.voice2sms.R;

/**
 * The opaque cover over GVoiceWebViewActivity's WebView while a scheduled send runs over the
 * keyguard (send-lock-cover). The WebView keeps running underneath, so inject.js can still tap
 * Send, but nobody holding the locked phone can see, touch, type into or screen-read the page:
 * <ul>
 *   <li>the cover is the top child of the content view and consumes every touch, hover, key
 *       and long-press it receives (the activity also drops input and back while covered);</li>
 *   <li>the page under it is hidden from accessibility (NO_HIDE_DESCENDANTS) and the window
 *       cannot use the soft keyboard (FLAG_ALT_FOCUSABLE_IM);</li>
 *   <li>the WebView's long-press / context menu is disabled; {@link #hide} restores it (the
 *       WebView is an app singleton and outlives this activity).</li>
 * </ul>
 * Binds the masked number only: never message text, a contact name or other threads.
 * Main thread only.
 */
final class LockCover {

    private final Activity activity;
    private View cover;
    private View page;

    LockCover(Activity activity) {
        this.activity = activity;
    }

    boolean isShown() {
        return cover != null;
    }

    /** Puts the cover over {@code pageView} (the WebView); no-op when already shown. */
    void show(View pageView) {
        if (cover != null || activity.isFinishing()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        View v = LayoutInflater.from(activity).inflate(R.layout.sendlater_send_cover, content, false);
        v.setOnTouchListener((view, event) -> true);
        v.setOnGenericMotionListener((view, event) -> true);
        v.setOnHoverListener((view, event) -> true);
        v.setOnKeyListener((view, keyCode, event) -> true);
        v.setOnLongClickListener(view -> true);
        content.addView(v, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        v.bringToFront();
        cover = v;
        page = pageView;
        if (page != null) {
            page.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            page.setOnLongClickListener(view -> true);
            page.setLongClickable(false);
            page.setHapticFeedbackEnabled(false);
        }
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        hideKeyboard();
    }

    /** Masked number under the title (LockCoverPolicy.maskedNumber); never a name or text. */
    void setRecipient(String e164) {
        if (cover == null) return;
        TextView t = cover.findViewById(R.id.sendlater_send_cover_recipient);
        String masked = LockCoverPolicy.maskedNumber(e164);
        if (t != null) {
            t.setText(masked.isEmpty() ? "" : activity.getString(R.string.sendlater_send_cover_recipient, masked));
        }
    }

    /** Removes the cover and restores the page's accessibility, long-press and the keyboard. */
    void hide() {
        if (page != null) {
            page.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            page.setOnLongClickListener(null);
            page.setLongClickable(true);
            page.setHapticFeedbackEnabled(true);
            page = null;
        }
        if (cover == null) return;
        ViewGroup parent = (ViewGroup) cover.getParent();
        if (parent != null) parent.removeView(cover);
        cover = null;
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            View focus = activity.getCurrentFocus();
            if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        } catch (RuntimeException ignored) {
            // best effort; the window flag already keeps the keyboard away
        }
    }
}
