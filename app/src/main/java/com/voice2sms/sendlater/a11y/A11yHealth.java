package com.voice2sms.sendlater.a11y;

import java.util.Locale;

/**
 * Pure decision behind the "accessibility service is off" warning (LIVE-F5, mirrors the D25/D28
 * battery guard). Without voice2sms's accessibility service Send Later loses the native Google
 * Voice edge tab, the on-screen thread reader and the DIRECT launch on a locked or dark screen,
 * so a pending message may silently wait for the user's tap.
 *
 * <p>{@link #isServiceListed} reads the {@code Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES}
 * value (colon-separated flattened component names, short or full class form).
 * {@link #decide} says whether to warn: only while Send Later is on and at least one message is
 * pending. Never touches contact data.
 */
public final class A11yHealth {

    /** What the warning should do now. */
    public enum State {
        /** The service is enabled: nothing to warn about; clear any warning. */
        OK,
        /** The service is off, but Send Later is off or nothing is pending: stay quiet. */
        IDLE,
        /** The service is off while a message is pending: warn (throttled). */
        WARN
    }

    private A11yHealth() {}

    /**
     * True when {@code enabledServices} lists {@code pkg}/{@code className} (also in the short
     * {@code pkg/.Simple} form). Matching ignores case and surrounding blanks, as the platform's
     * own parser does not depend on them. Null or empty -> false.
     */
    public static boolean isServiceListed(String enabledServices, String pkg, String className) {
        if (enabledServices == null || pkg == null || className == null) return false;
        String p = pkg.trim().toLowerCase(Locale.ROOT);
        String full = className.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty() || full.isEmpty()) return false;
        for (String entry : enabledServices.split(":")) {
            String e = entry.trim().toLowerCase(Locale.ROOT);
            int slash = e.indexOf('/');
            if (slash <= 0 || slash == e.length() - 1) continue;
            if (!p.equals(e.substring(0, slash))) continue;
            String cls = e.substring(slash + 1);
            if (cls.startsWith(".")) cls = p + cls;
            if (full.equals(cls)) return true;
        }
        return false;
    }

    /** OK when the service is on; else WARN only if Send Later is on and a message is pending. */
    public static State decide(boolean sendLaterOn, int pendingCount, boolean serviceEnabled) {
        if (serviceEnabled) return State.OK;
        if (!sendLaterOn || pendingCount <= 0) return State.IDLE;
        return State.WARN;
    }
}
