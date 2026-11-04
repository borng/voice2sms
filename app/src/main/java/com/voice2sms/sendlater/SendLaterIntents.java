package com.voice2sms.sendlater;

/**
 * Intent action names and extra keys shared by every Send Later component (OD8, OD14, OD15).
 * Constants only. App-internal broadcasts must still call {@code setPackage(getPackageName())};
 * the DEBUG_* actions are for debug builds / adb only.
 *
 * <p>Flow: capture (overlay or WebView bridge) -> {@link #ACTION_CAPTURE} opens the sheet ->
 * persisted -> {@link #ACTION_SCHEDULED} broadcast (undo window) -> {@link #ACTION_STATE_CHANGED}
 * on every status change. Notification buttons use SEND_NOW / SEND_ANYWAY / SNOOZE / CANCEL / EDIT.
 */
public final class SendLaterIntents {

    private SendLaterIntents() {}

    // ---- actions ----
    public static final String ACTION_CAPTURE = "com.voice2sms.sendlater.action.CAPTURE";
    public static final String ACTION_EDIT = "com.voice2sms.sendlater.action.EDIT";
    public static final String ACTION_UPCOMING = "com.voice2sms.sendlater.action.UPCOMING";
    public static final String ACTION_SCHEDULED = "com.voice2sms.sendlater.action.SCHEDULED";
    public static final String ACTION_STATE_CHANGED = "com.voice2sms.sendlater.action.STATE_CHANGED";
    public static final String ACTION_SEND_NOW = "com.voice2sms.sendlater.action.SEND_NOW";
    public static final String ACTION_SEND_ANYWAY = "com.voice2sms.sendlater.action.SEND_ANYWAY";
    public static final String ACTION_SNOOZE = "com.voice2sms.sendlater.action.SNOOZE";
    public static final String ACTION_CANCEL = "com.voice2sms.sendlater.action.CANCEL";
    public static final String ACTION_EXPIRE = "com.voice2sms.sendlater.action.EXPIRE";
    public static final String ACTION_DEBUG_SEED = "com.voice2sms.sendlater.action.DEBUG_SEED";
    public static final String ACTION_DEBUG_DUMP = "com.voice2sms.sendlater.action.DEBUG_DUMP";
    public static final String ACTION_DEBUG_EVALUATE = "com.voice2sms.sendlater.action.DEBUG_EVALUATE";
    public static final String ACTION_DEBUG_FIRE = "com.voice2sms.sendlater.action.DEBUG_FIRE";
    public static final String ACTION_DEBUG_INBOUND = "com.voice2sms.sendlater.action.DEBUG_INBOUND";

    // ---- extras ----
    /** long */
    public static final String EXTRA_MESSAGE_ID = "com.voice2sms.sendlater.extra.MESSAGE_ID";
    /** String: "NATIVE_GV" | "WEBVIEW" (ScheduledMessage.Source names). */
    public static final String EXTRA_SOURCE = "com.voice2sms.sendlater.extra.SOURCE";
    /** String: display name as captured. Never log. */
    public static final String EXTRA_DISPLAY_NAME = "com.voice2sms.sendlater.extra.DISPLAY_NAME";
    /** String: draft text. Never log. */
    public static final String EXTRA_DRAFT = "com.voice2sms.sendlater.extra.DRAFT";
    /** String: resolved recipient number, E.164. */
    public static final String EXTRA_E164 = "com.voice2sms.sendlater.extra.E164";
    /** String[]: candidate numbers when the name maps to several (R4). */
    public static final String EXTRA_CANDIDATES = "com.voice2sms.sendlater.extra.CANDIDATES";
    /** String: GV thread item id, e.g. "t.+1..." (WebView). */
    public static final String EXTRA_THREAD_ITEM_ID = "com.voice2sms.sendlater.extra.THREAD_ITEM_ID";
    /** String: Watermark.Quality name. */
    public static final String EXTRA_WATERMARK_QUALITY = "com.voice2sms.sendlater.extra.WATERMARK_QUALITY";
    /** String */
    public static final String EXTRA_WATERMARK_NEWEST_ID = "com.voice2sms.sendlater.extra.WATERMARK_NEWEST_ID";
    /** long */
    public static final String EXTRA_WATERMARK_NEWEST_TS = "com.voice2sms.sendlater.extra.WATERMARK_NEWEST_TS";
    /** String */
    public static final String EXTRA_WATERMARK_BUBBLE_HASH = "com.voice2sms.sendlater.extra.WATERMARK_BUBBLE_HASH";
    /** long */
    public static final String EXTRA_WATERMARK_CAPTURED_AT = "com.voice2sms.sendlater.extra.WATERMARK_CAPTURED_AT";
    /** long: epoch ms. */
    public static final String EXTRA_SCHEDULED_FOR = "com.voice2sms.sendlater.extra.SCHEDULED_FOR";
    /** String: human label, e.g. "Sat 9:00 AM". */
    public static final String EXTRA_LABEL = "com.voice2sms.sendlater.extra.LABEL";
    /** String: ScheduledMessage.Status name. */
    public static final String EXTRA_STATUS = "com.voice2sms.sendlater.extra.STATUS";
}
