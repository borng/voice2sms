package com.voice2sms.sendlater.battery;

import com.voice2sms.R;

/**
 * Battery indicator (R43, D28) shown next to the setup wizard's Battery status and as the icon of
 * the Settings "Battery" row. {@link #of} is pure: OK only when {@link BatteryStatus#isHealthy()}
 * (background use allowed), WARN otherwise, including an unknown (null) status. Samsung's
 * Never sleeping apps list cannot be read, so it never changes the indicator.
 */
public enum BatteryIndicator {
    /** Green check: background use allowed. */
    OK(R.drawable.sendlater_ic_battery_ok, R.string.sendlater_battery_indicator_ok),
    /** Amber warning: limited, scheduled messages may be late. */
    WARN(R.drawable.sendlater_ic_battery_warn, R.string.sendlater_battery_indicator_warn);

    /** Vector drawable for this state. */
    public final int iconRes;
    /** TalkBack description for this state. */
    public final int descriptionRes;

    BatteryIndicator(int iconRes, int descriptionRes) {
        this.iconRes = iconRes;
        this.descriptionRes = descriptionRes;
    }

    public static BatteryIndicator of(BatteryStatus status) {
        return status != null && status.isHealthy() ? OK : WARN;
    }
}
