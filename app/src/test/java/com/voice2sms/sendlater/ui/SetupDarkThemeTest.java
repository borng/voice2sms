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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * setup-dark (D51, R42a, R43): the first-install setup wizard follows the system light/dark mode.
 * Its theme is DayNight, its layout and SetupActivity carry no hard-coded colours, every colour
 * the layout or the activity uses has a night value, status text meets 4.5:1 and the battery
 * indicator fills 3:1 on the wizard's step card in both modes. Pure source/resource checks.
 */
public class SetupDarkThemeTest {

    private static final Path MAIN = resolveMain();
    private static final Path RES = MAIN.resolve("res");
    private static final Path LAYOUT = RES.resolve("layout/activity_setup.xml");
    private static final Path ACTIVITY = MAIN.resolve("java/com/voice2sms/SetupActivity.java");

    private static Path resolveMain() {
        Path p = Paths.get(System.getProperty("user.dir"), "src", "main");
        if (Files.isDirectory(p)) return p.toAbsolutePath();
        return Paths.get(System.getProperty("user.dir"), "app", "src", "main").toAbsolutePath();
    }

    // ------------------------------------------------------------------ theme

    @Test public void setupActivityUsesTheDayNightSetupTheme() throws IOException {
        String manifest = read(MAIN.resolve("AndroidManifest.xml"));
        Matcher a = Pattern.compile("(?s)<activity\\s[^>]*?android:name=\"\\.SetupActivity\"[^>]*>").matcher(manifest);
        assertTrue(a.find());
        assertTrue(a.group().contains("android:theme=\"@style/Theme.Voice2SMS.Setup\""));

        String themes = read(RES.resolve("values/sendlater_setup_theme.xml"));
        Matcher m = Pattern.compile("<style\\s+name=\"Theme\\.Voice2SMS\\.Setup\"\\s+parent=\"([^\"]+)\"")
                .matcher(themes);
        assertTrue("Theme.Voice2SMS.Setup declared", m.find());
        assertEquals("Theme.AppCompat.DayNight.NoActionBar", m.group(1));
        assertFalse("theme not in strings.xml",
                read(RES.resolve("values/strings.xml")).contains("Theme.Voice2SMS.Setup"));
    }

    // ------------------------------------------------------------------ colours

    @Test public void layoutHasNoHardCodedOrFrameworkColours() throws IOException {
        String layout = read(LAYOUT);
        assertFalse("hex colour in activity_setup.xml",
                Pattern.compile("=\"#[0-9A-Fa-f]{3,8}\"").matcher(layout).find());
        assertFalse("light-only framework colour (white, holo_*) in activity_setup.xml",
                layout.contains("@android:color/"));
    }

    @Test public void setupActivityHasNoHardCodedColours() throws IOException {
        String src = read(ACTIVITY);
        assertFalse("0xAARRGGBB literal in SetupActivity",
                Pattern.compile("0x[0-9A-Fa-f]{8}").matcher(src).find());
        assertFalse(src.contains("Color.parseColor"));
        assertFalse(src.contains("Color.rgb") || src.contains("Color.argb"));
        assertTrue(src.contains("R.color.sendlater_setup_status_ok"));
        assertTrue(src.contains("R.color.sendlater_setup_status_off"));
    }

    @Test public void everyStatusLineStartsWithTheOffStatusColour() throws IOException {
        String layout = read(LAYOUT);
        for (String id : Arrays.asList("step1_status", "step3_status", "sendlater_listener_status",
                "sendlater_exact_status", "sendlater_notifications_status", "battery_status")) {
            Matcher m = Pattern.compile("(?s)<TextView\\s[^>]*?android:id=\"@\\+id/" + id + "\"[^>]*/>")
                    .matcher(layout);
            assertTrue(id, m.find());
            assertTrue(id + " off colour",
                    m.group().contains("android:textColor=\"@color/sendlater_setup_status_off\""));
        }
    }

    @Test public void everyColourUsedHasADayAndNightValue() throws IOException {
        Set<String> used = new LinkedHashSet<>();
        used.addAll(refs(read(LAYOUT), "@color/"));
        used.addAll(refs(read(ACTIVITY), "R.color."));
        used.addAll(refs(read(RES.resolve("values/sendlater_setup_theme.xml")), "@color/"));
        for (String d : Arrays.asList("sendlater_ic_battery_ok.xml", "sendlater_ic_battery_warn.xml")) {
            used.addAll(refs(read(RES.resolve("drawable/" + d)), "@color/"));
        }
        assertTrue(used.contains("sendlater_setup_card_bg"));
        assertTrue(used.contains("sendlater_setup_status_ok"));
        String day = dayColours();
        String night = nightColours();
        for (String c : used) {
            assertTrue("day value for @color/" + c, hasColour(day, c));
            assertTrue("night value for @color/" + c, hasColour(night, c));
        }
    }

    // ------------------------------------------------------------------ contrast

    @Test public void statusAndStepTextMeet45To1OnTheCardInBothModes() throws IOException {
        String day = dayColours();
        String night = nightColours();
        for (String t : Arrays.asList("sendlater_setup_status_ok", "sendlater_setup_status_off",
                "sendlater_setup_text_title", "sendlater_setup_text_body")) {
            assertTrue("light " + t, contrast(colour(day, t), colour(day, "sendlater_setup_card_bg")) >= 4.5);
            assertTrue("dark " + t, contrast(colour(night, t), colour(night, "sendlater_setup_card_bg")) >= 4.5);
        }
        assertTrue("dark hint", contrast(colour(night, "sendlater_setup_text_hint"),
                colour(night, "sendlater_setup_card_bg")) >= 4.5);
        assertTrue("light code", contrast(colour(day, "sendlater_setup_code_text"),
                colour(day, "sendlater_setup_code_bg")) >= 4.5);
        assertTrue("dark code", contrast(colour(night, "sendlater_setup_code_text"),
                colour(night, "sendlater_setup_code_bg")) >= 4.5);
    }

    @Test public void lightModeKeepsTheOldWizardColours() throws IOException {
        String day = dayColours();
        assertEquals("FAFAFA", colour(day, "sendlater_setup_page_bg"));
        assertEquals("FFFFFF", colour(day, "sendlater_setup_card_bg"));
        assertEquals("1A1A1A", colour(day, "sendlater_setup_text_title"));
        assertEquals("555555", colour(day, "sendlater_setup_text_body"));
        assertEquals("0099CC", colour(day, "sendlater_setup_badge_bg"));
    }

    @Test public void batteryIndicatorFillsMeet3To1OnTheCardInBothModes() throws IOException {
        String day = dayColours();
        String night = nightColours();
        for (String c : Arrays.asList("sendlater_battery_ok", "sendlater_battery_warn")) {
            assertTrue("light " + c, contrast(colour(day, c), colour(day, "sendlater_setup_card_bg")) >= 3.0);
            assertTrue("dark " + c, contrast(colour(night, c), colour(night, "sendlater_setup_card_bg")) >= 3.0);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Set<String> refs(String text, String prefix) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile(Pattern.quote(prefix) + "([A-Za-z0-9_]+)").matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static boolean hasColour(String xml, String name) {
        return Pattern.compile("<color\\s+name=\"" + Pattern.quote(name) + "\"").matcher(xml).find();
    }

    private static String dayColours() throws IOException {
        return concat(RES.resolve("values"));
    }

    private static String nightColours() throws IOException {
        return concat(RES.resolve("values-night"));
    }

    private static String concat(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (p.toString().endsWith(".xml")) sb.append(read(p));
            }
        }
        return sb.toString();
    }

    private static String colour(String xml, String name) {
        Matcher m = Pattern.compile("<color\\s+name=\"" + Pattern.quote(name)
                + "\"\\s*>\\s*#(?:FF)?([0-9A-Fa-f]{6})\\s*</color>").matcher(xml);
        assertTrue("colour " + name, m.find());
        return m.group(1).toUpperCase(Locale.ROOT);
    }

    private static double lum(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static double contrast(String a, String b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static String read(Path p) throws IOException {
        assertNotNull(p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
