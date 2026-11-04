package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.SendLaterIntents;
import com.voice2sms.sendlater.SendLaterSettings;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Acceptance (upcoming-settings; R35, R36, R37, AC11): source-level checks of
 * UpcomingActivity, its manifest block, the 'Send Later' category in preferences.xml, the
 * settings strings and the Settings wiring. Paths are relative to the app module.
 *
 * <p>"Settings surface" = SettingsActivity.java plus every main source under
 * sendlater/ui and sendlater/settings, except the sheet's own files (which already
 * mention POST_NOTIFICATIONS etc.). Row keys other than the store-owned
 * SendLaterSettings.KEY_* are not pinned, so rows are checked by count and by wiring.
 */
public class UpcomingSettingsSourceTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path UI = MAIN.resolve("sendlater/ui");
    private static final Path SETTINGS_PKG = MAIN.resolve("sendlater/settings");
    private static final Path UPCOMING = UI.resolve("UpcomingActivity.java");
    private static final Path SETTINGS_ACTIVITY = MAIN.resolve("SettingsActivity.java");
    private static final Path RES = Paths.get("src/main/res");
    private static final Path PREFS = RES.resolve("xml/preferences.xml");
    private static final Path SETTINGS_STRINGS = RES.resolve("values/sendlater_settings.xml");
    private static final Path STRINGS = RES.resolve("values/strings.xml");
    private static final Path MANIFEST = Paths.get("src/main/AndroidManifest.xml");

    private static final String START = "<!-- sendlater:upcoming-settings start -->";
    private static final String END = "<!-- sendlater:upcoming-settings end -->";

    // ------------------------------------------------------------------ helpers

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** Removes comments and string literals so identifiers can be checked. */
    private static String code(String s) {
        String noBlock = s.replaceAll("(?s)/\\*.*?\\*/", " ");
        String noLine = noBlock.replaceAll("//[^\\n]*", " ");
        return noLine.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    /** Removes comments only (keeps string literals). */
    private static String noComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static List<Path> javaUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    /** Comment-free source of the Settings surface (see class doc). */
    private static String settingsSurface() throws IOException {
        StringBuilder sb = new StringBuilder(noComments(read(SETTINGS_ACTIVITY)));
        List<Path> files = javaUnder(UI);
        files.addAll(javaUnder(SETTINGS_PKG));
        for (Path p : files) {
            String n = p.getFileName().toString();
            if (n.equals("SendLaterSheetActivity.java") || n.equals("SheetViewModelLogic.java")) continue;
            sb.append('\n').append(noComments(read(p)));
        }
        return sb.toString();
    }

    private static boolean has(String s, String regex) {
        return Pattern.compile(regex).matcher(s).find();
    }

    private static Document parse(Path p) throws Exception {
        assertTrue("missing " + p, Files.exists(p));
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        return f.newDocumentBuilder().parse(p.toFile());
    }

    private static Map<String, String> settingsStrings() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList list = parse(SETTINGS_STRINGS).getElementsByTagName("string");
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            out.put(e.getAttribute("name"), e.getTextContent());
        }
        return out;
    }

    /** Resolves "@string/x" against sendlater_settings.xml (then strings.xml); literals pass through. */
    private static String resolve(String v) throws Exception {
        if (v == null || !v.startsWith("@string/")) return v;
        String name = v.substring("@string/".length());
        Map<String, String> s = settingsStrings();
        if (s.containsKey(name)) return s.get(name);
        NodeList list = parse(STRINGS).getElementsByTagName("string");
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            if (name.equals(e.getAttribute("name"))) return e.getTextContent();
        }
        NodeList l2 = parse(RES.resolve("values/sendlater_listener.xml")).getElementsByTagName("string");
        for (int i = 0; i < l2.getLength(); i++) {
            Element e = (Element) l2.item(i);
            if (name.equals(e.getAttribute("name"))) return e.getTextContent();
        }
        return v;
    }

    private static List<Element> sendLaterCategories() throws Exception {
        return sendLaterCategories(parse(PREFS));
    }

    private static List<Element> sendLaterCategories(Document doc) throws Exception {
        List<Element> out = new ArrayList<>();
        NodeList cats = doc.getElementsByTagName("PreferenceCategory");
        for (int i = 0; i < cats.getLength(); i++) {
            Element c = (Element) cats.item(i);
            if ("Send Later".equals(resolve(c.getAttribute("android:title")))) out.add(c);
        }
        return out;
    }

    private static Element sendLaterCategory() throws Exception {
        return sendLaterCategory(parse(PREFS));
    }

    private static Element sendLaterCategory(Document doc) throws Exception {
        List<Element> cats = sendLaterCategories(doc);
        assertEquals("exactly one PreferenceCategory titled 'Send Later'", 1, cats.size());
        return cats.get(0);
    }

    /** All elements with an android:key anywhere in the document, keyed by key (first wins). */
    private static Map<String, List<Element>> keyed(Document d) {
        Map<String, List<Element>> out = new HashMap<>();
        NodeList all = d.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            String k = e.getAttribute("android:key");
            if (k.isEmpty()) continue;
            out.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
        }
        return out;
    }

    private static boolean isInside(Node n, Element ancestor) {
        for (Node p = n.getParentNode(); p != null; p = p.getParentNode()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private static Map<String, String> storeKeys() throws IllegalAccessException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Field f : SendLaterSettings.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("KEY_")
                    && f.getType() == String.class) {
                out.put(f.getName(), (String) f.get(null));
            }
        }
        return out;
    }

    private static Element only(Map<String, List<Element>> k, String key) {
        List<Element> l = k.get(key);
        assertNotNull("preferences.xml has no element with key " + key, l);
        assertEquals("key " + key + " declared exactly once", 1, l.size());
        return l.get(0);
    }

    // ------------------------------------------------------------------ preferences.xml (R36)

    @Test
    public void sendLaterCategoryExistsOnce() throws Exception {
        sendLaterCategory();
    }

    @Test
    public void everyStoreOwnedKeyIsInTheSendLaterCategoryExactlyOnce() throws Exception {
        Map<String, String> keys = storeKeys();
        assertEquals("SendLaterSettings exposes the 8 pinned KEY_ constants", 8, keys.size());
        // Orchestrator ruling 2026-11-04: parse once so isInside compares nodes of one Document.
        Document doc = parse(PREFS);
        Element cat = sendLaterCategory(doc);
        Map<String, List<Element>> k = keyed(doc);
        for (Map.Entry<String, String> e : keys.entrySet()) {
            Element pref = only(k, e.getValue());
            assertTrue(e.getKey() + " (" + e.getValue() + ") must sit under 'Send Later'",
                    isInside(pref, cat));
        }
    }

    @Test
    public void noSendLaterKeyOutsideTheCategory() throws Exception {
        // Orchestrator ruling 2026-11-04: parse once so isInside compares nodes of one Document.
        Document doc = parse(PREFS);
        Element cat = sendLaterCategory(doc);
        for (Map.Entry<String, List<Element>> e : keyed(doc).entrySet()) {
            if (!e.getKey().startsWith("sendlater_")) continue;
            for (Element el : e.getValue()) {
                assertTrue(e.getKey() + " is outside the 'Send Later' category", isInside(el, cat));
            }
        }
    }

    @Test
    public void storeKeysAreNotRedefinedInSettingsCode() throws Exception {
        String src = settingsSurface();
        for (String key : storeKeys().values()) {
            assertFalse("literal \"" + key + "\" in Settings code: consume SendLaterSettings.KEY_*",
                    src.contains("\"" + key + "\""));
        }
    }

    @Test
    public void switchDefaultsMatchTheStoreDefaults() throws Exception {
        Map<String, List<Element>> k = keyed(parse(PREFS));

        Element enabled = only(k, SendLaterSettings.KEY_ENABLED);
        assertTrue("enabled is a switch", enabled.getTagName().contains("Switch"));
        String ev = enabled.getAttribute("android:defaultValue");
        assertTrue("enabled defaults off", ev.isEmpty() || "false".equals(ev));

        Element hold = only(k, SendLaterSettings.KEY_HOLD_DEFAULT);
        assertTrue("hold default is a switch", hold.getTagName().contains("Switch"));
        assertEquals("'Hold if they text first' shows its real default",
                String.valueOf(SendLaterSettings.DEFAULT_HOLD), hold.getAttribute("android:defaultValue"));

        Element clear = only(k, SendLaterSettings.KEY_CLEAR_DRAFT);
        assertTrue("clear draft is a switch", clear.getTagName().contains("Switch"));
        assertEquals("'Clear draft after scheduling' shows its real default",
                String.valueOf(SendLaterSettings.DEFAULT_CLEAR_DRAFT), clear.getAttribute("android:defaultValue"));
    }

    @Test
    public void outboundPolicyIsAListOfEnumNamesDefaultingToStillSend() throws Exception {
        Element p = only(keyed(parse(PREFS)), SendLaterSettings.KEY_OUTBOUND_POLICY);
        assertTrue("outbound policy is a ListPreference", p.getTagName().endsWith("ListPreference"));
        assertEquals(SendLaterSettings.DEFAULT_OUTBOUND_POLICY.name(), p.getAttribute("android:defaultValue"));
        String values = p.getAttribute("android:entryValues");
        assertTrue("entryValues is an array resource", values.startsWith("@array/"));
        String name = values.substring("@array/".length());
        List<String> items = new ArrayList<>();
        List<Path> valueFiles;
        try (Stream<Path> s = Files.list(RES.resolve("values"))) {
            valueFiles = s.filter(x -> x.toString().endsWith(".xml")).collect(Collectors.toList());
        }
        for (Path f : valueFiles) {
            Document d = parse(f);
            NodeList arrays = d.getElementsByTagName("*");
            for (int i = 0; i < arrays.getLength(); i++) {
                Element a = (Element) arrays.item(i);
                if (!a.getTagName().endsWith("array") || !name.equals(a.getAttribute("name"))) continue;
                NodeList it = a.getElementsByTagName("item");
                for (int j = 0; j < it.getLength(); j++) items.add(it.item(j).getTextContent().trim());
            }
        }
        assertTrue("entryValues contain STILL_SEND and HOLD_AND_ASK: " + items,
                items.contains("STILL_SEND") && items.contains("HOLD_AND_ASK"));
        assertEquals(2, items.size());
    }

    @Test
    public void categoryHoldsAllSpecifiedRows() throws Exception {
        // enabled, 4 slots, outbound policy, hold default, clear draft, exact alarm,
        // notification listener, contacts, notifications, Upcoming link = 13 keyed rows.
        Element cat = sendLaterCategory();
        int keyedRows = 0;
        NodeList all = cat.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (!((Element) all.item(i)).getAttribute("android:key").isEmpty()) keyedRows++;
        }
        assertTrue("expected at least 13 keyed rows under 'Send Later', got " + keyedRows, keyedRows >= 13);
    }

    @Test
    public void existingPreferencesAreUnchanged() throws Exception {
        // key -> {tag, defaultValue, category title} as on main @ 7fa8184
        String[][] baseline = {
                {"set_default_sms", "Preference", "", "Default SMS App"},
                {"switch_account", "Preference", "", "Google Account"},
                {"gemini_intercept_toggle", "Preference", "", "Gemini Integration"},
                {"gemini_auto_send", "SwitchPreferenceCompat", "true", "Gemini Integration"},
                {"setup_wizard", "Preference", "", "Gemini Integration"},
                {"show_autosend_notification", "SwitchPreferenceCompat", "true", "Notifications"},
                {"incoming_sms_notify", "SwitchPreferenceCompat", "true", "Notifications"},
                {"app_version", "Preference", "", "About"},
        };
        Map<String, List<Element>> k = keyed(parse(PREFS));
        for (String[] b : baseline) {
            Element e = only(k, b[0]);
            assertEquals(b[0] + " type", b[1], e.getTagName());
            assertEquals(b[0] + " default", b[2], e.getAttribute("android:defaultValue"));
            Element parent = (Element) e.getParentNode();
            assertEquals(b[0] + " category", b[3], resolve(parent.getAttribute("android:title")));
        }
    }

    // ------------------------------------------------------------------ strings (R36, R37)

    @Test
    public void settingsStringsLiveInTheirOwnFileAndStringsXmlIsUntouched() throws Exception {
        Map<String, String> s = settingsStrings();
        assertFalse("sendlater_settings.xml declares strings", s.isEmpty());
        for (String name : s.keySet()) {
            assertTrue("string names are prefixed sendlater_: " + name, name.startsWith("sendlater_"));
        }
        assertTrue("'Send Later' title string", s.containsValue("Send Later"));
        String base = read(STRINGS);
        assertFalse("strings.xml must not gain sendlater_ strings", base.contains("\"sendlater_"));
        assertFalse("strings.xml must not gain Send Later copy", base.contains("Send Later"));
    }

    @Test
    public void onboardingCopyExplainsAccessibilityAndListener() throws Exception {
        String all = String.join("\n", settingsStrings().values()).toLowerCase();
        boolean accessibilityWhy = false;
        boolean listenerWhy = false;
        for (String v : settingsStrings().values()) {
            String l = v.toLowerCase();
            if (l.contains("accessibility") && l.contains("google voice")) accessibilityWhy = true;
            if (l.contains("notification") && (l.contains("reply") || l.contains("repl")
                    || l.contains("text") || l.contains("hold"))) listenerWhy = true;
        }
        assertTrue("copy explains why the accessibility service also watches Google Voice", accessibilityWhy);
        assertTrue("copy explains why notification access is needed", listenerWhy);
        assertTrue("Samsung note: Allow restricted settings", all.contains("allow restricted settings"));
    }

    @Test
    public void exactAlarmDeniedSummaryMentionsInexactFallback() throws Exception {
        String all = String.join("\n", settingsStrings().values()).toLowerCase();
        assertTrue("a summary explains the inexact-alarm fallback",
                all.contains("inexact") || all.contains("approximate"));
    }

    @Test
    public void listenerRowSummaryIsThePinnedText() throws Exception {
        String pinned = "Needs the Google Voice app with notifications on";
        String surface = settingsSurface();
        String prefs = read(PREFS);
        boolean viaConstant = has(code(surface), "SendLaterListenerStatus\\s*\\.\\s*SUMMARY");
        boolean viaListenerString = surface.contains("R.string.sendlater_listener_summary")
                || prefs.contains("@string/sendlater_listener_summary");
        boolean viaOwnString = false;
        for (Map.Entry<String, String> e : settingsStrings().entrySet()) {
            if (pinned.equals(e.getValue().trim())
                    && (surface.contains("R.string." + e.getKey())
                    || prefs.contains("@string/" + e.getKey()))) viaOwnString = true;
        }
        assertTrue("listener row summary reads '" + pinned + "'",
                viaConstant || viaListenerString || viaOwnString);
    }

    // ------------------------------------------------------------------ Settings wiring (R36, AC11)

    @Test
    public void exactAlarmRowUsesLiveStatusAndRequestIntent() throws Exception {
        String c = code(settingsSurface());
        assertTrue("live status via canScheduleExactAlarms", c.contains("canScheduleExactAlarms"));
        assertTrue("tap opens ACTION_REQUEST_SCHEDULE_EXACT_ALARM",
                c.contains("ACTION_REQUEST_SCHEDULE_EXACT_ALARM"));
    }

    @Test
    public void listenerRowUsesListenerStatusAndOpensItsSettings() throws Exception {
        String c = code(settingsSurface());
        assertTrue("live status via SendLaterListenerStatus",
                has(c, "SendLaterListenerStatus\\s*\\.\\s*is(Enabled|Available)\\s*\\("));
        assertTrue("tap opens SendLaterListenerStatus.openSettingsIntent()",
                has(c, "SendLaterListenerStatus\\s*\\.\\s*openSettingsIntent\\s*\\("));
    }

    @Test
    public void contactsAndNotificationsRowsUseLivePermissionState() throws Exception {
        String c = code(settingsSurface());
        assertTrue("contacts row checks READ_CONTACTS", c.contains("READ_CONTACTS"));
        assertTrue("notifications row checks POST_NOTIFICATIONS", c.contains("POST_NOTIFICATIONS"));
        assertTrue("permission state is read live",
                has(c, "checkSelfPermission\\s*\\(") || has(c, "areNotificationsEnabled\\s*\\("));
        assertTrue("POST_NOTIFICATIONS is requested on API 33+",
                has(c, "requestPermissions\\s*\\(") || c.contains("RequestPermission"));
    }

    @Test
    public void summariesAreRefreshedInOnResume() throws Exception {
        String c = code(read(SETTINGS_ACTIVITY));
        Matcher m = Pattern.compile("void\\s+onResume\\s*\\(\\s*\\)").matcher(c);
        assertTrue("SettingsFragment keeps an onResume", m.find());
        // The status calls must be reachable from onResume: either inline or via a method
        // whose name is called in onResume and which (transitively, one level) does the checks.
        String body = blockAfter(c, m.end());
        boolean inline = body.contains("canScheduleExactAlarms") && body.contains("SendLaterListenerStatus");
        boolean viaHelper = false;
        Matcher call = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(body);
        String surface = code(settingsSurface());
        while (call.find() && !viaHelper) {
            String name = call.group(1);
            if (name.equals("onResume") || name.equals("super")) continue;
            Matcher def = Pattern.compile("\\b(void|boolean|String|int)\\s+" + Pattern.quote(name)
                    + "\\s*\\([^)]*\\)\\s*\\{").matcher(surface);
            while (def.find()) {
                String helper = blockAfter(surface, def.end() - 1);
                if (helper.contains("canScheduleExactAlarms") || helper.contains("SendLaterListenerStatus")) {
                    viaHelper = true;
                    break;
                }
            }
        }
        assertTrue("onResume recomputes the Send Later status summaries", inline || viaHelper);
    }

    @Test
    public void slotPickersUseFrameworkTimePickerAndNoMaterialInSettings() throws Exception {
        String surface = settingsSurface();
        assertTrue("slot times use android.app.TimePickerDialog",
                surface.contains("android.app.TimePickerDialog"));
        String settings = code(read(SETTINGS_ACTIVITY));
        assertFalse("no Material widgets under Theme.AppCompat",
                read(SETTINGS_ACTIVITY).contains("com.google.android.material"));
        assertFalse("not the Material time picker", settings.contains("MaterialTimePicker"));
    }

    @Test
    public void disablingCancelsAlarmsAndBroadcastsStateChangedToOwnPackage() throws Exception {
        String c = code(settingsSurface());
        assertTrue("disabling cancels alarms through SendLaterScheduler",
                has(c, "SendLaterScheduler\\s*\\.\\s*get\\s*\\(") && has(c, "\\.\\s*cancel(All|Alarms)\\s*\\("));
        assertTrue("disabling broadcasts SendLaterIntents.ACTION_STATE_CHANGED",
                has(c, "SendLaterIntents\\s*\\.\\s*ACTION_STATE_CHANGED"));
        assertTrue("app-internal broadcast is setPackage'd", has(c, "setPackage\\s*\\("));
        assertTrue("sendBroadcast is used", has(c, "sendBroadcast\\s*\\("));
    }

    @Test
    public void disablingKeepsRecords() throws Exception {
        String c = code(settingsSurface());
        assertFalse("records are kept: no table clearing", has(c, "clearAllTables\\s*\\("));
        assertFalse("records are kept: no database deletion", has(c, "deleteDatabase\\s*\\("));
    }

    @Test
    public void settingsLinksToUpcoming() throws Exception {
        String c = code(settingsSurface());
        String prefs = read(PREFS);
        assertTrue("an 'Upcoming' row opens UpcomingActivity",
                c.contains("UpcomingActivity") || c.contains("ACTION_UPCOMING")
                        || prefs.contains("UpcomingActivity") || prefs.contains(SendLaterIntents.ACTION_UPCOMING));
    }

    // ------------------------------------------------------------------ UpcomingActivity (R35)

    @Test
    public void upcomingActivityReadsTheFilterAndUsesTheSectionsModel() throws Exception {
        String c = code(read(UPCOMING));
        assertTrue("class UpcomingActivity", has(c, "class\\s+UpcomingActivity\\s+extends\\s+\\w*Activity"));
        assertTrue("reads SendLaterIntents.EXTRA_E164", has(c, "SendLaterIntents\\s*\\.\\s*EXTRA_E164"));
        assertTrue("buckets via UpcomingSections.build", has(c, "UpcomingSections\\s*\\.\\s*build\\s*\\("));
        assertTrue("row actions via UpcomingSections.actionsFor",
                has(c, "UpcomingSections\\s*\\.\\s*actionsFor\\s*\\("));
    }

    @Test
    public void upcomingRowActionsGoThroughEngineAndSheet() throws Exception {
        String c = code(read(UPCOMING));
        assertTrue("send now = engine onSendAnyway rules", has(c, "SendLaterEngine\\s*\\.\\s*onSendAnyway\\s*\\("));
        assertTrue("cancel = engine onCancel", has(c, "SendLaterEngine\\s*\\.\\s*onCancel\\s*\\("));
        assertTrue("change time opens the sheet with ACTION_EDIT", has(c, "SendLaterIntents\\s*\\.\\s*ACTION_EDIT"));
        assertTrue("edit carries EXTRA_MESSAGE_ID", has(c, "SendLaterIntents\\s*\\.\\s*EXTRA_MESSAGE_ID"));
    }

    @Test
    public void upcomingReloadsOnStateChangedWithANotExportedReceiver() throws Exception {
        String c = code(read(UPCOMING));
        assertTrue("listens for ACTION_STATE_CHANGED", has(c, "SendLaterIntents\\s*\\.\\s*ACTION_STATE_CHANGED"));
        assertTrue("runtime receiver is RECEIVER_NOT_EXPORTED", c.contains("RECEIVER_NOT_EXPORTED"));
    }

    @Test
    public void newCodeNeverUsesForceAutoSendOrTheRateLimiter() throws Exception {
        List<String> sources = new ArrayList<>();
        sources.add(read(UPCOMING));
        for (Path p : javaUnder(UI)) {
            if (p.getFileName().toString().startsWith("Upcoming")) sources.add(read(p));
        }
        for (Path p : javaUnder(SETTINGS_PKG)) sources.add(read(p));
        for (String s : sources) {
            String c = code(s);
            assertFalse(c.contains("EXTRA_FORCE_AUTO_SEND"));
            assertFalse(s.contains("force_auto_send"));
            assertFalse(c.contains("AutoSendRateLimiter"));
        }
    }

    // ------------------------------------------------------------------ manifest block

    @Test
    public void manifestDeclaresUpcomingInItsBlockAfterSetupActivity() throws Exception {
        String m = read(MANIFEST);
        int start = m.indexOf(START);
        int end = m.indexOf(END);
        assertTrue("manifest has " + START, start >= 0);
        assertTrue("manifest has " + END, end > start);
        assertEquals("one start marker", start, m.lastIndexOf(START));

        int setup = m.indexOf("android:name=\".SetupActivity\"");
        assertTrue("SetupActivity still declared", setup >= 0);
        int gt = m.indexOf('>', setup);
        assertTrue(gt > 0);
        int elementEnd = m.charAt(gt - 1) == '/' ? gt : m.indexOf("</activity>", setup);
        assertTrue("SetupActivity element closes", elementEnd > setup);
        assertTrue("block starts after the SetupActivity element", start > elementEnd);
        String between = m.substring(elementEnd, start);
        assertFalse("block sits right after SetupActivity (no other component in between)",
                has(between, "<(activity|service|receiver|provider)\\b"));

        String block = m.substring(start, end);
        assertTrue("declares .sendlater.ui.UpcomingActivity",
                block.contains("android:name=\".sendlater.ui.UpcomingActivity\"")
                        || block.contains("android:name=\"com.voice2sms.sendlater.ui.UpcomingActivity\""));
        assertTrue("intent-filter for ACTION_UPCOMING",
                block.contains("<action android:name=\"" + SendLaterIntents.ACTION_UPCOMING + "\""));
        assertTrue("CATEGORY_DEFAULT so setPackage'd implicit starts resolve",
                block.contains("android.intent.category.DEFAULT"));
    }

    /** Body of the block that starts at the first '{' at or after {@code from}. */
    private static String blockAfter(String s, int from) {
        int open = s.indexOf('{', from);
        assertTrue("no block after index " + from, open >= 0);
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return s.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced braces");
    }
}
