package com.voice2sms;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.preference.PreferenceManager;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monitors Gemini's SMS compose overlay and intercepts the Send button tap.
 *
 * Strategy (revised after testing):
 *   - The SMS card Send button returns null source on TYPE_VIEW_CLICKED
 *   - So we CACHE phone+body when the SMS card appears (TYPE_WINDOW_STATE_CHANGED
 *     and TYPE_WINDOW_CONTENT_CHANGED)
 *   - When a null-source Button click occurs with cached data, we fire the intent
 *
 * Requires one-time ADB setup to block Gemini's direct SMS:
 *   adb shell pm revoke com.google.android.googlequicksearchbox android.permission.SEND_SMS
 *   adb shell pm set-permission-flags ... android.permission.SEND_SMS user-fixed
 *   adb shell appops set com.google.android.googlequicksearchbox SEND_SMS ignore
 */
public class GeminiSmsInterceptService extends AccessibilityService {

    private static final String TAG = "Voice2SMS";
    private static final String GEMINI_PACKAGE = "com.google.android.googlequicksearchbox";

    // Resource IDs from Gemini's FloatyActivity
    private static final String RES_SEND_BUTTON =
            GEMINI_PACKAGE + ":id/assistant_robin_lockscreen_compatible_action_card_button_element";
    private static final String RES_TITLE_TEXT =
            GEMINI_PACKAGE + ":id/assistant_robin_floaty_title_text";
    private static final String RES_ACTION_CARD_TEXT =
            GEMINI_PACKAGE + ":id/assistant_robin_action_card_text";

    private static final Pattern PHONE_PATTERN = Pattern.compile("\\+\\d{10,}");
    private static final long CACHE_TTL_MS = 60_000; // 1 minute

    // Cached SMS card data — populated when the card appears,
    // consumed when Send is tapped
    private String cachedPhone = null;
    private String cachedBody = null;
    private long cachedTimestamp = 0;
    private long lastScanTimestamp = 0;
    private static final long SCAN_THROTTLE_MS = 500;

    // Watchdog: fires if cache is populated but no click/SENDTO consumed it
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private static final long WATCHDOG_DELAY_MS = 4000; // 4 seconds after last cache update
    private final Runnable watchdogRunnable = this::checkVoiceConfirmPath;

    @Override
    public void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        // Monitor all packages so we can catch Toast/notification from SMS failure
        info.packageNames = null;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.d(TAG, "GeminiSmsInterceptService connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        CharSequence eventPkg = event.getPackageName();

        // Log all notification events (from any package) for diagnostics
        if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            Log.d(TAG, "NOTIFICATION: pkg=" + eventPkg
                    + " text=" + event.getText()
                    + " class=" + event.getClassName()
                    + " cached=" + hasCachedSmsData());
            // If we see a Toast/notification while SMS data is cached,
            // Gemini likely tried SmsManager and got denied.
            // Fire our intent to handle it.
            if (hasCachedSmsData() && GEMINI_PACKAGE.equals(
                    eventPkg != null ? eventPkg.toString() : "")) {
                long msSinceSendto = System.currentTimeMillis()
                        - SmsHandlerActivity.lastSendtoTimestamp;
                if (msSinceSendto > 2000) {
                    Log.d(TAG, "Gemini notification with cached data — voice confirm path");
                    fireSendIntent(cachedPhone, cachedBody);
                    clearSmsCache();
                }
            }
            return;
        }

        // For non-Gemini packages, only handle notifications (above)
        if (eventPkg != null && !GEMINI_PACKAGE.equals(eventPkg.toString())) {
            return;
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence className = event.getClassName();
            Log.d(TAG, "WINDOW_STATE_CHANGED: class=" + className
                    + " cached=" + hasCachedSmsData()
                    + " cachedPhone=" + cachedPhone);
            // If the Gemini overlay dismisses while we have cached SMS data
            // and no recent SENDTO (i.e. Edit button wasn't tapped), this
            // likely means voice-confirm "Yes" triggered SmsManager directly.
            // Fire our intent to catch that path.
            if (hasCachedSmsData()) {
                long msSinceSendto = System.currentTimeMillis()
                        - SmsHandlerActivity.lastSendtoTimestamp;
                if (msSinceSendto > 2000) {
                    // Check if the SMS card is still visible
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    boolean cardStillVisible = false;
                    if (root != null) {
                        try {
                            List<AccessibilityNodeInfo> titleNodes =
                                    root.findAccessibilityNodeInfosByViewId(RES_TITLE_TEXT);
                            for (AccessibilityNodeInfo n : titleNodes) {
                                CharSequence t = n.getText();
                                if (t != null && (t.toString().contains("Text")
                                        || t.toString().contains("SMS")
                                        || t.toString().contains("Message"))) {
                                    cardStillVisible = true;
                                }
                                n.recycle();
                            }
                        } finally {
                            root.recycle();
                        }
                    }
                    Log.d(TAG, "Card still visible: " + cardStillVisible);
                    if (!cardStillVisible) {
                        Log.d(TAG, "SMS card disappeared with cached data — voice confirm path");
                        fireSendIntent(cachedPhone, cachedBody);
                        clearSmsCache();
                        return;
                    }
                }
            }
            // Fall through to cache scan
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Throttle scans — content changes fire rapidly
            long now = System.currentTimeMillis();
            if (now - lastScanTimestamp < SCAN_THROTTLE_MS) return;
            lastScanTimestamp = now;
            tryCacheSmsCardData();
            return;
        }

        if (eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return;

        AccessibilityNodeInfo source = event.getSource();
        boolean isSmsCardSend = false;

        if (source != null) {
            try {
                String resourceId = source.getViewIdResourceName();
                CharSequence contentDesc = source.getContentDescription();
                CharSequence text = source.getText();
                Log.d(TAG, "Click: resId=" + resourceId
                        + " desc=" + contentDesc
                        + " text=" + text);

                // Match the SMS card Send button by resource ID only.
                // Do NOT match by content-desc alone — the chat input also
                // has a "Send" button (assistant_robin_send_icon_button).
                // Do NOT use null-source fallback — the Edit/Modify button
                // also produces null source, and its SENDTO intent already
                // routes through SmsHandlerActivity.
                if (RES_SEND_BUTTON.equals(resourceId)) {
                    isSmsCardSend = true;
                    Log.d(TAG, "Send button matched by resource ID");
                }
            } finally {
                source.recycle();
            }
        } else {
            // Null source — could be the Send OR Edit button.
            // Guard: if SmsHandlerActivity just received a SENDTO (within 2s),
            // the Edit button already handled it — skip to avoid double-fire.
            CharSequence className = event.getClassName();
            long msSinceSendto = System.currentTimeMillis()
                    - SmsHandlerActivity.lastSendtoTimestamp;
            if ("android.widget.Button".equals(className != null ? className.toString() : "")
                    && hasCachedSmsData()
                    && msSinceSendto > 2000) {
                isSmsCardSend = true;
                Log.d(TAG, "Null-source Button click with cached data, no recent SENDTO ("
                        + msSinceSendto + "ms ago) — treating as Send");
            } else {
                Log.d(TAG, "Null-source click, class=" + className
                        + " cached=" + hasCachedSmsData()
                        + " msSinceSendto=" + msSinceSendto);
            }
        }

        if (isSmsCardSend) {
            if (hasCachedSmsData()) {
                Log.d(TAG, "Firing with cached data: phone=" + cachedPhone
                        + " body=" + cachedBody);
                fireSendIntent(cachedPhone, cachedBody);
                clearSmsCache();
            } else {
                // No cached data — try reading the tree right now
                Log.d(TAG, "Send matched but no cached data — scanning tree directly");
                handleSendButtonFromTree();
            }
        } else if (source == null
                && "android.widget.Button".equals(
                        event.getClassName() != null ? event.getClassName().toString() : "")
                && !isSmsCardSend) {
            // Null-source Button click that wasn't matched above — try scanning
            // the tree as last resort (the card might still be visible)
            long msSinceSendto = System.currentTimeMillis()
                    - SmsHandlerActivity.lastSendtoTimestamp;
            if (msSinceSendto > 2000) {
                Log.d(TAG, "Last-resort scan for null-source Button click");
                handleSendButtonFromTree();
            }
        }
    }

    /**
     * Scans the current window for an SMS compose card. If found, caches
     * the phone number and message body for use when Send is tapped.
     */
    private void tryCacheSmsCardData() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            // Look for a title that indicates SMS/text
            List<AccessibilityNodeInfo> titleNodes =
                    root.findAccessibilityNodeInfosByViewId(RES_TITLE_TEXT);
            if (titleNodes.isEmpty()) return;

            CharSequence titleText = titleNodes.get(0).getText();
            for (AccessibilityNodeInfo n : titleNodes) n.recycle();

            if (titleText == null) return;
            String title = titleText.toString();

            // Match various SMS-related titles Gemini shows
            boolean isSmsCard = title.contains("Text Message")
                    || title.contains("Text")
                    || title.contains("SMS")
                    || title.contains("Message");

            if (!isSmsCard) return;

            // Extract phone + body
            String[] data = extractPhoneAndBody(root);
            if (data != null && data[0] != null) {
                cachedPhone = data[0];
                cachedBody = data[1];
                cachedTimestamp = System.currentTimeMillis();
                Log.d(TAG, "Cached SMS card: title=\"" + title
                        + "\" phone=" + cachedPhone
                        + " body=" + cachedBody);
                // Reset watchdog — fires WATCHDOG_DELAY_MS after last cache update.
                // If voice "Yes" triggers SmsManager (which produces no accessibility
                // events), the watchdog will detect the card is gone and fire our intent.
                watchdogHandler.removeCallbacks(watchdogRunnable);
                watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_DELAY_MS);
            }
        } finally {
            root.recycle();
        }
    }

    /**
     * Fallback: reads the tree directly when we caught a Send click
     * but have no cached data.
     */
    private void handleSendButtonFromTree() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.w(TAG, "No root window available");
            return;
        }

        try {
            String[] extracted = extractPhoneAndBody(root);
            if (extracted == null || extracted[0] == null) {
                Log.w(TAG, "Could not extract phone number from Gemini UI");
                return;
            }
            fireSendIntent(extracted[0], extracted[1]);
        } finally {
            root.recycle();
        }
    }

    private void fireSendIntent(String phoneNumber, String messageBody) {
        Log.d(TAG, "Intercepted SMS: phone=" + phoneNumber
                + ", body=" + (messageBody != null ? "\"" + messageBody + "\"" : "null"));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean autoSend = prefs.getBoolean("gemini_auto_send", true);

        StringBuilder uriStr = new StringBuilder("smsto:");
        uriStr.append(Uri.encode(phoneNumber));
        if (messageBody != null) {
            uriStr.append("?body=");
            uriStr.append(Uri.encode(messageBody));
        }

        Intent sendIntent = new Intent(Intent.ACTION_SENDTO);
        sendIntent.setData(Uri.parse(uriStr.toString()));
        sendIntent.putExtra("force_auto_send", autoSend);
        sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(sendIntent);

        Log.d(TAG, "Launched Voice2SMS: auto_send=" + autoSend);
    }

    /**
     * Extracts [phoneNumber, messageBody] from Gemini's SMS compose card.
     *
     * The assistant_robin_action_card_text nodes appear in traversal order:
     *   [0] "Voice2SMS"              (app name)
     *   [1] "Jane Doe"                (contact name)
     *   [2] "Mobile . +15551234567"  (phone — matched by regex)
     *   [3] "Hello"                  (body — first text after phone)
     */
    private String[] extractPhoneAndBody(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> textNodes =
                root.findAccessibilityNodeInfosByViewId(RES_ACTION_CARD_TEXT);

        String phoneNumber = null;
        String messageBody = null;
        boolean foundPhone = false;

        for (AccessibilityNodeInfo node : textNodes) {
            CharSequence text = node.getText();
            if (text == null) continue;
            String textStr = text.toString();

            if (!foundPhone) {
                Matcher m = PHONE_PATTERN.matcher(textStr);
                if (m.find()) {
                    phoneNumber = m.group();
                    foundPhone = true;
                }
            } else if (messageBody == null) {
                messageBody = textStr;
            }
        }

        for (AccessibilityNodeInfo n : textNodes) n.recycle();

        if (phoneNumber == null) return null;
        return new String[]{phoneNumber, messageBody};
    }

    private boolean hasCachedSmsData() {
        return cachedPhone != null
                && (System.currentTimeMillis() - cachedTimestamp) < CACHE_TTL_MS;
    }

    private void clearSmsCache() {
        cachedPhone = null;
        cachedBody = null;
        cachedTimestamp = 0;
        watchdogHandler.removeCallbacks(watchdogRunnable);
    }

    /**
     * Watchdog: fires WATCHDOG_DELAY_MS after the last cache update.
     * If cache is still populated (no click handler consumed it),
     * check whether the SMS card is still visible. If gone, the user
     * likely voice-confirmed "Yes" and Gemini tried SmsManager (blocked).
     * Fire our intent to handle it.
     */
    private void checkVoiceConfirmPath() {
        if (!hasCachedSmsData()) {
            Log.d(TAG, "Watchdog: cache already consumed, skipping");
            return;
        }

        long msSinceSendto = System.currentTimeMillis()
                - SmsHandlerActivity.lastSendtoTimestamp;
        if (msSinceSendto <= 2000) {
            Log.d(TAG, "Watchdog: recent SENDTO (" + msSinceSendto + "ms ago), skipping");
            return;
        }

        // Check if the SMS card is still visible
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean cardStillVisible = false;
        if (root != null) {
            try {
                List<AccessibilityNodeInfo> titleNodes =
                        root.findAccessibilityNodeInfosByViewId(RES_TITLE_TEXT);
                for (AccessibilityNodeInfo n : titleNodes) {
                    CharSequence t = n.getText();
                    if (t != null && (t.toString().contains("Text")
                            || t.toString().contains("SMS")
                            || t.toString().contains("Message"))) {
                        cardStillVisible = true;
                    }
                    n.recycle();
                }
            } finally {
                root.recycle();
            }
        }

        Log.d(TAG, "Watchdog: card visible=" + cardStillVisible
                + " phone=" + cachedPhone + " body=" + cachedBody);

        if (!cardStillVisible) {
            Log.d(TAG, "Watchdog: SMS card gone with unconsumed cache — voice confirm path");
            fireSendIntent(cachedPhone, cachedBody);
            clearSmsCache();
        } else {
            // Card still visible — user hasn't acted yet, reschedule
            Log.d(TAG, "Watchdog: card still visible, rescheduling");
            watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_DELAY_MS);
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "GeminiSmsInterceptService interrupted");
    }
}
