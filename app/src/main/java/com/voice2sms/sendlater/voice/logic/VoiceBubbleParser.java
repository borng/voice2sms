package com.voice2sms.sendlater.voice.logic;

import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R3: native Voice message bubbles -> {@link ThreadEvent}s and the tap-time bubble watermark.
 * Content-description shapes (recon 2026-11-04):
 * <pre>
 *   "&lt;text&gt; from me, 6:46 PM"               outbound, today
 *   "&lt;text&gt; from &lt;Name&gt;, 6:45 PM"           inbound, today
 *   "&lt;text&gt; from me, Oct 28, 2:43 PM"       dated (older) form, either direction
 * </pre>
 * An optional year ("Oct 28, 2025, 2:43 PM") and 24-hour times are accepted too. Anything else
 * returns null. Parsed events never log their text.
 */
public final class VoiceBubbleParser {

    private static final String SP = "[\\s\\u00a0\\u202f\\u2007]";
    private static final Pattern BUBBLE = Pattern.compile(
            "^(?:(.*)" + SP + "+)?from" + SP + "+(.+?)," + SP + "*"
                    + "(?:([A-Za-z]{3,9})\\.?" + SP + "+(\\d{1,2})(?:," + SP + "*(\\d{4}))?," + SP + "*)?"
                    + "(\\d{1,2}):(\\d{2})(?:" + SP + "*([AaPp])\\.?[Mm]\\.?)?" + SP + "*$",
            Pattern.DOTALL);

    private static final String[] MONTHS = {
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    /** A dated bubble more than this far in the future belongs to the previous year. */
    private static final long FUTURE_SLACK_MS = 24L * 60 * 60 * 1000;

    private VoiceBubbleParser() {}

    /**
     * @param contentDescription a bubble's content description
     * @param nowMs              the device clock at capture; supplies today's date and the year
     * @param zone               the device time zone the times are shown in
     * @return the event (kind SMS, textHash = {@link ThreadEvent#hashOf}), or null if not a bubble
     */
    public static ThreadEvent parse(String contentDescription, long nowMs, TimeZone zone) {
        if (contentDescription == null || zone == null) return null;
        Matcher m = BUBBLE.matcher(contentDescription.trim());
        if (!m.matches()) return null;

        String text = m.group(1) == null ? "" : m.group(1);
        String who = m.group(2).trim();
        ThreadEvent.Direction dir = "me".equals(who) ? ThreadEvent.Direction.OUT : ThreadEvent.Direction.IN;

        int hour = Integer.parseInt(m.group(6));
        int minute = Integer.parseInt(m.group(7));
        if (minute > 59) return null;
        String ampm = m.group(8);
        if (ampm != null) {
            if (hour < 1 || hour > 12) return null;
            boolean pm = ampm.equalsIgnoreCase("p");
            hour = (hour % 12) + (pm ? 12 : 0);
        } else if (hour > 23) {
            return null;
        }

        Calendar now = Calendar.getInstance(zone, Locale.US);
        now.setTimeInMillis(nowMs);
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.clear();
        if (m.group(3) == null) {
            c.set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH), hour, minute, 0);
        } else {
            int month = monthIndex(m.group(3));
            int day = Integer.parseInt(m.group(4));
            if (month < 0 || day < 1 || day > 31) return null;
            boolean explicitYear = m.group(5) != null;
            int year = explicitYear ? Integer.parseInt(m.group(5)) : now.get(Calendar.YEAR);
            c.setLenient(false);
            c.set(year, month, day, hour, minute, 0);
            try {
                c.getTimeInMillis();
            } catch (IllegalArgumentException invalidDate) {
                return null;
            }
            if (!explicitYear && c.getTimeInMillis() > nowMs + FUTURE_SLACK_MS) {
                c.set(Calendar.YEAR, year - 1);
            }
        }
        c.set(Calendar.MILLISECOND, 0);
        return new ThreadEvent(dir, ThreadEvent.Kind.SMS, c.getTimeInMillis(), ThreadEvent.hashOf(text), text);
    }

    /** The newest parsable bubble (latest time; on a tie the later one in on-screen order), or null. */
    public static ThreadEvent newest(List<String> descs, long nowMs, TimeZone zone) {
        if (descs == null) return null;
        ThreadEvent best = null;
        for (String d : descs) {
            ThreadEvent e = parse(d, nowMs, zone);
            if (e != null && (best == null || e.getTimeMs() >= best.getTimeMs())) best = e;
        }
        return best;
    }

    /**
     * R3 watermark from the on-screen bubble descriptions: the newest bubble's time with quality
     * TIMESTAMP and bubbleHash = that bubble's textHash; DEVICE_ONLY when nothing parses.
     * capturedAtDevice is {@code nowMs}.
     */
    public static Watermark bubbleWatermark(List<String> descs, long nowMs, TimeZone zone) {
        ThreadEvent e = newest(descs, nowMs, zone);
        if (e == null) return new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, nowMs, null);
        return new Watermark(Watermark.Quality.TIMESTAMP, null, e.getTimeMs(), e.getTextHash(), nowMs, null);
    }

    private static int monthIndex(String name) {
        String k = name.length() >= 3 ? name.substring(0, 3).toLowerCase(Locale.US) : "";
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(k)) return i;
        }
        return -1;
    }
}
