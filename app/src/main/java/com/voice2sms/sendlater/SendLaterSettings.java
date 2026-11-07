package com.voice2sms.sendlater;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.voice2sms.sendlater.domain.OutboundPolicy;
import com.voice2sms.sendlater.store.SendLaterDatabase;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Send Later preferences (R36), read from the default SharedPreferences (the master switch from
 * its own non-backed-up file, D36: {@link #SWITCH_PREFS}). Keys and defaults are
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

    /**
     * D36: the master switch lives in its own prefs file, excluded from cloud backup and device
     * transfer like every sendlater_* file (res/xml/sendlater_*_rules.xml), so a restored or
     * transferred phone never inherits it. The other Send Later preferences stay in the default
     * prefs and are backed up as before.
     */
    public static final String SWITCH_PREFS = "sendlater_switch";
    /**
     * Written to the default (backed-up) prefs once the switch has been set up on a phone. Seen
     * without a local switch value, it means the default prefs came back from a backup or device
     * transfer: Send Later starts off there (D36). A fresh install has neither and starts on (D26).
     */
    public static final String SWITCH_SEEN_MARKER = "sendlater_switch_seen";

    private static final Object SWITCH_LOCK = new Object();

    public static boolean isEnabled(Context c) {
        SharedPreferences local = switchPrefs(c);
        Object v = Read.bool(local, KEY_ENABLED, DEFAULT_ENABLED);
        if (v == null) {
            synchronized (SWITCH_LOCK) {
                v = Read.bool(local, KEY_ENABLED, DEFAULT_ENABLED);
                if (v == null) {
                    v = Switch.migrate(prefs(c), local,
                            c.getDatabasePath(SendLaterDatabase.NAME).exists());
                }
            }
        }
        return Parse.enabled(Read.one(KEY_ENABLED, v));
    }

    /** Stores the master switch (D36: in {@link #SWITCH_PREFS}, never in the backed-up prefs). */
    public static void setEnabled(Context c, boolean on) {
        switchPrefs(c).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    /** The non-backed-up prefs file that holds the master switch (D36). */
    public static SharedPreferences switchPrefs(Context c) {
        return c.getSharedPreferences(SWITCH_PREFS, Context.MODE_PRIVATE);
    }
    /** Minutes of day, Morning..Night order. */
    public static int[] slotMinutes(Context c) { return Parse.slotMinutes(Read.slots(prefs(c))); }
    public static OutboundPolicy outboundPolicy(Context c) {
        return Parse.outboundPolicy(Read.one(KEY_OUTBOUND_POLICY, Read.string(prefs(c), KEY_OUTBOUND_POLICY)));
    }
    public static boolean holdDefault(Context c) {
        return Parse.holdDefault(Read.one(KEY_HOLD_DEFAULT, Read.bool(prefs(c), KEY_HOLD_DEFAULT, DEFAULT_HOLD)));
    }
    public static boolean clearDraft(Context c) {
        return Parse.clearDraft(Read.one(KEY_CLEAR_DRAFT,
                Read.bool(prefs(c), KEY_CLEAR_DRAFT, DEFAULT_CLEAR_DRAFT)));
    }

    private static SharedPreferences prefs(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c);
    }

    /**
     * Reads only the keys a getter needs (no {@code getAll()} copy), returning the stored object
     * the {@link Parse} layer would have found in {@code getAll()}: the natural type when stored
     * that way, else the string form, else null (absent or another type, which Parse treats as
     * the default). Typed getters are tried in turn; SharedPreferences throws ClassCastException
     * for a key stored as another type.
     */
    static final class Read {
        private Read() {}

        /** A one-entry map for Parse, or null (all defaults) when {@code v} is null. */
        static Map<String, ?> one(String key, Object v) {
            return v == null ? null : Collections.singletonMap(key, v);
        }

        /** The four slot values, absent ones left out. */
        static Map<String, ?> slots(SharedPreferences p) {
            Map<String, Object> m = new HashMap<>();
            for (int i = 0; i < SLOT_KEYS.length; i++) {
                Object v = number(p, SLOT_KEYS[i], DEFAULT_SLOTS[i]);
                if (v != null) m.put(SLOT_KEYS[i], v);
            }
            return m;
        }

        /** Boolean or String stored at {@code key}, else null. */
        static Object bool(SharedPreferences p, String key, boolean def) {
            if (p == null || !p.contains(key)) return null;
            try {
                return p.getBoolean(key, def);
            } catch (ClassCastException notBoolean) {
                return string(p, key);
            }
        }

        /** Integer, Long or String stored at {@code key}, else null. */
        static Object number(SharedPreferences p, String key, int def) {
            if (p == null || !p.contains(key)) return null;
            try {
                return p.getInt(key, def);
            } catch (ClassCastException notInt) {
                try {
                    return p.getLong(key, def);
                } catch (ClassCastException notLong) {
                    return string(p, key);
                }
            }
        }

        /** String stored at {@code key}, else null. */
        static Object string(SharedPreferences p, String key) {
            if (p == null) return null;
            try {
                return p.getString(key, null);
            } catch (ClassCastException notString) {
                return null;
            }
        }
    }

    /**
     * D36 one-time set-up of the local switch, the first time it is read on this phone. Pure
     * over the two prefs so it is JVM-tested:
     * <ul>
     *   <li>{@link #SWITCH_SEEN_MARKER} in the default prefs: they were restored or transferred from
     *       another phone, so Send Later starts <b>off</b>.</li>
     *   <li>The old {@link #KEY_ENABLED} in the default prefs (builds before D36): an upgrade on
     *       this phone keeps its value, but only when Send Later's database exists here
     *       ({@code localData}, never backed up); without it the value came from a backup of an
     *       older build and Send Later starts <b>off</b>.</li>
     *   <li>Neither: a fresh install, {@link #DEFAULT_ENABLED} (on, D26).</li>
     * </ul>
     * Writes the result to {@code local}, removes the old key from the default prefs (so it is
     * no longer backed up) and marks them with {@link #SWITCH_SEEN_MARKER}. Returns the value.
     */
    static final class Switch {
        private Switch() {}

        static boolean initial(SharedPreferences defaults, boolean localData) {
            if (defaults != null && defaults.contains(SWITCH_SEEN_MARKER)) return false;
            Object legacy = Read.bool(defaults, KEY_ENABLED, DEFAULT_ENABLED);
            if (legacy != null) {
                return localData && Parse.enabled(Read.one(KEY_ENABLED, legacy));
            }
            return DEFAULT_ENABLED;
        }

        static boolean migrate(SharedPreferences defaults, SharedPreferences local, boolean localData) {
            boolean on = initial(defaults, localData);
            local.edit().putBoolean(KEY_ENABLED, on).apply();
            if (defaults != null) {
                defaults.edit().remove(KEY_ENABLED).putBoolean(SWITCH_SEEN_MARKER, true).apply();
            }
            return on;
        }
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
