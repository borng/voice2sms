package com.voice2sms.sendlater;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.SharedPreferences;

import com.voice2sms.sendlater.SendLaterSettings.Parse;
import com.voice2sms.sendlater.SendLaterSettings.Read;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * simplify-io item 2: per-key reads give exactly what the old {@code getAll()} parse gave, for
 * every stored type a preference can hold.
 */
public class SendLaterSettingsReadTest {

    /** Casts like SharedPreferencesImpl: a typed getter throws ClassCastException on another type. */
    static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> m = new HashMap<>();
        int getAllCalls;

        @Override public Map<String, ?> getAll() { getAllCalls++; return new HashMap<>(m); }
        @Override public String getString(String k, String d) { String v = (String) m.get(k); return v != null ? v : d; }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String k, Set<String> d) { Set<String> v = (Set<String>) m.get(k); return v != null ? v : d; }
        @Override public int getInt(String k, int d) { Integer v = (Integer) m.get(k); return v != null ? v : d; }
        @Override public long getLong(String k, long d) { Long v = (Long) m.get(k); return v != null ? v : d; }
        @Override public float getFloat(String k, float d) { Float v = (Float) m.get(k); return v != null ? v : d; }
        @Override public boolean getBoolean(String k, boolean d) { Boolean v = (Boolean) m.get(k); return v != null ? v : d; }
        @Override public boolean contains(String k) { return m.containsKey(k); }
        @Override public Editor edit() { throw new UnsupportedOperationException(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    }

    /** Every value type a SharedPreferences entry can have, plus edge strings. */
    private static final List<Object> VALUES = Arrays.<Object>asList(
            null, true, false, "true", " FALSE ", "yes", "", 0, 479, 1439, 1440, -1,
            600L, 5000L, " 720 ", "7x", "STILL_SEND", " HOLD ", "nope", 1.5f,
            new HashSet<>(Collections.singletonList("a")));

    private static FakePrefs prefsWith(String key, Object v) {
        FakePrefs p = new FakePrefs();
        if (v != null) p.m.put(key, v);
        return p;
    }

    @Test public void booleansMatchFullMapParse() {
        String[] keys = {SendLaterSettings.KEY_ENABLED, SendLaterSettings.KEY_HOLD_DEFAULT,
                SendLaterSettings.KEY_CLEAR_DRAFT};
        boolean[] defs = {SendLaterSettings.DEFAULT_ENABLED, SendLaterSettings.DEFAULT_HOLD,
                SendLaterSettings.DEFAULT_CLEAR_DRAFT};
        for (int i = 0; i < keys.length; i++) {
            for (Object v : VALUES) {
                FakePrefs p = prefsWith(keys[i], v);
                boolean old = Parse.bool(p.getAll(), keys[i], defs[i]);
                boolean now = Parse.bool(Read.one(keys[i], Read.bool(p, keys[i], defs[i])), keys[i], defs[i]);
                assertEquals(keys[i] + "=" + v, old, now);
            }
        }
    }

    @Test public void slotsMatchFullMapParse() {
        for (Object v : VALUES) {
            for (String key : SendLaterSettings.SLOT_KEYS) {
                FakePrefs p = prefsWith(key, v);
                p.m.put(SendLaterSettings.KEY_ENABLED, "unrelated");
                assertArrayEquals(key + "=" + v, Parse.slotMinutes(p.getAll()),
                        Parse.slotMinutes(Read.slots(p)));
            }
        }
    }

    @Test public void outboundPolicyMatchesFullMapParse() {
        String key = SendLaterSettings.KEY_OUTBOUND_POLICY;
        for (Object v : VALUES) {
            FakePrefs p = prefsWith(key, v);
            assertEquals(key + "=" + v, Parse.outboundPolicy(p.getAll()),
                    Parse.outboundPolicy(Read.one(key, Read.string(p, key))));
        }
    }

    @Test public void readsNeverCopyTheWholeMap() {
        FakePrefs p = new FakePrefs();
        p.m.put(SendLaterSettings.KEY_ENABLED, false);
        p.m.put(SendLaterSettings.KEY_SLOT_MORNING, "500");
        Read.bool(p, SendLaterSettings.KEY_ENABLED, true);
        Read.slots(p);
        Read.string(p, SendLaterSettings.KEY_OUTBOUND_POLICY);
        assertEquals(0, p.getAllCalls);
    }

    @Test public void absentOrUnreadableIsNull() {
        FakePrefs p = new FakePrefs();
        assertNull(Read.bool(p, "k", true));
        assertNull(Read.number(p, "k", 1));
        assertNull(Read.string(p, "k"));
        assertNull(Read.one("k", null));
        p.m.put("k", 1.5f);
        assertNull(Read.bool(p, "k", true));
        assertNull(Read.number(p, "k", 1));
        assertNull(Read.string(p, "k"));
        assertNull(Read.bool(null, "k", true));
    }
}
