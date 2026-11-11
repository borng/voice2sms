package com.voice2sms.sendlater.battery;

/**
 * Plain-words battery advice (R38, R39, D25), shared by the Settings "Battery" row summary and the
 * once-a-day warning notification. Pure JVM so the copy is unit-tested.
 */
public final class BatteryAdvice {

    static final String HEALTHY = "Unrestricted — scheduled messages send on time.";
    static final String LIMITED = "Scheduled messages may be late. Tap to allow background use.";
    static final String FORCE_STOP = "If voice2sms is force-stopped, it stays stopped, even after a "
            + "reboot, until you open it again. Scheduled messages cannot go out until then.";
    static final String SAMSUNG = "Also add voice2sms to Battery → Never sleeping apps.";

    private BatteryAdvice() {}

    /**
     * Row summary / notification text for {@code status}. {@code isSamsung} decides the Samsung
     * sentence: no API can read Samsung's sleeping-apps list, so it is advice shown on every
     * Samsung phone, healthy or not.
     */
    public static String text(BatteryStatus status, boolean isSamsung) {
        StringBuilder sb = new StringBuilder();
        if (status != null && status.isHealthy()) {
            sb.append(HEALTHY);
        } else {
            sb.append(LIMITED).append(' ').append(FORCE_STOP);
        }
        if (isSamsung) sb.append(' ').append(SAMSUNG);
        return sb.toString();
    }
}
