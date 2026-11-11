package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Acceptance (setup-checks; D59, OD44, OD45): wiring of SetupChecks into the launcher page and
 * the group sub-screens, checked from sources and resources.
 *
 * <ul>
 *   <li>SettingsActivity tests accessibility only through
 *       {@code A11yWarning.isServiceEnabled(c, false)}; its own isAccessibilityServiceEnabled
 *       (AccessibilityManager list) is gone.</li>
 *   <li>The probes reuse the existing checks (DefaultSms.isDefault, A11yWarning,
 *       SendLaterListenerStatus.isEnabled, notificationsAllowed/exactAlarmsAllowed, battery,
 *       full-screen intent, google_account pref, SendLaterSettings.isEnabled) and the fix intents
 *       reuse the existing screens. "SetupChecks family" = SetupChecks.java plus every main Java
 *       file that names SetupChecks.</li>
 *   <li>A home group row with a missing item shows a warning icon at the row end in place of the
 *       chevron (content description "Needs attention") and its summary "Needs: &lt;labels&gt;";
 *       OK rows keep the chevron. The same icon marks the failing row in a sub-screen, whose tap
 *       opens SetupChecks.fixIntent. Everything is rechecked from onResume.</li>
 *   <li>The warning icon's fill meets 3:1 on the light (#FFFFFF, #FAFAFA) and dark (#303030,
 *       #121212) Settings surfaces.</li>
 * </ul>
 * Strings live in res/values/sendlater_*.xml (never strings.xml).
 */
public class SetupChecksSourceTest {

    private static final Path MAIN = resolveMain();
    private static final Path JAVA = MAIN.resolve("java").resolve("com").resolve("voice2sms");
    private static final Path RES = MAIN.resolve("res");
    private static final Path SETTINGS = JAVA.resolve("SettingsActivity.java");
    private static final Path SETUP_CHECKS = JAVA.resolve("sendlater").resolve("ui").resolve("SetupChecks.java");

    private static final String[] LIGHT_SURFACES = {"FFFFFF", "FAFAFA"};
    private static final String[] DARK_SURFACES = {"303030", "121212"};

    private static Path resolveMain() {
        Path p = Paths.get(System.getProperty("user.dir"), "src", "main");
        if (Files.isDirectory(p)) return p.toAbsolutePath();
        return Paths.get(System.getProperty("user.dir"), "app", "src", "main").toAbsolutePath();
    }

    // ------------------------------------------------------------------ accessibility test

    @Test public void settingsActivityChecksAccessibilityOnlyThroughA11yWarningFailClosed() throws IOException {
        String s = stripComments(read(SETTINGS));
        assertFalse("own isAccessibilityServiceEnabled removed", s.contains("isAccessibilityServiceEnabled"));
        assertFalse("no AccessibilityManager list check", s.contains("getEnabledAccessibilityServiceList"));
        assertFalse("no AccessibilityManager", s.contains("AccessibilityManager"));
        assertFalse("no direct Settings.Secure listing", s.contains("ENABLED_ACCESSIBILITY_SERVICES"));
        assertTrue("uses A11yWarning.isServiceEnabled(c, false)",
                Pattern.compile("A11yWarning\\.isServiceEnabled\\(\\s*\\w+\\s*,\\s*false\\s*\\)").matcher(s).find());
        assertEveryA11yCallFailsClosed("SettingsActivity", s);
    }

    @Test public void setupChecksFamilyChecksAccessibilityFailClosed() throws IOException {
        String fam = family();
        assertTrue("probe uses A11yWarning.isServiceEnabled", fam.contains("A11yWarning.isServiceEnabled("));
        assertEveryA11yCallFailsClosed("SetupChecks family", fam);
        assertFalse(fam.contains("getEnabledAccessibilityServiceList"));
    }

    // ------------------------------------------------------------------ probes and fixes

    @Test public void probesReuseTheExistingChecks() throws IOException {
        assertTrue("SetupChecks.java exists at " + SETUP_CHECKS, Files.exists(SETUP_CHECKS));
        String fam = family();
        String[] needed = {
                "DefaultSms.isDefault(", "A11yWarning.isServiceEnabled(", "SendLaterListenerStatus.isEnabled(",
                "notificationsAllowed(", "exactAlarmsAllowed(", "\"google_account\"", "SendLaterSettings.isEnabled(",
        };
        for (String n : needed) assertTrue("SetupChecks family uses " + n, fam.contains(n));
        assertTrue("battery probe uses BatteryHealth/BatteryIndicator",
                fam.contains("BatteryHealth.check(") || fam.contains("BatteryIndicator.of("));
        assertTrue("full-screen-intent probe uses the existing canUseFullScreenIntent logic",
                fam.contains("canUseFullScreenIntent("));
        assertFalse("no second default-SMS check", fam.contains("getDefaultSmsPackage"));
    }

    @Test public void fixIntentsReuseTheExistingScreens() throws IOException {
        assertTrue("SetupChecks.java exists at " + SETUP_CHECKS, Files.exists(SETUP_CHECKS));
        String sc = stripComments(read(SETUP_CHECKS));
        assertTrue("declares fixIntent(Context, Item)",
                Pattern.compile("Intent\\s+fixIntent\\(\\s*(?:final\\s+)?(?:android\\.content\\.)?Context\\s+\\w+\\s*,\\s*(?:final\\s+)?Item\\s+\\w+\\s*\\)")
                        .matcher(sc).find());
        String fam = family();
        assertTrue("accessibility fix = A11yWarning.settingsIntent()", fam.contains("A11yWarning.settingsIntent()"));
        assertTrue("listener fix = SendLaterListenerStatus.openSettingsIntent()",
                fam.contains("SendLaterListenerStatus.openSettingsIntent()"));
        assertTrue("battery fix = BatteryHealth.fixIntent", fam.contains("BatteryHealth.fixIntent("));
    }

    // ------------------------------------------------------------------ home rows

    @Test public void homeRowWithAMissingItemShowsWarningIconAndNeedsSummary() throws IOException {
        String attention = stringNamed("Needs attention");
        String needs = needsSummaryString();
        String warnLayout = warningLayout(attention);

        String fam = family();
        assertTrue("warning widget layout applied from code: R.layout." + warnLayout,
                fam.contains("R.layout." + warnLayout));
        assertTrue("OK rows get the chevron back: R.layout.sendlater_home_chevron",
                fam.contains("R.layout.sendlater_home_chevron"));
        assertTrue("summary 'Needs: <labels>' from R.string." + needs, fam.contains("R.string." + needs));

        String settings = stripComments(read(SETTINGS));
        String summaries = reach(settings, "refreshGroupSummaries");
        assertTrue("group summaries / attention computed with SetupChecks.missing",
                summaries.contains("SetupChecks.missing("));
    }

    @Test public void sendLaterAttentionNoLongerHandBuilt() throws IOException {
        String body = methodBody(stripComments(read(SETTINGS)), "refreshGroupSummaries");
        assertFalse("the hand-built attention expression (missed the listener) is replaced by SetupChecks",
                Pattern.compile("attention\\s*=\\s*!batteryOk").matcher(body).find());
    }

    // ------------------------------------------------------------------ rows in sub-screens

    @Test public void failingRowInsideAGroupIsMarkedAndOpensItsFix() throws IOException {
        String settings = stripComments(read(SETTINGS));
        String fam = family();
        assertTrue("SettingsActivity uses SetupChecks", settings.contains("SetupChecks"));
        assertTrue("sub-screen finds the failing row with SetupChecks.rowKey", fam.contains("SetupChecks.rowKey("));
        assertTrue("tapping the failing row opens SetupChecks.fixIntent", fam.contains("fixIntent("));
        Matcher m = Pattern.compile("fixIntent\\(").matcher(settings);
        boolean calledFromUi = m.find();
        if (!calledFromUi) {
            // allow a helper in sendlater/ui to make the call
            for (Path p : familyFiles()) {
                if (p.equals(SETUP_CHECKS)) continue;
                if (stripComments(read(p)).contains("SetupChecks.fixIntent(")) calledFromUi = true;
            }
        }
        assertTrue("fixIntent is called from the Settings UI", calledFromUi);
        assertTrue("a fix is launched with startActivity", fam.contains("startActivity("));
    }

    @Test public void checksRunAgainOnResume() throws IOException {
        String settings = stripComments(read(SETTINGS));
        String onResume = methodBody(settings, "onResume");
        assertTrue("SettingsFragment.onResume reaches SetupChecks (directly or one call down)",
                reach(settings, "onResume").contains("SetupChecks"));
        assertTrue(onResume.contains("refreshGroupSummaries()"));
    }

    // ------------------------------------------------------------------ contrast

    @Test public void warningIconMeets3To1InLightAndDark() throws IOException {
        String attention = stringNamed("Needs attention");
        String layout = warningLayout(attention);
        String drawable = drawableOf(read(RES.resolve("layout/" + layout + ".xml")));
        String xml = read(RES.resolve("drawable/" + drawable + ".xml"));
        Matcher fill = Pattern.compile("android:fillColor=\"([^\"]+)\"").matcher(xml);
        assertTrue("warning drawable has a fill", fill.find());
        String ref = fill.group(1);
        String day;
        String night;
        if (ref.startsWith("@color/")) {
            String name = ref.substring(7);
            day = colour(allValues("values"), name);
            String n = findColour(allValues("values-night"), name);
            night = n != null ? n : day;
        } else {
            Matcher h = Pattern.compile("#(?:FF)?([0-9A-Fa-f]{6})").matcher(ref);
            assertTrue("fill is a colour resource or hex: " + ref, h.matches());
            day = night = h.group(1).toUpperCase(Locale.ROOT);
        }
        for (String s : LIGHT_SURFACES) assertTrue("light fill " + day + " on " + s, contrast(day, s) >= 3.0);
        for (String s : DARK_SURFACES) assertTrue("dark fill " + night + " on " + s, contrast(night, s) >= 3.0);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertEveryA11yCallFailsClosed(String where, String src) {
        Matcher m = Pattern.compile("A11yWarning\\.isServiceEnabled\\(([^()]*)\\)").matcher(src);
        while (m.find()) {
            String args = m.group(1);
            assertTrue(where + ": A11yWarning.isServiceEnabled(" + args + ") must pass failOpen=false",
                    Pattern.compile(",\\s*false\\s*$").matcher(args.trim()).find());
        }
    }

    /** Name of the sendlater_*.xml string whose value is exactly {@code value}. */
    private static String stringNamed(String value) throws IOException {
        Matcher m = Pattern.compile("<string\\s+name=\"([A-Za-z0-9_]+)\"[^>]*>\\s*" + Pattern.quote(value)
                + "\\s*</string>").matcher(sendlaterStrings());
        assertTrue("a res/values/sendlater_*.xml string reads \"" + value + "\"", m.find());
        return m.group(1);
    }

    /** The "Needs: %1$s" summary string. */
    private static String needsSummaryString() throws IOException {
        Matcher m = Pattern.compile("<string\\s+name=\"([A-Za-z0-9_]+)\"[^>]*>\\s*Needs: %(?:1\\$)?s\\s*</string>")
                .matcher(sendlaterStrings());
        assertTrue("a res/values/sendlater_*.xml string reads \"Needs: %1$s\"", m.find());
        return m.group(1);
    }

    private static String sendlaterStrings() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.list(RES.resolve("values"))) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.startsWith("sendlater_") && n.endsWith(".xml")) sb.append(read(p));
            }
        }
        return sb.toString();
    }

    /** The layout whose image carries contentDescription=@string/{attention} and a drawable. */
    private static String warningLayout(String attention) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> s = Files.list(RES.resolve("layout"))) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String xml = read(p);
                if (xml.contains("android:contentDescription=\"@string/" + attention + "\"")
                        && drawableOrNull(xml) != null) {
                    assertFalse(p.getFileName() + ": the warning icon is not hidden from TalkBack",
                            xml.contains("importantForAccessibility=\"no\""));
                    String n = p.getFileName().toString();
                    hits.add(n.substring(0, n.length() - 4));
                }
            }
        }
        assertFalse("a layout shows the warning icon with contentDescription @string/" + attention, hits.isEmpty());
        return hits.get(0);
    }

    private static String drawableOrNull(String xml) {
        Matcher m = Pattern.compile("(?:android:src|app:srcCompat)=\"@drawable/([A-Za-z0-9_]+)\"").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static String drawableOf(String xml) {
        String d = drawableOrNull(xml);
        assertNotNull("warning layout references a drawable", d);
        return d;
    }

    /** SetupChecks.java plus every main Java file that names SetupChecks. */
    private static List<Path> familyFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        if (Files.exists(SETUP_CHECKS)) out.add(SETUP_CHECKS);
        try (Stream<Path> s = Files.walk(JAVA)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!p.toString().endsWith(".java") || p.equals(SETUP_CHECKS)) continue;
                if (read(p).contains("SetupChecks")) out.add(p);
            }
        }
        return out;
    }

    private static String family() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path p : familyFiles()) sb.append(stripComments(read(p))).append('\n');
        return sb.toString();
    }

    /** Body of the first method named {@code name} (brace-matched). */
    private static String methodBody(String src, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*(?:throws[^{]*)?\\{").matcher(src);
        assertTrue("method " + name, m.find());
        int i = m.end();
        int depth = 1;
        while (i < src.length() && depth > 0) {
            char c = src.charAt(i++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return src.substring(m.end(), i);
    }

    /** The method's body plus the bodies of the methods it calls (one level, same file). */
    private static String reach(String src, String name) {
        String body = methodBody(src, name);
        StringBuilder sb = new StringBuilder(body);
        Set<String> called = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\b([a-z][A-Za-z0-9_]*)\\s*\\(").matcher(body);
        while (m.find()) called.add(m.group(1));
        for (String c : called) {
            if (c.equals(name)) continue;
            Matcher d = Pattern.compile("(?:private|static|void|boolean|public|protected)[^;{=]*\\b"
                    + Pattern.quote(c) + "\\s*\\([^)]*\\)\\s*\\{").matcher(src);
            if (d.find()) sb.append('\n').append(methodBody(src.substring(d.start()), c));
        }
        return sb.toString();
    }

    private static String allValues(String dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.list(RES.resolve(dir))) {
            for (Path p : (Iterable<Path>) s::iterator) sb.append(read(p));
        }
        return sb.toString();
    }

    private static String findColour(String xml, String name) {
        Matcher m = Pattern.compile("<color\\s+name=\"" + Pattern.quote(name)
                + "\"\\s*>\\s*#(?:FF)?([0-9A-Fa-f]{6})\\s*</color>").matcher(xml);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static String colour(String xml, String name) {
        String c = findColour(xml, name);
        assertNotNull("colour " + name, c);
        return c;
    }

    private static double lum(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    static double contrast(String a, String b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test public void contrastHelperMatchesKnownRatios() {
        // sanity: white on black = 21:1; existing a11y warn disc (#C5221F) on white > 3:1
        assertEquals(21.0, contrast("FFFFFF", "000000"), 0.01);
        assertTrue(contrast("C5221F", "FFFFFF") >= 3.0);
    }
}
