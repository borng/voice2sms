package com.voice2sms.sendlater.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Echo rule (LIVE-F9). Pure JVM.
 *
 * <p>Some numbers (the D17 test number among them) send every text straight back. Such an echo is
 * an INBOUND event whose text hash equals the hash of an OUTBOUND event at most
 * {@link #WINDOW_MS} earlier, with no other inbound event between the two (other outbound events
 * may sit between). An echo is our own text: it is never a reply and never verifies a send.
 *
 * <p>Events are judged in list order (oldest first, as a thread read gives them). Hashes are
 * {@link ThreadEvent#hashOf(String)}; an event without a hash is never an echo nor an echo source.
 * One outbound absorbs at most one echo: a second identical inbound has the first between them.
 */
public final class EchoFilter {

    /** The echo arrives at most this long after our own text (minute-precision times included). */
    public static final long WINDOW_MS = 2 * 60_000L;

    private EchoFilter() {}

    /** True when {@code events.get(index)} is an echo of an earlier outbound event in the list. */
    public static boolean isEcho(List<ThreadEvent> events, int index) {
        if (events == null || index < 0 || index >= events.size()) return false;
        ThreadEvent in = events.get(index);
        if (in == null || in.getDirection() != ThreadEvent.Direction.IN || in.getTextHash() == null) return false;
        for (int j = index - 1; j >= 0; j--) {
            ThreadEvent e = events.get(j);
            if (e == null) continue;
            if (e.getDirection() == ThreadEvent.Direction.IN) return false;   // another inbound between
            if (!in.getTextHash().equals(e.getTextHash())) continue;
            long dt = in.getTimeMs() - e.getTimeMs();
            if (dt >= 0 && dt <= WINDOW_MS) return true;
        }
        return false;
    }

    /** One flag per event: true for an echo. */
    public static boolean[] echoFlags(List<ThreadEvent> events) {
        if (events == null) return new boolean[0];
        boolean[] out = new boolean[events.size()];
        for (int i = 0; i < out.length; i++) out[i] = isEcho(events, i);
        return out;
    }

    /** {@code events} without its echoes (a new list; null in gives an empty list). */
    public static List<ThreadEvent> withoutEchoes(List<ThreadEvent> events) {
        List<ThreadEvent> out = new ArrayList<>();
        if (events == null) return out;
        boolean[] echo = echoFlags(events);
        for (int i = 0; i < echo.length; i++) {
            if (!echo[i]) out.add(events.get(i));
        }
        return out;
    }
}
