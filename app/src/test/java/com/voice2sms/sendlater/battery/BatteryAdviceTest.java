package com.voice2sms.sendlater.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

/**
 * Battery advice copy (R38, R39, D25). {@link BatteryAdvice#text} is pure JVM and gives both the
 * Settings row summary and the warning notification text. {@link BatteryStatus} is the pure value
 * that {@code BatteryHealth.check(Context)} returns.
 */
public class BatteryAdviceTest {

    static final String HEALTHY = "Unrestricted — scheduled messages send on time";
    static final String LIMITED = "Scheduled messages may be late. Tap to allow background use";
    static final String SAMSUNG = "Also add voice2sms to Battery → Never sleeping apps";

    private static BatteryStatus healthy(boolean samsung) {
        return new BatteryStatus(false, true, samsung);
    }

    private static BatteryStatus optimized(boolean samsung) {
        return new BatteryStatus(false, false, samsung);
    }

    private static BatteryStatus restricted(boolean samsung) {
        return new BatteryStatus(true, true, samsung);
    }

    // --- BatteryStatus: healthy = not background-restricted AND ignoring optimizations (R38) ---

    @Test public void statusIsHealthyOnlyWhenUnrestrictedAndIgnoringOptimizations() {
        assertTrue(new BatteryStatus(false, true, false).isHealthy());
        assertFalse("battery-optimized is limited", new BatteryStatus(false, false, false).isHealthy());
        assertFalse("background-restricted is limited", new BatteryStatus(true, true, false).isHealthy());
        assertFalse(new BatteryStatus(true, false, true).isHealthy());
    }

    @Test public void statusKeepsItsFields() {
        BatteryStatus s = new BatteryStatus(true, false, true);
        assertTrue(s.isRestricted());
        assertFalse(s.isIgnoringOptimizations());
        assertTrue(s.isSamsung());
        BatteryStatus t = new BatteryStatus(false, true, false);
        assertFalse(t.isRestricted());
        assertTrue(t.isIgnoringOptimizations());
        assertFalse(t.isSamsung());
    }

    // --- healthy text ---

    @Test public void healthyNonSamsungSaysUnrestrictedAndNothingAboutLateness() {
        String t = BatteryAdvice.text(healthy(false), false);
        assertNotNull(t);
        assertTrue(t, t.startsWith(HEALTHY));
        assertFalse(t, t.contains("may be late"));
        assertFalse(t, t.contains("Tap to allow background use"));
    }

    @Test public void healthyNonSamsungHasNoNeverSleepingSentence() {
        String t = BatteryAdvice.text(healthy(false), false);
        assertFalse(t, t.contains("Never sleeping"));
    }

    @Test public void healthySamsungStillAddsNeverSleepingAdvice() {
        // R38: no API can read Samsung's "Never sleeping apps" list, so the Samsung row is advice,
        // not a status: it is shown even when the readable checks are healthy.
        String t = BatteryAdvice.text(healthy(true), true);
        assertTrue(t, t.startsWith(HEALTHY));
        assertTrue(t, t.contains(SAMSUNG));
    }

    // --- limited text ---

    @Test public void optimizedNonSamsungSaysMayBeLate() {
        String t = BatteryAdvice.text(optimized(false), false);
        assertTrue(t, t.startsWith(LIMITED));
        assertFalse(t, t.contains("Unrestricted"));
        assertFalse(t, t.contains("Never sleeping"));
    }

    @Test public void restrictedNonSamsungSaysMayBeLate() {
        String t = BatteryAdvice.text(restricted(false), false);
        assertTrue(t, t.startsWith(LIMITED));
        assertFalse(t, t.contains("Unrestricted"));
        assertFalse(t, t.contains("Never sleeping"));
    }

    @Test public void limitedSamsungAddsNeverSleepingSentence() {
        String t = BatteryAdvice.text(optimized(true), true);
        assertTrue(t, t.startsWith(LIMITED));
        assertTrue(t, t.contains(SAMSUNG));
        String r = BatteryAdvice.text(restricted(true), true);
        assertTrue(r, r.contains(SAMSUNG));
    }

    @Test public void samsungSentenceAppearsOnce() {
        String t = BatteryAdvice.text(optimized(true), true);
        assertEquals(t, t.indexOf(SAMSUNG), t.lastIndexOf(SAMSUNG));
    }

    @Test public void isSamsungArgumentDecidesTheSamsungSentence() {
        // The isSamsung argument is authoritative for the advice text.
        assertFalse(BatteryAdvice.text(optimized(true), false).contains("Never sleeping"));
        assertTrue(BatteryAdvice.text(optimized(false), true).contains(SAMSUNG));
    }

    @Test public void limitedTextExplainsForceStopInPlainWords() {
        // D25 copy: a force-stopped voice2sms stays stopped (even across a reboot) until opened
        // again, and scheduled messages cannot go out until then.
        for (boolean samsung : new boolean[] {false, true}) {
            String t = BatteryAdvice.text(optimized(samsung), samsung).toLowerCase(Locale.ROOT);
            assertTrue(t, t.contains("force"));
            assertTrue(t, t.contains("stop"));
            assertTrue(t, t.contains("reboot") || t.contains("restart"));
            assertTrue(t, t.contains("open"));
        }
    }

    @Test public void textIsPureJvmAndNeverNull() {
        for (boolean r : new boolean[] {false, true})
            for (boolean i : new boolean[] {false, true})
                for (boolean s : new boolean[] {false, true}) {
                    String t = BatteryAdvice.text(new BatteryStatus(r, i, s), s);
                    assertNotNull(t);
                    assertFalse(t.trim().isEmpty());
                }
    }
}
