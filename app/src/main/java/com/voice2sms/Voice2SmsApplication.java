package com.voice2sms;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.MutableContextWrapper;
import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.ProcessGlobalConfig;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewFeature;

/**
 * Application subclass that holds a singleton WebView instance.
 * The WebView survives Activity lifecycle — on warm starts the SPA
 * is already loaded so inject.js can run immediately (~0.5s vs ~2.3s).
 *
 * Tier 3: Also configures async WebView startup and warms up the renderer
 * process before the Activity creates its WebView.
 */
public class Voice2SmsApplication extends Application {

    private static final String TAG = "Voice2SMS";
    private static final String GV_MESSAGES_URL = "https://voice.google.com/u/0/messages";

    private WebView singletonWebView;
    private MutableContextWrapper contextWrapper;
    private boolean spaLoaded = false;

    @Override
    public void onCreate() {
        super.onCreate();

        // Tier 3: Configure async WebView startup (MUST be before any WebView creation)
        configureAsyncStartup();

        // Warm up renderer process (prefetch disabled — causes NPE race, see warmUpRenderer)
        warmUpRenderer();

        // Notification channels (no-op pre-O). Created in Application.onCreate so
        // they exist before the first SMS_DELIVER broadcast arrives — channels are
        // sticky once registered, so this is cheap on subsequent launches.
        ensureNotificationChannels();
    }

    /**
     * Create static notification channels. Sticky after first creation, so callers
     * don't need to re-create per notification.
     *
     * Channel: {@code incoming_sms} — used by {@link SmsReceiver} for every
     * incoming text. Importance HIGH so 2FA OTPs heads-up.
     */
    private void ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(SmsReceiver.NOTIF_CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    SmsReceiver.NOTIF_CHANNEL_ID,
                    "Incoming SMS",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(
                    "Notifies on incoming text messages while Voice2SMS is the default SMS app.");
            nm.createNotificationChannel(ch);
        }
    }

    /**
     * Configure WebView to perform startup work asynchronously,
     * reducing UI thread blocking during Application/Activity creation.
     */
    private void configureAsyncStartup() {
        try {
            if (WebViewFeature.isStartupFeatureSupported(this,
                    WebViewFeature.STARTUP_FEATURE_SET_UI_THREAD_STARTUP_MODE_V2)) {
                ProcessGlobalConfig config = new ProcessGlobalConfig();
                config.setUiThreadStartupModeV2(this,
                        ProcessGlobalConfig.UI_THREAD_STARTUP_MODE_ASYNC);
                ProcessGlobalConfig.apply(config);
                Log.d(TAG, "Configured async WebView startup mode");
            } else {
                Log.d(TAG, "Async startup mode not supported on this device");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to configure async startup: " + e.getMessage());
        }
    }

    /**
     * Start the renderer process early. Runs before the Activity creates its WebView,
     * saving ~200ms on cold start.
     *
     * NOTE: We do NOT call profile.prefetchUrlAsync() for GV_MESSAGES_URL here.
     * Prefetching the same URL that GVoiceWebViewActivity immediately loads causes
     * an internal chromium race — a post-prefetch Runnable (WV.h22.run) NPEs on
     * the main Handler 1-2ms after the prefetch's onResult callback, killing the
     * process. Reproduced deterministically on WebView 148 with androidx.webkit 1.15.0.
     */
    @SuppressWarnings("RestrictedApi")
    private void warmUpRenderer() {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WARM_UP_RENDERER_PROCESS)) {
                Log.d(TAG, "Renderer warm-up not supported on this device");
                return;
            }

            Profile profile = ProfileStore.getInstance().getOrCreateProfile("Default");
            profile.warmUpRendererProcess();
            Log.d(TAG, "Renderer warm-up started");
        } catch (Exception e) {
            Log.w(TAG, "Warm-up failed: " + e.getMessage());
        }
    }

    /**
     * Get or create the singleton WebView.
     * Must be called from the UI thread.
     */
    public synchronized WebView getOrCreateWebView() {
        if (singletonWebView == null) {
            Log.d(TAG, "Creating singleton WebView");
            contextWrapper = new MutableContextWrapper(getApplicationContext());
            singletonWebView = new RichContentWebView(contextWrapper);
            setupWebViewSettings(singletonWebView);

            // Keep renderer alive when Activity is not visible
            singletonWebView.setRendererPriorityPolicy(
                    WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        return singletonWebView;
    }

    /**
     * Swap the WebView's context to an Activity (for dialogs, autofill, etc.).
     */
    public void attachActivityContext(android.app.Activity activity) {
        if (contextWrapper != null) {
            contextWrapper.setBaseContext(activity);
        }
    }

    /**
     * Release the Activity context, falling back to Application context.
     */
    public void detachActivityContext() {
        if (contextWrapper != null) {
            contextWrapper.setBaseContext(getApplicationContext());
        }
    }

    public boolean isSpaLoaded() {
        return spaLoaded;
    }

    public void setSpaLoaded(boolean loaded) {
        this.spaLoaded = loaded;
    }

    /**
     * Called when the renderer process dies — we need to recreate the WebView.
     */
    public synchronized void onRendererGone() {
        Log.w(TAG, "Renderer gone, clearing singleton WebView");
        singletonWebView = null;
        contextWrapper = null;
        spaLoaded = false;
    }

    private void setupWebViewSettings(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/131.0.0.0 Mobile Safari/537.36");
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
    }
}
