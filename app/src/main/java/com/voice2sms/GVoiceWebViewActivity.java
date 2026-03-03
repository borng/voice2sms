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
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;

import java.io.BufferedReader;
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

    private WebView webView;
    private String recipient;
    private String body;
    private boolean injected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        recipient = getIntent().getStringExtra("recipient");
        body = getIntent().getStringExtra("body");

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

        // Set up WebViewClient and JS interface (re-set each time since
        // the bridge closure captures `this` Activity)
        setupWebViewClient();
        webView.addJavascriptInterface(new V2SBridge(), "V2SBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                if (cm.message() != null && cm.message().contains("[Voice2SMS]")) {
                    Log.d(TAG, "JS: " + cm.message());
                }
                return super.onConsoleMessage(cm);
            }
        });

        if (app.isSpaLoaded() && recipient != null) {
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
            } else {
                attemptAccountAuth();
            }
        }
        // else: SPA loaded but no recipient — just show the WebView
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        recipient = intent.getStringExtra("recipient");
        body = intent.getStringExtra("body");
        injected = false;

        Log.d(TAG, "onNewIntent: recipient=" + recipient + ", body=" + (body != null ? "\"" + body + "\"" : "null"));

        webView.onResume();

        // Always reload the page for a clean messages-list state.
        // The SPA may be on a conversation thread or stale compose view.
        // onPageFinished will inject the composer once the page is ready.
        Log.d(TAG, "Warm start: reloading page for clean state");
        loadGoogleVoice();
    }

    private void setupWebViewClient() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("google.com") || url.contains("googleapis.com")
                        || url.contains("gstatic.com")) {
                    return false;
                }
                Log.d(TAG, "Blocking non-Google URL: " + url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // Always inject fingerprint mask (includes dark mode CSS).
                // Must run early before page renders to avoid flash of light mode.
                injectFingerprintMask();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "onPageFinished: " + url);

                CookieManager.getInstance().flush();

                if (url.startsWith("https://voice.google.com") && !injected && recipient != null) {
                    // Mark SPA as loaded for future warm starts
                    Voice2SmsApplication app = (Voice2SmsApplication) getApplication();
                    app.setSpaLoaded(true);
                    injectComposer();
                } else if (url.startsWith("https://voice.google.com") && !injected) {
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
                    view.loadUrl(GV_MESSAGES_URL);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Log.w(TAG, "Renderer gone! crashed=" + detail.didCrash());
                Voice2SmsApplication app = (Voice2SmsApplication) getApplication();
                app.onRendererGone();
                webView = null; // prevent stale reference in onPause/onDestroy
                recreate();
                return true;
            }
        });
    }

    private void attemptAccountAuth() {
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
        am.getAuthToken(account, "weblogin:service=grandcentral",
                null, this, future -> {
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
                }, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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

    private void loadGoogleVoice() {
        webView.loadUrl(GV_MESSAGES_URL);
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

    private void injectComposer() {
        injected = true;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean autoSend = getIntent().getBooleanExtra("force_auto_send", false);
        int autoSendDelay = prefs.getInt("auto_send_delay", 2) * 1000;

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
            Log.d(TAG, "Injected composer JS for recipient: " + recipient
                    + ", body=" + (body != null ? "\"" + body + "\"" : "null")
                    + ", autoSend=" + autoSend + ", delay=" + autoSendDelay);
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject JS", e);
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

    private class V2SBridge {
        @JavascriptInterface
        public void requestTapAndType(float cssX, float cssY, String text) {
            Log.d(TAG, "JS requested tap(" + cssX + "," + cssY + ") + type: " + text);
            runOnUiThread(() -> tapAndType(cssX, cssY, text));
        }

        @JavascriptInterface
        public void requestType(String text) {
            Log.d(TAG, "JS requested native typing: " + text);
            runOnUiThread(() -> typeIntoWebView(text, 0));
        }

        @JavascriptInterface
        public void requestShowKeyboard() {
            Log.d(TAG, "JS requested showKeyboard");
            runOnUiThread(() -> {
                webView.setFocusableInTouchMode(true);
                webView.requestFocus();
                Handler handler = new Handler(Looper.getMainLooper());
                handler.postDelayed(() -> {
                    android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(webView, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
                        Log.d(TAG, "Called showSoftInput(SHOW_FORCED)");
                    }
                }, 50);
            });
        }

        @JavascriptInterface
        public void requestPageReload() {
            Log.d(TAG, "JS requested page reload for warm start recompose");
            runOnUiThread(() -> {
                injected = false;
                webView.loadUrl(GV_MESSAGES_URL);
            });
        }

        @JavascriptInterface
        public void requestFocusAndKeyboard(float cssX, float cssY) {
            Log.d(TAG, "JS requested focus+keyboard at CSS (" + cssX + "," + cssY + ")");
            runOnUiThread(() -> {
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

                Handler handler = new Handler(Looper.getMainLooper());
                handler.postDelayed(() -> {
                    android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(webView, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
                        Log.d(TAG, "Called showSoftInput after touch");
                    }
                }, 100);
            });
        }
    }

    private void tapAndType(float cssX, float cssY, String text) {
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
            handler.postDelayed(() -> typeIntoWebView(text, 0), 2000);
        } else {
            Log.d(TAG, "Tap-only (no text to type), keyboard should appear");
        }
    }

    private void typeIntoWebView(String text, long initialDelay) {
        Handler handler = new Handler(Looper.getMainLooper());

        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        webView.requestFocusFromTouch();

        handler.postDelayed(() -> {
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
                            webView.evaluateJavascript(
                                "document.querySelector('input[placeholder*=\"name or phone\"]')?.value || ''",
                                value -> {
                                    Log.d(TAG, "Input value after shell typing: " + value);
                                    webView.evaluateJavascript(
                                        "if(window._v2sOnTyped)window._v2sOnTyped()", null);
                                }
                            );
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "Shell input typing failed: " + e.getMessage());
                        runOnUiThread(() -> typeViaInputConnection(text));
                    }
                }).start();
            } catch (Exception e) {
                Log.w(TAG, "Shell input approach failed: " + e.getMessage());
                typeViaInputConnection(text);
            }
        }, initialDelay);
    }

    private void typeViaInputConnection(String text) {
        Handler handler = new Handler(Looper.getMainLooper());
        for (int i = 0; i < text.length(); i++) {
            final String ch = String.valueOf(text.charAt(i));
            handler.postDelayed(() -> {
                InputConnection ic = webView.onCreateInputConnection(new EditorInfo());
                if (ic != null) {
                    ic.commitText(ch, 1);
                } else {
                    Log.w(TAG, "No InputConnection — input may not be focused");
                }
            }, i * 60L);
        }
        handler.postDelayed(() -> {
            Log.d(TAG, "InputConnection typing complete, signaling JS");
            webView.evaluateJavascript("if(window._v2sOnTyped)window._v2sOnTyped()", null);
        }, text.length() * 60L + 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        // Detach WebView from our layout but do NOT destroy it
        ViewGroup container = findViewById(R.id.webview_container);
        if (container != null && webView != null) {
            container.removeView(webView);
        }

        Voice2SmsApplication app = (Voice2SmsApplication) getApplication();
        app.detachActivityContext();

        super.onDestroy();
        // Note: we do NOT call webView.destroy() — it stays alive in the Application
    }
}
