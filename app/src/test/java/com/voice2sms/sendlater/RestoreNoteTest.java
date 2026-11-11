package com.voice2sms.sendlater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D44 (on top of D36): when the Send Later switch is set up on a phone whose default prefs came
 * back from a backup or device transfer (the {@link SendLaterSettings#SWITCH_SEEN_MARKER} path),
 * Send Later starts off AND Settings shows the one-line note "Turned off after restore" next to
 * the switch. The "turned off by restore" flag lives in the no-backup switch prefs; it clears the
 * moment the user turns the switch on or off by hand. A fresh install shows no note. The restore
 * decision no longer depends on whether sendlater.db exists (the engine may create it before the
 * first switch read).
 *
 * <p>Interfaces asserted (reflection, so this file compiles before they exist):
 * <ul>
 *   <li>{@code SendLaterSettings.Switch.offByRestore(SharedPreferences local) -> boolean}</li>
 *   <li>{@code SendLaterSettings.Switch.set(SharedPreferences local, boolean on)} — the hand-set
 *       write that {@code setEnabled(Context, boolean)} uses; stores the switch and clears the
 *       flag.</li>
 *   <li>{@code public static boolean SendLaterSettings.offByRestore(Context)}</li>
 * </ul>
 */
public class RestoreNoteTest {

    private static final String NOTE = "Turned off after restore";
    private static final Path SETTINGS =
            Paths.get("src/main/java/com/voice2sms/sendlater/SendLaterSettings.java");
    private static final Path CONTROLLER =
            Paths.get("src/main/java/com/voice2sms/sendlater/ui/SendLaterSettingsController.java");
    private static final Path VALUES = Paths.get("src/main/res/values");

    // ------------------------------------------------------------------ reflection helpers

    private static Class<?> switchClass() throws ClassNotFoundException {
        return Class.forName("com.voice2sms.sendlater.SendLaterSettings$Switch");
    }

    private static Method method(Class<?> owner, String name, Class<?>... args) {
        try {
            Method m = owner.getDeclaredMethod(name, args);
            m.setAccessible(true);
            assertTrue(name + " must be static", Modifier.isStatic(m.getModifiers()));
            return m;
        } catch (NoSuchMethodException e) {
            fail("missing " + owner.getSimpleName() + "." + name + " (D44)");
            return null;
        }
    }

    private static Object call(Method m, Object... args) throws Exception {
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof Exception) throw (Exception) t;
            throw new AssertionError(t);
        }
    }

    private static boolean offByRestore(SharedPreferences local) throws Exception {
        Method m = method(switchClass(), "offByRestore", SharedPreferences.class);
        return (Boolean) call(m, local);
    }

    private static void handSet(SharedPreferences local, boolean on) throws Exception {
        call(method(switchClass(), "set", SharedPreferences.class, boolean.class), local, on);
    }

    private static boolean migrate(SharedPreferences defaults, SharedPreferences local, boolean localData) {
        return SendLaterSettings.Switch.migrate(defaults, local, localData);
    }

    private static SwitchBackupTest.MapPrefs restoredDefaults() {
        SwitchBackupTest.MapPrefs d = new SwitchBackupTest.MapPrefs();
        d.m.put(SendLaterSettings.SWITCH_SEEN_MARKER, true);
        return d;
    }

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String body(String src, String signature) {
        int at = src.indexOf(signature);
        assertTrue("missing " + signature, at >= 0);
        int end = src.indexOf("\n    }\n", at);
        assertTrue(end > at);
        return src.substring(at, end);
    }

    /** Name of the string resource whose text is the restore note, from res/values/sendlater_*.xml. */
    private static String noteStringName() throws IOException {
        File[] files = VALUES.toFile().listFiles();
        assertNotNull(files);
        Pattern p = Pattern.compile("<string\\s+name=\"([A-Za-z0-9_]+)\"[^>]*>\\s*"
                + Pattern.quote(NOTE) + "\\.?\\s*</string>");
        String found = null;
        for (File f : files) {
            if (!f.getName().endsWith(".xml")) continue;
            Matcher m = p.matcher(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (m.find()) {
                assertTrue("the note lives in a sendlater_*.xml values file, not strings.xml: " + f.getName(),
                        f.getName().startsWith("sendlater_"));
                found = m.group(1);
            }
        }
        assertNotNull("no string resource with the text '" + NOTE + "'", found);
        return found;
    }

    // ------------------------------------------------------------------ AC1: restored phone

    @Test public void restoredPhoneStartsOffWithTheNote() throws Exception {
        for (boolean localData : new boolean[] {false, true}) {
            SwitchBackupTest.MapPrefs defaults = restoredDefaults(), local = new SwitchBackupTest.MapPrefs();
            assertFalse("restored phone starts off", migrate(defaults, local, localData));
            assertEquals(Boolean.FALSE, local.m.get(SendLaterSettings.KEY_ENABLED));
            assertTrue("restored phone shows the 'Turned off after restore' note (localData=" + localData + ")",
                    offByRestore(local));
        }
    }

    @Test public void restoreFlagLivesInTheNoBackupSwitchPrefsOnly() throws Exception {
        SwitchBackupTest.MapPrefs defaults = restoredDefaults(), local = new SwitchBackupTest.MapPrefs();
        defaults.m.put(SendLaterSettings.KEY_HOLD_DEFAULT, false);
        Set<String> before = new HashSet<>(defaults.m.keySet());
        migrate(defaults, local, false);
        assertTrue(offByRestore(local));
        assertEquals("no restore flag written to the backed-up default prefs", before, defaults.m.keySet());
        Set<String> extra = new HashSet<>(local.m.keySet());
        extra.remove(SendLaterSettings.KEY_ENABLED);
        assertFalse("the flag is persisted in the switch (no-backup) prefs", extra.isEmpty());
    }

    @Test public void restoreFlagSurvivesLaterReads() throws Exception {
        SwitchBackupTest.MapPrefs defaults = restoredDefaults(), local = new SwitchBackupTest.MapPrefs();
        migrate(defaults, local, false);
        // A second read sees the stored local value; the note stays until the user acts.
        assertTrue(offByRestore(local));
        assertTrue(offByRestore(local));
        assertEquals(Boolean.FALSE, local.m.get(SendLaterSettings.KEY_ENABLED));
    }

    @Test public void settingsShowsTheNoteNextToTheSwitch() throws Exception {
        String name = noteStringName();
        String c = read(CONTROLLER);
        assertTrue("the controller reads the restore flag", c.contains("SendLaterSettings.offByRestore("));
        assertTrue("the controller shows R.string." + name, c.contains("R.string." + name));
        String refresh = body(c, "public void refresh()");
        assertTrue("refresh() (onResume) recomputes the note", refresh.contains("offByRestore(")
                || refresh.contains("restoreNote(") || refresh.contains("R.string." + name));
    }

    @Test public void publicContextReaderExists() throws Exception {
        Method m = method(SendLaterSettings.class, "offByRestore", Context.class);
        assertTrue(Modifier.isPublic(m.getModifiers()));
        assertEquals(boolean.class, m.getReturnType());
        String b = body(read(SETTINGS), "public static boolean offByRestore(Context c)");
        assertTrue("read from the no-backup switch prefs", b.contains("switchPrefs(c)"));
    }

    // ------------------------------------------------------------------ AC2: fresh install

    @Test public void freshInstallShowsNoNote() throws Exception {
        SwitchBackupTest.MapPrefs defaults = new SwitchBackupTest.MapPrefs(), local = new SwitchBackupTest.MapPrefs();
        assertTrue(migrate(defaults, local, false));
        assertFalse("fresh install: no note", offByRestore(local));

        SwitchBackupTest.MapPrefs nullDefaultsLocal = new SwitchBackupTest.MapPrefs();
        assertTrue(migrate(null, nullDefaultsLocal, false));
        assertFalse(offByRestore(nullDefaultsLocal));
    }

    @Test public void emptySwitchPrefsShowNoNote() throws Exception {
        assertFalse(offByRestore(new SwitchBackupTest.MapPrefs()));
    }

    @Test public void upgradeOnThisPhoneShowsNoNote() throws Exception {
        for (boolean old : new boolean[] {true, false}) {
            SwitchBackupTest.MapPrefs defaults = new SwitchBackupTest.MapPrefs(), local = new SwitchBackupTest.MapPrefs();
            defaults.m.put(SendLaterSettings.KEY_ENABLED, old);
            assertEquals(old, migrate(defaults, local, true));
            assertFalse("an upgrade on the same phone is not a restore (old=" + old + ")", offByRestore(local));
        }
    }

    // ------------------------------------------------------------------ AC3: hand toggle clears

    @Test public void turningTheSwitchOnByHandClearsTheNote() throws Exception {
        SwitchBackupTest.MapPrefs defaults = restoredDefaults(), local = new SwitchBackupTest.MapPrefs();
        migrate(defaults, local, false);
        assertTrue(offByRestore(local));
        handSet(local, true);
        assertEquals(Boolean.TRUE, local.m.get(SendLaterSettings.KEY_ENABLED));
        assertFalse("note cleared after turning on by hand", offByRestore(local));
    }

    @Test public void turningTheSwitchOffByHandClearsTheNote() throws Exception {
        SwitchBackupTest.MapPrefs defaults = restoredDefaults(), local = new SwitchBackupTest.MapPrefs();
        migrate(defaults, local, false);
        assertTrue(offByRestore(local));
        handSet(local, false);
        assertEquals(Boolean.FALSE, local.m.get(SendLaterSettings.KEY_ENABLED));
        assertFalse("note cleared after turning off by hand", offByRestore(local));
    }

    @Test public void noteStaysClearedAfterOnThenOff() throws Exception {
        SwitchBackupTest.MapPrefs defaults = restoredDefaults(), local = new SwitchBackupTest.MapPrefs();
        migrate(defaults, local, false);
        handSet(local, true);
        handSet(local, false);
        assertFalse(offByRestore(local));
    }

    @Test public void setEnabledUsesTheHandSetWrite() throws Exception {
        String b = body(read(SETTINGS), "public static void setEnabled(Context c, boolean on)");
        assertTrue("setEnabled clears the restore flag via Switch.set(switchPrefs(c), on)",
                b.contains("Switch.set(switchPrefs(c), on)"));
        String c = read(CONTROLLER);
        int write = c.indexOf("SendLaterSettings.setEnabled(app, on);");
        assertTrue("the Settings switch writes through setEnabled", write > 0);
    }

    // ------------------------------------------------------------------ AC4: no sendlater.db dependency

    @Test public void restoreDecisionDoesNotDependOnTheDatabaseFile() throws Exception {
        String src = read(SETTINGS);
        String isEnabled = body(src, "public static boolean isEnabled(Context c)");
        assertFalse("isEnabled must not decide restore from sendlater.db's existence (the engine may "
                + "create it before the first switch read)", isEnabled.contains("getDatabasePath("));
        assertFalse(isEnabled.contains("SendLaterDatabase.NAME"));
        assertFalse("SendLaterSettings does not probe the database file at all",
                src.contains("getDatabasePath(SendLaterDatabase.NAME)"));
    }

    @Test public void restoredPhoneWithEngineCreatedDatabaseStillStartsOffWithNote() throws Exception {
        // The marker path is decided by the backed-up marker alone, whatever local data exists.
        SwitchBackupTest.MapPrefs defaults = restoredDefaults(), local = new SwitchBackupTest.MapPrefs();
        assertFalse(migrate(defaults, local, true));
        assertTrue(offByRestore(local));
    }
}
