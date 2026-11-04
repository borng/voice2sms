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
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
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
 * already move with the IME). Framework widgets only (the service context has no app theme).
 * Main thread only.
 */
final class OverlayWindows {

    private static final String TAG = "Voice2SMS-SLV";

    private static final int ACCENT = SendLaterAccent.ACCENT;
    private static final int ON_ACCENT = SendLaterAccent.ON_ACCENT;

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Runnable onTabTap;
    private final Runnable onChipTap;

    private View tab;
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
        int gap = dp(6);
        int tabW = dp(28);
        int tabH = dp(56);
        if (showTab) {
            if (tab == null) tab = buildTab();
            WindowManager.LayoutParams lp = params(tabW, tabH);
            lp.x = dm.widthPixels - tabW;
            // Bottom just above the compose row, so Send / attach / camera stay uncovered (R19).
            lp.y = Math.max(0, anchor.top - tabH - gap);
            place(tab, lp);
        } else {
            remove(tab);
        }
        if (chipLabel != null) {
            if (chip == null) chip = buildChip();
            chip.setText(chipLabel);
            chip.setContentDescription(chipLabel);
            int maxW = Math.max(dp(80), anchor.width() - tabW - 3 * gap);
            chip.setMaxWidth(maxW);
            chip.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            lp.x = anchor.left + gap * 2;
            lp.y = Math.max(0, anchor.top - chip.getMeasuredHeight() - gap);
            place(chip, lp);
        } else {
            remove(chip);
        }
    }

    void hideTabAndChip() {
        remove(tab);
        remove(chip);
    }

    void hideAll() {
        hideTabAndChip();
        hideToast();
    }

    private View buildTab() {
        View v = new ClockTab(service);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        float r = dp(14);
        bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        v.setBackground(bg);
        v.setContentDescription(service.getString(R.string.sendlater_overlay_tab));
        v.setClickable(true);
        v.setOnClickListener(x -> onTabTap.run());
        return v;
    }

    private TextView buildChip() {
        TextView t = pillText();
        t.setClickable(true);
        t.setOnClickListener(x -> onChipTap.run());
        return t;
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
            toast.setPadding(dp(16), dp(10), dp(8), dp(10));
            toastText = new TextView(service);
            toastText.setTextColor(Color.WHITE);
            toastText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            toast.addView(toastText);
            toastUndo = new TextView(service);
            toastUndo.setText(R.string.sendlater_overlay_undo);
            toastUndo.setTextColor(ACCENT);
            toastUndo.setTypeface(Typeface.DEFAULT_BOLD);
            toastUndo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            toastUndo.setPadding(dp(16), dp(4), dp(8), dp(4));
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
        int margin = dp(16);
        int maxW = dm.widthPixels - 2 * margin;
        toastText.setMaxWidth(maxW - dp(96));
        toast.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = toast.getMeasuredHeight();
        WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        lp.x = margin;
        Rect a = lastAnchor;
        // Above the chip slot over the compose row; lower third of the screen when there is no anchor.
        lp.y = a != null ? Math.max(0, a.top - h - dp(56)) : dm.heightPixels * 2 / 3;
        place(toast, lp);
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
