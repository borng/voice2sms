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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dark-settings (D50, R36b, OD41, R43): Settings and Upcoming follow the system light/dark mode,
 * the Upcoming and home Scheduled card layouts carry no hard-coded colours, every colour they use
 * has a night value, and banner text / indicator fills meet 4.5:1 (text) and 3:1 (icon) in both
 * modes. Pure source/resource checks.
 */
public class DarkSettingsThemeTest {

    private static final Path MAIN = resolveMain();
    private static final Path RES = MAIN.resolve("res");

    private static Path resolveMain() {
        Path p = Paths.get(System.getProperty("user.dir"), "src", "main");
        if (Files.isDirectory(p)) return p.toAbsolutePath();
        return Paths.get(System.getProperty("user.dir"), "app", "src", "main").toAbsolutePath();
    }

    /** Layouts drawn by Upcoming and the home Scheduled card (the card reuses the Upcoming row). */
    private static final List<String> LAYOUTS = Arrays.asList(
            "activity_upcoming.xml", "sendlater_upcoming_row.xml",
            "sendlater_upcoming_header.xml", "sendlater_home_card.xml");

    /** Indicator drawables shown on Settings rows and the Upcoming banner. */
    private static final List<String> INDICATORS = Arrays.asList(
            "sendlater_ic_battery_ok.xml", "sendlater_ic_battery_warn.xml", "sendlater_ic_a11y_warn.xml");

    /** AppCompat window backgrounds (light #FAFAFA, dark #303030) plus white and #121212. */
    private static final String[] LIGHT_SURFACES = {"FFFFFF", "FAFAFA"};
    private static final String[] DARK_SURFACES = {"303030", "121212"};

    // ------------------------------------------------------------------ theme

    @Test public void settingsAndUpcomingUseTheDayNightSettingsTheme() throws IOException {
        String manifest = read(MAIN.resolve("AndroidManifest.xml"));
        assertEquals("@style/Theme.Voice2SMS.Settings", themeOf(manifest, ".SettingsActivity"));
        assertEquals("@style/Theme.Voice2SMS.Settings", themeOf(manifest, ".sendlater.ui.UpcomingActivity"));

        String themes = read(RES.resolve("values/sendlater_settings_theme.xml"));
        Matcher m = Pattern.compile("<style\\s+name=\"Theme\\.Voice2SMS\\.Settings\"\\s+parent=\"([^\"]+)\"")
                .matcher(themes);
        assertTrue("Theme.Voice2SMS.Settings declared", m.find());
        assertTrue("parent is DayNight: " + m.group(1), m.group(1).contains("DayNight"));
        assertTrue("keeps an action bar: " + m.group(1), !m.group(1).contains("NoActionBar"));
        assertFalse("theme not in strings.xml",
                read(RES.resolve("values/strings.xml")).contains("Theme.Voice2SMS.Settings"));
    }

    @Test public void setupWebViewAndSheetThemesUnchanged() throws IOException {
        String manifest = read(MAIN.resolve("AndroidManifest.xml"));
        // setup-dark (D51, R42a) moved the wizard to its own DayNight theme; see SetupDarkThemeTest.
        assertEquals("@style/Theme.Voice2SMS.Setup", themeOf(manifest, ".SetupActivity"));
        assertEquals("@style/Theme.AppCompat.Light.DarkActionBar", themeOf(manifest, ".GVoiceWebViewActivity"));
        assertEquals("@style/Theme.SendLater.Sheet", themeOf(manifest, ".sendlater.ui.SendLaterSheetActivity"));
    }

    // ------------------------------------------------------------------ colours

    @Test public void layoutsHaveNoHardCodedColours() throws IOException {
        Pattern hex = Pattern.compile("=\"#[0-9A-Fa-f]{3,8}\"");
        for (String l : LAYOUTS) {
            Matcher m = hex.matcher(read(RES.resolve("layout/" + l)));
            assertFalse(l + " has a hard-coded colour", m.find());
        }
    }

    @Test public void everyColourUsedHasANightValue() throws IOException {
        Set<String> used = new LinkedHashSet<>();
        for (String l : LAYOUTS) used.addAll(colourRefs(read(RES.resolve("layout/" + l))));
        for (String d : INDICATORS) used.addAll(colourRefs(read(RES.resolve("drawable/" + d))));
        assertTrue("banner colours referenced", used.contains("sendlater_upcoming_banner_bg"));
        assertTrue(used.contains("sendlater_battery_ok"));
        String night = nightColours();
        for (String c : used) {
            assertTrue("night value for @color/" + c,
                    Pattern.compile("<color\\s+name=\"" + Pattern.quote(c) + "\"").matcher(night).find());
        }
    }

    @Test public void sectionHeaderUsesAThemeSafeColourNotTheAccent() throws IOException {
        String header = read(RES.resolve("layout/sendlater_upcoming_header.xml"));
        assertTrue(header.contains("android:textColor=\"@color/sendlater_section_header\""));
    }

    // ------------------------------------------------------------------ contrast

    @Test public void bannerTextMeets45To1InBothModes() throws IOException {
        String day = dayColours();
        String night = nightColours();
        String[][] pairs = {
                {"sendlater_upcoming_banner_text", "sendlater_upcoming_banner_bg"},
                {"sendlater_a11y_banner_text", "sendlater_a11y_banner_bg"},
        };
        for (String[] p : pairs) {
            assertTrue("light " + p[0], contrast(colour(day, p[0]), colour(day, p[1])) >= 4.5);
            assertTrue("dark " + p[0], contrast(colour(night, p[0]), colour(night, p[1])) >= 4.5);
        }
        // a11y warning disc on its own banner: 3:1 (icon).
        assertTrue(contrast(colour(day, "sendlater_a11y_warn"), colour(day, "sendlater_a11y_banner_bg")) >= 3.0);
        assertTrue(contrast(colour(night, "sendlater_a11y_warn"), colour(night, "sendlater_a11y_banner_bg")) >= 3.0);
    }

    @Test public void sectionHeaderTextMeets45To1InBothModes() throws IOException {
        String d = colour(dayColours(), "sendlater_section_header");
        String n = colour(nightColours(), "sendlater_section_header");
        for (String s : LIGHT_SURFACES) assertTrue("light header on " + s, contrast(d, s) >= 4.5);
        for (String s : DARK_SURFACES) assertTrue("dark header on " + s, contrast(n, s) >= 4.5);
    }

    @Test public void indicatorFillsMeet3To1AndKeepWhiteGlyphContrast() throws IOException {
        String day = dayColours();
        String night = nightColours();
        for (String c : Arrays.asList("sendlater_battery_ok", "sendlater_battery_warn", "sendlater_a11y_warn")) {
            String d = colour(day, c);
            String n = colour(night, c);
            for (String s : LIGHT_SURFACES) assertTrue("light " + c + " on " + s, contrast(d, s) >= 3.0);
            for (String s : DARK_SURFACES) assertTrue("dark " + c + " on " + s, contrast(n, s) >= 3.0);
            // values-night resolves for the always-light setup wizard too (system dark mode),
            // so the night fill must also hold 3:1 on the wizard's white card.
            for (String s : LIGHT_SURFACES) assertTrue("night " + c + " on light " + s, contrast(n, s) >= 3.0);
            assertTrue("white glyph on light " + c, contrast("FFFFFF", d) >= 3.0);
            assertTrue("white glyph on dark " + c, contrast("FFFFFF", n) >= 3.0);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String themeOf(String manifest, String activity) {
        Matcher m = Pattern.compile("(?s)<activity\\s[^>]*?android:name=\"" + Pattern.quote(activity) + "\"[^>]*>")
                .matcher(manifest);
        assertTrue("activity " + activity, m.find());
        Matcher t = Pattern.compile("android:theme=\"([^\"]+)\"").matcher(m.group());
        assertTrue("theme on " + activity, t.find());
        return t.group(1);
    }

    private static Set<String> colourRefs(String xml) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("@color/([A-Za-z0-9_]+)").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String dayColours() throws IOException {
        return read(RES.resolve("values/sendlater_colors.xml")) + read(RES.resolve("values/sendlater_a11y.xml"));
    }

    private static String nightColours() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (java.util.stream.Stream<Path> s = Files.list(RES.resolve("values-night"))) {
            for (Path p : (Iterable<Path>) s::iterator) sb.append(read(p));
        }
        return sb.toString();
    }

    private static String colour(String xml, String name) {
        Matcher m = Pattern.compile("<color\\s+name=\"" + Pattern.quote(name) + "\"\\s*>\\s*#(?:FF)?([0-9A-Fa-f]{6})\\s*</color>")
                .matcher(xml);
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
