package com.voice2sms.sendlater.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide cache of the WebView's asset scripts (inject.js). Assets never change while the
 * process lives, so each one is read from the APK once instead of on every page load. The text is
 * the asset's lines, each followed by "\n" (as the activity always read it). A failed read is not
 * cached: the next call tries again. Pure JVM (the asset opener is passed in); thread-safe.
 */
public final class AssetScripts {

    /** Opens an asset by name ({@code getAssets()::open} on Android). */
    public interface Opener {
        InputStream open(String name) throws IOException;
    }

    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private AssetScripts() {}

    /** The script {@code name}, read once per process; null when it cannot be read. */
    public static String read(Opener opener, String name) {
        if (name == null) return null;
        String cached = CACHE.get(name);
        if (cached != null) return cached;
        if (opener == null) return null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(opener.open(name), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            String text = sb.toString();
            String raced = CACHE.putIfAbsent(name, text);
            return raced != null ? raced : text;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Tests only: forgets every cached script. */
    static void clear() {
        CACHE.clear();
    }
}
