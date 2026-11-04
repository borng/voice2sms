package com.voice2sms.sendlater.battery;

/**
 * Pure value returned by {@link BatteryHealth#check} (R38). Healthy means the two readable
 * platform signals both allow alarms to fire on time: the app is not background-restricted and
 * it is exempt from battery optimizations. Samsung's "Never sleeping apps" list cannot be read,
 * so {@link #isSamsung()} only selects extra advice; it never changes {@link #isHealthy()}.
 */
public final class BatteryStatus {

    private final boolean restricted;
    private final boolean ignoringOptimizations;
    private final boolean samsung;

    public BatteryStatus(boolean restricted, boolean ignoringOptimizations, boolean samsung) {
        this.restricted = restricted;
        this.ignoringOptimizations = ignoringOptimizations;
        this.samsung = samsung;
    }

    /** {@code ActivityManager.isBackgroundRestricted()} (API 28+; false below). */
    public boolean isRestricted() { return restricted; }

    /** {@code PowerManager.isIgnoringBatteryOptimizations(pkg)}. */
    public boolean isIgnoringOptimizations() { return ignoringOptimizations; }

    /** {@code Build.MANUFACTURER} is Samsung. */
    public boolean isSamsung() { return samsung; }

    public boolean isHealthy() { return !restricted && ignoringOptimizations; }

    @Override public String toString() {
        return "BatteryStatus{restricted=" + restricted + ", ignoringOptimizations="
                + ignoringOptimizations + ", samsung=" + samsung + "}";
    }
}
