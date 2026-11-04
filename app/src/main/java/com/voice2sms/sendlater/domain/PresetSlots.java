package com.voice2sms.sendlater.domain;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Rolling day-part presets for the sheet (R13, D19, A1): "In 1 hour" first, then the next
 * {@link #MAX_SLOT_ROWS} slot times at least 10 minutes ahead, rolling into tomorrow, labelled
 * "This afternoon · 1:00 PM", "Tonight · 9:00 PM", "Tomorrow morning · 8:00 AM" ...
 *
 * <p>Slot times are minutes of the local day (Settings values). The i-th slot of the day (in
 * ascending order) is named morning / afternoon / evening / night; a fifth or later slot is "night".
 */
public final class PresetSlots {

    /** A1 defaults: 8:00 AM, 1:00 PM, 6:00 PM, 9:00 PM. */
    public static final int[] DEFAULT_SLOT_MINUTES = {8 * 60, 13 * 60, 18 * 60, 21 * 60};
    public static final int MAX_SLOT_ROWS = 4;
    public static final long MIN_LEAD_MS = 10 * 60_000L;
    public static final long IN_ONE_HOUR_MS = 60 * 60_000L;

    private static final String[] PARTS = {"morning", "afternoon", "evening", "night"};
    private static final String SEP = " · ";

    public static final class Preset {
        private final String label;
        private final long timeMs;

        public Preset(String label, long timeMs) {
            this.label = label;
            this.timeMs = timeMs;
        }

        /** E.g. "This afternoon · 1:00 PM". */
        public String getLabel() { return label; }
        public long getTimeMs() { return timeMs; }

        @Override public String toString() { return label; }
    }

    private PresetSlots() {}

    public static List<Preset> compute(long nowMs, TimeZone zone, int[] slotMinutesOfDay) {
        if (zone == null) zone = TimeZone.getDefault();
        int[] slots = (slotMinutesOfDay == null || slotMinutesOfDay.length == 0)
                ? DEFAULT_SLOT_MINUTES.clone() : slotMinutesOfDay.clone();
        Arrays.sort(slots);

        List<Preset> out = new ArrayList<>();
        out.add(new Preset("In 1 hour" + SEP + time(nowMs + IN_ONE_HOUR_MS, zone), nowMs + IN_ONE_HOUR_MS));

        int rows = 0;
        for (int day = 0; day <= 7 && rows < MAX_SLOT_ROWS; day++) {
            for (int i = 0; i < slots.length && rows < MAX_SLOT_ROWS; i++) {
                long t = at(nowMs, zone, day, slots[i]);
                if (t - nowMs < MIN_LEAD_MS) continue;
                out.add(new Preset(dayPart(day, i, t, zone) + SEP + time(t, zone), t));
                rows++;
            }
        }
        return out;
    }

    private static long at(long nowMs, TimeZone zone, int dayOffset, int minuteOfDay) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(nowMs);
        c.add(Calendar.DAY_OF_MONTH, dayOffset);
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        c.set(Calendar.MINUTE, minuteOfDay % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String dayPart(int dayOffset, int slotIndex, long t, TimeZone zone) {
        String part = PARTS[Math.min(slotIndex, PARTS.length - 1)];
        if (dayOffset == 0) return "night".equals(part) ? "Tonight" : "This " + part;
        if (dayOffset == 1) return "Tomorrow " + part;
        SimpleDateFormat f = new SimpleDateFormat("EEEE", Locale.getDefault());
        f.setTimeZone(zone);
        return f.format(new Date(t)) + " " + part;
    }

    static String time(long t, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.getDefault());
        f.setTimeZone(zone);
        return f.format(new Date(t));
    }
}
