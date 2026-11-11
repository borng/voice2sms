package com.voice2sms;

import android.app.role.RoleManager;
import android.content.Context;
import android.os.Build;
import android.provider.Telephony;

/**
 * The one place that answers "is voice2sms the default texting app" (D54).
 *
 * <p>On Android Q+ the SMS role is the source of truth. Telephony.Sms.getDefaultSmsPackage
 * returns null on some Android 16 devices even while this app holds android.app.role.SMS, so
 * it is only consulted below Q, where the role does not exist.
 */
public final class DefaultSms {

    private DefaultSms() {}

    public static boolean isDefault(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager rm = c.getSystemService(RoleManager.class);
                return rm != null && rm.isRoleHeld(RoleManager.ROLE_SMS);
            }
            return c.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(c));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
