package com.voice2sms.sendlater.send;

/**
 * How the due-time send reaches the screen (R27, R30, D15, OD10). Pure JVM.
 * <ol>
 *   <li>voice2sms's accessibility service is enabled: {@link Result#DIRECT} startActivity (bound
 *       accessibility services are exempt from background-activity-start limits);</li>
 *   <li>else the keyguard is locked or the screen is off, and full-screen intents are allowed:
 *       {@link Result#FULL_SCREEN_INTENT} (the system launches it only on a locked or dark
 *       screen);</li>
 *   <li>else {@link Result#CANNOT_SHOW}: on a lit, unlocked screen a full-screen intent is only
 *       a heads-up, so the sender reports CANNOT_SHOW with {@link SendDetails#plannerCause}
 *       ("Unlock to send" when locked, else "Couldn't send automatically").</li>
 * </ol>
 */
public final class LaunchPlanner {

    /** Values pinned by LaunchPlannerTest. */
    public enum Result { DIRECT, FULL_SCREEN_INTENT, CANNOT_SHOW }

    private LaunchPlanner() {}

    public static Result plan(boolean a11yEnabled, boolean locked, boolean screenOn, boolean canUseFsi) {
        if (a11yEnabled) return Result.DIRECT;
        if ((locked || !screenOn) && canUseFsi) return Result.FULL_SCREEN_INTENT;
        return Result.CANNOT_SHOW;
    }
}
