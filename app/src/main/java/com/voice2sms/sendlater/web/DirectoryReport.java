package com.voice2sms.sendlater.web;

import com.voice2sms.sendlater.voice.logic.PhoneNumbers;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-list scrape sent by inject.js to {@code V2SBridge.onThreadDirectory}:
 * {@code [{displayName, e164}, ...]} (R4). Entries whose number is not E.164 or whose name is
 * blank are dropped; a display name is never turned into a number here.
 */
public final class DirectoryReport {

    public static final int MAX_ENTRIES = 300;

    private DirectoryReport() {}

    /** One (name, number) pair seen in the GV web thread list. */
    public static final class Entry {
        public final String displayName;
        public final String e164;

        Entry(String displayName, String e164) {
            this.displayName = displayName;
            this.e164 = e164;
        }
    }

    /** Valid entries (possibly empty); empty for malformed input. */
    public static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        if (json == null) return out;
        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (JSONException e) {
            return out;
        }
        for (int i = 0; i < arr.length() && out.size() < MAX_ENTRIES; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Object n = o.opt("displayName");
            Object e = o.opt("e164");
            if (!(n instanceof String) || !(e instanceof String)) continue;
            String name = ((String) n).trim();
            String num = (String) e;
            if (name.isEmpty() || !PhoneNumbers.isE164(num)) continue;
            out.add(new Entry(name, num));
        }
        return out;
    }
}
