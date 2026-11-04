package com.voice2sms.sendlater.ui;

/**
 * UI M2 (sheet half): at most one capture sheet at a time. The sheet is launched with
 * {@code taskAffinity=""} from overlays and the WebView, so a second tap can start a second
 * instance in a new task, where {@code singleTop} does not help. The first capture sheet holds the
 * guard until it is destroyed; a second ACTION_CAPTURE that cannot take it closes at once without
 * showing anything. Edit sheets never use the guard. Pure JVM ({@code CaptureSheetGuardTest}).
 */
final class CaptureSheetGuard {

    private static Object owner;

    private CaptureSheetGuard() {}

    /** True when {@code who} now holds the guard (or already did); false when another holds it. */
    static synchronized boolean tryAcquire(Object who) {
        if (who == null) return false;
        if (owner == null || owner == who) {
            owner = who;
            return true;
        }
        return false;
    }

    /** A recreated sheet (saved state present) keeps the guard its earlier instance held. */
    static synchronized void takeOver(Object who) {
        if (who != null) owner = who;
    }

    /** Releases the guard if {@code who} holds it; a no-op otherwise. */
    static synchronized void release(Object who) {
        if (who != null && owner == who) owner = null;
    }

    /** Whether some capture sheet holds the guard. */
    static synchronized boolean isHeld() {
        return owner != null;
    }
}
