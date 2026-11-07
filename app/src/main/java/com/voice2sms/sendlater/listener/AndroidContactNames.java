package com.voice2sms.sendlater.listener;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.core.content.ContextCompat;

import com.voice2sms.sendlater.store.RecipientNames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link ContactNames} over {@code ContactsContract.PhoneLookup} (LIVE-F12). Used only while
 * READ_CONTACTS is granted (never requested here); denial or any failure gives an empty set.
 * Synchronous: the listener calls it on its worker thread. Never logs names or numbers.
 *
 * <p>D33: also the {@link RecipientNames} the repository fills a missing display name from at
 * save time (the first DISPLAY_NAME, see {@link ContactDisplayName}).
 */
public final class AndroidContactNames implements ContactNames, RecipientNames {

    /**
     * Set once a lookup with DISPLAY_NAME_ALTERNATIVE threw and the primary-only retry worked:
     * later lookups then make one query, not two. Process-wide (the provider does not change).
     */
    private static volatile boolean alternativeUnsupported;

    private final Context app;

    public AndroidContactNames(Context context) {
        this.app = context.getApplicationContext();
    }

    /** D33: the first DISPLAY_NAME saved for {@code e164}; null on denial, no contact or failure. */
    @Override public String displayNameFor(String e164) {
        return ContactDisplayName.lookup(new ContactDisplayName.Resolver() {
            @Override public boolean readContactsGranted() {
                return granted();
            }

            @Override public List<String> displayNames(String number) {
                Uri uri = lookupUri(number);
                if (uri == null) return null;
                Set<String> names = query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME});
                return names == null ? null : new ArrayList<>(names);
            }
        }, e164);
    }

    @Override public Set<String> namesFor(String e164) {
        if (e164 == null || e164.trim().isEmpty()) return Collections.emptySet();
        if (!granted()) return Collections.emptySet();
        Uri uri = lookupUri(e164);
        if (uri == null) return Collections.emptySet();
        Set<String> out = alternativeUnsupported ? null
                : query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME,
                        ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE});
        if (out == null) {
            // A provider without the alternative-name column: the primary name only.
            out = query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME});
            // Remember it only when the primary-only query then worked, so a transient failure of
            // both does not drop alternative names for the rest of the process.
            if (out != null) alternativeUnsupported = true;
        }
        return out != null ? out : Collections.<String>emptySet();
    }

    private boolean granted() {
        return ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static Uri lookupUri(String e164) {
        try {
            return Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(e164));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Every non-blank value of {@code columns} for the lookup in row order, or null when the
     * query failed.
     */
    private Set<String> query(Uri uri, String[] columns) {
        Set<String> out = new LinkedHashSet<>();
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
