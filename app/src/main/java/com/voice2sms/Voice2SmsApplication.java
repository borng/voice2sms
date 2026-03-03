package com.voice2sms;

import android.app.Application;
import android.content.MutableContextWrapper;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.OutcomeReceiverCompat;
import androidx.webkit.PrefetchException;
import androidx.webkit.ProcessGlobalConfig;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.Executor;

/**
 * Application subclass that holds a singleton WebView instance.
 * The WebView survives Activity lifecycle — on warm starts the SPA
 * is already loaded so inject.js can run immediately (~0.5s vs ~2.3s).
 *
 * Tier 3: Also configures async WebView startup, warms up the renderer
 * process, and prefetches the GV URL before the Activity creates its WebView.
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

        // Tier 3: Warm up renderer process and prefetch GV URL
        warmUpAndPrefetch();
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
     * Start the renderer process early and prefetch the GV URL into HTTP cache.
     * Both run before the Activity creates its WebView, saving ~200-400ms on cold start.
     */
    @SuppressWarnings("RestrictedApi")
    private void warmUpAndPrefetch() {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WARM_UP_RENDERER_PROCESS)) {
                Log.d(TAG, "Renderer warm-up not supported on this device");
                return;
            }

            Profile profile = ProfileStore.getInstance().getOrCreateProfile("Default");
            profile.warmUpRendererProcess();
            Log.d(TAG, "Renderer warm-up started");

            // Prefetch GV URL to populate HTTP cache
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROFILE_URL_PREFETCH)) {
                Executor mainExecutor = command ->
                        new Handler(Looper.getMainLooper()).post(command);

                profile.prefetchUrlAsync(
                        GV_MESSAGES_URL,
                        new CancellationSignal(),
                        mainExecutor,
                        new OutcomeReceiverCompat<Void, PrefetchException>() {
                            @Override
                            public void onResult(Void result) {
                                Log.d(TAG, "GV URL prefetch completed");
                            }

                            @Override
                            public void onError(PrefetchException e) {
                                Log.w(TAG, "GV URL prefetch failed: " + e.getMessage());
                            }
                        });
                Log.d(TAG, "GV URL prefetch started");
            } else {
                Log.d(TAG, "URL prefetch not supported on this device");
            }
        } catch (Exception e) {
            Log.w(TAG, "Warm-up/prefetch failed: " + e.getMessage());
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
            singletonWebView = new WebView(contextWrapper);
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

        WebView.setWebContentsDebuggingEnabled(true);
    }
}
