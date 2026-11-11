package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Acceptance (home-scheduled; R35a, R36a, R36, R43, D44, D47, D48, OD37, OD38, AC15):
 * source and resource checks of the launcher page (SettingsActivity).
 *
 * <p><b>Grouped settings (R36a).</b> {@code res/xml/preferences.xml} stays the root screen. Its
 * top-level rows (elements not nested in a sub-PreferenceScreen, PreferenceCategory wrappers
 * ignored) include, in this order, rows titled Send Later, Default SMS app, Google account,
 * Gemini, Notifications, About (case-insensitive; a title may continue, e.g. "Gemini
 * integration"). Other top-level rows (such as the card) are allowed. Every preference key that
 * existed before this node is still declared exactly once across the preference XMLs
 * ({@code res/xml/*.xml} whose root is a PreferenceScreen), never as a top-level row of the root,
 * with the same element type, defaultValue and persistent flag. A key's "sub-screen" is its
 * nearest PreferenceScreen ancestor: a nested screen inside preferences.xml, or the root of
 * another XML file. Either implementation (nested screens with onPreferenceStartScreen /
 * rootKey, or one PreferenceFragmentCompat per group) passes.
 *
 * <p><b>Scheduled card (R35a).</b> "Card loader files" = main sources that reference
 * {@code ScheduledCardModel} (other than the model itself). Every store read there
 * ({@code allPending(}, {@code allHeld(}) and every {@code ScheduledCardModel.build(} call runs
 * inside a background block ({@code .execute(}, {@code .submit(} or {@code new Thread(} on a
 * non-main executor).
 *
 * <p><b>Pinned interface (for pending-notification, R45).</b>
 * {@code public static final String SettingsActivity.EXTRA_SHOW_SCHEDULED =
 * "com.voice2sms.extra.SHOW_SCHEDULED"} (boolean extra: true = show the launcher page with the
 * Scheduled messages card in view).
 */
public class HomeScheduledSourceTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path SETTINGS_ACTIVITY = MAIN.resolve("SettingsActivity.java");
    private static final Path MODEL = MAIN.resolve("sendlater/ui/ScheduledCardModel.java");
    private static final Path RES = Paths.get("src/main/res");
    private static final Path XML_DIR = RES.resolve("xml");
    private static final Path ROOT_PREFS = XML_DIR.resolve("preferences.xml");
    private static final Path VALUES = RES.resolve("values");

    static final String EXTRA_NAME = "EXTRA_SHOW_SCHEDULED";
    static final String EXTRA_VALUE = "com.voice2sms.extra.SHOW_SCHEDULED";

    /** The six group rows, in order (R36a). */
    private static final List<String> GROUP_TITLES = Arrays.asList(
            "send later", "default sms app", "google account", "gemini", "notifications", "about");

    /** Every key in preferences.xml before this node, with its element type. */
    private static final Map<String, String> FORMER = new LinkedHashMap<>();
    /** Keys that were android:persistent="false" (D36: the master switch is not backed up). */
    private static final Set<String> NOT_PERSISTENT = new HashSet<>();
    /** android:defaultValue of keys that had one. */
    private static final Map<String, String> DEFAULTS = new HashMap<>();
    /** Expected group of each former key (setup_wizard: any group). */
    private static final Map<String, String> GROUP_OF = new HashMap<>();

    static {
        String P = "Preference", S = "SwitchPreferenceCompat", L = "ListPreference";
        put("set_default_sms", P, "default sms app");
        put("switch_account", P, "google account");
        put("gemini_intercept_toggle", P, "gemini");
        put("gemini_auto_send", S, "gemini");
        put("setup_wizard", P, null);
        put("show_autosend_notification", S, "notifications");
        put("incoming_sms_notify", S, "notifications");
        put("sendlater_enabled", S, "send later");
        put("sendlater_upcoming", P, "send later");
        put("sendlater_slot_morning", P, "send later");
        put("sendlater_slot_afternoon", P, "send later");
        put("sendlater_slot_evening", P, "send later");
        put("sendlater_slot_night", P, "send later");
        put("sendlater_outbound_policy", L, "send later");
        put("sendlater_hold_default", S, "send later");
        put("sendlater_clear_draft", S, "send later");
        put("sendlater_exact_alarm", P, "send later");
        put("sendlater_notifications", P, "send later");
        put("sendlater_accessibility", P, "send later");
        put("sendlater_listener", P, "send later");
        put("sendlater_contacts", P, "send later");
        put("sendlater_help", P, "send later");
        put("sendlater_battery", P, "send later");
        put("app_version", P, "about");

        NOT_PERSISTENT.addAll(Arrays.asList("sendlater_enabled", "sendlater_slot_morning",
                "sendlater_slot_afternoon", "sendlater_slot_evening", "sendlater_slot_night",
                "sendlater_exact_alarm", "sendlater_notifications", "sendlater_accessibility",
                "sendlater_listener", "sendlater_contacts", "sendlater_help", "sendlater_battery"));

        DEFAULTS.put("gemini_auto_send", "true");
        DEFAULTS.put("show_autosend_notification", "true");
        DEFAULTS.put("incoming_sms_notify", "true");
        DEFAULTS.put("sendlater_enabled", "true");
        DEFAULTS.put("sendlater_outbound_policy", "STILL_SEND");
        DEFAULTS.put("sendlater_hold_default", "true");
        DEFAULTS.put("sendlater_clear_draft", "true");
    }

    private static void put(String key, String tag, String group) {
        FORMER.put(key, tag);
        GROUP_OF.put(key, group);
    }

    /** Former keys that had a click/change handler or live summary wired in Java. */
    private static final List<String> WIRED = Arrays.asList(
            "set_default_sms", "switch_account", "gemini_intercept_toggle", "gemini_auto_send",
            "setup_wizard", "sendlater_enabled", "sendlater_upcoming", "sendlater_slot_morning",
            "sendlater_slot_afternoon", "sendlater_slot_evening", "sendlater_slot_night",
            "sendlater_exact_alarm", "sendlater_notifications", "sendlater_accessibility",
            "sendlater_listener", "sendlater_contacts", "sendlater_battery", "app_version");

    // ------------------------------------------------------------------ helpers

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String noComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\\n]*", " ");
    }

    /** Blanks string literal contents (keeps the quotes and the length) so parens inside do not count. */
    private static String blankStrings(String s) {
        Matcher m = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            char[] c = new char[m.group().length()];
            Arrays.fill(c, ' ');
            c[0] = '"';
            c[c.length - 1] = '"';
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(c)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<Path> mainJava() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    private static Map<Path, String> mainSources() throws IOException {
        Map<Path, String> out = new LinkedHashMap<>();
        for (Path p : mainJava()) out.put(p, noComments(read(p)));
        return out;
    }

    private static Document parse(Path p) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        return f.newDocumentBuilder().parse(p.toFile());
    }

    /** Preference XMLs: res/xml files whose document element is PreferenceScreen. */
    private static Map<Path, Document> preferenceXmls() throws Exception {
        Map<Path, Document> out = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(XML_DIR)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".xml")).sorted().collect(Collectors.toList())) {
                Document d = parse(p);
                String root = d.getDocumentElement().getTagName();
                if (root.equals("PreferenceScreen") || root.endsWith(".PreferenceScreen")) out.put(p, d);
            }
        }
        assertTrue("preferences.xml is a preference screen", out.containsKey(ROOT_PREFS));
        return out;
    }

    private static boolean isScreen(Node n) {
        if (!(n instanceof Element)) return false;
        String t = ((Element) n).getTagName();
        return t.equals("PreferenceScreen") || t.endsWith(".PreferenceScreen");
    }

    private static boolean isCategory(Element e) {
        String t = e.getTagName();
        return t.equals("PreferenceCategory") || t.endsWith(".PreferenceCategory");
    }

    /** Nearest PreferenceScreen ancestor (the sub-screen holding the element). */
    private static Element container(Element e) {
        for (Node p = e.getParentNode(); p != null; p = p.getParentNode()) if (isScreen(p)) return (Element) p;
        return null;
    }

    private static String key(Element e) {
        return e.getAttribute("android:key");
    }

    /** Top-level rows of the root screen, in document order. */
    private static List<Element> rootRows(Document root) {
        List<Element> out = new ArrayList<>();
        Element top = root.getDocumentElement();
        NodeList all = top.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (isCategory(e)) continue;
            if (container(e) == top) out.add(e);
        }
        return out;
    }

    private static Map<String, String> allStrings() throws Exception {
        Map<String, String> out = new HashMap<>();
        try (Stream<Path> s = Files.list(VALUES)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".xml")).collect(Collectors.toList())) {
                NodeList l = parse(p).getElementsByTagName("string");
                for (int i = 0; i < l.getLength(); i++) {
                    Element e = (Element) l.item(i);
                    out.put(e.getAttribute("name"), e.getTextContent());
                }
            }
        }
        return out;
    }

    private static String resolve(String v, Map<String, String> strings) {
        if (v == null) return "";
        if (v.startsWith("@string/")) {
            String r = strings.get(v.substring("@string/".length()));
            return r == null ? v : r;
        }
        return v;
    }

    private static String norm(String s) {
        return s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Index of the group whose title the row carries, or -1. */
    private static int groupIndex(Element row, Map<String, String> strings) {
        String t = norm(resolve(row.getAttribute("android:title"), strings));
        for (int i = 0; i < GROUP_TITLES.size(); i++) {
            if (t.equals(GROUP_TITLES.get(i)) || t.startsWith(GROUP_TITLES.get(i) + " ")) return i;
        }
        return -1;
    }

    /** The six group rows of the root, in order; fails if one is missing or out of order. */
    private static List<Element> groupRows() throws Exception {
        return groupRows(preferenceXmls());
    }

    /** Same, over already-parsed XMLs (DOM nodes are compared by identity). */
    private static List<Element> groupRows(Map<Path, Document> xmls) throws Exception {
        Document root = xmls.get(ROOT_PREFS);
        Map<String, String> strings = allStrings();
        Element[] found = new Element[GROUP_TITLES.size()];
        List<Integer> order = new ArrayList<>();
        for (Element row : rootRows(root)) {
            int g = groupIndex(row, strings);
            if (g < 0) continue;
            assertTrue("two root rows titled " + GROUP_TITLES.get(g), found[g] == null);
            found[g] = row;
            order.add(g);
        }
        for (int i = 0; i < found.length; i++) {
            assertNotNull("root screen has no top-level row titled '" + GROUP_TITLES.get(i)
                    + "' (R36a)", found[i]);
        }
        assertEquals("group rows in the order Send Later, Default SMS app, Google account, Gemini,"
                + " Notifications, About", Arrays.asList(0, 1, 2, 3, 4, 5), order);
        return Arrays.asList(found);
    }

    /** All declarations of {@code key} across the preference XMLs. */
    private static List<Element> declarations(Map<Path, Document> xmls, String key, Map<Element, Path> where) {
        List<Element> out = new ArrayList<>();
        for (Map.Entry<Path, Document> x : xmls.entrySet()) {
            NodeList all = x.getValue().getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Element e = (Element) all.item(i);
                if (key.equals(key(e))) {
                    out.add(e);
                    if (where != null) where.put(e, x.getKey());
                }
            }
        }
        return out;
    }

    /** Names of Java constants declared as {@code NAME = "value"} in main sources. */
    private static Set<String> constantsFor(String value, Map<Path, String> src) {
        Set<String> out = new HashSet<>();
        Pattern p = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"" + Pattern.quote(value) + "\"");
        for (String s : src.values()) {
            Matcher m = p.matcher(s);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    /** Files (comment-free source) that use {@code key}: as a literal outside a constant declaration, or through a constant. */
    private static List<Path> usersOfKey(String key, Map<Path, String> src) {
        Set<String> consts = constantsFor(key, src);
        List<Path> out = new ArrayList<>();
        Pattern decl = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*\"" + Pattern.quote(key) + "\"");
        for (Map.Entry<Path, String> e : src.entrySet()) {
            String s = decl.matcher(e.getValue()).replaceAll(" ");
            boolean used = s.contains("\"" + key + "\"");
            for (String c : consts) {
                if (Pattern.compile("\\b" + Pattern.quote(c) + "\\b").matcher(s).find()) used = true;
            }
            if (used) out.add(e.getKey());
        }
        return out;
    }

    private static Map<Path, String> cardLoaders() throws IOException {
        Map<Path, String> out = new LinkedHashMap<>();
        for (Map.Entry<Path, String> e : mainSources().entrySet()) {
            if (e.getKey().equals(MODEL)) continue;
            if (e.getValue().contains("ScheduledCardModel")) out.put(e.getKey(), e.getValue());
        }
        assertFalse("no main source uses ScheduledCardModel: the launcher page has no Scheduled"
                + " messages card (R35a)", out.isEmpty());
        return out;
    }

    private static String allOf(Map<Path, String> files) {
        StringBuilder sb = new StringBuilder();
        for (String s : files.values()) sb.append(s).append('\n');
        return sb.toString();
    }

    /** True when {@code pos} sits inside the argument list of a background-run call. */
    private static boolean insideBackgroundBlock(String code, int pos) {
        Pattern start = Pattern.compile("(\\.execute\\s*\\(|\\.submit\\s*\\(|new\\s+Thread\\s*\\()");
        Matcher m = start.matcher(code);
        while (m.find()) {
            int open = m.end() - 1;
            if (open >= pos) break;
            String before = code.substring(Math.max(0, m.start() - 80), m.start());
            if (before.contains("MainExecutor") || before.contains("mainExecutor")
                    || before.contains("getMainLooper")) continue;
            int depth = 0;
            for (int i = open; i < pos; i++) {
                char c = code.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                if (depth == 0) break;
            }
            if (depth > 0) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ R36a: grouped settings

    @Test
    public void rootScreenHasTheSixGroupRowsInOrder() throws Exception {
        groupRows();
    }

    @Test
    public void noFormerSettingIsATopLevelRowOfTheRoot() throws Exception {
        Document root = preferenceXmls().get(ROOT_PREFS);
        for (Element row : rootRows(root)) {
            assertFalse("'" + key(row) + "' must live in its group's sub-screen, not on the root"
                    + " screen (R36a)", FORMER.containsKey(key(row)));
        }
    }

    @Test
    public void everyFormerKeyIsDeclaredOnceWithTheSameTypeAndDefaults() throws Exception {
        Map<Path, Document> xmls = preferenceXmls();
        Document root = xmls.get(ROOT_PREFS);
        for (Map.Entry<String, String> k : FORMER.entrySet()) {
            List<Element> d = declarations(xmls, k.getKey(), null);
            assertEquals(k.getKey() + " declared exactly once across the preference XMLs", 1, d.size());
            Element e = d.get(0);
            String tag = e.getTagName();
            assertTrue(k.getKey() + " keeps its type " + k.getValue() + " (was), now " + tag,
                    tag.equals(k.getValue()) || tag.endsWith("." + k.getValue()));
            assertNotEquals(k.getKey() + " sits in a sub-screen, not on the root",
                    root.getDocumentElement(), container(e));
            if (NOT_PERSISTENT.contains(k.getKey())) {
                assertEquals(k.getKey() + " stays android:persistent=\"false\"", "false",
                        e.getAttribute("android:persistent"));
            }
            if (DEFAULTS.containsKey(k.getKey())) {
                assertEquals(k.getKey() + " keeps its defaultValue", DEFAULTS.get(k.getKey()),
                        e.getAttribute("android:defaultValue"));
            }
        }
    }

    @Test
    public void formerKeysArePartitionedIntoSixGroupSubScreens() throws Exception {
        Map<Path, Document> xmls = preferenceXmls();
        List<Element> groups = groupRows(xmls);
        Map<String, Element> containerOfGroup = new HashMap<>();
        for (Map.Entry<String, String> k : GROUP_OF.entrySet()) {
            if (k.getValue() == null) continue;
            List<Element> d = declarations(xmls, k.getKey(), null);
            assertEquals(k.getKey() + " declared once", 1, d.size());
            Element c = container(d.get(0));
            Element prev = containerOfGroup.get(k.getValue());
            if (prev == null) {
                containerOfGroup.put(k.getValue(), c);
            } else {
                assertTrue(k.getKey() + " shares the '" + k.getValue() + "' sub-screen with the"
                        + " rest of its group", prev == c);
            }
        }
        assertEquals(6, containerOfGroup.size());
        assertEquals("six distinct sub-screens, one per group",
                6, new HashSet<>(containerOfGroup.values()).size());

        Element wizard = declarations(xmls, "setup_wizard", null).get(0);
        assertTrue("the setup wizard row sits in one of the group sub-screens",
                containerOfGroup.containsValue(container(wizard)));

        // A group row that is itself a nested PreferenceScreen must be the sub-screen of its group.
        for (int i = 0; i < groups.size(); i++) {
            Element row = groups.get(i);
            if (isScreen(row)) {
                assertTrue("nested screen '" + GROUP_TITLES.get(i) + "' holds that group's keys",
                        containerOfGroup.get(GROUP_TITLES.get(i)) == row);
            }
        }
    }

    @Test
    public void everyGroupRowHasAKeyAndALiveSummaryRefreshedOnResume() throws Exception {
        Map<Path, String> src = mainSources();
        Set<String> keys = new HashSet<>();
        List<Element> groups = groupRows();
        for (int i = 0; i < GROUP_TITLES.size(); i++) {
            Element row = groups.get(i);
            String k = key(row);
            assertFalse("group row '" + GROUP_TITLES.get(i) + "' needs an android:key so its"
                    + " summary can be set live", k.isEmpty());
            assertTrue("group row keys are distinct", keys.add(k));
            List<Path> users = usersOfKey(k, src);
            assertFalse("group row '" + k + "' is not referenced from Java: its one-line summary"
                    + " is not live (R36a)", users.isEmpty());
            boolean summarised = false;
            for (Path p : users) {
                String s = src.get(p);
                if ((s.contains("setSummary(") || s.contains("SummaryProvider"))
                        && s.contains("onResume")) summarised = true;
            }
            assertTrue("group row '" + k + "': the file that uses it sets a summary and refreshes"
                    + " in onResume (R36a live summary)", summarised);
        }
    }

    @Test
    public void everyFormerlyWiredKeyIsStillWired() throws Exception {
        groupRows(); // the hierarchy is grouped (fails before R36a lands)
        Map<Path, String> src = mainSources();
        for (String k : WIRED) {
            assertFalse("'" + k + "' lost its Java wiring (click handler / live summary)",
                    usersOfKey(k, src).isEmpty());
        }
        String all = allOf(src);
        assertTrue("SendLaterSettingsController still drives the Send Later rows (R36)",
                all.contains("new SendLaterSettingsController("));
        assertTrue("the Send Later rows still refresh live", Pattern.compile(
                "(?s)void\\s+onResume\\s*\\(\\s*\\)\\s*\\{.{0,800}?\\.refresh\\s*\\(\\s*\\)").matcher(all).find());
        assertTrue("battery row indicator kept (R43)", all.contains("BatteryIndicator.of("));
        assertTrue("build stamp long-press copy kept", all.contains("setPrimaryClip(")
                && all.contains("BuildConfig.GIT_SHA"));
        assertTrue("setup wizard row still opens SetupActivity", all.contains("SetupActivity.class"));
    }

    // ------------------------------------------------------------------ R35a: the card

    @Test
    public void cardReadsTheStoreOffTheMainThread() throws Exception {
        Map<Path, String> loaders = cardLoaders();
        int reads = 0;
        for (Map.Entry<Path, String> e : loaders.entrySet()) {
            String code = blankStrings(e.getValue());
            Matcher m = Pattern.compile("\\.allPending\\s*\\(|\\.allHeld\\s*\\(|ScheduledCardModel\\s*\\.\\s*build\\s*\\(")
                    .matcher(code);
            while (m.find()) {
                reads++;
                assertTrue(e.getKey().getFileName() + ": '" + m.group() + "' at offset " + m.start()
                        + " runs on the main thread; the card reads the Room store in a background"
                        + " block (execute/submit/new Thread)", insideBackgroundBlock(code, m.start()));
            }
            assertFalse("no allowMainThreadQueries", code.contains("allowMainThreadQueries"));
        }
        assertTrue("the card reads SCHEDULED and HELD records from the store", reads > 0);
        String all = allOf(loaders);
        assertTrue("the card loads from SendLaterRepository", all.contains("SendLaterRepository"));
        assertTrue("results are handed back to the UI thread", all.contains("runOnUiThread(")
                || all.contains(".post(") || all.contains("getMainExecutor") || all.contains("postValue("));
    }

    @Test
    public void cardRefreshesOnResumeAndOnStoreChange() throws Exception {
        String all = allOf(cardLoaders());
        assertTrue("card reloads in onResume", all.contains("onResume"));
        assertTrue("card listens for store changes (ACTION_STATE_CHANGED)",
                all.contains("ACTION_STATE_CHANGED"));
        assertTrue("runtime receiver is not exported", all.contains("RECEIVER_NOT_EXPORTED"));
        assertTrue("receiver is unregistered", all.contains("unregisterReceiver("));
    }

    @Test
    public void cardRowsAndSeeAllRouteToTheSheetOrUpcoming() throws Exception {
        String all = allOf(cardLoaders());
        assertTrue("row taps decide through ScheduledCardModel.tapFor", all.contains("tapFor("));
        assertTrue("SCHEDULED row opens the sheet in edit mode",
                all.contains("ACTION_EDIT") && all.contains("EXTRA_MESSAGE_ID"));
        assertTrue("app-internal intent is package-scoped", all.contains("setPackage("));
        assertTrue("HELD row and See all open UpcomingActivity", all.contains("UpcomingActivity"));
        assertTrue("card shows the Send Later master switch state",
                all.contains("SendLaterSettings.isEnabled("));
    }

    @Test
    public void cardStringsExistOutsideStringsXml() throws Exception {
        String all = allOf(cardLoaders());
        Map<String, String> strings = new HashMap<>();
        Map<String, String> plurals = new HashMap<>();
        try (Stream<Path> s = Files.list(VALUES)) {
            for (Path p : s.filter(x -> x.getFileName().toString().startsWith("sendlater_")
                    && x.toString().endsWith(".xml")).collect(Collectors.toList())) {
                Document d = parse(p);
                NodeList l = d.getElementsByTagName("string");
                for (int i = 0; i < l.getLength(); i++) {
                    Element e = (Element) l.item(i);
                    strings.put(e.getAttribute("name"), e.getTextContent());
                }
                NodeList pl = d.getElementsByTagName("plurals");
                for (int i = 0; i < pl.getLength(); i++) {
                    Element e = (Element) pl.item(i);
                    plurals.put(e.getAttribute("name"), e.getTextContent());
                }
            }
        }
        assertTrue("a sendlater_*.xml string reads exactly \"Scheduled messages\" (card title)",
                strings.containsValue("Scheduled messages"));

        boolean seeAll = false, empty = false;
        Matcher m = Pattern.compile("R\\.(string|plurals)\\.([A-Za-z0-9_]+)").matcher(all);
        while (m.find()) {
            String name = m.group(2);
            String text = "string".equals(m.group(1)) ? strings.get(name) : plurals.get(name);
            if (text == null) continue;
            if (name.contains("see_all") && text.contains("%") && text.contains("d")) seeAll = true;
            if (name.contains("empty")) empty = true;
        }
        assertTrue("the card uses a sendlater_* 'see_all' string/plural with a count placeholder"
                + " for \"See all (N)\"", seeAll);
        assertTrue("the card uses a sendlater_* '...empty...' string for its empty state", empty);
    }

    @Test
    public void cardNeverLogsMessageTextOrNames() throws Exception {
        for (Map.Entry<Path, String> e : cardLoaders().entrySet()) {
            Matcher m = Pattern.compile("(?s)\\bLog\\.[a-z]+\\s*\\((.{0,300}?)\\);").matcher(e.getValue());
            while (m.find()) {
                String args = m.group(1);
                assertFalse(e.getKey().getFileName() + " logs message content: " + m.group(),
                        args.contains("getText(") || args.contains("getRecipientDisplayName(")
                                || args.contains("firstLine(") || args.contains("getRecipientE164("));
            }
        }
    }

    // ------------------------------------------------------------------ pinned extra (R45)

    @Test
    public void settingsActivityPinsTheShowScheduledExtra() throws Exception {
        Class<?> c = Class.forName("com.voice2sms.SettingsActivity", false,
                HomeScheduledSourceTest.class.getClassLoader());
        Field f;
        try {
            f = c.getField(EXTRA_NAME);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("SettingsActivity." + EXTRA_NAME + " missing (pending-notification"
                    + " opens the launcher page's Scheduled messages card with it)");
        }
        int mod = f.getModifiers();
        assertTrue("public static final", Modifier.isPublic(mod) && Modifier.isStatic(mod)
                && Modifier.isFinal(mod));
        assertEquals(String.class, f.getType());
        String src = noComments(read(SETTINGS_ACTIVITY));
        assertTrue("SettingsActivity." + EXTRA_NAME + " = \"" + EXTRA_VALUE + "\"",
                Pattern.compile("\\b" + EXTRA_NAME + "\\s*=\\s*\"" + Pattern.quote(EXTRA_VALUE) + "\"")
                        .matcher(src).find());
    }

    @Test
    public void settingsActivityActsOnTheShowScheduledExtra() throws Exception {
        String src = noComments(read(SETTINGS_ACTIVITY));
        Matcher m = Pattern.compile("getBooleanExtra\\s*\\(\\s*" + EXTRA_NAME + "\\b").matcher(src);
        if (!m.find()) {
            fail("SettingsActivity reads getBooleanExtra(" + EXTRA_NAME + ", ...) to bring the"
                    + " Scheduled messages card into view");
        }
    }
}
