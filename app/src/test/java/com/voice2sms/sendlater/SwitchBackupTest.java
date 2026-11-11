package com.voice2sms.sendlater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * D36 (amends OD32): the Send Later master switch is not in Google backup or device transfer.
 * It lives in its own sendlater_switch prefs file (excluded by both rule files); the old key in
 * the default prefs is migrated once; a phone whose default prefs came back from a backup or a
 * transfer starts with Send Later off; a fresh install starts on (D26).
 */
public class SwitchBackupTest {

    private static final Path FULL = Paths.get("src/main/res/xml/sendlater_backup_rules.xml");
    private static final Path EXTRACT = Paths.get("src/main/res/xml/sendlater_data_extraction_rules.xml");
    private static final Path PREFS_XML = Paths.get("src/main/res/xml/preferences.xml");
    private static final Path CONTROLLER =
            Paths.get("src/main/java/com/voice2sms/sendlater/ui/SendLaterSettingsController.java");
    private static final Path SETTINGS =
            Paths.get("src/main/java/com/voice2sms/sendlater/SendLaterSettings.java");
    private static final Path DEBUG_RECEIVER =
            Paths.get("src/debug/java/com/voice2sms/sendlater/debug/SendLaterDebugReceiver.java");

    /** SharedPreferences over a map, with a working editor; typed getters cast like the real one. */
    static final class MapPrefs implements SharedPreferences {
        final Map<String, Object> m = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(m); }
        @Override public String getString(String k, String d) { String v = (String) m.get(k); return v != null ? v : d; }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String k, Set<String> d) { Set<String> v = (Set<String>) m.get(k); return v != null ? v : d; }
        @Override public int getInt(String k, int d) { Integer v = (Integer) m.get(k); return v != null ? v : d; }
        @Override public long getLong(String k, long d) { Long v = (Long) m.get(k); return v != null ? v : d; }
        @Override public float getFloat(String k, float d) { Float v = (Float) m.get(k); return v != null ? v : d; }
        @Override public boolean getBoolean(String k, boolean d) { Boolean v = (Boolean) m.get(k); return v != null ? v : d; }
        @Override public boolean contains(String k) { return m.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> put = new HashMap<>();
                final java.util.List<String> removed = new java.util.ArrayList<>();
                boolean clear;
                @Override public Editor putString(String k, String v) { put.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { put.put(k, v); return this; }
                @Override public Editor putInt(String k, int v) { put.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { put.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { put.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { put.put(k, v); return this; }
                @Override public Editor remove(String k) { removed.add(k); return this; }
                @Override public Editor clear() { clear = true; return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    if (clear) m.clear();
                    for (String k : removed) m.remove(k);
                    m.putAll(put);
                }
            };
        }
    }

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ migration rules

    @Test public void freshInstallStartsOn() {
        MapPrefs defaults = new MapPrefs(), local = new MapPrefs();
        assertTrue(SendLaterSettings.Switch.migrate(defaults, local, false));
        assertEquals(Boolean.TRUE, local.m.get(SendLaterSettings.KEY_ENABLED));
        assertEquals(Boolean.TRUE, defaults.m.get(SendLaterSettings.SWITCH_SEEN_MARKER));
        assertFalse(defaults.contains(SendLaterSettings.KEY_ENABLED));
    }

    @Test public void restoredDefaultPrefsStartOff() {
        // A backup of a D36 build holds only the marker; the switch file was never in it.
        for (boolean localData : new boolean[] {false, true}) {
            MapPrefs defaults = new MapPrefs(), local = new MapPrefs();
            defaults.m.put(SendLaterSettings.SWITCH_SEEN_MARKER, true);
            assertFalse(SendLaterSettings.Switch.migrate(defaults, local, localData));
            assertEquals(Boolean.FALSE, local.m.get(SendLaterSettings.KEY_ENABLED));
        }
    }

    @Test public void upgradeOnThisPhoneKeepsTheOldValueAndRemovesTheOldKey() {
        for (Object old : new Object[] {true, false, "true", "false"}) {
            MapPrefs defaults = new MapPrefs(), local = new MapPrefs();
            defaults.m.put(SendLaterSettings.KEY_ENABLED, old);
            defaults.m.put(SendLaterSettings.KEY_HOLD_DEFAULT, false);
            boolean want = Boolean.parseBoolean(String.valueOf(old));
            assertEquals(String.valueOf(old), want, SendLaterSettings.Switch.migrate(defaults, local, true));
            assertEquals(want, local.m.get(SendLaterSettings.KEY_ENABLED));
            assertFalse("old key no longer in the backed-up prefs", defaults.contains(SendLaterSettings.KEY_ENABLED));
            assertEquals("other preferences untouched", Boolean.FALSE, defaults.m.get(SendLaterSettings.KEY_HOLD_DEFAULT));
            assertTrue(defaults.contains(SendLaterSettings.SWITCH_SEEN_MARKER));
        }
    }

    @Test public void oldKeyWithoutLocalDataIsARestoreOfAnOlderBuildAndStartsOff() {
        MapPrefs defaults = new MapPrefs(), local = new MapPrefs();
        defaults.m.put(SendLaterSettings.KEY_ENABLED, true);
        assertFalse(SendLaterSettings.Switch.migrate(defaults, local, false));
        assertEquals(Boolean.FALSE, local.m.get(SendLaterSettings.KEY_ENABLED));
        assertFalse(defaults.contains(SendLaterSettings.KEY_ENABLED));
    }

    @Test public void unreadableOldValueFallsBackToTheDefault() {
        MapPrefs defaults = new MapPrefs(), local = new MapPrefs();
        defaults.m.put(SendLaterSettings.KEY_ENABLED, "maybe");
        assertEquals(SendLaterSettings.DEFAULT_ENABLED, SendLaterSettings.Switch.migrate(defaults, local, true));
    }

    @Test public void nullDefaultsIsAFreshInstall() {
        MapPrefs local = new MapPrefs();
        assertTrue(SendLaterSettings.Switch.migrate(null, local, false));
        assertEquals(Boolean.TRUE, local.m.get(SendLaterSettings.KEY_ENABLED));
    }

    @Test public void migrationHappensOnceThenTheLocalValueWins() throws IOException {
        // isEnabled migrates only while the switch file has no value (re-checked under a lock).
        String src = read(SETTINGS);
        int at = src.indexOf("public static boolean isEnabled(Context c)");
        assertTrue(at > 0);
        String body = src.substring(at, src.indexOf("\n    }\n", at));
        assertTrue(body.contains("switchPrefs(c)"));
        assertTrue(body.contains("synchronized (SWITCH_LOCK)"));
        assertTrue(body.indexOf("Switch.migrate(") > body.indexOf("synchronized (SWITCH_LOCK)"));
        // D44 replaced the sendlater.db existence test (racy with the engine) by install times.
        assertTrue(body.contains("updatedOnThisPhone(c)"));
        assertFalse("the switch is not read from the default prefs", body.contains("Read.bool(prefs(c)"));
    }

    // ------------------------------------------------------------------ backup rules + writers

    @Test public void switchFileIsExcludedFromBackupAndDeviceTransfer() throws IOException {
        assertEquals("sendlater_switch", SendLaterSettings.SWITCH_PREFS);
        String line = "<exclude domain=\"sharedpref\" path=\"sendlater_switch.xml\" />";
        assertTrue("fullBackupContent", read(FULL).contains(line));
        String x = read(EXTRACT);
        int c0 = x.indexOf("<cloud-backup>"), c1 = x.indexOf("</cloud-backup>");
        int d0 = x.indexOf("<device-transfer>"), d1 = x.indexOf("</device-transfer>");
        assertTrue(c0 > 0 && c1 > c0 && d0 > 0 && d1 > d0);
        assertTrue("cloud-backup", x.substring(c0, c1).contains(line));
        assertTrue("device-transfer", x.substring(d0, d1).contains(line));
    }

    @Test public void settingsSwitchIsNotPersistedToTheDefaultPrefs() throws IOException {
        String prefs = read(PREFS_XML);
        int k = prefs.indexOf("android:key=\"sendlater_enabled\"");
        assertTrue(k >= 0);
        String element = prefs.substring(prefs.lastIndexOf('<', k), prefs.indexOf("/>", k));
        assertTrue("persistent=false keeps the framework from writing the backed-up key",
                element.contains("android:persistent=\"false\""));
        assertTrue("still shows on by default (D26)", element.contains("android:defaultValue=\"true\""));

        String c = read(CONTROLLER);
        assertTrue(c.contains("enabled.setChecked(SendLaterSettings.isEnabled("));
        int write = c.indexOf("SendLaterSettings.setEnabled(app, on);");
        int apply = c.indexOf("applyEnabled(app, on)");
        assertTrue("stores the value before the switch is applied", write > 0 && apply > write);
    }

    @Test public void debugEnableWritesTheSwitchFile() throws IOException {
        String d = read(DEBUG_RECEIVER);
        assertTrue(d.contains("SendLaterSettings.switchPrefs(app).edit()"));
        assertFalse(d.contains("getDefaultSharedPreferences(app).edit()\n                    .putBoolean(SendLaterSettings.KEY_ENABLED"));
    }

    @Test public void noProductionCodeWritesTheSwitchToTheDefaultPrefs() throws IOException {
        String src = read(SETTINGS);
        int at = src.indexOf("public static void setEnabled(");
        assertTrue(at > 0);
        String body = src.substring(at, src.indexOf("\n    }\n", at));
        // D44: setEnabled writes the switch prefs through the hand-set write, which also clears
        // the restore note.
        assertTrue(body.contains("Switch.set(switchPrefs(c), on)"));
        int set = src.indexOf("static void set(SharedPreferences local, boolean on)");
        assertTrue(set > 0);
        assertTrue(src.substring(set, src.indexOf("\n        }\n", set))
                .contains("local.edit().putBoolean(KEY_ENABLED, on)"));
    }
}
