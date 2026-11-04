package com.voice2sms.sendlater.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.voice2sms.R;

import org.junit.Test;

/** R43 / D28: the indicator is a pure function of {@link BatteryStatus}. */
public class BatteryIndicatorTest {

    @Test public void okOnlyWhenHealthy() {
        assertEquals(BatteryIndicator.OK, BatteryIndicator.of(new BatteryStatus(false, true, false)));
    }

    @Test public void warnWhenLimitedInAnyWay() {
        assertEquals("not exempt from optimizations",
                BatteryIndicator.WARN, BatteryIndicator.of(new BatteryStatus(false, false, false)));
        assertEquals("background-restricted",
                BatteryIndicator.WARN, BatteryIndicator.of(new BatteryStatus(true, true, false)));
        assertEquals("both", BatteryIndicator.WARN, BatteryIndicator.of(new BatteryStatus(true, false, false)));
        assertEquals("unknown status", BatteryIndicator.WARN, BatteryIndicator.of(null));
    }

    @Test public void samsungNeverChangesTheIndicator() {
        assertEquals(BatteryIndicator.OK, BatteryIndicator.of(new BatteryStatus(false, true, true)));
        assertEquals(BatteryIndicator.WARN, BatteryIndicator.of(new BatteryStatus(false, false, true)));
    }

    @Test public void eachStateHasItsOwnDrawableAndDescription() {
        assertEquals(R.drawable.sendlater_ic_battery_ok, BatteryIndicator.OK.iconRes);
        assertEquals(R.drawable.sendlater_ic_battery_warn, BatteryIndicator.WARN.iconRes);
        assertEquals(R.string.sendlater_battery_indicator_ok, BatteryIndicator.OK.descriptionRes);
        assertEquals(R.string.sendlater_battery_indicator_warn, BatteryIndicator.WARN.descriptionRes);
        assertNotEquals(BatteryIndicator.OK.descriptionRes, BatteryIndicator.WARN.descriptionRes);
    }
}
