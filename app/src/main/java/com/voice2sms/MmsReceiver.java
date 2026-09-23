package com.voice2sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Stub MMS receiver.
 *
 * KNOWN GAP: while Voice2SMS is the default SMS app, incoming MMS (picture
 * messages, group MMS, long SMS the carrier transcodes into MMS) are LOST.
 * The platform delivers a single WAP_PUSH_DELIVER notification PDU exclusively
 * to the default app; the actual content lives on the MMSC and must be fetched
 * via HTTP using the carrier's MMS APN, then persisted to content://mms +
 * content://mms/part. None of that happens here, and it is non-trivial to
 * implement (no minimal one-row equivalent of the SMS path).
 *
 * Tradeoff for v1: SMS receive is fixed ({@link SmsReceiver}); MMS receive
 * stays a black hole until someone implements the full WAP-push / HTTP-GET
 * flow or wires in a library like klinker41/android-smsmms.
 */
public class MmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // No-op. See class doc.
    }
}
