package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Acceptance tests for {@code com.voice2sms.sendlater.send.LaunchPlanner} (R27, R30, D15, OD10/11):
 * {@code plan(a11yEnabled, locked, screenOn, canUseFsi)} -> DIRECT | FULL_SCREEN_INTENT | CANNOT_SHOW.
 * (1) voice2sms's accessibility service enabled -> DIRECT; (2) else keyguard locked or screen off
 * and full-screen intents allowed -> FULL_SCREEN_INTENT; (3) else CANNOT_SHOW (the engine records
 * a LOCKED reminder). Called by reflection so the suite compiles before the class exists.
 */
public class LaunchPlannerTest {

    private static final String CLASS = "com.voice2sms.sendlater.send.LaunchPlanner";

    private static Method planMethod() throws Exception {
        Class<?> c = Class.forName(CLASS, false, LaunchPlannerTest.class.getClassLoader());
        Method m = c.getDeclaredMethod("plan", boolean.class, boolean.class, boolean.class, boolean.class);
        assertTrue("plan must be static", Modifier.isStatic(m.getModifiers()));
        assertTrue("plan must be public", Modifier.isPublic(m.getModifiers()));
        return m;
    }

    private static String plan(boolean a11y, boolean locked, boolean screenOn, boolean fsi) throws Exception {
        Object r;
        try {
            r = planMethod().invoke(null, a11y, locked, screenOn, fsi);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
        assertNotNull("plan never returns null", r);
        assertTrue("plan returns an enum constant", r instanceof Enum);
        return ((Enum<?>) r).name();
    }

    @Test public void resultTypeHasExactlyTheThreePinnedValues() throws Exception {
        Class<?> rt = planMethod().getReturnType();
        assertTrue(rt.isEnum());
        Set<String> names = new HashSet<>();
        for (Object c : rt.getEnumConstants()) names.add(((Enum<?>) c).name());
        assertEquals(new HashSet<>(Arrays.asList("DIRECT", "FULL_SCREEN_INTENT", "CANNOT_SHOW")), names);
    }

    @Test public void plannerIsPureJvm() throws Exception {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater/send/LaunchPlanner.java");
        assertTrue("missing " + p, Files.exists(p));
        String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        assertFalse(src.contains("import android."));
        assertFalse(src.contains("import androidx."));
    }

    // (1) accessibility service enabled -> DIRECT, whatever the screen state.
    @Test public void a11yEnabledIsDirectInEveryScreenState() throws Exception {
        for (boolean locked : new boolean[] {false, true}) {
            for (boolean screenOn : new boolean[] {false, true}) {
                for (boolean fsi : new boolean[] {false, true}) {
                    assertEquals("a11y on, locked=" + locked + " screenOn=" + screenOn + " fsi=" + fsi,
                            "DIRECT", plan(true, locked, screenOn, fsi));
                }
            }
        }
    }

    // (2) locked + FSI allowed -> FULL_SCREEN_INTENT.
    @Test public void lockedWithFsiAllowedIsFullScreenIntent() throws Exception {
        assertEquals("FULL_SCREEN_INTENT", plan(false, true, true, true));
        assertEquals("FULL_SCREEN_INTENT", plan(false, true, false, true));
    }

    @Test public void screenOffWithFsiAllowedIsFullScreenIntent() throws Exception {
        assertEquals("screen off counts like locked", "FULL_SCREEN_INTENT", plan(false, false, false, true));
    }

    // (3) otherwise CANNOT_SHOW.
    @Test public void unlockedScreenOnWithoutA11yCannotShow() throws Exception {
        assertEquals("FSI on an unlocked, lit screen is only a heads-up: cannot show",
                "CANNOT_SHOW", plan(false, false, true, true));
        assertEquals("CANNOT_SHOW", plan(false, false, true, false));
    }

    @Test public void lockedWithoutFsiPermissionCannotShow() throws Exception {
        assertEquals("CANNOT_SHOW", plan(false, true, true, false));
        assertEquals("CANNOT_SHOW", plan(false, true, false, false));
        assertEquals("CANNOT_SHOW", plan(false, false, false, false));
    }
}
