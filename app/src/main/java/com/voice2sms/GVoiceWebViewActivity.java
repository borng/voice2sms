package com.voice2sms;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.content.ClipData;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import androidx.core.view.ViewCompat;
import androidx.preference.PreferenceManager;

import com.voice2sms.sendlater.SendLaterSettings;
import com.voice2sms.sendlater.engine.GvSession;
import com.voice2sms.sendlater.web.AssetScripts;
import com.voice2sms.sendlater.web.CapturePayload;
import com.voice2sms.sendlater.web.ChipState;
import com.voice2sms.sendlater.web.BridgeNonce;
import com.voice2sms.sendlater.web.LiveDelegate;
import com.voice2sms.sendlater.web.NewComposeRecipient;
import com.voice2sms.sendlater.web.OriginGuard;
import com.voice2sms.sendlater.send.ForegroundUse;
import com.voice2sms.sendlater.send.RegularSend;
import com.voice2sms.sendlater.send.ScheduledSendController;
import com.voice2sms.sendlater.send.ScheduledCompose;
import com.voice2sms.sendlater.web.SendLaterWebHooks;
import com.voice2sms.sendlater.web.ThreadReadCallback;
import com.voice2sms.sendlater.web.ThreadUrl;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Hosts a WebView pointed at voice.google.com.
 * Uses a singleton WebView from Voice2SmsApplication so the SPA stays
 * loaded across Activity lifecycles (warm start optimization).
 *
 * Launch mode is singleTask — subsequent intents arrive via onNewIntent().
 */
public class GVoiceWebViewActivity extends Activity {

    private static final String TAG = "Voice2SMS";
    private static final String GV_MESSAGES_URL = "https://voice.google.com/u/0/messages";
    private static final int REQUEST_ACCOUNT_PICKER = 2001;
    /**
     * LIVE-F2: AccountManager "weblogin:service=grandcentral" token exchange. Off by default: on
     * current Google Play services (seen on the S22) it shows the UnsupportedTokenLabel consent
     * screen again and again and the token it finally returns is not usable, so the WebView ends
     * on the Workspace marketing page. With it off, a signed-out WebView loads Google's own
     * sign-in page ({@link #signInUrl}) and the user signs in there. Flip to true only to
     * experiment with the old token flow (attemptAccountAuth / requestAuthToken are kept intact).
     */
    static final boolean USE_WEBLOGIN_TOKEN = false;
    /** Google sign-in that returns to GV messages. The account hint, if any, is appended. */
    private static final String SIGN_IN_BASE =
            "https://accounts.google.com/ServiceLogin?continue=" + GV_MESSAGES_URL;
    /** Bounds the signed-out-landing -> sign-in redirect per Activity (cookie-less loop guard). */
    static final int MAX_SIGN_IN_REDIRECTS = 3;
    private static final int FILE_CHOOSER_REQUEST = 3001;
    /** LIVE-F2: signed-out landings redirected to sign-in by this Activity (UI thread only). */
    private int signInRedirects;

    private WebView webView;
    /** Intent recipient; volatile: R10a reads it on the JavaBridge thread (sendLaterNewComposeState). */
    private volatile String recipient;
    private String body;
    /**
     * deliver M1 (F1): whether the current regular compose (recipient/body) asked for auto-send,
     * read when its intent arrives. injectComposer reads this, not getIntent(): a scheduled launch
     * that onNewIntent refused while this compose runs replaces getIntent() but not the compose.
     */
    private boolean regularAutoSend;
    private boolean injected = false;
    private ValueCallback<Uri[]> fileUploadCallback;
    private volatile boolean stickerInjectionActive;
    /** Send Later wiring (R10/R11/R17/R18); see SendLaterWebHooks. */
    private SendLaterWebHooks sendLaterHooks;
    /**
     * Main-frame URL, written on the UI thread by onPageStarted, onPageFinished and
     * doUpdateVisitedHistory; read by the Send Later V2SBridge methods on the binder thread,
     * which serve nothing unless OriginGuard.isTrustedGvUrl accepts it.
     */
    private volatile String mainFrameUrl;
    /**
     * deliver sec-M1: the per-document nonce every Send Later / scheduled V2SBridge call must
     * pass. Static because the WebView is a process singleton that outlives this Activity:
     * issued in onPageFinished only on a trusted GV page, dropped in onPageStarted and when the
     * renderer is gone. See BridgeNonce.
     */
    private static final BridgeNonce BRIDGE_NONCE = new BridgeNonce();
    /** Send Later due-time send (R30/R31, OD11): the scheduled branch; see ScheduledSendController. */
    private ScheduledSendController scheduledSend;
    /**
     * LIVE-F13: the Activity the page's one stable V2SBridge ({@link PageBridge}) forwards to.
     * An object added with addJavascriptInterface reaches the page only on its next load, so a
     * per-Activity bridge left a warm, un-reloaded page calling the previous (destroyed)
     * Activity. Set in onCreate, cleared in onDestroy only while it is still this Activity's.
     */
    private static final LiveDelegate<V2SBridge> LIVE_BRIDGE = new LiveDelegate<>();
    /** The single object the singleton WebView exposes to the page as "V2SBridge". */
    private static final PageBridge PAGE_BRIDGE = new PageBridge(LIVE_BRIDGE);
    /** This Activity's bridge, attached to LIVE_BRIDGE while the Activity lives. */
    private V2SBridge pageBridgeTarget;

    /**
     * LIVE-F13: the one object to register as "V2SBridge" on a newly created singleton WebView
     * (Voice2SmsApplication.getOrCreateWebView, before its first load). Never re-added or removed
     * per Activity.
     */
    static Object pageBridge() {
        return PAGE_BRIDGE;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        recipient = getIntent().getStringExtra("recipient");
        body = getIntent().getStringExtra("body");
        regularAutoSend = wantsAutoSend(getIntent());
        AutoSendAuth.strip(getIntent());

        Voice2SmsApplication app = (Voice2SmsApplication) getApplication();
        webView = app.getOrCreateWebView();
        app.attachActivityContext(this);

        // Detach from any previous parent
        ViewParent parent = webView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(webView);
        }

        // Attach to our container
        ViewGroup container = findViewById(R.id.webview_container);
        container.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Set up the WebViewClient (re-set each time: it captures `this` Activity). The page's
        // V2SBridge object is NOT re-added here (LIVE-F13): it is registered once per WebView
        // (Voice2SmsApplication.getOrCreateWebView) and forwards to this Activity's bridge below.
        sendLaterHooks = new SendLaterWebHooks(this, new SendLaterHost());
        setupWebViewClient();
        // The singleton WebView may already show a page that no callback will report again.
        mainFrameUrl = webView.getUrl();
        pageBridgeTarget = new V2SBridge();
        LIVE_BRIDGE.attach(pageBridgeTarget);
        scheduledSend = new ScheduledSendController(this, new ScheduledHost());
        boolean scheduledLaunch = scheduledSend.handle(getIntent(), savedInstanceState != null);
        // deliver M1: a regular compose holds the WebView from its intent on (no scheduled
        // launch loads a page over it until the script clicks Send, gives up or MAX_MS).
        if (!scheduledLaunch && recipient != null) noteRegularCompose();
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                // I7: inject.js console lines carry recipient numbers. Relay them only in debug
                // builds, and consume them in every build so Chromium does not log them itself.
                if (cm.message() != null && cm.message().contains("[Voice2SMS]")) {
                    // deliver M1: the regular compose's progress (Send clicked / gave up) ends
                    // its hold on the WebView. Never logged here.
                    RegularSend.onConsole(cm.message(), System.currentTimeMillis());
                    if (BuildConfig.DEBUG) Log.d(TAG, "JS: " + cm.message());
                    return true;
                }
                return super.onConsoleMessage(cm);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                              FileChooserParams params) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }

                // If a sticker injection triggered this, just cancel the picker.
                // The JS-side DataTransfer injection handles the actual file.
                if (stickerInjectionActive) {
                    Log.d(TAG, "Suppressing file picker — sticker injected via JS");
                    callback.onReceiveValue(null);
                    stickerInjectionActive = false;
                    return true;
                }

                fileUploadCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    Log.w(TAG, "File chooser launch failed", e);
                    fileUploadCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Listen for rich content (stickers, GIFs) from GBoard
        ViewCompat.setOnReceiveContentListener(webView, RichContentWebView.IMAGE_MIME_TYPES,
            (view, payload) -> {
                ClipData clip = payload.getClip();
                if (clip == null || clip.getItemCount() == 0) return payload;
                Uri uri = clip.getItemAt(0).getUri();
                if (uri == null) return payload;
                handleStickerImage(uri);
                return null; // consumed
            });

        if (scheduledLaunch && scheduledSend.skipsPageLoad()) {
            // R3 (send-hardening): an unclaimed scheduled launch over the keyguard (e.g. the
            // relaunch after a renderer crash) is finishing; never load the inbox under it.
            Log.d(TAG, "Unclaimed scheduled launch over the keyguard: finishing without a page load");
        } else if (scheduledLaunch && scheduledSend.isActive()) {
            // LIVE-F11: a claimed scheduled job owns the page load, cold or warm: beginCompose
            // loads the job's thread once its row is read; never the messages list over it.
            webView.onResume();
            loadForScheduledJob(app);
        } else if (app.isSpaLoaded() && recipient != null) {
            // Warm start — SPA already loaded but may be on a stale view.
            // Reload the page for a clean messages-list state.
            // onPageFinished will inject the composer once ready.
            Log.d(TAG, "Warm start: SPA loaded, reloading for clean state");
            webView.onResume();
            loadGoogleVoice();
        } else if (!app.isSpaLoaded()) {
            // Cold start — need to load the page
            CookieManager cookieManager = CookieManager.getInstance();
            String cookies = cookieManager.getCookie("https://voice.google.com");

            if (cookies != null && cookies.contains("SID")) {
                loadGoogleVoice();
            } else if (scheduledSend.isActive()) {
                // No web session: never open the account picker over the lock screen.
                scheduledSend.onWebFailure("no_session");
            } else if (!scheduledLaunch || !scheduledSend.isOverKeyguard()) {
                attemptAccountAuth();
            }
        }
        // else: SPA loaded but no recipient — just show the WebView
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Scheduled branch: it reloads the page itself, or queues behind the running send.
        if (scheduledSend != null && scheduledSend.handle(intent, false)) return;
        if (scheduledSend != null) scheduledSend.onOtherIntent();

        recipient = intent.getStringExtra("recipient");
        body = intent.getStringExtra("body");
        regularAutoSend = wantsAutoSend(intent);
        AutoSendAuth.strip(intent);
        injected = false;
        // deliver M1: the regular compose holds the WebView from now on (bounded).
        if (recipient != null && (scheduledSend == null || !scheduledSend.isActive())) noteRegularCompose();

        if (BuildConfig.DEBUG) Log.d(TAG, "onNewIntent: recipient=" + recipient
                + ", body=" + (body != null ? "[present]" : "null"));

        webView.onResume();

        // Always reload the page for a clean messages-list state.
        // The SPA may be on a conversation thread or stale compose view.
        // onPageFinished will inject the composer once the page is ready.
        Log.d(TAG, "Warm start: reloading page for clean state");
        loadGoogleVoice();
    }

    /**
     * I7: a URL for logs, cut at the first '?' or '#'. Thread pages carry the recipient's E.164 in
     * the query (itemId=t.+...), so production logs never get the query or fragment.
     */
    static String withoutQuery(String url) {
        if (url == null) return null;
        int cut = url.length();
        int q = url.indexOf('?');
        if (q >= 0) cut = q;
        int h = url.indexOf('#');
        if (h >= 0 && h < cut) cut = h;
        return url.substring(0, cut);
    }

    private void setupWebViewClient() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // send-lock-cover: over the keyguard only the target thread may load.
                if (request.isForMainFrame() && scheduledSend != null && !scheduledSend.allowsNavigation(url)) {
                    Log.d(TAG, "Blocking navigation while a scheduled send is covered");
                    return true;
                }
                if (OriginGuard.isAllowedNavigation(request.getUrl().getScheme(), request.getUrl().getHost())) {
                    return false;
                }
                Log.d(TAG, "Blocking non-Google URL: " + withoutQuery(url));
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // deliver sec-M1: the next document gets no bridge nonce until it is a finished
                // trusted GV page (onPageFinished).
                BRIDGE_NONCE.invalidate();
                mainFrameUrl = url;
                // Always inject fingerprint mask (includes dark mode CSS).
                // Must run early before page renders to avoid flash of light mode.
                injectFingerprintMask();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                mainFrameUrl = url;
                Log.d(TAG, "onPageFinished: " + withoutQuery(url));
                // deliver sec-L2: one OriginGuard decision for this callback (was startsWith).
                final boolean trustedGv = OriginGuard.isTrustedGvUrl(url);
                // The nonce is issued only when the WebView's own URL is trusted too.
                if (trustedGv) BRIDGE_NONCE.issueFor(view.getUrl());
                else BRIDGE_NONCE.invalidate();

                CookieManager.getInstance().flush();
                // LIVE-F2: signed out, GV bounces to its marketing/about page. Show Google's
                // sign-in instead; the user signs in in the WebView (never automated).
                if (isSignedOutLanding(url) && redirectToSignIn()) return;
                // Send Later module (v2sSendLaterInit), once per page load.
                if (trustedGv && sendLaterHooks != null) installSendLater();
                // R44: Enter inserts a newline in the composer, once per page load.
                if (trustedGv) installEnterNewline();
                // LIVE-F6: a later main-frame load during a scheduled compose (a cold WebView's
                // post-load reloads) wiped the script; let the dispatch below inject it again.
                if (injected && scheduledSend != null && scheduledSend.wantsReinject(url)) injected = false;

                if (trustedGv && !injected && recipient != null) {
                    // Mark SPA as loaded for future warm starts
                    Voice2SmsApplication app = (Voice2SmsApplication) getApplication();
                    app.setSpaLoaded(true);
                    if (scheduledSend == null || !scheduledSend.isActive()) injectComposer();
                    else if (scheduledSend.isComposing()) injectScheduledComposer();
                } else if (trustedGv && !injected) {
                    // No recipient but page loaded — mark SPA ready
                    Voice2SmsApplication app = (Voice2SmsApplication) getApplication();
                    app.setSpaLoaded(true);
                } else if (url.contains("accounts.google.com") && injected) {
                    Log.d(TAG, "Auth redirect detected, resetting injected flag");
                    injected = false;
                    Voice2SmsApplication app = (Voice2SmsApplication) getApplication();
                    app.setSpaLoaded(false);
                } else if (!url.contains("voice.google.com") && !injected
                        && url.contains("accounts.google.com")
                        && url.contains("MergeSession")
                        && !url.contains("WILL_NOT_SIGN_IN")) {
                    Log.d(TAG, "Auth MergeSession loaded, redirecting to GV");
                    // LIVE-F11: a running scheduled job continues to its thread, not the list.
                    if (scheduledSend != null && scheduledSend.isActive()) loadForScheduledJob(null);
                    else view.loadUrl(GV_MESSAGES_URL);
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                String previous = mainFrameUrl;
                mainFrameUrl = url;
                // LIVE-F10 F1: the job's own thread showing up drops an empty-conversation clearance.
                if (scheduledSend != null && url != null) scheduledSend.onMainFrameUrl(url);
                // The page may have asked for the chip before this SPA navigation was reported.
                if (sendLaterHooks != null && url != null && !url.equals(previous)) {
                    sendLaterHooks.onMainFrameUrlChanged();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Log.w(TAG, "Renderer gone! crashed=" + detail.didCrash());
                Voice2SmsApplication app = (Voice2SmsApplication) getApplication();
                app.onRendererGone();
                // deliver sec-M1: the document holding the nonce is gone.
                BRIDGE_NONCE.invalidate();
                // deliver M1: the page running a regular compose is gone with the renderer.
                RegularSend.clear();
                webView = null; // prevent stale reference in onPause/onDestroy
                recreate();
                return true;
            }
        });
    }

    private void attemptAccountAuth() {
        if (!USE_WEBLOGIN_TOKEN) {
            // LIVE-F2: no AccountManager consent loop; the user signs in on Google's page.
            loadSignIn();
            return;
        }
        try {
            android.accounts.AccountManager am = android.accounts.AccountManager.get(this);
            android.accounts.Account[] accounts = am.getAccountsByType("com.google");

            if (accounts.length == 0) {
                loadGoogleVoice();
                return;
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            String savedAccount = prefs.getString("google_account", null);

            if (savedAccount != null) {
                for (android.accounts.Account account : accounts) {
                    if (account.name.equals(savedAccount)) {
                        requestAuthToken(am, account);
                        return;
                    }
                }
            }

            if (accounts.length == 1) {
                prefs.edit().putString("google_account", accounts[0].name).apply();
                requestAuthToken(am, accounts[0]);
            } else {
                Intent pickIntent = android.accounts.AccountManager.newChooseAccountIntent(
                        null, null, new String[]{"com.google"}, null, null, null, null);
                startActivityForResult(pickIntent, REQUEST_ACCOUNT_PICKER);
            }
        } catch (Exception e) {
            Log.w(TAG, "AccountManager auth failed, falling back to manual login", e);
            loadGoogleVoice();
        }
    }

    private void requestAuthToken(android.accounts.AccountManager am, android.accounts.Account account) {
        if (!USE_WEBLOGIN_TOKEN) {
            loadSignIn();
            return;
        }
        am.getAuthToken(account, "weblogin:service=grandcentral",
                null, this, future -> {
                    runOnUiThread(() -> {
                        if (!isActivityAlive()) return;
                        try {
                            Bundle result = future.getResult();
                            String authUrl = result.getString(android.accounts.AccountManager.KEY_AUTHTOKEN);
                            if (authUrl != null && authUrl.startsWith("http")
                                    && !authUrl.contains("WILL_NOT_SIGN_IN")) {
                                Log.d(TAG, "Loading auth token exchange URL");
                                webView.loadUrl(authUrl);
                            } else {
                                Log.d(TAG, "Auth token not usable, loading GV directly");
                                loadGoogleVoice();
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Auth token exchange failed", e);
                            loadGoogleVoice();
                        }
                    });
                }, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                // Modern photo picker returns via ClipData; fall back to getData()
                if (data.getClipData() != null) {
                    ClipData cd = data.getClipData();
                    results = new Uri[cd.getItemCount()];
                    for (int i = 0; i < cd.getItemCount(); i++) {
                        results[i] = cd.getItemAt(i).getUri();
                    }
                } else if (data.getDataString() != null) {
                    results = new Uri[]{Uri.parse(data.getDataString())};
                }
            }
            if (fileUploadCallback != null) {
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
            return;
        }

        if (requestCode == REQUEST_ACCOUNT_PICKER) {
            if (resultCode == RESULT_OK && data != null) {
                String accountName = data.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME);
                if (accountName != null) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                    prefs.edit().putString("google_account", accountName).apply();

                    android.accounts.AccountManager am = android.accounts.AccountManager.get(this);
                    android.accounts.Account[] accounts = am.getAccountsByType("com.google");
                    for (android.accounts.Account account : accounts) {
                        if (account.name.equals(accountName)) {
                            requestAuthToken(am, account);
                            return;
                        }
                    }
                }
            }
            loadGoogleVoice();
        }
    }

    /**
     * LIVE-F11: the one page-load path while a scheduled job is active (onCreate's cold and warm
     * starts, the auth MergeSession continuation). Never GV_MESSAGES_URL for a job in its thread:
     * the controller loads the job's thread URL (beginCompose once the row is read, else a bounded
     * reload) or, after the fallback, the messages list. {@code app} (onCreate only): a cold
     * WebView without a web session fails the job instead (never the account picker).
     */
    private void loadForScheduledJob(Voice2SmsApplication app) {
        if (!isActivityAlive() || scheduledSend == null) return;
        if (app != null && !app.isSpaLoaded()) {
            // The engine's session test: a non-empty SID cookie (not any *SID* name); false on error.
            if (!GvSession.hasWebSession(this)) {
                scheduledSend.onWebFailure("no_session");
                return;
            }
        }
        scheduledSend.loadForJob();
    }

    private void loadGoogleVoice() {
        if (webView == null) return;
        webView.loadUrl(GV_MESSAGES_URL);
    }

    /**
     * LIVE-F2: load Google's sign-in page, continuing to GV messages. The saved account
     * (pref "google_account", set by the old picker flow) is only an Email= hint; never logged.
     */
    private void loadSignIn() {
        if (webView == null) return;
        String saved = null;
        try {
            saved = PreferenceManager.getDefaultSharedPreferences(this).getString("google_account", null);
        } catch (RuntimeException ignored) {
            // No hint then.
        }
        webView.loadUrl(signInUrl(saved));
    }

    /**
     * LIVE-F2: replace a signed-out landing with Google's sign-in. Returns false (page left as is)
     * during a scheduled send (it may be over the lock screen) or after MAX_SIGN_IN_REDIRECTS.
     */
    private boolean redirectToSignIn() {
        if (scheduledSend != null && scheduledSend.isActive()) return false;
        if (signInRedirects >= MAX_SIGN_IN_REDIRECTS) return false;
        signInRedirects++;
        Log.d(TAG, "Signed-out landing, loading Google sign-in");
        loadSignIn();
        return true;
    }

    /** LIVE-F2: ServiceLogin URL continuing to GV messages, with an encoded Email= hint if given. */
    static String signInUrl(String accountHint) {
        if (accountHint == null || accountHint.trim().isEmpty()) return SIGN_IN_BASE;
        try {
            return SIGN_IN_BASE + "&Email=" + java.net.URLEncoder.encode(accountHint.trim(), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return SIGN_IN_BASE;
        }
    }

    /**
     * LIVE-F2: the main frame is where signed-out GV lands: any https page on
     * workspace.google.com, or https://voice.google.com/about (and below). accounts.google.com
     * is the sign-in itself and is left alone.
     */
    static boolean isSignedOutLanding(String url) {
        if (url == null) return false;
        java.net.URI u;
        try {
            u = new java.net.URI(url);
        } catch (java.net.URISyntaxException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) return false;
        String host = u.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.equals("workspace.google.com")) return true;
        if (!host.equals("voice.google.com")) return false;
        String path = u.getPath() == null ? "" : u.getPath();
        return path.equals("/about") || path.startsWith("/about/");
    }


    private void injectFingerprintMask() {
        try {
            InputStream is = getAssets().open("fingerprint-mask.js");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            webView.evaluateJavascript(sb.toString(), null);
            Log.d(TAG, "Injected fingerprint mask");
        } catch (Exception e) {
            Log.w(TAG, "Failed to inject fingerprint mask", e);
        }
    }

    /**
     * deliver M1: a regular compose intent arrived; it holds the WebView until injectComposer
     * re-marks it and the script reports Send clicked / gave up (RegularSend, bounded).
     */
    private void noteRegularCompose() {
        RegularSend.begin(System.currentTimeMillis(), regularAutoSend);
    }

    /** The regular compose intent's auto-send request (Gemini, Wear, RESPOND_VIA_MESSAGE). */
    private static boolean wantsAutoSend(Intent intent) {
        // Not exported, but gated the same way as SmsHandlerActivity: the flag counts only with
        // this process's AutoSendAuth token.
        return intent != null && intent.getBooleanExtra("force_auto_send", false)
                && AutoSendAuth.isAuthorized(intent);
    }

    private void injectComposer() {
        injected = true;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        // deliver M1 (F1): the compose's own request, kept across a refused scheduled intent.
        boolean autoSend = regularAutoSend;
        int autoSendDelay = prefs.getInt("auto_send_delay", 2) * 1000;

        // Global auto-send rate limit (single chokepoint for Gemini, Wear,
        // RESPOND_VIA_MESSAGE, and any future auto-send origin). When hit, fall
        // through to manual review — the composer still opens prefilled, but
        // doesn't fire the inject.js send sequence. Toast tells the user why.
        if (autoSend && !AutoSendRateLimiter.allow()) {
            Log.w(TAG, "Auto-send rate limit hit (" + AutoSendRateLimiter.recentCount()
                    + "/" + AutoSendRateLimiter.MAX_PER_WINDOW + " in window) — falling back to manual review");
            android.widget.Toast.makeText(this,
                    "Auto-send rate limit (" + AutoSendRateLimiter.MAX_PER_WINDOW
                            + "/min) — review and send manually",
                    android.widget.Toast.LENGTH_LONG).show();
            autoSend = false;
        }
        // deliver M1: from here until the script clicks Send or gives up (bounded by
        // RegularSend.MAX_MS) a due scheduled job waits instead of loading a page over it.
        RegularSend.begin(System.currentTimeMillis(), autoSend);

        try {
            InputStream is = getAssets().open("inject.js");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();

            String js = sb.toString();
            String jsonRecipient = jsonEncode(recipient);
            String jsonBody = jsonEncode(body != null ? body : "");
            js += "\nvoice2sms(" + jsonRecipient + ", " + jsonBody + ", "
                    + autoSend + ", " + autoSendDelay + ");";

            webView.evaluateJavascript(js, null);
            if (BuildConfig.DEBUG) Log.d(TAG, "Injected composer JS for recipient: " + recipient
                    + ", body=" + (body != null ? "[present]" : "null")
                    + ", autoSend=" + autoSend + ", delay=" + autoSendDelay);
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject JS", e);
        }
    }

    /**
     * Scheduled branch of the composer (R30): inject.js auto-send for the scheduled message. The
     * scheduled queue already spaced and capped this send, so the global auto-send rate limiter
     * and the Wear/Gemini extra are not involved; ScheduledSendController verifies the result.
     */
    private void injectScheduledComposer() {
        injected = true;
        if (!isActivityAlive() || scheduledSend == null) return;
        // LIVE-F11: a finished GV page that is not the job's thread (the messages list, or GV
        // rewrote the thread URL while booting) loads the job's thread again (bounded) instead.
        if (scheduledSend.reloadsThreadInsteadOf(mainFrameUrl)) {
            injected = false;
            return;
        }
        String script = readAssetScript("inject.js");
        if (script == null) {
            scheduledSend.onWebFailure("inject_failed");
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        long delayMs = ScheduledSendController.sendDelayMs(prefs.getInt("auto_send_delay", 2) * 1000L);
        // LIVE-F6/F8: a token per injection; inject.js reports "typed" and asks before tapping Send.
        // live-presend-failclosed: the scheduled body and token go only into a GV page.
        if (!OriginGuard.isTrustedGvUrl(webView.getUrl())) return;
        String token = scheduledSend.onComposeInjection(delayMs);
        if (token == null) return;
        // LIVE-F10: type into the job's own thread when this is its page, else the new-conversation
        // flow; the script polls the tri-state Send gate while the pre-send check is pending.
        String mode = scheduledSend.composeModeFor(webView.getUrl());
        script += "\nwindow._v2sScheduledOpts = {mode: " + jsonEncode(mode)
                + ", settleMs: " + ScheduledCompose.THREAD_SETTLE_MS
                + ", pollMs: " + ScheduledCompose.CLICK_POLL_MS
                + ", waitMs: " + ScheduledSendController.clickWaitMs() + "};";
        // deliver sec-M1: the scheduled script's bridge calls carry this document's nonce.
        script += BridgeNonce.pageStatement(BRIDGE_NONCE.current());
        script += "\nv2sScheduledCompose(" + jsonEncode(recipient) + ", " + jsonEncode(body != null ? body : "")
                + ", " + delayMs + ", " + jsonEncode(token) + ");";
        webView.evaluateJavascript(script, null);
    }

    /** The asset script {@code name}, read from the APK once per process (AssetScripts). */
    private String readAssetScript(String name) {
        String script = AssetScripts.read(getAssets()::open, name);
        if (script == null) Log.w(TAG, "Failed to read " + name);
        return script;
    }

    /**
     * Installs inject.js v2sInstallEnterNewline() once per page load (R44, D29): Enter in the
     * message box inserts a newline; only Send sends. Independent of Send Later and of the
     * composer injection; the JS side is idempotent per document.
     */
    private void installEnterNewline() {
        if (!isActivityAlive()) return;
        String script = readAssetScript("inject.js");
        if (script == null) return;
        webView.evaluateJavascript(script + "\nv2sInstallEnterNewline();", null);
    }

    /** Installs the inject.js Send Later module (v2sSendLaterInit) once per page load. */
    private void installSendLater() {
        if (!isActivityAlive() || sendLaterHooks == null) return;
        String js = sendLaterHooks.moduleScript();
        if (js != null) webView.evaluateJavascript(js, null);
    }

    /**
     * Reads the newest {@code limit} bubbles of the open GV web thread for the send path. Calls
     * back (main thread) with null when the WebView is not showing that 1:1 thread. Never
     * navigates.
     */
    public void readThread(String e164, int limit, ThreadReadCallback cb) {
        if (cb == null) return;
        runOnUiThread(() -> {
            if (!isActivityAlive() || sendLaterHooks == null
                    || !OriginGuard.isTrustedGvUrl(webView.getUrl())
                    || !ThreadUrl.isThreadOf(webView.getUrl(), e164)) {
                cb.onThreadRead(null);
                return;
            }
            webView.evaluateJavascript(sendLaterHooks.beginThreadRead(e164, limit, cb), null);
        });
    }

    /** Page access for SendLaterWebHooks; every WebView touch is behind isActivityAlive(). */
    private class SendLaterHost implements SendLaterWebHooks.Host {
        @Override
        public void evaluate(String js) {
            runOnUiThread(() -> {
                if (!isActivityAlive()) return;
                // deliver sec-L2: Send Later script runs only on a trusted GV page.
                if (!OriginGuard.isTrustedGvUrl(webView.getUrl())) return;
                webView.evaluateJavascript(js, null);
            });
        }

        @Override
        public String currentUrl() {
            return isActivityAlive() ? webView.getUrl() : null;
        }

        @Override
        public String bridgeNonce() {
            return BRIDGE_NONCE.current();
        }

        @Override
        public void readThread(String e164, int limit, ThreadReadCallback cb) {
            GVoiceWebViewActivity.this.readThread(e164, limit, cb);
        }

        @Override
        public void onSendLaterSwitch(boolean enabled) {
            // R40: the composer probe runs only while Send Later is on (it feeds ForegroundUse
            // for scheduled sends, which never start while the switch is off).
            composerProbeHandler.removeCallbacks(composerProbe);
            if (enabled && isActivityAlive() && ForegroundUse.isResumed()) {
                composerProbeHandler.post(composerProbe);
            } else {
                ForegroundUse.noteComposer(false, System.currentTimeMillis());
            }
        }
    }

    /**
     * True when the message box (inject.js fillBody's selectors) still holds text. Answers a
     * boolean only, never the text.
     */
    private static final String SCHEDULED_COMPOSER_PROBE_JS = "(function(){var t=document.querySelector("
            + "'textarea[placeholder=\"Type a message\"], textarea[placeholder*=\"message\"], "
            + "textarea[placeholder*=\"text\"], textarea[aria-label*=\"message\"], "
            + "[contenteditable=\"true\"][aria-label*=\"message\"]');"
            + "if(!t)return false;var v=t.isContentEditable?t.textContent:t.value;"
            + "return !!(v&&v.trim().length>0);})()";

    /** Activity access for the scheduled branch; every WebView touch is behind isActivityAlive(). */
    private class ScheduledHost implements ScheduledSendController.Host {
        @Override
        public boolean isAlive() {
            return isActivityAlive();
        }

        @Override
        public String mainFrameUrl() {
            return mainFrameUrl;
        }

        @Override
        public boolean beginCompose(String e164, String text) {
            if (!isActivityAlive()) return false;
            recipient = e164;
            body = text;
            injected = false;
            webView.onResume();
            // LIVE-F10: a FULL load of the job's thread (an SPA push does not route), so the
            // pre-send check and the verification read the conversation itself.
            String thread = ThreadUrl.threadUrlOf(e164);
            if (thread != null) webView.loadUrl(thread);
            else loadGoogleVoice();
            return true;
        }

        @Override
        public void forgetCompose(String e164, String text) {
            // deliver L1 (main thread): a later injectComposer must never retype the scheduled
            // text. Only the values beginCompose set are dropped; a newer intent's are kept.
            if (e164 == null || !e164.equals(recipient)) return;
            if (text != null && body != null && !text.equals(body)) return;
            recipient = null;
            body = null;
        }

        @Override
        public boolean loadMessagesList() {
            if (!isActivityAlive()) return false;
            injected = false;
            loadGoogleVoice();
            return true;
        }

        @Override
        public void clearComposer(String text) {
            if (text == null) return;
            final String script = readAssetScript("inject.js");
            if (script == null) return;
            runOnUiThread(() -> {
                if (!isActivityAlive()) return;
                // live-presend-failclosed: inject.js and our body go only into a GV page.
                if (!OriginGuard.isTrustedGvUrl(webView.getUrl())) return;
                // Only our exact body is removed; never logged.
                webView.evaluateJavascript(script + "\nv2sScheduledClear(" + jsonEncode(text) + ");", null);
            });
        }

        @Override
        public void readThread(String e164, int limit, ThreadReadCallback cb) {
            GVoiceWebViewActivity.this.readThread(e164, limit, cb);
        }

        @Override
        public void probeComposer(ScheduledSendController.ComposerProbe cb) {
            runOnUiThread(() -> {
                if (!isActivityAlive() || !OriginGuard.isTrustedGvUrl(webView.getUrl())) {
                    cb.onProbe(null);
                    return;
                }
                // Only "is there text in the message box", never the text itself.
                webView.evaluateJavascript(SCHEDULED_COMPOSER_PROBE_JS,
                        value -> cb.onProbe("true".equals(value) ? Boolean.TRUE
                                : "false".equals(value) ? Boolean.FALSE : null));
            });
        }

        @Override
        public void restoreDraft(String draft, String text, ScheduledSendController.ComposerProbe cb) {
            final String script = draft == null ? null : readAssetScript("inject.js");
            if (script == null) {
                cb.onProbe(null);
                return;
            }
            runOnUiThread(() -> {
                // D32 Send both: the user's draft goes back only into a GV page; never logged.
                if (!isActivityAlive() || !OriginGuard.isTrustedGvUrl(webView.getUrl())) {
                    cb.onProbe(null);
                    return;
                }
                webView.evaluateJavascript(script + "\nv2sScheduledRestoreDraft(" + jsonEncode(draft) + ", "
                                + jsonEncode(text != null ? text : "") + ");",
                        value -> cb.onProbe("true".equals(value) ? Boolean.TRUE : Boolean.FALSE));
            });
        }

        @Override
        public void finish() {
            GVoiceWebViewActivity.this.finish();
        }

        @Override
        public View pageView() {
            return isActivityAlive() ? webView : null;
        }
    }

    private static String jsonEncode(String s) {
        try {
            JSONArray arr = new JSONArray();
            arr.put(s);
            String json = arr.toString();
            return json.substring(1, json.length() - 1);
        } catch (Exception e) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r") + "\"";
        }
    }

    /**
     * True when this Activity is still usable — webView non-null and Activity not
     * tearing down. All V2SBridge runnables must check this before touching webView,
     * because the singleton WebView can be nulled by onRenderProcessGone.
     */
    private boolean isActivityAlive() {
        return webView != null && !isFinishing() && !isDestroyed();
    }

    private void showKeyboardForWebView() {
        if (!isActivityAlive()) return;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(webView, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
            Log.d(TAG, "Called showSoftInput(SHOW_FORCED)");
        }
    }

    private class V2SBridge {
        @JavascriptInterface
        public void requestTapAndType(float cssX, float cssY, String text) {
            if (BuildConfig.DEBUG) Log.d(TAG, "JS requested tap(" + cssX + "," + cssY + ") + type: " + text);
            runOnUiThread(() -> {
                if (!isActivityAlive()) return;
                tapAndType(cssX, cssY, text);
            });
        }

        @JavascriptInterface
        public void requestType(String text) {
            if (BuildConfig.DEBUG) Log.d(TAG, "JS requested native typing: " + text);
            runOnUiThread(() -> {
                if (!isActivityAlive()) return;
                typeIntoWebView(text, 0);
            });
        }

        @JavascriptInterface
        public void requestShowKeyboard() {
            Log.d(TAG, "JS requested showKeyboard");
            runOnUiThread(() -> {
                if (!isActivityAlive()) return;
                // send-lock-cover: no keyboard over the keyguard while the page is covered.
                if (scheduledSend != null && scheduledSend.isCovered()) return;
                webView.setFocusableInTouchMode(true);
                webView.requestFocus();
                new Handler(Looper.getMainLooper())
                        .postDelayed(GVoiceWebViewActivity.this::showKeyboardForWebView, 50);
            });
        }

        @JavascriptInterface
        public void requestPageReload() {
            Log.d(TAG, "JS requested page reload for warm start recompose");
            runOnUiThread(() -> {
                if (!isActivityAlive()) return;
                injected = false;
                webView.loadUrl(GV_MESSAGES_URL);
            });
        }

        @JavascriptInterface
        public void requestFocusAndKeyboard(float cssX, float cssY) {
            Log.d(TAG, "JS requested focus+keyboard at CSS (" + cssX + "," + cssY + ")");
            runOnUiThread(() -> {
                if (!isActivityAlive()) return;
                webView.setFocusableInTouchMode(true);
                webView.requestFocus();
                webView.requestFocusFromTouch();

                float scale = webView.getScale();
                float viewX = cssX * scale;
                float viewY = cssY * scale;
                Log.d(TAG, "Dispatching touch at view (" + viewX + "," + viewY +
                        "), scale=" + scale);

                long downTime = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(downTime, downTime,
                        MotionEvent.ACTION_DOWN, viewX, viewY, 0);
                MotionEvent up = MotionEvent.obtain(downTime, downTime + 80,
                        MotionEvent.ACTION_UP, viewX, viewY, 0);
                webView.dispatchTouchEvent(down);
                webView.dispatchTouchEvent(up);
                down.recycle();
                up.recycle();

                new Handler(Looper.getMainLooper())
                        .postDelayed(GVoiceWebViewActivity.this::showKeyboardForWebView, 100);
            });
        }

        // ---- Send Later (R10/R11/R17/R18); binder thread, logic in SendLaterWebHooks ----

        @JavascriptInterface
        public void requestSendLater(String nonce, String json) {
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(mainFrameUrl)) return;
            final CapturePayload payload =
                    sendLaterHooks != null ? sendLaterHooks.bridgeAcceptCapture(json) : null;
            if (payload == null) return;
            runOnUiThread(() -> {
                if (!isActivityAlive()) return;
                sendLaterHooks.openSheet(payload, webView.getUrl());
            });
        }

        @JavascriptInterface
        public String sendLaterChipState(String nonce, String e164) {
            final String url = mainFrameUrl;
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(url)) return ChipState.disabled();
            if (sendLaterHooks == null) {
                return ChipState.disabled();
            }
            return sendLaterHooks.bridgeChipState(e164, url);
        }

        /**
         * R10a (D46, OD36): chip state for GV's new-message page. The page reports its recipient
         * chip count and the single chip's E.164; this activity confirms that number against its
         * own intent recipient (NewComposeRecipient), never page text alone.
         */
        @JavascriptInterface
        public String sendLaterNewComposeState(String nonce, int chipCount, String e164) {
            final String url = mainFrameUrl;
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(url)) return ChipState.disabled();
            if (sendLaterHooks == null) {
                return ChipState.disabled();
            }
            final String confirmed = NewComposeRecipient.confirm(url, recipient, chipCount, e164);
            return sendLaterHooks.bridgeNewComposeState(confirmed, url);
        }

        @JavascriptInterface
        public void requestSendLaterChipTap(String nonce, String e164) {
            final String url = mainFrameUrl;
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(url)) return;
            if (sendLaterHooks == null) {
                return;
            }
            sendLaterHooks.bridgeChipTap(e164, url);
        }

        @JavascriptInterface
        public boolean requestSendLaterUndo(String nonce, long messageId) {
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(mainFrameUrl)) return false;
            if (sendLaterHooks == null) {
                return false;
            }
            return sendLaterHooks.bridgeUndo(messageId);
        }

        @JavascriptInterface
        public void onThreadRead(String nonce, String json) {
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(mainFrameUrl)) return;
            if (sendLaterHooks == null) {
                return;
            }
            sendLaterHooks.bridgeThreadRead(json);
        }

        // ---- Scheduled send (R30; LIVE-F6/F8); binder thread ----

        /** inject.js v2sScheduledCompose: our body is in the message box for {@code token}. */
        @JavascriptInterface
        public void onScheduledTyped(String nonce, String token) {
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(mainFrameUrl)) return;
            runOnUiThread(() -> {
                if (!isActivityAlive() || scheduledSend == null) return;
                scheduledSend.onComposerInjected(token);
            });
        }

        /**
         * LIVE-F10: inject.js v2sScheduledCompose's tri-state Send ask: "yes" (tap now, once per
         * job), "wait" (the pre-send check is pending: ask again) or "no".
         */
        @JavascriptInterface
        public String scheduledClickState(String nonce, String token) {
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(mainFrameUrl)) return "no";
            return ScheduledSendController.clickState(token);
        }

        /**
         * LIVE-F10 F1: as {@link #scheduledClickState}, with the page's own location: on the job's
         * 1:1 thread an empty-conversation clearance is dropped (WAIT) and the thread read again.
         */
        @JavascriptInterface
        public String scheduledClickStateAt(String nonce, String token, String pageUrl) {
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(mainFrameUrl)) return "no";
            return ScheduledSendController.clickState(token, pageUrl);
        }

        /**
         * deliver H1/L2: as {@link #scheduledClickStateAt}, with the page's recipient chips
         * ({@code chipCount}; {@code chipDigits}: the single chip's digit runs, never a name).
         * "no" unless the page is the job's own thread or a new-message view whose single chip is
         * the job's number.
         */
        @JavascriptInterface
        public String scheduledClickStateIn(String nonce, String token, String pageUrl, int chipCount, String chipDigits) {
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(mainFrameUrl)) return "no";
            return ScheduledSendController.clickState(token, pageUrl, chipCount, chipDigits);
        }

        /** LIVE-F10: the job's thread page settled without the conversation (new-conversation flow next). */
        @JavascriptInterface
        public void onScheduledNoThread(String nonce, String token) {
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(mainFrameUrl)) return;
            runOnUiThread(() -> {
                if (!isActivityAlive() || scheduledSend == null) return;
                scheduledSend.onNoThread(token);
            });
        }

        /** LIVE-F11: the scheduled script found the page is not the job's thread (load it again, bounded). */
        @JavascriptInterface
        public void onScheduledWrongPage(String nonce, String token) {
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(mainFrameUrl)) return;
            runOnUiThread(() -> {
                if (!isActivityAlive() || scheduledSend == null) return;
                scheduledSend.onWrongPage(token);
            });
        }

        /**
         * OD34: thread mode found text in the thread's box that is not our body before typing;
         * the job stops unsent and the draft is left alone.
         */
        @JavascriptInterface
        public void onScheduledDraftPresent(String nonce, String token) {
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(mainFrameUrl)) return;
            runOnUiThread(() -> {
                if (!isActivityAlive() || scheduledSend == null) return;
                scheduledSend.onDraftPresent(token);
            });
        }

        /**
         * D32: thread mode found a draft (text that is not our body) in the thread's box before
         * typing. Answers "replace", "both" or "stop" (ScheduledSendController.draftStep); "stop"
         * ends the job unsent with the choice notification, "both" keeps the draft in memory for
         * this job. The draft is never logged.
         */
        @JavascriptInterface
        public String scheduledDraftStep(String nonce, String token, String boxText) {
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(mainFrameUrl)) return "stop";
            final String step = ScheduledSendController.draftStep(token, boxText);
            if ("stop".equals(step) || "both".equals(step)) {
                runOnUiThread(() -> {
                    if (!isActivityAlive() || scheduledSend == null) return;
                    scheduledSend.onDraftStep(token, step, boxText);
                });
            }
            return step;
        }

        @JavascriptInterface
        public void onThreadDirectory(String nonce, String json) {
            if (!BRIDGE_NONCE.accepts(nonce) || !OriginGuard.isTrustedGvUrl(mainFrameUrl)) return;
            if (sendLaterHooks == null) {
                return;
            }
            sendLaterHooks.bridgeThreadDirectory(json);
        }
    }

    /**
     * LIVE-F13: the stable object the page sees as "V2SBridge", registered once per singleton
     * WebView. Every call forwards to the attached Activity's {@link V2SBridge}, which keeps all
     * the guards (BridgeNonce, OriginGuard on that Activity's mainFrameUrl, isActivityAlive).
     * With no Activity attached each method gives the answer V2SBridge gives when it refuses:
     * nothing, {@code ChipState.disabled()}, {@code false}, {@code "no"} or {@code "stop"}.
     * Holds no Activity itself (static; only the delegate's current target).
     */
    static final class PageBridge {
        private final LiveDelegate<V2SBridge> live;

        PageBridge(LiveDelegate<V2SBridge> live) {
            this.live = live;
        }

        @JavascriptInterface
        public final void requestTapAndType(float cssX, float cssY, String text) {
            V2SBridge b = live.current();
            if (b != null) b.requestTapAndType(cssX, cssY, text);
        }

        @JavascriptInterface
        public final void requestType(String text) {
            V2SBridge b = live.current();
            if (b != null) b.requestType(text);
        }

        @JavascriptInterface
        public final void requestShowKeyboard() {
            V2SBridge b = live.current();
            if (b != null) b.requestShowKeyboard();
        }

        @JavascriptInterface
        public final void requestPageReload() {
            V2SBridge b = live.current();
            if (b != null) b.requestPageReload();
        }

        @JavascriptInterface
        public final void requestFocusAndKeyboard(float cssX, float cssY) {
            V2SBridge b = live.current();
            if (b != null) b.requestFocusAndKeyboard(cssX, cssY);
        }

        @JavascriptInterface
        public final void requestSendLater(String nonce, String json) {
            V2SBridge b = live.current();
            if (b != null) b.requestSendLater(nonce, json);
        }

        @JavascriptInterface
        public final String sendLaterChipState(String nonce, String e164) {
            V2SBridge b = live.current();
            return b != null ? b.sendLaterChipState(nonce, e164) : ChipState.disabled();
        }

        @JavascriptInterface
        public final String sendLaterNewComposeState(String nonce, int chipCount, String e164) {
            V2SBridge b = live.current();
            return b != null ? b.sendLaterNewComposeState(nonce, chipCount, e164) : ChipState.disabled();
        }

        @JavascriptInterface
        public final void requestSendLaterChipTap(String nonce, String e164) {
            V2SBridge b = live.current();
            if (b != null) b.requestSendLaterChipTap(nonce, e164);
        }

        @JavascriptInterface
        public final boolean requestSendLaterUndo(String nonce, long messageId) {
            V2SBridge b = live.current();
            return b != null && b.requestSendLaterUndo(nonce, messageId);
        }

        @JavascriptInterface
        public final void onThreadRead(String nonce, String json) {
            V2SBridge b = live.current();
            if (b != null) b.onThreadRead(nonce, json);
        }

        @JavascriptInterface
        public final void onScheduledTyped(String nonce, String token) {
            V2SBridge b = live.current();
            if (b != null) b.onScheduledTyped(nonce, token);
        }

        @JavascriptInterface
        public final String scheduledClickState(String nonce, String token) {
            V2SBridge b = live.current();
            return b != null ? b.scheduledClickState(nonce, token) : "no";
        }

        @JavascriptInterface
        public final String scheduledClickStateAt(String nonce, String token, String pageUrl) {
            V2SBridge b = live.current();
            return b != null ? b.scheduledClickStateAt(nonce, token, pageUrl) : "no";
        }

        @JavascriptInterface
        public final String scheduledClickStateIn(String nonce, String token, String pageUrl, int chipCount, String chipDigits) {
            V2SBridge b = live.current();
            return b != null ? b.scheduledClickStateIn(nonce, token, pageUrl, chipCount, chipDigits) : "no";
        }

        @JavascriptInterface
        public final void onScheduledNoThread(String nonce, String token) {
            V2SBridge b = live.current();
            if (b != null) b.onScheduledNoThread(nonce, token);
        }

        @JavascriptInterface
        public final void onScheduledWrongPage(String nonce, String token) {
            V2SBridge b = live.current();
            if (b != null) b.onScheduledWrongPage(nonce, token);
        }

        @JavascriptInterface
        public final void onScheduledDraftPresent(String nonce, String token) {
            V2SBridge b = live.current();
            if (b != null) b.onScheduledDraftPresent(nonce, token);
        }

        @JavascriptInterface
        public final String scheduledDraftStep(String nonce, String token, String boxText) {
            V2SBridge b = live.current();
            return b != null ? b.scheduledDraftStep(nonce, token, boxText) : "stop";
        }

        @JavascriptInterface
        public final void onThreadDirectory(String nonce, String json) {
            V2SBridge b = live.current();
            if (b != null) b.onThreadDirectory(nonce, json);
        }
    }

    /**
     * Read a sticker/image from a content URI delivered by GBoard via commitContent,
     * convert to a format GV web accepts, and inject into the page's file upload input.
     */
    private void handleStickerImage(Uri uri) {
        new Thread(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) {
                    Log.w(TAG, "Could not open sticker URI: " + uri);
                    return;
                }
                byte[] rawBytes = readAllBytes(is);
                is.close();

                String rawMime = getContentResolver().getType(uri);
                if (rawMime == null) rawMime = "image/png";

                // GV web accepts JPG/PNG/GIF but not WebP — convert if needed
                final byte[] finalBytes;
                final String finalMime;
                if (rawMime.equals("image/webp")) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length);
                    if (bitmap != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                        finalBytes = baos.toByteArray();
                        finalMime = "image/png";
                        bitmap.recycle();
                    } else {
                        finalBytes = rawBytes;
                        finalMime = rawMime;
                    }
                } else {
                    finalBytes = rawBytes;
                    finalMime = rawMime;
                }

                String ext;
                switch (finalMime) {
                    case "image/gif":  ext = "gif";  break;
                    case "image/jpeg": ext = "jpg";  break;
                    default:           ext = "png";  break;
                }

                String fileName = "sticker." + ext;
                String base64 = Base64.encodeToString(finalBytes, Base64.NO_WRAP);
                // Use JSONObject.quote() to safely escape all arguments (prevents
                // JS injection from malicious MIME types or filenames)
                String js = "sendImageToGV("
                        + org.json.JSONObject.quote(base64) + ","
                        + org.json.JSONObject.quote(finalMime) + ","
                        + org.json.JSONObject.quote(fileName) + ")";

                runOnUiThread(() -> {
                    if (!isActivityAlive()) return;
                    stickerInjectionActive = true;
                    Log.d(TAG, "Injecting sticker: " + finalMime + ", " + finalBytes.length + " bytes");
                    webView.evaluateJavascript(js, null);
                    // Auto-clear flag after 5s in case the JS path never
                    // triggers onShowFileChooser (avoids permanently
                    // suppressing the user's manual file picker)
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (stickerInjectionActive) {
                            stickerInjectionActive = false;
                            Log.d(TAG, "Sticker injection flag auto-cleared");
                        }
                    }, 5000);
                });
            } catch (Exception e) {
                Log.w(TAG, "Failed to handle sticker image", e);
            }
        }).start();
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private void tapAndType(float cssX, float cssY, String text) {
        if (!isActivityAlive()) return;
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        webView.requestFocusFromTouch();

        float scale = webView.getScale();
        float viewX = cssX * scale;
        float viewY = cssY * scale;

        Log.d(TAG, "Tapping at view coords: (" + viewX + ", " + viewY +
                "), scale=" + scale);

        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, viewX, viewY, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 100,
                MotionEvent.ACTION_UP, viewX, viewY, 0);
        webView.dispatchTouchEvent(down);
        webView.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();

        if (text != null && !text.isEmpty()) {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(() -> {
                if (!isActivityAlive()) return;
                typeIntoWebView(text, 0);
            }, 2000);
        } else {
            Log.d(TAG, "Tap-only (no text to type), keyboard should appear");
        }
    }

    private void typeIntoWebView(String text, long initialDelay) {
        if (!isActivityAlive()) return;
        Handler handler = new Handler(Looper.getMainLooper());

        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        webView.requestFocusFromTouch();

        handler.postDelayed(() -> {
            if (!isActivityAlive()) return;
            try {
                new Thread(() -> {
                    try {
                        for (int i = 0; i < text.length(); i++) {
                            char c = text.charAt(i);
                            if (c == '+') {
                                Runtime.getRuntime().exec(new String[]{"input", "keyevent", "81"}).waitFor();
                            } else {
                                Runtime.getRuntime().exec(new String[]{"input", "text", String.valueOf(c)}).waitFor();
                            }
                            Thread.sleep(50);
                        }
                        Log.d(TAG, "Shell input typing complete");
                        runOnUiThread(() -> {
                            if (!isActivityAlive()) return;
                            webView.evaluateJavascript(
                                "document.querySelector('input[placeholder*=\"name or phone\"]')?.value || ''",
                                value -> {
                                    if (!isActivityAlive()) return;
                                    if (BuildConfig.DEBUG) Log.d(TAG, "Input value after shell typing: " + value);
                                    webView.evaluateJavascript(
                                        "if(window._v2sOnTyped)window._v2sOnTyped()", null);
                                }
                            );
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "Shell input typing failed: " + e.getMessage());
                        runOnUiThread(() -> {
                            if (!isActivityAlive()) return;
                            typeViaInputConnection(text);
                        });
                    }
                }).start();
            } catch (Exception e) {
                Log.w(TAG, "Shell input approach failed: " + e.getMessage());
                typeViaInputConnection(text);
            }
        }, initialDelay);
    }

    private void typeViaInputConnection(String text) {
        if (!isActivityAlive()) return;
        Handler handler = new Handler(Looper.getMainLooper());
        for (int i = 0; i < text.length(); i++) {
            final String ch = String.valueOf(text.charAt(i));
            handler.postDelayed(() -> {
                if (!isActivityAlive()) return;
                InputConnection ic = webView.onCreateInputConnection(new EditorInfo());
                if (ic != null) {
                    ic.commitText(ch, 1);
                } else {
                    Log.w(TAG, "No InputConnection — input may not be focused");
                }
            }, i * 60L);
        }
        handler.postDelayed(() -> {
            if (!isActivityAlive()) return;
            Log.d(TAG, "InputConnection typing complete, signaling JS");
            webView.evaluateJavascript("if(window._v2sOnTyped)window._v2sOnTyped()", null);
        }, text.length() * 60L + 300);
    }

    /**
     * send-hardening R5: while resumed and Send Later is on, probe whether the message box holds
     * text (a draft typed on the soft keyboard sends no touches here). Answers a boolean only,
     * never the text. While a scheduled send owns the page the text is ours, so it does not
     * count. With the switch off the probe stops itself (R40); SendLaterWebHooks restarts it via
     * Host.onSendLaterSwitch when the switch goes on.
     */
    private final Handler composerProbeHandler = new Handler(Looper.getMainLooper());
    private final Runnable composerProbe = new Runnable() {
        @Override
        public void run() {
            if (!isActivityAlive()) return;
            if (!SendLaterSettings.isEnabled(GVoiceWebViewActivity.this)) {
                ForegroundUse.noteComposer(false, System.currentTimeMillis());
                return;
            }
            if (scheduledSend != null && (scheduledSend.isActive() || scheduledSend.isCovered())) {
                ForegroundUse.noteComposer(false, System.currentTimeMillis());
            } else {
                webView.evaluateJavascript(SCHEDULED_COMPOSER_PROBE_JS,
                        value -> ForegroundUse.noteComposer("true".equals(value), System.currentTimeMillis()));
            }
            composerProbeHandler.postDelayed(this, ForegroundUse.COMPOSER_PROBE_INTERVAL_MS);
        }
    };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (scheduledSend != null) scheduledSend.onWindowFocusChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ForegroundUse.noteResumed(false);
        composerProbeHandler.removeCallbacks(composerProbe);
        ForegroundUse.noteComposer(false, System.currentTimeMillis());
        if (scheduledSend != null) scheduledSend.onPauseOrStop(false);
        if (sendLaterHooks != null) sendLaterHooks.onPause();
        CookieManager.getInstance().flush();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ForegroundUse.noteResumed(true);
        composerProbeHandler.removeCallbacks(composerProbe);
        composerProbeHandler.post(composerProbe);
        if (scheduledSend != null) scheduledSend.onResume();
        if (sendLaterHooks != null) sendLaterHooks.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (scheduledSend != null) scheduledSend.onPauseOrStop(true);
    }

    /**
     * send-lock-cover: while a scheduled send is covered over the keyguard, every touch is
     * consumed here (the WebView underneath only gets inject.js's own events). Otherwise records
     * the touch so a due send does not take the page over from an active user (OD23).
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (scheduledSend != null && scheduledSend.isCovered()) return true;
        ForegroundUse.noteTouch(System.currentTimeMillis());
        return super.dispatchTouchEvent(ev);
    }

    /** send-lock-cover: keys (and back) are consumed while covered. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (scheduledSend != null && scheduledSend.isCovered()) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (scheduledSend != null && scheduledSend.isCovered()) return true;
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (scheduledSend != null && scheduledSend.isCovered()) return;   // back is consumed while covered
        finish();
    }

    @Override
    protected void onDestroy() {
        // Cancel any pending file chooser callback so the WebView doesn't
        // permanently disable its file upload input
        if (fileUploadCallback != null) {
            fileUploadCallback.onReceiveValue(null);
            fileUploadCallback = null;
        }

        // LIVE-F13: the page's stable V2SBridge stops forwarding to this Activity, unless a newer
        // Activity already took it over (its onCreate may run before this onDestroy). The bridge
        // object itself stays registered: removing it would drop it from the page on the next load.
        LIVE_BRIDGE.detach(pageBridgeTarget);

        // Remove the content listener to break references from singleton WebView → this Activity
        if (webView != null) {
            ViewCompat.setOnReceiveContentListener(webView, RichContentWebView.IMAGE_MIME_TYPES, null);
        }

        // Detach WebView from our layout but do NOT destroy it
        ViewGroup container = findViewById(R.id.webview_container);
        if (container != null && webView != null) {
            container.removeView(webView);
        }

        composerProbeHandler.removeCallbacks(composerProbe);
        if (sendLaterHooks != null) sendLaterHooks.onDestroy();
        if (scheduledSend != null) scheduledSend.onDestroy();

        Voice2SmsApplication app = (Voice2SmsApplication) getApplication();
        app.detachActivityContext();

        super.onDestroy();
        // Note: we do NOT call webView.destroy() — it stays alive in the Application
    }
}
