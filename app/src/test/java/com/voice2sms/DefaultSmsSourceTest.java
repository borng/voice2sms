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
 * D54 (handoff H1): the "is voice2sms the default texting app" check lives in one helper,
 * {@link DefaultSms#isDefault}, which asks RoleManager on Android Q+ (Telephony's
 * getDefaultSmsPackage returns null on Android 16 even while the app holds the SMS role).
 */
public class DefaultSmsSourceTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path HELPER = MAIN.resolve("DefaultSms.java");
    private static final Path HOME_STRINGS = Paths.get("src/main/res/values/sendlater_home.xml");

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String code(Path p) throws IOException {
        // strip comments so a mention in a javadoc does not count as a call
        return read(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static String methodBody(String src, String name) {
        int i = src.indexOf(name + "(");
        assertTrue("method " + name + " not found", i >= 0);
        int open = src.indexOf('{', i);
        int depth = 0;
        for (int j = open; j < src.length(); j++) {
            char ch = src.charAt(j);
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return src.substring(open, j + 1);
        }
        throw new AssertionError("unbalanced braces in " + name);
    }

    @Test
    public void helperUsesRoleManagerOnQAndTelephonyOnlyBelowQ() throws IOException {
        String body = methodBody(code(HELPER), "static boolean isDefault");
        assertTrue("helper must check SDK_INT >= Q",
                body.matches("(?s).*SDK_INT\\s*>=\\s*Build\\.VERSION_CODES\\.Q.*"));
        int q = body.indexOf("VERSION_CODES.Q");
        int role = body.indexOf("isRoleHeld(RoleManager.ROLE_SMS)");
        int tel = body.indexOf("getDefaultSmsPackage(");
        assertTrue("helper must ask RoleManager.isRoleHeld(ROLE_SMS)", role > q);
        assertTrue("helper keeps Telephony as the below-Q fallback", tel > role);
    }

    @Test
    public void noOtherGetDefaultSmsPackageCallSites() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                if (p.equals(HELPER)) continue;
                if (code(p).contains("getDefaultSmsPackage")) offenders.add(p.toString());
            }
        }
        assertEquals("getDefaultSmsPackage outside DefaultSms", new ArrayList<String>(), offenders);
    }

    @Test
    public void noAdHocRoleHeldChecksOutsideHelper() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                if (p.equals(HELPER)) continue;
                if (code(p).contains("isRoleHeld(RoleManager.ROLE_SMS)")) offenders.add(p.toString());
            }
        }
        assertEquals("SMS role check outside DefaultSms", new ArrayList<String>(), offenders);
    }

    @Test
    public void callersUseHelper() throws IOException {
        for (String f : new String[] {"SettingsActivity.java", "SetupActivity.java",
                "SmsHandlerActivity.java"}) {
            assertTrue(f + " must call DefaultSms.isDefault",
                    code(MAIN.resolve(f)).contains("DefaultSms.isDefault("));
        }
    }

    @Test
    public void wordingPerD54() throws IOException {
        String xml = read(HOME_STRINGS);
        assertEquals("voice2sms is your default texting app",
                string(xml, "sendlater_home_summary_default_sms_yes"));
        assertEquals("Not your default texting app: texts from other apps won\\'t open here. Tap to fix.",
                string(xml, "sendlater_home_summary_default_sms_no"));
        assertFalse(xml.contains("Not the default SMS app"));
    }

    @Test
    public void settingsRefreshesStatusOnResume() throws IOException {
        String src = code(MAIN.resolve("SettingsActivity.java"));
        String onResume = methodBody(src, "public void onResume");
        // Home group row (refreshGroupSummaries) and the sub-screen row both recompute on resume,
        // so returning from the role dialog shows the new state.
        assertTrue(onResume.contains("refreshGroupSummaries()"));
        assertTrue(onResume.contains("updateDefaultSmsStatus("));
        assertTrue(methodBody(src, "void updateDefaultSmsStatus").contains("DefaultSms.isDefault("));
    }

    private static String string(String xml, String name) {
        Matcher m = Pattern.compile("<string name=\"" + name + "\">([^<]*)</string>").matcher(xml);
        assertTrue("missing string " + name, m.find());
        return m.group(1);
    }
}
