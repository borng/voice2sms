package com.voice2sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Stub receiver required for default SMS app registration. GV handles actual MMS. */
public class MmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // No-op: Google Voice handles incoming MMS
    }
}
