package com.voice2sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * sec-main-high wiring guards: every app-internal force_auto_send also attaches the
 * AutoSendAuth token, both consumers honour the flag only through AutoSendAuth.isAuthorized,
 * and the token is never logged. Comments are stripped; string literals are kept.
 */
public class AutoSendAuthSourceTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");

    private static final Pattern PUT_FORCE = Pattern.compile(
            "(\\w+)\\s*\\.\\s*putExtra\\s*\\(\\s*(?:SmsHandlerActivity\\s*\\.\\s*)?"
                    + "(?:EXTRA_FORCE_AUTO_SEND|\"force_auto_send\")\\s*,\\s*([^)]*)\\)\\s*;");

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static List<Path> mainJava() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    /** Removes comments, keeping string and char literals. */
    static String code(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i += 2;
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                char q = c;
                out.append(c);
                i++;
                while (i < n && s.charAt(i) != q) {
                    if (s.charAt(i) == '\\' && i + 1 < n) { out.append(s.charAt(i)); i++; }
                    out.append(s.charAt(i));
                    i++;
                }
                if (i < n) { out.append(s.charAt(i)); i++; }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    @Test public void everyForceAutoSendPutAlsoAttachesTheToken() throws IOException {
        List<String> sites = new ArrayList<>();
        for (Path p : mainJava()) {
            String c = code(read(p));
            Matcher m = PUT_FORCE.matcher(c);
            while (m.find()) {
                String var = m.group(1);
                sites.add(p.getFileName() + ":" + var);
                assertEquals(p + ": force_auto_send is only ever put as true", "true", m.group(2).trim());
                String next = c.substring(m.end()).trim();
                assertTrue(p + ": putExtra(force_auto_send) on " + var
                                + " must be followed by AutoSendAuth.attach(" + var + ");",
                        next.startsWith("AutoSendAuth.attach(" + var + ");"));
            }
        }
        // Gemini, Wear, RESPOND_VIA_MESSAGE and the SmsHandlerActivity forward.
        for (String expected : new String[]{"GeminiSmsInterceptService.java:sendIntent",
                "WearSmsListenerService.java:i", "RespondViaMessageService.java:webIntent",
                "SmsHandlerActivity.java:webIntent"}) {
            assertTrue("missing auto-send origin " + expected + " in " + sites, sites.contains(expected));
        }
        assertEquals("unexpected force_auto_send site: " + sites, 4, sites.size());
    }

    @Test public void smsHandlerHonoursTheFlagOnlyThroughTheToken() throws IOException {
        String c = code(read(MAIN.resolve("SmsHandlerActivity.java")));
        assertFalse("must not copy the caller's raw flag",
                c.contains("getBooleanExtra(\"force_auto_send\"")
                        || c.contains("getBooleanExtra(EXTRA_FORCE_AUTO_SEND"));
        assertFalse(c.contains("hasExtra(\"force_auto_send\")"));
        int auth = c.indexOf("boolean autoSend = AutoSendAuth.isAuthorized(src);");
        int strip = c.indexOf("AutoSendAuth.strip(src);");
        int gate = c.indexOf("if (autoSend) {");
        int put = c.indexOf("webIntent.putExtra(EXTRA_FORCE_AUTO_SEND, true);");
        assertTrue(auth >= 0 && strip > auth && gate > strip && put > gate);
        assertTrue("constant kept", c.contains("public static final String EXTRA_FORCE_AUTO_SEND = \"force_auto_send\";"));
    }

    @Test public void webViewActivityHonoursTheFlagOnlyThroughTheToken() throws IOException {
        String c = code(read(MAIN.resolve("GVoiceWebViewActivity.java")));
        Matcher m = Pattern.compile("private static boolean wantsAutoSend\\(Intent intent\\)\\s*\\{([^}]*)\\}").matcher(c);
        assertTrue(m.find());
        assertTrue(m.group(1).contains("AutoSendAuth.isAuthorized(intent)"));
        // Read once, then stripped from the activity's intent.
        assertTrue(c.contains("regularAutoSend = wantsAutoSend(getIntent());\n        AutoSendAuth.strip(getIntent());"));
        assertTrue(c.contains("regularAutoSend = wantsAutoSend(intent);\n        AutoSendAuth.strip(intent);"));
        // The only reader of force_auto_send is wantsAutoSend.
        int count = 0;
        Matcher r = Pattern.compile("getBooleanExtra\\(\\s*(?:\"force_auto_send\"|\\w*\\.?EXTRA_FORCE_AUTO_SEND)").matcher(c);
        while (r.find()) count++;
        assertEquals(1, count);
    }

    @Test public void geminiIntentCarryingTheTokenIsPackageScoped() throws IOException {
        String c = code(read(MAIN.resolve("GeminiSmsInterceptService.java")));
        int scope = c.indexOf("sendIntent.setPackage(getPackageName());");
        int attach = c.indexOf("AutoSendAuth.attach(sendIntent);");
        int start = c.indexOf("startActivity(sendIntent);");
        assertTrue(scope >= 0 && attach > scope && start > attach);
    }

    @Test public void tokenIsNeverLoggedOrExposed() throws IOException {
        for (Path p : mainJava()) {
            String c = code(read(p));
            String name = p.getFileName().toString();
            if (!name.equals("AutoSendAuth.java")) {
                assertFalse(name + " reads the raw token", c.contains("AutoSendAuth.token("));
                assertFalse(name + " reads the token extra", c.contains("AutoSendAuth.EXTRA_TOKEN"));
            }
            for (String line : c.split("\n")) {
                if (line.matches(".*\\bLog\\s*\\.\\s*\\w+\\s*\\(.*")) {
                    assertFalse(name + " logs the token: " + line.trim(),
                            line.contains("AutoSendAuth") || line.contains("EXTRA_TOKEN"));
                }
            }
        }
        String auth = code(read(MAIN.resolve("AutoSendAuth.java")));
        assertFalse(auth.contains("Log."));
        assertTrue(auth.contains("new SecureRandom()"));
        assertTrue("token accessor is not public", auth.contains("\n    static String token()"));
    }
}
