package com.voice2sms.sendlater.voice.overlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.voice2sms.R;
import com.voice2sms.sendlater.web.SendLaterAccent;

import java.util.HashSet;
import java.util.Set;

/**
 * The native Voice overlay windows: edge tab, chip and the scheduled/undo confirmation (R1, R5,
 * R17, R19, OD9). Accessibility overlays added through the service's WindowManager, never
 * focusable, positioned in screen coordinates from the input_message_container bounds (they
 * already move with the IME). Placement comes from that anchor (the Voice window), not the full
 * display, so split-screen, the Fold inner display and freeform keep them over Voice (UI M1).
 * Every touch target is at least 48dp and the confirmation is announced by TalkBack (UI M9).
 * Framework widgets only (the service context has no app theme). Main thread only.
 */
final class OverlayWindows {

    private static final String TAG = "Voice2SMS-SLV";

    private static final int ACCENT = SendLaterAccent.ACCENT;
    private static final int ON_ACCENT = SendLaterAccent.ON_ACCENT;

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Runnable onTabTap;
    private final Runnable onChipTap;

    private final OverlayGeometry geo;
    private FrameLayout tab;
    private TextView chip;
    private LinearLayout toast;
    private TextView toastText;
    private TextView toastUndo;
    private Runnable toastUndoAction;
    private Rect lastAnchor;
    /** Views currently added to the WindowManager. */
    private final Set<View> added = new HashSet<>();

    OverlayWindows(AccessibilityService service, Runnable onTabTap, Runnable onChipTap) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.onTabTap = onTabTap;
        this.onChipTap = onChipTap;
        this.geo = new OverlayGeometry(service.getResources().getDisplayMetrics().density);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                service.getResources().getDisplayMetrics()));
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics screen() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    private static WindowManager.LayoutParams params(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private boolean place(View v, WindowManager.LayoutParams lp) {
        try {
            if (!added.contains(v)) {
                wm.addView(v, lp);
                added.add(v);
            } else if (v.getLayoutParams() instanceof WindowManager.LayoutParams
                    && same((WindowManager.LayoutParams) v.getLayoutParams(), lp)) {
                return true;
            } else {
                wm.updateViewLayout(v, lp);
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "overlay window refused: " + e.getClass());
            return false;
        }
    }

    private static boolean same(WindowManager.LayoutParams a, WindowManager.LayoutParams b) {
        return a.x == b.x && a.y == b.y && a.width == b.width && a.height == b.height;
    }

    private void remove(View v) {
        if (v == null || !added.remove(v)) return;
        try {
            wm.removeViewImmediate(v);
        } catch (RuntimeException e) {
            Log.w(TAG, "overlay window removal failed: " + e.getClass());
        }
    }

    boolean isShowing() {
        return !added.isEmpty();
    }

    // ------------------------------------------------------------------ tab + chip

    /** Shows or hides the tab and the chip ({@code chipLabel} null = no chip) at {@code anchor}. */
    void update(boolean showTab, String chipLabel, Rect anchor) {
        if (anchor == null) {
            hideTabAndChip();
            return;
        }
        lastAnchor = new Rect(anchor);
        DisplayMetrics dm = screen();
        if (showTab) {
            if (tab == null) tab = buildTab();
            tab.setEnabled(true);
            // 48dp touch window, right edge on the Voice window's right edge (UI M1, M9); the
            // visible tab stays 28dp. Bottom above the compose row: Send stays uncovered (R19).
            int[] xy = geo.tab(anchor.left, anchor.top, anchor.right, anchor.bottom, dm.widthPixels);
            WindowManager.LayoutParams lp = params(geo.tabTouchWidth(), geo.tabHeight());
            lp.x = xy[0];
            lp.y = xy[1];
            place(tab, lp);
        } else {
            remove(tab);
        }
        if (chipLabel != null) {
            if (chip == null) chip = buildChip();
            chip.setText(chipLabel);
            chip.setContentDescription(chipLabel);
            int maxW = geo.chipMaxWidth(anchor.left, anchor.right);
            chip.setMaxWidth(maxW);
            chip.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            int[] xy = geo.chip(anchor.left, anchor.top, anchor.right, chip.getMeasuredHeight());
            lp.x = xy[0];
            lp.y = xy[1];
            place(chip, lp);
        } else {
            remove(chip);
        }
    }

    void hideTabAndChip() {
        remove(tab);
        remove(chip);
    }

    /**
     * First tab tap (UI M2): disable and remove the tab synchronously, before any worker hop, so a
     * second tap in the same gesture burst cannot reach it.
     */
    void disableTab() {
        if (tab != null) tab.setEnabled(false);
        remove(tab);
    }

    void hideAll() {
        hideTabAndChip();
        hideToast();
    }

    /** A 48dp transparent touch window holding the 28dp visible tab at its right edge (UI M9). */
    private FrameLayout buildTab() {
        FrameLayout touch = new FrameLayout(service);
        View v = new ClockTab(service);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        float r = dp(14);
        bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        v.setBackground(bg);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        touch.addView(v, new FrameLayout.LayoutParams(geo.tabVisibleWidth(),
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END));
        touch.setContentDescription(service.getString(R.string.sendlater_overlay_tab));
        touch.setClickable(true);
        touch.setOnClickListener(x -> {
            if (!x.isEnabled()) return;
            onTabTap.run();
        });
        asButton(touch);
        return touch;
    }

    private TextView buildChip() {
        TextView t = pillText();
        // 48dp touch height; the visible pill stays about 32dp (transparent inset) (UI M9).
        int inset = (geo.chipMinHeight() - dp(32)) / 2;
        t.setMinHeight(geo.chipMinHeight());
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackground(new InsetDrawable(t.getBackground(), 0, inset, 0, inset));
        // An InsetDrawable background sets the view padding to its insets: restore the pill padding.
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setClickable(true);
        t.setOnClickListener(x -> onChipTap.run());
        asButton(t);
        return t;
    }

    /** TalkBack reads the view as a button. */
    private static void asButton(View v) {
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
    }

    private TextView pillText() {
        TextView t = new TextView(service);
        t.setTextColor(ON_ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(16));
        t.setBackground(bg);
        return t;
    }

    // ------------------------------------------------------------------ confirmation

    /** Shows {@code text} (with an Undo action when {@code undo} is non-null) above the compose row. */
    void showToast(String text, Runnable undo) {
        if (toast == null) {
            toast = new LinearLayout(service);
            toast.setOrientation(LinearLayout.HORIZONTAL);
            toast.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ON_ACCENT);
            bg.setCornerRadius(dp(20));
            toast.setBackground(bg);
            toast.setPadding(dp(16), dp(2), dp(4), dp(2));
            toast.setMinimumHeight(geo.undoMinSize());
            toastText = new TextView(service);
            toastText.setTextColor(Color.WHITE);
            toastText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            // UI M9: TalkBack announces "Scheduled for…" and later the Undo result.
            toastText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            toast.addView(toastText);
            toastUndo = new TextView(service);
            toastUndo.setText(R.string.sendlater_overlay_undo);
            toastUndo.setContentDescription(service.getString(R.string.sendlater_overlay_undo_description));
            toastUndo.setTextColor(ACCENT);
            toastUndo.setTypeface(Typeface.DEFAULT_BOLD);
            toastUndo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            toastUndo.setGravity(Gravity.CENTER);
            toastUndo.setMinHeight(geo.undoMinSize());
            toastUndo.setMinWidth(geo.undoMinSize());
            toastUndo.setPadding(dp(16), 0, dp(8), 0);
            toastUndo.setClickable(true);
            asButton(toastUndo);
            toastUndo.setOnClickListener(x -> {
                Runnable r = toastUndoAction;
                toastUndoAction = null;
                hideToast();
                if (r != null) r.run();
            });
            toast.addView(toastUndo);
        }
        toastText.setText(text);
        toastUndoAction = undo;
        toastUndo.setVisibility(undo != null ? View.VISIBLE : View.GONE);
        DisplayMetrics dm = screen();
        Rect a = lastAnchor;
        // Inset from the Voice pane (UI M1); the whole display only when there is no anchor.
        int[] xw = a != null ? geo.toastX(a.left, a.right, true) : geo.toastX(0, dm.widthPixels, false);
        int maxW = xw[1];
        toastText.setMaxWidth(Math.max(dp(48), maxW - dp(96)));
        toast.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = toast.getMeasuredHeight();
        WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        lp.x = xw[0];
        // Above the chip slot over the compose row; lower third of the screen when there is no anchor.
        lp.y = a != null ? Math.max(0, a.top - h - dp(56)) : dm.heightPixels * 2 / 3;
        boolean fresh = !added.contains(toast);
        if (place(toast, lp) && fresh) {
            // A new window's text is not a live-region change: announce it once. Later text
            // changes (the Undo result) are announced by the live region.
            toast.announceForAccessibility(text);
        }
    }

    /**
     * How long the confirmation (with Undo) stays up: {@code defaultMs}, or the user's
     * accessibility timeout when it is longer (API 29+, UI M9).
     */
    long confirmTimeoutMs(long defaultMs, boolean withControls) {
        long recommended = defaultMs;
        if (Build.VERSION.SDK_INT >= 29) {
            AccessibilityManager am = (AccessibilityManager) service.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                int flags = AccessibilityManager.FLAG_CONTENT_TEXT
                        | (withControls ? AccessibilityManager.FLAG_CONTENT_CONTROLS : 0);
                recommended = am.getRecommendedTimeoutMillis((int) Math.min(Integer.MAX_VALUE, defaultMs), flags);
            }
        }
        return OverlayGeometry.confirmMs(Build.VERSION.SDK_INT, recommended, defaultMs);
    }

    void hideToast() {
        toastUndoAction = null;
        remove(toast);
    }

    /** Light-blue edge tab with a small clock glyph in the on-accent colour. */
    private static final class ClockTab extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ClockTab(Context c) {
            super(c);
            paint.setColor(ON_ACCENT);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float r = Math.min(w, h) * 0.3f;
            float cx = w * 0.55f;
            float cy = h / 2f;
            paint.setStrokeWidth(r * 0.22f);
            canvas.drawCircle(cx, cy, r, paint);
            canvas.drawLine(cx, cy, cx, cy - r * 0.6f, paint);
            canvas.drawLine(cx, cy, cx + r * 0.45f, cy, paint);
        }
    }
}
