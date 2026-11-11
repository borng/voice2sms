package com.voice2sms.sendlater.send;

/**
 * How the due-time send reaches the screen (R27, R30, D15, OD10). Pure JVM.
 * <ol>
 *   <li>voice2sms's accessibility service is enabled: {@link Result#DIRECT} startActivity (bound
 *       accessibility services are exempt from background-activity-start limits);</li>
 *   <li>else the keyguard is locked or the screen is off, and full-screen intents are allowed:
 *       {@link Result#FULL_SCREEN_INTENT} (the system launches it only on a locked or dark
 *       screen);</li>
 *   <li>else the send was started by the user's own tap from an activity in the foreground
 *       (LIVE-F4: the Send now / Send anyway trampoline is showing) on an unlocked, lit screen:
 *       {@link Result#DIRECT} too (an app with a visible window may start activities), so a user
 *       tap never depends on the accessibility service;</li>
 *   <li>else {@link Result#CANNOT_SHOW}: on a lit, unlocked screen a full-screen intent is only
 *       a heads-up, so the sender reports CANNOT_SHOW with {@link SendDetails#plannerCause}
 *       ("Unlock to send" when locked, else "Couldn't send automatically").</li>
 * </ol>
 */
public final class LaunchPlanner {

    /** Values pinned by LaunchPlannerTest. */
    public enum Result { DIRECT, FULL_SCREEN_INTENT, CANNOT_SHOW }

    private LaunchPlanner() {}

    /** As {@link #plan(boolean, boolean, boolean, boolean, boolean)} with no foreground user tap. */
    public static Result plan(boolean a11yEnabled, boolean locked, boolean screenOn, boolean canUseFsi) {
        return plan(a11yEnabled, locked, screenOn, canUseFsi, false);
    }

    /**
     * {@code userTapForeground}: the send was started by the user's tap and voice2sms's tap
     * activity is in the foreground now (LIVE-F4). It gives DIRECT only on an unlocked, lit
     * screen; a locked or dark screen falls through to the usual rules (OD31 L1: a tap never acts
     * over the keyguard).
     */
    public static Result plan(boolean a11yEnabled, boolean locked, boolean screenOn, boolean canUseFsi,
                              boolean userTapForeground) {
        if (a11yEnabled) return Result.DIRECT;
        if (userTapForeground && !locked && screenOn) return Result.DIRECT;
        if ((locked || !screenOn) && canUseFsi) return Result.FULL_SCREEN_INTENT;
        return Result.CANNOT_SHOW;
    }
}
