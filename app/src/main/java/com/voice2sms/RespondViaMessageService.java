package com.voice2sms;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Stub service required for default SMS app registration. */
public class RespondViaMessageService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // No-op: Google Voice handles respond-via-message
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
