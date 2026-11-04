package com.voice2sms.sendlater.send;

import android.app.Activity;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.PopupWindow;
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
 *
 * <p>OD31 PQ3: on an unlocked phone a Stop button sits on the cover until typing starts
 * ({@link LockCoverPolicy#showStop}). It lives in its own small window (a PopupWindow anchored
 * to the cover), because the activity drops every touch on its own window while covered; nothing
 * else on the cover becomes touchable.
 * Main thread only.
 */
final class LockCover {

    private final Activity activity;
    private View cover;
    private View page;

    private PopupWindow stop;
    private boolean stopWanted;
    private Runnable onStop;

    LockCover(Activity activity) {
        this.activity = activity;
    }

    boolean isStopShown() {
        return stop != null;
    }

    /**
     * Shows or removes the Stop button (OD31 PQ3). {@code onStopTap} runs on the main thread;
     * the caller re-checks the policy before acting. No-op without a cover.
     */
    void setStopVisible(boolean visible, Runnable onStopTap) {
        stopWanted = visible && cover != null;
        onStop = onStopTap;
        if (!stopWanted) {
            hideStop();
            return;
        }
        if (stop != null) return;
        final View anchor = cover;
        // A popup needs the window token: post runs once the cover is attached.
        anchor.post(() -> {
            if (stopWanted && stop == null && cover == anchor && !activity.isFinishing()) showStopNow(anchor);
        });
    }

    private void showStopNow(View anchor) {
        Button b = new Button(activity);
        b.setText(R.string.sendlater_send_cover_stop);
        b.setContentDescription(activity.getString(R.string.sendlater_send_cover_stop_description));
        int min = dp(48);
        b.setMinHeight(min);
        b.setMinimumHeight(min);
        b.setMinWidth(dp(120));
        b.setMinimumWidth(dp(120));
        b.setOnClickListener(v -> {
            Runnable r = onStop;
            if (r != null) r.run();
        });
        PopupWindow w = new PopupWindow(b, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        w.setTouchable(true);
        w.setOutsideTouchable(false);
        try {
            w.showAtLocation(anchor, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(72));
            stop = w;
        } catch (RuntimeException e) {
            // no window token (finishing): no Stop; Home still leaves, the job still times out
        }
    }

    private void hideStop() {
        PopupWindow w = stop;
        stop = null;
        if (w == null) return;
        try {
            w.dismiss();
        } catch (RuntimeException ignored) {
            // window already gone
        }
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
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
        stopWanted = false;
        hideStop();
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
