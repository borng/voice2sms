package com.voice2sms;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebView;

import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;

/**
 * WebView subclass that advertises image MIME type support to the keyboard.
 *
 * Stock WebView does not set EditorInfo.contentMimeTypes, so GBoard hides its
 * sticker/GIF panel when a WebView text field is focused. This subclass
 * overrides onCreateInputConnection() to advertise image types, causing GBoard
 * to show the sticker panel and deliver images via commitContent().
 *
 * The actual image handling is done by an OnReceiveContentListener set on this
 * view in GVoiceWebViewActivity.
 */
public class RichContentWebView extends WebView {

    static final String[] IMAGE_MIME_TYPES = {
        "image/png", "image/gif", "image/webp", "image/jpeg"
    };

    public RichContentWebView(Context context) {
        super(context);
    }

    public RichContentWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RichContentWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection ic = super.onCreateInputConnection(outAttrs);
        if (ic == null) return null;
        EditorInfoCompat.setContentMimeTypes(outAttrs, IMAGE_MIME_TYPES);
        return InputConnectionCompat.createWrapper(this, ic, outAttrs);
    }
}
