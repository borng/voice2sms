package com.voice2sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * I7 privacy-logging guard for the two pre-existing classes outside sendlater/ that see message
 * text or recipient URLs (integration-gate finding F1).
 *
 * <p>In GeminiSmsInterceptService and GVoiceWebViewActivity, every {@code Log.x(...)} whose
 * arguments read accessibility text ({@code getText()}, {@code getContentDescription()}, and in
 * the service the click-site locals {@code contentDesc} / {@code text}) or a page {@code url}
 * must sit under a {@code BuildConfig.DEBUG} guard, either inline
 * ({@code if (BuildConfig.DEBUG) Log.d(...)}) or in an enclosing {@code if (BuildConfig.DEBUG)}
 * block. A URL passed through {@code withoutQuery(url)} is allowed unguarded: the query and
 * fragment (where Voice puts the E.164) are cut off.
 *
 * <p>gate-residuals (I7): in GVoiceWebViewActivity a Log whose arguments name a
 * {@code text}, {@code value} or {@code body} identifier (the V2SBridge typing requests and
 * the read-back after shell typing carry JS-supplied message text) must be guarded too.
 */
public class PrivacyLoggingSourceTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path SERVICE = MAIN.resolve("GeminiSmsInterceptService.java");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");

    private static final Pattern LOG_CALL = Pattern.compile("\\bLog\\.[vdiwe]\\s*\\(");
    private static final Pattern DEBUG_IF =
            Pattern.compile("if\\s*\\(\\s*BuildConfig\\.DEBUG\\s*\\)\\s*$");

    private static final Pattern COMMON_SENSITIVE =
            Pattern.compile("\\bgetText\\s*\\(|\\bgetContentDescription\\s*\\(");
    private static final Pattern SERVICE_SENSITIVE =
            Pattern.compile("\\bcontentDesc\\b|\\btext\\b");
    /** gate-residuals: JS-supplied text reaches the activity under these names. */
    private static final Pattern ACTIVITY_SENSITIVE =
            Pattern.compile("\\btext\\b|\\bvalue\\b|\\bbody\\b");
    private static final Pattern URL = Pattern.compile("\\burl\\b");
    private static final Pattern REDACTED_URL = Pattern.compile("\\bwithoutQuery\\s*\\(\\s*url\\s*\\)");

    @Test
    public void serviceLogsNoAccessibilityTextOutsideDebug() throws IOException {
        List<String> bad = unguardedSensitiveLogs(SERVICE, true);
        assertTrue("I7: unguarded Log of accessibility text in GeminiSmsInterceptService "
                + "(wrap in if (BuildConfig.DEBUG)): " + bad, bad.isEmpty());
    }

    @Test
    public void activityLogsNoUrlOrTextOutsideDebug() throws IOException {
        List<String> bad = unguardedSensitiveLogs(ACTIVITY, false);
        assertTrue("I7: unguarded Log of a URL or accessibility text in GVoiceWebViewActivity "
                + "(use withoutQuery(url) or wrap in if (BuildConfig.DEBUG)): " + bad, bad.isEmpty());
    }

    /** The guard must actually find the F1 sites, so it cannot pass by matching nothing. */
    @Test
    public void guardSeesTheF1Sites() throws IOException {
        String svc = strip(read(SERVICE));
        assertTrue("NOTIFICATION log site not found", svc.contains("\"NOTIFICATION: pkg=\""));
        assertTrue("Click log site not found", svc.contains("\"Click: resId=\""));
        String act = strip(read(ACTIVITY));
        assertTrue("onPageFinished log site not found", act.contains("\"onPageFinished: \""));
        assertEquals("both URL log sites must go through withoutQuery(url)", 2,
                count(REDACTED_URL, logArgsOf(act)));
    }

    /** Synthetic check that the scanner flags the pattern F1 found and accepts the fixes. */
    @Test
    public void scannerFlagsUnguardedAndAcceptsGuarded() {
        String code = "class X { void a(E event, String url) {\n"
                + "  Log.d(TAG, \"n=\" + event.getText());\n"
                + "  if (BuildConfig.DEBUG) Log.d(TAG, \"n=\" + event.getText());\n"
                + "  if (BuildConfig.DEBUG) { int k = 1; Log.d(TAG, \"d=\" + n.getContentDescription()); }\n"
                + "  Log.d(TAG, \"u=\" + url);\n"
                + "  Log.d(TAG, \"u=\" + withoutQuery(url));\n"
                + "  Log.d(TAG, \"url text getText()\");\n"
                + "} }";
        List<String> bad = scan("X", strip(code), false);
        assertEquals(bad.toString(), 2, bad.size());
        assertTrue(bad.toString(), bad.get(0).startsWith("X:2"));
        assertTrue(bad.toString(), bad.get(1).startsWith("X:5"));
    }

    /** gate-residuals: the three typing-text log sites exist, so the guard cannot pass vacuously. */
    @Test
    public void guardSeesTheTypingTextSites() throws IOException {
        String act = strip(read(ACTIVITY));
        for (String site : new String[] {"\"JS requested tap(\"", "\"JS requested native typing: \"",
                "\"Input value after shell typing: \""}) {
            assertTrue(site + " log site not found", act.contains(site));
        }
    }

    /** Mutation check: unguarding any of the three typing-text logs makes the scan flag it. */
    @Test
    public void unguardedTypingTextLogsAreFlagged() throws IOException {
        String act = strip(read(ACTIVITY));
        assertTrue(scan("A", act, false).isEmpty());
        String[] sites = {"\"JS requested tap(\"", "\"JS requested native typing: \"",
                "\"Input value after shell typing: \""};
        for (String site : sites) {
            int at = act.indexOf(site);
            int log = act.lastIndexOf("Log.d(", at);
            int guard = act.lastIndexOf("if (BuildConfig.DEBUG) ", log);
            assertTrue(site + " must sit right after an inline BuildConfig.DEBUG guard",
                    guard >= 0 && guard + "if (BuildConfig.DEBUG) ".length() == log);
            String mutated = act.substring(0, guard) + act.substring(log);
            List<String> bad = scan("A", mutated, false);
            assertEquals(site + " unguarded: " + bad, 1, bad.size());
        }
    }

    /** Synthetic: text / value / body identifiers are flagged in the activity scan unless guarded. */
    @Test
    public void activityScanFlagsTextValueBody() {
        String code = "class X { void a(String text, String value, String body) {\n"
                + "  Log.d(TAG, \"t=\" + text);\n"
                + "  Log.d(TAG, \"v=\" + value);\n"
                + "  Log.d(TAG, \"b=\" + body);\n"
                + "  if (BuildConfig.DEBUG) Log.d(TAG, \"t=\" + text);\n"
                + "  Log.d(TAG, \"text value body\");\n"
                + "} }";
        List<String> bad = scan("X", strip(code), false);
        assertEquals(bad.toString(), 3, bad.size());
    }

    // ---- scanner ----

    private static List<String> unguardedSensitiveLogs(Path p, boolean service) throws IOException {
        return scan(p.getFileName().toString(), strip(read(p)), service);
    }

    private static List<String> scan(String where, String code, boolean service) {
        List<String> bad = new ArrayList<>();
        Matcher m = LOG_CALL.matcher(code);
        while (m.find()) {
            int open = m.end() - 1;
            int close = matchingParen(code, open);
            String args = withoutStrings(code.substring(open + 1, close));
            String unredacted = REDACTED_URL.matcher(args).replaceAll("_");
            boolean sensitive = COMMON_SENSITIVE.matcher(args).find()
                    || URL.matcher(unredacted).find()
                    || (service ? SERVICE_SENSITIVE : ACTIVITY_SENSITIVE).matcher(args).find();
            if (sensitive && !debugGuarded(code, m.start())) {
                bad.add(where + ":" + lineOf(code, m.start()) + " " + args.trim());
            }
        }
        return bad;
    }

    /** Inline {@code if (BuildConfig.DEBUG) Log...} or inside an {@code if (BuildConfig.DEBUG) {}} block. */
    private static boolean debugGuarded(String code, int idx) {
        int start = statementStart(code, idx);
        if (DEBUG_IF.matcher(code.substring(start, idx)).find()) return true;
        int depth = 0;
        for (int i = idx - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == '}') depth++;
            else if (c == '{') {
                if (depth > 0) { depth--; continue; }
                int hs = statementStart(code, i);
                if (DEBUG_IF.matcher(code.substring(hs, i)).find()) return true;
            }
        }
        return false;
    }

    private static int statementStart(String code, int idx) {
        for (int i = idx - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ';' || c == '{' || c == '}') return i + 1;
        }
        return 0;
    }

    private static int matchingParen(String code, int open) {
        int depth = 0;
        boolean inStr = false;
        char q = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == q) inStr = false;
                continue;
            }
            if (c == '"' || c == '\'') { inStr = true; q = c; continue; }
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        throw new AssertionError("unbalanced parentheses at " + open);
    }

    private static String logArgsOf(String code) {
        StringBuilder sb = new StringBuilder();
        Matcher m = LOG_CALL.matcher(code);
        while (m.find()) {
            int open = m.end() - 1;
            sb.append(code, open, matchingParen(code, open) + 1).append('\n');
        }
        return sb.toString();
    }

    private static String withoutStrings(String s) {
        return s.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("'(?:\\\\.|[^'\\\\])'", "' '");
    }

    /** Blanks comments, keeping offsets and newlines, leaving string literals intact. */
    private static String strip(String code) {
        StringBuilder sb = new StringBuilder(code);
        int i = 0;
        while (i < sb.length()) {
            char c = sb.charAt(i);
            if (c == '"' || c == '\'') {
                char q = c;
                i++;
                while (i < sb.length() && sb.charAt(i) != q) {
                    if (sb.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
            } else if (c == '/' && i + 1 < sb.length() && sb.charAt(i + 1) == '/') {
                while (i < sb.length() && sb.charAt(i) != '\n') sb.setCharAt(i++, ' ');
            } else if (c == '/' && i + 1 < sb.length() && sb.charAt(i + 1) == '*') {
                while (i < sb.length() && !(sb.charAt(i) == '*' && i + 1 < sb.length() && sb.charAt(i + 1) == '/')) {
                    if (sb.charAt(i) != '\n') sb.setCharAt(i, ' ');
                    i++;
                }
                if (i < sb.length()) { sb.setCharAt(i, ' '); sb.setCharAt(i + 1, ' '); i += 2; }
            } else {
                i++;
            }
        }
        return sb.toString();
    }

    private static int count(Pattern p, String s) {
        int n = 0;
        Matcher m = p.matcher(s);
        while (m.find()) n++;
        return n;
    }

    private static int lineOf(String code, int idx) {
        int n = 1;
        for (int i = 0; i < idx; i++) if (code.charAt(i) == '\n') n++;
        return n;
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
