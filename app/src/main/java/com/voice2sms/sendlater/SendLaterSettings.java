package com.voice2sms.sendlater;

import android.content.Context;

import androidx.preference.PreferenceManager;

import com.voice2sms.sendlater.domain.OutboundPolicy;

import java.util.Map;

/**
 * Send Later preferences (R36), read from the default SharedPreferences. Keys and defaults are
 * pinned; the {@link Parse} layer is pure and JVM-tested. Values may be stored as their natural
 * type or as strings (ListPreference/EditTextPreference); anything unreadable falls back to the
 * default.
 */
public final class SendLaterSettings {
    private SendLaterSettings() {}

    public static final String KEY_ENABLED = "sendlater_enabled";
    public static final String KEY_SLOT_MORNING = "sendlater_slot_morning";
    public static final String KEY_SLOT_AFTERNOON = "sendlater_slot_afternoon";
    public static final String KEY_SLOT_EVENING = "sendlater_slot_evening";
    public static final String KEY_SLOT_NIGHT = "sendlater_slot_night";
    public static final String KEY_OUTBOUND_POLICY = "sendlater_outbound_policy";
    public static final String KEY_HOLD_DEFAULT = "sendlater_hold_default";
    public static final String KEY_CLEAR_DRAFT = "sendlater_clear_draft";

    /** On by default (R41, D26): a fresh install and an install that never set the switch. */
    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_SLOT_MORNING = 480;
    public static final int DEFAULT_SLOT_AFTERNOON = 780;
    public static final int DEFAULT_SLOT_EVENING = 1080;
    public static final int DEFAULT_SLOT_NIGHT = 1260;
    public static final OutboundPolicy DEFAULT_OUTBOUND_POLICY = OutboundPolicy.STILL_SEND;
    public static final boolean DEFAULT_HOLD = true;
    public static final boolean DEFAULT_CLEAR_DRAFT = true;

    /** Slot keys in Morning, Afternoon, Evening, Night order. */
    public static final String[] SLOT_KEYS = {
            KEY_SLOT_MORNING, KEY_SLOT_AFTERNOON, KEY_SLOT_EVENING, KEY_SLOT_NIGHT};
    static final int[] DEFAULT_SLOTS = {
            DEFAULT_SLOT_MORNING, DEFAULT_SLOT_AFTERNOON, DEFAULT_SLOT_EVENING, DEFAULT_SLOT_NIGHT};

    public static boolean isEnabled(Context c) { return Parse.enabled(all(c)); }
    /** Minutes of day, Morning..Night order. */
    public static int[] slotMinutes(Context c) { return Parse.slotMinutes(all(c)); }
    public static OutboundPolicy outboundPolicy(Context c) { return Parse.outboundPolicy(all(c)); }
    public static boolean holdDefault(Context c) { return Parse.holdDefault(all(c)); }
    public static boolean clearDraft(Context c) { return Parse.clearDraft(all(c)); }

    private static Map<String, ?> all(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c).getAll();
    }

    /** Pure parse layer over a {@code SharedPreferences.getAll()} map (null map = all defaults). */
    public static final class Parse {
        private Parse() {}

        public static boolean enabled(Map<String, ?> m) { return bool(m, KEY_ENABLED, DEFAULT_ENABLED); }
        public static boolean holdDefault(Map<String, ?> m) { return bool(m, KEY_HOLD_DEFAULT, DEFAULT_HOLD); }
        public static boolean clearDraft(Map<String, ?> m) { return bool(m, KEY_CLEAR_DRAFT, DEFAULT_CLEAR_DRAFT); }

        public static int[] slotMinutes(Map<String, ?> m) {
            int[] out = new int[SLOT_KEYS.length];
            for (int i = 0; i < out.length; i++) out[i] = minutesOfDay(m, SLOT_KEYS[i], DEFAULT_SLOTS[i]);
            return out;
        }

        public static OutboundPolicy outboundPolicy(Map<String, ?> m) {
            Object v = m == null ? null : m.get(KEY_OUTBOUND_POLICY);
            if (v instanceof String) {
                for (OutboundPolicy p : OutboundPolicy.values()) {
                    if (p.name().equals(((String) v).trim())) return p;
                }
            }
            return DEFAULT_OUTBOUND_POLICY;
        }

        static boolean bool(Map<String, ?> m, String key, boolean def) {
            Object v = m == null ? null : m.get(key);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) {
                String s = ((String) v).trim();
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s)) return false;
            }
            return def;
        }

        /** 0..1439 from an Integer/Long or a decimal string; otherwise {@code def}. */
        static int minutesOfDay(Map<String, ?> m, String key, int def) {
            Object v = m == null ? null : m.get(key);
            long n;
            if (v instanceof Integer || v instanceof Long) {
                n = ((Number) v).longValue();
            } else if (v instanceof String) {
                try {
                    n = Long.parseLong(((String) v).trim());
                } catch (NumberFormatException e) {
                    return def;
                }
            } else {
                return def;
            }
            return (n >= 0 && n < 24 * 60) ? (int) n : def;
        }
    }
}
