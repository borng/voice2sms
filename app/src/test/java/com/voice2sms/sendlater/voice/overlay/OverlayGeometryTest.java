package com.voice2sms.sendlater.voice.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** dl-overlay (UI M1, M9): placement from the Voice anchor, 48dp targets, Undo timeout. */
public class OverlayGeometryTest {

    private static final int DPX = 3; // px per dp (xxhdpi)
    private static final OverlayGeometry G = new OverlayGeometry(DPX);

    @Test public void tabTouchAreaIsAtLeast48dpWhileTheVisibleTabStaysSlim() {
        assertTrue(G.tabTouchWidth() >= 48 * DPX);
        assertTrue(G.tabHeight() >= 48 * DPX);
        assertEquals(28 * DPX, G.tabVisibleWidth());
        assertTrue(G.chipMinHeight() >= 48 * DPX);
        assertTrue(G.undoMinSize() >= 48 * DPX);
    }

    @Test public void fullScreenVoicePutsTheTabOnTheDisplayEdge() {
        int[] tab = G.tab(0, 1500, 1080, 1700, 1080);
        assertEquals(1080 - G.tabTouchWidth(), tab[0]);
        assertEquals(1500 - G.tabHeight() - G.gap(), tab[1]);
    }

    @Test public void splitScreenLeftPaneKeepsTheTabOverVoice() {
        // Voice in the left half of a 2208 px wide inner Fold display.
        int[] tab = G.tab(0, 1500, 1104, 1700, 2208);
        assertEquals(1104 - G.tabTouchWidth(), tab[0]);
        assertTrue("tab right edge inside the Voice pane", tab[0] + G.tabTouchWidth() <= 1104);
    }

    @Test public void freeformWindowPlacesTabAndChipFromTheAnchor() {
        int left = 400, right = 1300;
        int[] tab = G.tab(left, 900, right, 1000, 2208);
        assertEquals(right - G.tabTouchWidth(), tab[0]);
        int[] chip = G.chip(left, 900, right, 120);
        assertEquals(left + 2 * G.gap(), chip[0]);
        assertEquals(900 - 120 - G.gap(), chip[1]);
    }

    @Test public void tabNeverLeavesTheDisplayOrGoesAboveTheTop() {
        int[] tab = G.tab(0, 10, 3000, 100, 1080);
        assertEquals(1080 - G.tabTouchWidth(), tab[0]);
        assertEquals(0, tab[1]);
        int[] narrow = G.tab(0, 500, 20, 600, 1080);
        assertEquals(0, narrow[0]);
    }

    @Test public void chipMaxWidthLeavesRoomForTheTabTouchArea() {
        int w = G.chipMaxWidth(400, 1300);
        assertEquals(900 - G.tabTouchWidth() - 3 * G.gap(), w);
        assertTrue(G.chipMaxWidth(0, 50) >= 80 * DPX);
    }

    @Test public void toastIsInsetFromTheVoicePaneNotTheDisplay() {
        int[] t = G.toastX(1104, 2208, true);
        assertEquals(1104 + 16 * DPX, t[0]);
        assertEquals(1104 - 32 * DPX, t[1]);
        assertTrue(t[0] + t[1] <= 2208);
    }

    @Test public void toastWithoutAnchorUsesTheDisplay() {
        int[] t = G.toastX(0, 1080, false);
        assertEquals(16 * DPX, t[0]);
        assertEquals(1080 - 32 * DPX, t[1]);
    }

    @Test public void toastKeepsAUsableWidthInATinyPane() {
        int[] t = G.toastX(100, 220, true);
        assertTrue("starts inside the pane", t[0] >= 100);
        assertTrue("ends inside the pane", t[0] + t[1] <= 220);
        assertTrue(t[1] > 0);
    }

    @Test public void undoWindowHonoursTheRecommendedTimeoutOnApi29Plus() {
        assertEquals(10_000L, OverlayGeometry.confirmMs(28, 60_000L, 10_000L));
        assertEquals(60_000L, OverlayGeometry.confirmMs(29, 60_000L, 10_000L));
        assertEquals(10_000L, OverlayGeometry.confirmMs(34, 10_000L, 10_000L));
        assertEquals("never shorter than the default", 10_000L, OverlayGeometry.confirmMs(34, 2_000L, 10_000L));
    }

    @Test public void tapGateLetsOneTapThroughUntilSettledOrReleased() {
        OverlayGeometry.TapGate g = new OverlayGeometry.TapGate();
        assertTrue(g.tryEnter(1000));
        assertFalse("double tap dropped", g.tryEnter(1050));
        assertTrue(g.isBlocking(1050));
        g.settle(1200);
        assertFalse(g.tryEnter(1200 + OverlayGeometry.TapGate.SETTLE_MS - 1));
        assertTrue(g.tryEnter(1200 + OverlayGeometry.TapGate.SETTLE_MS));
        g.release();
        assertFalse(g.isBlocking(1200 + OverlayGeometry.TapGate.SETTLE_MS));
        assertTrue(g.tryEnter(5000));
    }

    @Test public void tapGateExpiresIfTheTapNeverSettles() {
        OverlayGeometry.TapGate g = new OverlayGeometry.TapGate();
        assertTrue(g.tryEnter(0));
        assertTrue(g.isBlocking(OverlayGeometry.TapGate.MAX_IN_FLIGHT_MS - 1));
        assertFalse(g.isBlocking(OverlayGeometry.TapGate.MAX_IN_FLIGHT_MS));
    }
}
