package com.voice2sms;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
 * After the page loads, injects JavaScript to compose a message
 * with the recipient and body passed from SmsHandlerActivity.
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

        webView = findViewById(R.id.webview);
        setupWebView();

        // Check if we already have a session cookie for Google Voice
        CookieManager cookieManager = CookieManager.getInstance();
        String cookies = cookieManager.getCookie("https://voice.google.com");

        if (cookies != null && cookies.contains("SID")) {
            // Looks like we have an active session, load directly
            loadGoogleVoice();
        } else {
            // Try AccountManager auth flow
            attemptAccountAuth();
        }
    }

    // Exact Chrome Mobile UA — matches real Chrome 131 on Pixel 8 / Android 14
    private static final String CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/131.0.0.0 Mobile Safari/537.36";

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(CHROME_UA);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // Enable third-party cookies for Google auth
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // Enable debugging so we can inspect/automate via CDP if needed
        WebView.setWebContentsDebuggingEnabled(true);
        webView.addJavascriptInterface(new V2SBridge(), "V2SBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Keep Google auth and voice URLs in the WebView
                if (url.contains("google.com") || url.contains("googleapis.com")
                        || url.contains("gstatic.com")) {
                    return false;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // Reset injection flag when navigating to a new GV page
                if (url.startsWith("https://voice.google.com")) {
                    injected = false;
                }
                // Inject fingerprint masking as early as possible
                injectFingerprintMask();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "onPageFinished: " + url);
                if (url.startsWith("https://voice.google.com") && !injected && recipient != null) {
                    injectComposer();
                } else if (!url.contains("voice.google.com") && !injected
                        && url.contains("accounts.google.com")
                        && url.contains("MergeSession")
                        && !url.contains("WILL_NOT_SIGN_IN")) {
                    // Successful auth token exchange — redirect to GV
                    Log.d(TAG, "Auth MergeSession loaded, redirecting to GV");
                    view.loadUrl(GV_MESSAGES_URL);
                }
            }
        });
    }

    private void attemptAccountAuth() {
        try {
            AccountManager am = AccountManager.get(this);
            Account[] accounts = am.getAccountsByType("com.google");

            if (accounts.length == 0) {
                // No Google accounts; just load GV and let user sign in manually
                loadGoogleVoice();
                return;
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            String savedAccount = prefs.getString("google_account", null);

            if (savedAccount != null) {
                // Use previously selected account
                for (Account account : accounts) {
                    if (account.name.equals(savedAccount)) {
                        requestAuthToken(am, account);
                        return;
                    }
                }
            }

            if (accounts.length == 1) {
                // Only one account, use it
                prefs.edit().putString("google_account", accounts[0].name).apply();
                requestAuthToken(am, accounts[0]);
            } else {
                // Multiple accounts — show picker
                Intent pickIntent = AccountManager.newChooseAccountIntent(
                        null, null, new String[]{"com.google"}, null, null, null, null);
                startActivityForResult(pickIntent, REQUEST_ACCOUNT_PICKER);
            }
        } catch (Exception e) {
            Log.w(TAG, "AccountManager auth failed, falling back to manual login", e);
            loadGoogleVoice();
        }
    }

    private void requestAuthToken(AccountManager am, Account account) {
        // Use "weblogin:" token type to get a web session token
        am.getAuthToken(account, "weblogin:service=grandcentral",
                null, this, new AccountManagerCallback<Bundle>() {
                    @Override
                    public void run(AccountManagerFuture<Bundle> future) {
                        try {
                            Bundle result = future.getResult();
                            String authUrl = result.getString(AccountManager.KEY_AUTHTOKEN);
                            if (authUrl != null && authUrl.startsWith("http")
                                    && !authUrl.contains("WILL_NOT_SIGN_IN")) {
                                // Load the token exchange URL — this sets session cookies
                                Log.d(TAG, "Loading auth token exchange URL");
                                webView.loadUrl(authUrl);
                            } else {
                                Log.d(TAG, "Auth token not usable (WILL_NOT_SIGN_IN or null), loading GV directly");
                                loadGoogleVoice();
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Auth token exchange failed", e);
                            loadGoogleVoice();
                        }
                    }
                }, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ACCOUNT_PICKER) {
            if (resultCode == RESULT_OK && data != null) {
                String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                if (accountName != null) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                    prefs.edit().putString("google_account", accountName).apply();

                    AccountManager am = AccountManager.get(this);
                    Account[] accounts = am.getAccountsByType("com.google");
                    for (Account account : accounts) {
                        if (account.name.equals(accountName)) {
                            requestAuthToken(am, account);
                            return;
                        }
                    }
                }
            }
            // Fallback
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
        boolean autoSend = getIntent().getBooleanExtra("force_auto_send",
                prefs.getBoolean("auto_send", false));
        int autoSendDelay = prefs.getInt("auto_send_delay", 2) * 1000;

        try {
            // Load inject.js from assets
            InputStream is = getAssets().open("inject.js");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();

            String js = sb.toString();
            // Append the function call with JSON-encoded arguments for safety
            String jsonRecipient = jsonEncode(recipient);
            String jsonBody = jsonEncode(body != null ? body : "");
            js += "\nvoice2sms(" + jsonRecipient + ", " + jsonBody + ", "
                    + autoSend + ", " + autoSendDelay + ");";

            webView.evaluateJavascript(js, null);
            Log.d(TAG, "Injected composer JS for recipient: " + recipient);
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject JS", e);
        }
    }

    /** Encode a string as a JSON string literal (with surrounding quotes). */
    private static String jsonEncode(String s) {
        try {
            JSONArray arr = new JSONArray();
            arr.put(s);
            String json = arr.toString(); // ["the string"]
            return json.substring(1, json.length() - 1); // "the string"
        } catch (Exception e) {
            // Fallback: basic escaping
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r") + "\"";
        }
    }

    /**
     * JavaScript interface: lets inject.js call back to Java for operations
     * that require trusted native events (e.g., typing into inputs).
     */
    private class V2SBridge {
        /**
         * Called by inject.js with the input's CSS coordinates and text to type.
         * Simulates a real tap at (cssX, cssY) to establish an InputConnection,
         * then types the text character by character via dispatchKeyEvent.
         */
        @JavascriptInterface
        public void requestTapAndType(float cssX, float cssY, String text) {
            Log.d(TAG, "JS requested tap(" + cssX + "," + cssY + ") + type: " + text);
            runOnUiThread(() -> tapAndType(cssX, cssY, text));
        }

        /** Backward compat — type without tap (may not trigger autocomplete). */
        @JavascriptInterface
        public void requestType(String text) {
            Log.d(TAG, "JS requested native typing: " + text);
            runOnUiThread(() -> typeIntoWebView(text, 0));
        }
    }

    /**
     * Simulate a real tap at CSS coordinates, then type text.
     * The tap goes through the full Android touch pipeline which establishes
     * an InputConnection — required for key events to reach the focused input.
     */
    private void tapAndType(float cssX, float cssY, String text) {
        // Ensure WebView has focus first
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        webView.requestFocusFromTouch();

        // Convert CSS pixels to Android view pixels (account for WebView scale)
        float scale = webView.getScale();
        float viewX = cssX * scale;
        float viewY = cssY * scale;
        // Note: getBoundingClientRect() returns viewport-relative coords,
        // so we do NOT subtract scrollY

        Log.d(TAG, "Tapping at view coords: (" + viewX + ", " + viewY +
                "), scale=" + scale);

        // Dispatch touch DOWN + UP to simulate a real tap
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, viewX, viewY, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 100,
                MotionEvent.ACTION_UP, viewX, viewY, 0);
        webView.dispatchTouchEvent(down);
        webView.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();

        // Wait longer for InputConnection to be established (2 seconds), then type
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> typeIntoWebView(text, 0), 2000);
    }

    /**
     * Type text using multiple strategies, trying each until one works.
     * Strategy 1: `input text` shell command (goes through Android input framework)
     * Strategy 2: InputConnection.commitText (IME pipeline)
     * Strategy 3: dispatchKeyEvent (direct key events)
     */
    private void typeIntoWebView(String text, long initialDelay) {
        Handler handler = new Handler(Looper.getMainLooper());

        // Ensure WebView has proper focus before typing
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
        webView.requestFocusFromTouch();

        handler.postDelayed(() -> {
            // Strategy 1: Use Android's input command via shell
            // This goes through the system's InputManager and produces truly trusted events
            try {
                // Replace + with keyevent since 'input text' doesn't handle + well
                // Type each char individually with small delays for reliability
                new Thread(() -> {
                    try {
                        for (int i = 0; i < text.length(); i++) {
                            char c = text.charAt(i);
                            if (c == '+') {
                                // '+' needs special handling — use keyevent KEYCODE_PLUS (81)
                                Runtime.getRuntime().exec(new String[]{"input", "keyevent", "81"}).waitFor();
                            } else {
                                Runtime.getRuntime().exec(new String[]{"input", "text", String.valueOf(c)}).waitFor();
                            }
                            Thread.sleep(50);
                        }
                        Log.d(TAG, "Shell input typing complete");
                        runOnUiThread(() -> {
                            // Check if input actually has text now
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
                        // Fall back to InputConnection approach
                        runOnUiThread(() -> typeViaInputConnection(text));
                    }
                }).start();
            } catch (Exception e) {
                Log.w(TAG, "Shell input approach failed: " + e.getMessage());
                typeViaInputConnection(text);
            }
        }, initialDelay);
    }

    /** Fallback: type via InputConnection.commitText() */
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
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
