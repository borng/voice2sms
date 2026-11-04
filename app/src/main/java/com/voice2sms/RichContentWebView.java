package com.voice2sms;

import android.content.Context;
import android.text.InputType;
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
 *
 * R44 (D29): Enter in the GV message box inserts a newline and never sends. When the
 * focused field is multi-line, or asks for a Send action, the keyboard is told there is
 * no Enter action (IME_ACTION_NONE + IME_FLAG_NO_ENTER_ACTION), so GBoard shows a
 * return key instead of a Send key. inject.js v2sInstallEnterNewline() turns that
 * Enter into a newline in the composer.
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
        outAttrs.imeOptions = imeOptionsWithoutEnterAction(outAttrs.imeOptions, outAttrs.inputType);
        EditorInfoCompat.setContentMimeTypes(outAttrs, IMAGE_MIME_TYPES);
        return InputConnectionCompat.createWrapper(this, ic, outAttrs);
    }

    /**
     * imeOptions with the Enter action removed (R44): for a multi-line text field, or any
     * field whose action is Send, the action becomes IME_ACTION_NONE and
     * IME_FLAG_NO_ENTER_ACTION is set; every other flag is kept. Other fields (search,
     * next, go on single-line inputs) are returned unchanged.
     */
    static int imeOptionsWithoutEnterAction(int imeOptions, int inputType) {
        boolean multiLine = (inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        boolean sendAction = (imeOptions & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEND;
        if (!multiLine && !sendAction) return imeOptions;
        return (imeOptions & ~EditorInfo.IME_MASK_ACTION)
                | EditorInfo.IME_ACTION_NONE
                | EditorInfo.IME_FLAG_NO_ENTER_ACTION;
    }
}
