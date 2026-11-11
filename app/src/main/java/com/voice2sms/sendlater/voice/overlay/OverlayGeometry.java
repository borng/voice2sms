package com.voice2sms.sendlater.voice.overlay;

/**
 * Pure placement and sizing for the native Voice overlay windows (UI review M1, M9; R1, R19).
 *
 * <p>Everything is placed from the Voice compose-row anchor ({@code input_message_container}
 * bounds in screen coordinates), never from the full display width, so split-screen, the Fold
 * inner display and freeform windows keep the tab, chip and toast over Google Voice. The display
 * width only clamps the result on screen. Touch targets are at least 48dp; the edge tab stays
 * visually 28dp wide inside a 48dp touch window. Pure JVM (no Android imports).
 */
final class OverlayGeometry {

    /** Minimum touch target (Android accessibility guideline). */
    static final int MIN_TOUCH_DP = 48;
    static final int TAB_VISIBLE_DP = 28;
    static final int TAB_HEIGHT_DP = 56;
    static final int GAP_DP = 6;
    static final int TOAST_MARGIN_DP = 16;
    static final int CHIP_MIN_WIDTH_DP = 80;

    private final float density;

    /** @param density px per dp ({@code DisplayMetrics.density}). */
    OverlayGeometry(float density) {
        this.density = density <= 0 ? 1f : density;
    }

    int px(int dp) {
        return Math.round(dp * density);
    }

    int gap() { return px(GAP_DP); }

    int tabVisibleWidth() { return px(TAB_VISIBLE_DP); }

    int tabTouchWidth() { return px(MIN_TOUCH_DP); }

    int tabHeight() { return px(Math.max(TAB_HEIGHT_DP, MIN_TOUCH_DP)); }

    int chipMinHeight() { return px(MIN_TOUCH_DP); }

    int undoMinSize() { return px(MIN_TOUCH_DP); }

    /**
     * Tab window origin {x, y}: right edge on the anchor's right edge (the Voice window edge),
     * bottom just above the compose row so Send / attach / camera stay uncovered (R19).
     */
    int[] tab(int anchorLeft, int anchorTop, int anchorRight, int anchorBottom, int displayWidth) {
        int w = tabTouchWidth();
        int x = anchorRight - w;
        if (displayWidth > 0) x = Math.min(x, displayWidth - w);
        x = Math.max(0, x);
        int y = Math.max(0, anchorTop - tabHeight() - gap());
        return new int[] {x, y};
    }

    /** Chip window origin {x, y} for a chip window {@code chipHeight} px tall. */
    int[] chip(int anchorLeft, int anchorTop, int anchorRight, int chipHeight) {
        int x = Math.max(0, anchorLeft + 2 * gap());
        int y = Math.max(0, anchorTop - chipHeight - gap());
        return new int[] {x, y};
    }

    /** Chip max width: the Voice pane minus the tab touch area and gaps. */
    int chipMaxWidth(int anchorLeft, int anchorRight) {
        return Math.max(px(CHIP_MIN_WIDTH_DP), (anchorRight - anchorLeft) - tabTouchWidth() - 3 * gap());
    }

    /**
     * Toast {x, maxWidth}: inset from the Voice pane ({@code hasAnchor}) or, without an anchor,
     * from {@code [left, right)} taken as the display.
     */
    int[] toastX(int left, int right, boolean hasAnchor) {
        int w = Math.max(1, right - left);
        int margin = Math.min(px(TOAST_MARGIN_DP), w / 8);
        int maxW = Math.max(1, w - 2 * margin);
        return new int[] {Math.max(0, left + margin), maxW};
    }

    /**
     * How long the confirmation and Undo stay up: the system's recommended timeout
     * ({@code AccessibilityManager.getRecommendedTimeoutMillis}, API 29+) when it is longer
     * than {@code defaultMs}, else {@code defaultMs}.
     */
    static long confirmMs(int sdkInt, long recommendedMs, long defaultMs) {
        if (sdkInt < 29) return defaultMs;
        return Math.max(defaultMs, recommendedMs);
    }

    /**
     * Edge-tab tap gate (UI M2, native half): the first tap enters synchronously, any further tap
     * is dropped until the sheet start settles (plus {@link #SETTLE_MS}) or the tap is released
     * (dropped / start failed). Expires on its own after {@link #MAX_IN_FLIGHT_MS}. Main thread.
     */
    static final class TapGate {
        static final long SETTLE_MS = 1_500L;
        static final long MAX_IN_FLIGHT_MS = 5_000L;

        private long until;

        boolean tryEnter(long nowElapsed) {
            if (nowElapsed < until) return false;
            until = nowElapsed + MAX_IN_FLIGHT_MS;
            return true;
        }

        void settle(long nowElapsed) {
            until = nowElapsed + SETTLE_MS;
        }

        void release() {
            until = 0L;
        }

        boolean isBlocking(long nowElapsed) {
            return nowElapsed < until;
        }
    }
}
