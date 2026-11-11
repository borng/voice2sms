package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * D56 (H4): the About sub-screen explains what voice2sms does and how to schedule, in plain
 * words; the root row reads "About voice2sms" / "What it does · version &lt;versionName&gt;".
 * The build-stamp row (app_version) stays in the same sub-screen.
 */
public class AboutTextSourceTest {

    private static final Path RES = Paths.get("src/main/res");
    private static final Path PREFS = RES.resolve("xml/preferences.xml");
    private static final Path HOME_STRINGS = RES.resolve("values/sendlater_home.xml");
    private static final Path SETTINGS = Paths.get("src/main/java/com/voice2sms/SettingsActivity.java");

    private static Document parse(Path p) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        return f.newDocumentBuilder().parse(p.toFile());
    }

    private static Map<String, String> homeStrings() throws Exception {
        Map<String, String> m = new HashMap<>();
        NodeList list = parse(HOME_STRINGS).getElementsByTagName("string");
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            m.put(e.getAttribute("name"), e.getTextContent().replace("\\'", "'"));
        }
        return m;
    }

    private static String resolve(String ref, Map<String, String> strings) {
        if (ref.startsWith("@string/")) return strings.get(ref.substring("@string/".length()));
        return ref;
    }

    private static Element aboutScreen() throws Exception {
        NodeList screens = parse(PREFS).getElementsByTagName("PreferenceScreen");
        for (int i = 0; i < screens.getLength(); i++) {
            Element e = (Element) screens.item(i);
            if ("group_about".equals(e.getAttribute("android:key"))) return e;
        }
        throw new AssertionError("group_about sub-screen not found");
    }

    private static Element keyed(Element scope, String key) {
        NodeList all = scope.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (key.equals(e.getAttribute("android:key"))) return e;
        }
        return null;
    }

    @Test
    public void rootRowTitleIsAboutVoice2sms() throws Exception {
        Map<String, String> s = homeStrings();
        assertEquals("About voice2sms", resolve(aboutScreen().getAttribute("android:title"), s));
    }

    @Test
    public void rootSummaryIsWhatItDoesAndVersion() throws Exception {
        Map<String, String> s = homeStrings();
        assertEquals("What it does · version %1$s", s.get("sendlater_home_summary_about"));
        String src = new String(Files.readAllBytes(SETTINGS), StandardCharsets.UTF_8);
        assertTrue("root About summary uses versionName",
                src.matches("(?s).*R\\.string\\.sendlater_home_summary_about,\\s*BuildConfig\\.VERSION_NAME.*"));
    }

    @Test
    public void aboutScreenDescribesTheAppNonSelectable() throws Exception {
        Map<String, String> s = homeStrings();
        Element what = keyed(aboutScreen(), "about_what");
        assertNotNull("description row in About", what);
        assertEquals("false", what.getAttribute("android:selectable"));
        assertEquals("false", what.getAttribute("android:persistent"));
        String text = (resolve(what.getAttribute("android:title"), s) + " "
                + resolve(what.getAttribute("android:summary"), s)).toLowerCase(Locale.US);
        for (String needle : new String[]{"google voice", "default texting app", "gemini",
                "watch", "send later"}) {
            assertTrue("description mentions " + needle + ": " + text, text.contains(needle));
        }
    }

    @Test
    public void aboutScreenSaysHowToSchedule() throws Exception {
        Map<String, String> s = homeStrings();
        Element how = keyed(aboutScreen(), "about_how");
        assertNotNull("how-to row in About", how);
        assertEquals("false", how.getAttribute("android:selectable"));
        assertEquals("false", how.getAttribute("android:persistent"));
        String text = resolve(how.getAttribute("android:summary"), s).toLowerCase(Locale.US);
        for (String needle : new String[]{"conversation", "blue clock", "right edge"}) {
            assertTrue("how-to mentions " + needle + ": " + text, text.contains(needle));
        }
    }

    @Test
    public void buildStampRowStaysInAbout() throws Exception {
        assertNotNull("app_version still in About", keyed(aboutScreen(), "app_version"));
    }
}
