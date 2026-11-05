package com.voice2sms.sendlater.listener;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.core.content.ContextCompat;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * {@link ContactNames} over {@code ContactsContract.PhoneLookup} (LIVE-F12). Used only while
 * READ_CONTACTS is granted (never requested here); denial or any failure gives an empty set.
 * Synchronous: the listener calls it on its worker thread. Never logs names or numbers.
 */
public final class AndroidContactNames implements ContactNames {

    private final Context app;

    public AndroidContactNames(Context context) {
        this.app = context.getApplicationContext();
    }

    @Override public Set<String> namesFor(String e164) {
        if (e164 == null || e164.trim().isEmpty()) return Collections.emptySet();
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            return Collections.emptySet();
        }
        Uri uri;
        try {
            uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(e164));
        } catch (RuntimeException e) {
            return Collections.emptySet();
        }
        Set<String> out = query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE});
        // A provider without the alternative-name column: retry with the primary name only.
        if (out == null) out = query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME});
        return out != null ? out : Collections.<String>emptySet();
    }

    /** Every non-blank value of {@code columns} for the lookup, or null when the query failed. */
    private Set<String> query(Uri uri, String[] columns) {
        Set<String> out = new HashSet<>();
        Cursor cur = null;
        try {
            cur = app.getContentResolver().query(uri, columns, null, null, null);
            while (cur != null && cur.moveToNext()) {
                for (int i = 0; i < columns.length; i++) add(out, cur.getString(i));
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (cur != null) {
                try { cur.close(); } catch (RuntimeException ignored) { /* nothing to do */ }
            }
        }
    }

    private static void add(Set<String> out, String name) {
        if (name != null && !name.trim().isEmpty()) out.add(name);
    }
}
