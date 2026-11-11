package com.voice2sms.sendlater.battery;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

/**
 * Reads whether the phone may hold back voice2sms's alarms (R38). Read-only: nothing here changes
 * a setting; {@link #fixIntent} only opens the screen where the user can change it.
 */
public final class BatteryHealth {

    private BatteryHealth() {}

    public static BatteryStatus check(Context context) {
        Context c = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        boolean restricted = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            restricted = am != null && am.isBackgroundRestricted();
        }
        boolean ignoring = false;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null) ignoring = pm.isIgnoringBatteryOptimizations(c.getPackageName());
        return new BatteryStatus(restricted, ignoring, isSamsung());
    }

    public static boolean isSamsung() {
        String m = Build.MANUFACTURER;
        return m != null && m.toLowerCase(Locale.ROOT).contains("samsung");
    }

    /**
     * The screen that fixes the battery status, first one that resolves: the ignore-battery-
     * optimizations request for this app (skipped when already exempt, where it would do
     * nothing), the battery optimization list, then this app's details page (where a
     * background restriction is lifted). Null only if none of the three resolves.
     */
    public static Intent fixIntent(Context context) {
        Context c = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        PackageManager pm = c.getPackageManager();
        Uri pkg = Uri.parse("package:" + c.getPackageName());
        boolean ignoring = false;
        PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (power != null) ignoring = power.isIgnoringBatteryOptimizations(c.getPackageName());

        Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg);
        if (!ignoring && pm.resolveActivity(request, 0) != null) return newTask(request);

        Intent list = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        if (!ignoring && pm.resolveActivity(list, 0) != null) return newTask(list);

        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
        if (pm.resolveActivity(details, 0) != null) return newTask(details);

        return null;
    }

    private static Intent newTask(Intent i) {
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
