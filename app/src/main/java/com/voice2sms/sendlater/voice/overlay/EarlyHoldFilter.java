package com.voice2sms.sendlater.voice.overlay;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import java.util.ArrayList;
import java.util.List;

/**
 * Which visible inbound bubbles are new since a pending message's watermark (R33 early hold).
 *
 * <p>Bubble times have minute precision, so the bubble the watermark was taken from parses to the
 * same time as a reply in the same minute. When the watermark bubble is visible (same time and
 * text hash), only rows below it count. Otherwise a row counts when it is strictly newer than the
 * watermark time. A DEVICE_ONLY watermark (no bubbles were visible at capture) counts inbound rows
 * from the minute of capture. The engine applies its own classification afterwards. Pure JVM.
 */
public final class EarlyHoldFilter {

    private static final long MINUTE_MS = 60_000L;

    private EarlyHoldFilter() {}

    /**
     * @param rows      parsed visible rows in screen order (oldest first); null entries are rows that
     *                  are not bubbles
     * @param wm        the message's reply watermark (null = DEVICE_ONLY at {@code createdAt})
     * @param createdAt the message's creation time
     */
    public static List<ThreadEvent> newInbound(List<ThreadEvent> rows, Watermark wm, long createdAt) {
        List<ThreadEvent> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) return out;
        boolean timed = wm != null && wm.getQuality() != Watermark.Quality.DEVICE_ONLY && wm.getNewestTs() != null;
        if (timed) {
            long ts = wm.getNewestTs();
            int anchor = -1;
            for (int i = 0; i < rows.size(); i++) {
                ThreadEvent e = rows.get(i);
                if (e != null && e.getTimeMs() == ts && wm.getBubbleHash() != null
                        && wm.getBubbleHash().equals(e.getTextHash())) {
                    anchor = i;
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                ThreadEvent e = rows.get(i);
                if (e == null || !e.isInbound()) continue;
                if (anchor >= 0 ? i > anchor : e.getTimeMs() > ts) out.add(e);
            }
            return out;
        }
        long captured = wm != null ? wm.getCapturedAtDevice() : createdAt;
        long floor = captured - Math.floorMod(captured, MINUTE_MS);
        for (ThreadEvent e : rows) {
            if (e != null && e.isInbound() && e.getTimeMs() >= floor) out.add(e);
        }
        return out;
    }
}
