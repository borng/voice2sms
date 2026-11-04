package com.voice2sms.sendlater.voice.overlay;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.voice2sms.sendlater.store.ThreadDirectory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * R4 candidate lists for a Voice title, off the main thread: device contacts with that exact
 * display name (only when READ_CONTACTS is granted, A5; never requested here) and the numbers the
 * GV web thread list showed under that name.
 */
final class CandidateLookup {

    private static final String TAG = "Voice2SMS-SLV";

    private CandidateLookup() {}

    static boolean contactsGranted(Context c) {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static List<String> contacts(Context c, String title) {
        List<String> out = new ArrayList<>();
        if (title == null || !contactsGranted(c)) return out;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ?",
                    new String[]{title.trim()}, null);
            while (cur != null && cur.moveToNext()) {
                String n = cur.getString(0);
                if (n != null) out.add(n);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "contacts lookup failed: " + e.getClass());
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }

    static List<String> directory(Context c, String title) {
        try {
            List<String> l = ThreadDirectory.get(c).candidatesFor(title);
            return l != null ? l : Collections.<String>emptyList();
        } catch (RuntimeException e) {
            Log.w(TAG, "directory lookup failed: " + e.getClass());
            return Collections.emptyList();
        }
    }
}
