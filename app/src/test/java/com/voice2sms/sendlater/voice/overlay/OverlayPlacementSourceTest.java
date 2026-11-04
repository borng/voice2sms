package com.voice2sms.sendlater.voice.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * dl-overlay source guards (UI review M1, M2 native half, M9): OverlayWindows places everything
 * through OverlayGeometry from the Voice anchor, the toast is announced, the Undo window honours
 * the accessibility timeout, and the tab is gated and hidden before the worker hop.
 */
public class OverlayPlacementSourceTest {

    private static final Path OVERLAY = Paths.get("src/main/java/com/voice2sms/sendlater/voice/overlay");

    private static String code(String file) throws IOException {
        String s = new String(Files.readAllBytes(OVERLAY.resolve(file)), StandardCharsets.UTF_8);
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static String body(String src, String signature) {
        int i = src.indexOf(signature);
        assertTrue("missing " + signature, i >= 0);
        int open = src.indexOf('{', i);
        int depth = 0;
        for (int j = open; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, j + 1);
        }
        throw new AssertionError("unbalanced " + signature);
    }

    @Test public void placementComesFromTheAnchorNotTheDisplayWidth() throws IOException {
        String w = code("OverlayWindows.java");
        assertTrue("tab via geometry", w.contains("geo.tab(anchor.left, anchor.top, anchor.right"));
        assertTrue("chip via geometry", w.contains("geo.chip(anchor.left"));
        assertTrue("toast via geometry from the anchor", w.contains("geo.toastX(a.left, a.right, true)"));
        assertFalse("no tab x from the full display", w.matches("(?s).*widthPixels\\s*-\\s*tab.*"));
        assertFalse("no toast x from a fixed margin", w.matches("(?s).*lp\\.x\\s*=\\s*margin\\s*;.*"));
    }

    @Test public void touchTargetsAreAtLeast48dp() throws IOException {
        String w = code("OverlayWindows.java");
        assertTrue("tab touch window", w.contains("params(geo.tabTouchWidth(), geo.tabHeight())"));
        assertTrue("chip min height", w.contains("setMinHeight(geo.chipMinHeight())"));
        assertTrue("undo min height", w.contains("toastUndo.setMinHeight(geo.undoMinSize())"));
        assertTrue("undo min width", w.contains("toastUndo.setMinWidth(geo.undoMinSize())"));
    }

    @Test public void confirmationIsAnnouncedAndUndoIsAButton() throws IOException {
        String w = code("OverlayWindows.java");
        assertTrue("live region", w.contains("setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)"));
        assertTrue("announce a new toast", w.contains("announceForAccessibility("));
        assertTrue("undo description", w.contains("R.string.sendlater_overlay_undo_description"));
        assertTrue("button role", w.contains("asButton(toastUndo)") && w.contains("Button.class.getName()"));
    }

    @Test public void undoWindowUsesTheRecommendedTimeout() throws IOException {
        String w = code("OverlayWindows.java");
        assertTrue(w.contains("getRecommendedTimeoutMillis("));
        assertTrue(w.contains("Build.VERSION.SDK_INT >= 29"));
        String c = code("VoiceOverlayController.java");
        String toast = body(c, "private void showToast(String text, final PendingScheduled undoable)");
        assertTrue("confirmation timeout", toast.contains("windows.confirmTimeoutMs(CONFIRM_MS"));
        assertFalse("no fixed Undo window", toast.matches("(?s).*postDelayed\\(hideToastRunnable,\\s*CONFIRM_MS\\).*"));
    }

    @Test public void tabTapIsGatedAndHiddenBeforeTheWorkerHop() throws IOException {
        String c = code("VoiceOverlayController.java");
        String tap = body(c, "private void onTabTap()");
        int gate = tap.indexOf("tabGate.tryEnter(");
        int hide = tap.indexOf("windows.disableTab()");
        int hop = tap.indexOf("WORKER.execute(");
        assertTrue("gate", gate >= 0);
        assertTrue("hide", hide > gate);
        assertTrue("both before the worker hop", hop > hide);
        String refresh = body(c, "private void refresh()");
        assertTrue("refresh keeps the tab hidden while a tap is in flight", refresh.contains("tabGate.isBlocking("));
        String open = body(c, "private void openSheet(");
        assertTrue("settled after a start", open.contains("tabGate.settle("));
        assertTrue("released when dropped or not started", open.contains("releaseTab()"));
    }
}
