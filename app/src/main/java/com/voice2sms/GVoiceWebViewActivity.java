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
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.preference.PreferenceManager;

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
                // Reset injection flag when navigating to a new page
                if (url.contains("voice.google.com/u/0/messages")) {
                    injected = false;
                }
                // Inject fingerprint masking as early as possible
                injectFingerprintMask();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url.contains("voice.google.com") && !injected && recipient != null) {
                    injectComposer();
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
                            if (authUrl != null && authUrl.startsWith("http")) {
                                // Load the token exchange URL — this sets session cookies
                                Log.d(TAG, "Loading auth token exchange URL");
                                webView.loadUrl(authUrl);
                            } else {
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
            // Append the function call
            String escapedRecipient = recipient.replace("\\", "\\\\").replace("'", "\\'");
            String escapedBody = (body != null) ? body.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") : "";
            js += "\nvoice2sms('" + escapedRecipient + "', '" + escapedBody + "', "
                    + autoSend + ", " + autoSendDelay + ");";

            webView.evaluateJavascript(js, null);
            Log.d(TAG, "Injected composer JS for recipient: " + recipient);
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject JS", e);
        }
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
