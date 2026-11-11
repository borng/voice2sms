package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Acceptance (wizard-all-steps; D60, OD46, D51): the first-install setup wizard
 * (SetupActivity + res/layout/activity_setup.xml) has a step for every required setup item that
 * {@link SetupChecks.Item} lists, each with a live status line and a fix button.
 *
 * <ul>
 *   <li>Every SetupChecks.Item maps, in SetupActivity, to a status TextView of activity_setup.xml
 *       (the item constant and the {@code R.id} of its status line in one statement, e.g. a map
 *       entry, a switch case or a status(...) call). Each item has its own status line, followed
 *       in the layout by a fix Button that SetupActivity wires.</li>
 *   <li>Wizard statuses come from the SetupChecks probes ({@code SetupChecks.live(...)}). The
 *       accessibility status therefore uses {@code A11yWarning.isServiceEnabled(c, false)}; the
 *       old AccessibilityManager list check is gone.</li>
 *   <li>Full-screen intent: its status line and button sit inside the Send Later step
 *       ({@code step_sendlater}), open {@code ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT} for this
 *       package, and are hidden below Android 14 (API 34) without hiding the Send Later step.</li>
 *   <li>Google sign-in: status from the {@code google_account} pref (SetupChecks GOOGLE_ACCOUNT),
 *       button opens GVoiceWebViewActivity; the step sits near Default SMS (before the Gemini
 *       accessibility step, outside the Send Later and Battery steps).</li>
 *   <li>Existing step order kept; missing steps show the same warning mark as setup-checks
 *       (sendlater_ic_setup_warn / sendlater_setup_warn) with "Needs attention" for TalkBack; the
 *       mark meets 3:1 on the wizard card in light and dark; new status lines start with the
 *       off status colour (4.5:1 pinned by SetupDarkThemeTest).</li>
 * </ul>
 */
public class WizardAllStepsSourceTest {

    private static final Path MAIN = resolveMain();
    private static final Path JAVA = MAIN.resolve("java").resolve("com").resolve("voice2sms");
    private static final Path RES = MAIN.resolve("res");
    private static final Path SETUP = JAVA.resolve("SetupActivity.java");
    private static final Path LAYOUT = RES.resolve("layout").resolve("activity_setup.xml");

    private static Path resolveMain() {
        Path p = Paths.get(System.getProperty("user.dir"), "src", "main");
        if (Files.isDirectory(p)) return p.toAbsolutePath();
        return Paths.get(System.getProperty("user.dir"), "app", "src", "main").toAbsolutePath();
    }

    // ------------------------------------------------------------------ every item has a step

    @Test public void everySetupChecksItemMapsToItsOwnWizardStatusLine() throws Exception {
        Map<SetupChecks.Item, String> map = statusMap();
        assertEquals("one status line per SetupChecks item", SetupChecks.Item.values().length,
                new HashSet<>(map.values()).size());
    }

    @Test public void everySetupChecksItemHasAWiredFixButtonAfterItsStatusLine() throws Exception {
        Map<SetupChecks.Item, String> map = statusMap();
        String src = code();
        assertTrue(src.contains("setOnClickListener"));
        for (SetupChecks.Item item : SetupChecks.Item.values()) {
            String button = fixButtonAfter(map.get(item), new HashSet<>(map.values()));
            assertNotNull(item + ": a Button follows status line " + map.get(item)
                    + " before the next status line", button);
            assertTrue(item + ": SetupActivity wires button " + button,
                    src.contains("R.id." + button));
        }
    }

    @Test public void wizardStatusesComeFromSetupChecksProbes() throws IOException {
        String src = code();
        assertTrue("SetupActivity reads the SetupChecks probes", src.contains("SetupChecks.live("));
        assertTrue("statuses come from Probes.isOk(item)", src.contains(".isOk("));
    }

    @Test public void accessibilityStatusUsesA11yWarningFailClosedNotAccessibilityManager() throws Exception {
        String src = code();
        assertFalse("own AccessibilityManager check removed", src.contains("getEnabledAccessibilityServiceList"));
        assertFalse("no AccessibilityManager", src.contains("AccessibilityManager"));
        assertFalse("own isAccessibilityServiceEnabled removed", src.contains("isAccessibilityServiceEnabled"));
        assertFalse("no direct Settings.Secure listing", src.contains("ENABLED_ACCESSIBILITY_SERVICES"));
        // Either straight through A11yWarning (fail closed) or through the SetupChecks probe,
        // whose ACCESSIBILITY check is A11yWarning.isServiceEnabled(c, false) (SetupChecksSourceTest).
        boolean direct = Pattern.compile("A11yWarning\\.isServiceEnabled\\(\\s*\\w+\\s*,\\s*false\\s*\\)")
                .matcher(src).find();
        boolean viaProbe = src.contains("SetupChecks.live(")
                && statusMap().containsKey(SetupChecks.Item.ACCESSIBILITY);
        assertTrue("accessibility status via A11yWarning.isServiceEnabled(c, false)", direct || viaProbe);
        assertFalse("never the fail-open one-arg form",
                Pattern.compile("A11yWarning\\.isServiceEnabled\\(\\s*\\w+\\s*\\)").matcher(src).find());
        assertFalse("never fail open",
                Pattern.compile("A11yWarning\\.isServiceEnabled\\(\\s*\\w+\\s*,\\s*true\\s*\\)").matcher(src).find());
        Matcher m = Pattern.compile("A11yWarning\\.isServiceEnabled\\(\\s*\\w+\\s*,\\s*(\\w+)\\s*\\)").matcher(src);
        while (m.find()) assertEquals("fail closed", "false", m.group(1));
    }

    // ------------------------------------------------------------------ full-screen intent

    @Test public void fullScreenIntentStepSitsInsideTheSendLaterStep() throws Exception {
        Document d = layout();
        String status = statusMap().get(SetupChecks.Item.FULL_SCREEN_INTENT);
        Element e = byId(d, status);
        assertNotNull(e);
        assertTrue("full-screen status inside step_sendlater", hasAncestor(e, "step_sendlater"));
        String button = fixButtonAfter(status, new HashSet<>(statusMap().values()));
        assertNotNull(button);
        assertTrue("full-screen button inside step_sendlater", hasAncestor(byId(d, button), "step_sendlater"));
    }

    @Test public void fullScreenIntentFixOpensTheAppFullScreenIntentScreen() throws IOException {
        String src = code();
        boolean direct = src.contains("ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT");
        boolean viaFix = Pattern.compile("fixIntent\\([^;]*FULL_SCREEN_INTENT").matcher(src).find();
        assertTrue("button opens ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT", direct || viaFix);
        if (direct) {
            assertTrue("for this package",
                    Pattern.compile("ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT[^;]*\"package:\"\\s*\\+\\s*getPackageName\\(\\)")
                            .matcher(src).find()
                    || Pattern.compile("ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT\\s*,\\s*\\w+\\s*\\)").matcher(src).find());
        }
    }

    @Test public void fullScreenIntentStepHiddenBelowApi34Only() throws Exception {
        String src = code();
        Document d = layout();
        String status = statusMap().get(SetupChecks.Item.FULL_SCREEN_INTENT);
        String button = fixButtonAfter(status, new HashSet<>(statusMap().values()));
        assertNotNull(button);
        // Views whose visibility may carry the hide: the status line, the button, or a wrapper of
        // them that is itself inside step_sendlater (never the whole Send Later step).
        Set<String> candidates = new HashSet<>();
        candidates.add(status);
        candidates.add(button);
        for (Node n = byId(d, status).getParentNode(); n instanceof Element; n = n.getParentNode()) {
            String id = idOf((Element) n);
            if ("step_sendlater".equals(id)) break;
            if (id != null) candidates.add(id);
        }
        assertTrue("API 34 guard in SetupActivity",
                Pattern.compile("(SDK_INT|sdkInt\\(\\))\\s*(>=|<)\\s*(34|Build\\.VERSION_CODES\\.UPSIDE_DOWN_CAKE|VERSION_CODES\\.UPSIDE_DOWN_CAKE)")
                        .matcher(src).find());
        boolean hides = false;
        for (String chunk : statements(src)) {
            if (!chunk.contains("setVisibility") || !chunk.contains("GONE")) continue;
            if (chunk.contains("R.id.step_sendlater")) {
                assertFalse("the Send Later step itself is not hidden by the full-screen guard",
                        chunk.contains("34") || chunk.contains("UPSIDE_DOWN_CAKE"));
            }
            for (String c : candidates) {
                if (chunk.contains("R.id." + c + ")") || chunk.contains("R.id." + c + ",")
                        || chunk.contains("R.id." + c + " ")) hides = true;
            }
        }
        // A field/local holding the view is fine too: the R.id lookup and the GONE may be split.
        if (!hides) {
            for (String c : candidates) {
                Matcher m = Pattern.compile("(\\w+)\\s*=\\s*findViewById\\(\\s*R\\.id\\." + c + "\\s*\\)").matcher(src);
                while (m.find()) {
                    if (Pattern.compile("\\b" + m.group(1) + "\\.setVisibility\\([^;]*GONE").matcher(src).find()) hides = true;
                }
            }
        }
        assertTrue("full-screen status/button (or their wrapper) set GONE below API 34", hides);
    }

    // ------------------------------------------------------------------ Google sign-in

    @Test public void googleSignInStepButtonOpensGVoiceWebViewActivity() throws IOException {
        String src = code();
        assertTrue("sign-in button opens GVoiceWebViewActivity", src.contains("GVoiceWebViewActivity.class"));
    }

    @Test public void googleSignInStatusReflectsGoogleAccountPrefViaSetupChecks() throws Exception {
        // SetupChecks.GOOGLE_ACCOUNT's probe is the google_account pref (SetupChecks.check).
        String checks = read(JAVA.resolve("sendlater").resolve("ui").resolve("SetupChecks.java"));
        assertTrue(checks.contains("\"google_account\""));
        String status = statusMap().get(SetupChecks.Item.GOOGLE_ACCOUNT);
        assertNotNull(status);
        assertTrue("sign-in status read through the SetupChecks probe", code().contains("SetupChecks.live("));
    }

    @Test public void googleSignInStepSitsNearDefaultSmsBeforeTheGeminiStep() throws Exception {
        Document d = layout();
        String status = statusMap().get(SetupChecks.Item.GOOGLE_ACCOUNT);
        Element e = byId(d, status);
        assertNotNull(e);
        assertFalse("not inside Send Later", hasAncestor(e, "step_sendlater"));
        assertFalse("not inside Battery", hasAncestor(e, "step_battery"));
        List<String> order = documentOrderIds(d);
        assertTrue("sign-in status before the accessibility (Gemini) step",
                order.indexOf(status) < order.indexOf("step3_status"));
        assertTrue("sign-in status before the ADB step", order.indexOf(status) < order.indexOf("btn_copy_commands"));
    }

    // ------------------------------------------------------------------ order, mark, colours

    @Test public void existingStepOrderKept() throws Exception {
        List<String> order = documentOrderIds(layout());
        String[] ids = {"step1_status", "btn_set_default_sms", "btn_copy_commands", "step3_status",
                "btn_enable_accessibility", "step_sendlater", "sendlater_listener_status",
                "sendlater_exact_status", "sendlater_notifications_status", "step_battery",
                "battery_status", "btn_battery_fix", "btn_done"};
        int last = -1;
        for (String id : ids) {
            int i = order.indexOf(id);
            assertTrue(id + " still present", i >= 0);
            assertTrue(id + " keeps its place", i > last);
            last = i;
        }
    }

    @Test public void missingStepsShowTheSetupChecksWarningMark() throws IOException {
        String src = code();
        String xml = read(LAYOUT);
        boolean codeDrawable = src.contains("R.drawable.sendlater_ic_setup_warn");
        boolean codeLayout = src.contains("R.layout.sendlater_setup_warn");
        boolean xmlDrawable = xml.contains("@drawable/sendlater_ic_setup_warn");
        boolean xmlInclude = xml.contains("@layout/sendlater_setup_warn");
        assertTrue("wizard uses the setup-checks warning mark",
                codeDrawable || codeLayout || xmlDrawable || xmlInclude);
        if (xmlDrawable) {
            Matcher m = Pattern.compile("(?s)<ImageView\\s[^>]*?@drawable/sendlater_ic_setup_warn[^>]*>").matcher(xml);
            while (m.find()) {
                assertTrue("warning mark read as 'Needs attention'",
                        m.group().contains("android:contentDescription=\"@string/sendlater_setup_needs_attention\""));
            }
        }
        if (codeDrawable && !codeLayout && !xmlDrawable && !xmlInclude) {
            assertTrue("warning mark read as 'Needs attention'",
                    src.contains("R.string.sendlater_setup_needs_attention"));
        }
        // shown only while missing: visibility or image switched from the item status
        assertTrue("mark toggled in code", src.contains("setVisibility") || codeDrawable || codeLayout);
    }

    @Test public void warningMarkMeets3To1OnTheWizardCardInLightAndDark() throws IOException {
        String day = allValues("values");
        String night = allValues("values-night");
        assertTrue(contrast(colour(day, "sendlater_setup_warn"), colour(day, "sendlater_setup_card_bg")) >= 3.0);
        assertTrue(contrast(colour(night, "sendlater_setup_warn"), colour(night, "sendlater_setup_card_bg")) >= 3.0);
    }

    @Test public void everyItemStatusLineStartsWithTheOffStatusColour() throws Exception {
        String xml = read(LAYOUT);
        for (Map.Entry<SetupChecks.Item, String> en : statusMap().entrySet()) {
            Matcher m = Pattern.compile("(?s)<TextView\\s[^>]*?android:id=\"@\\+id/" + en.getValue() + "\"[^>]*/>")
                    .matcher(xml);
            assertTrue(en.getKey() + " status TextView " + en.getValue(), m.find());
            assertTrue(en.getKey() + " off colour (4.5:1 pinned by SetupDarkThemeTest)",
                    m.group().contains("android:textColor=\"@color/sendlater_setup_status_off\""));
        }
    }

    @Test public void newStepsKeepTheWizardFreeOfHardCodedColours() throws IOException {
        String xml = read(LAYOUT);
        assertFalse("hex colour in activity_setup.xml", Pattern.compile("#[0-9A-Fa-f]{3,8}\\b").matcher(xml).find());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Item -> status TextView id: a SetupActivity statement naming exactly one Item constant and
     * an R.id that is a TextView in activity_setup.xml. Fails naming the first unmapped item.
     */
    private static Map<SetupChecks.Item, String> statusMap() throws Exception {
        String src = code();
        Document d = layout();
        Set<String> textViews = new HashSet<>();
        NodeList tvs = d.getElementsByTagName("TextView");
        for (int i = 0; i < tvs.getLength(); i++) {
            String id = idOf((Element) tvs.item(i));
            if (id != null) textViews.add(id);
        }
        List<String> chunks = statements(src);
        Map<SetupChecks.Item, String> out = new LinkedHashMap<>();
        for (SetupChecks.Item item : SetupChecks.Item.values()) {
            String found = null;
            for (String chunk : chunks) {
                if (!Pattern.compile("\\b" + item.name() + "\\b").matcher(chunk).find()) continue;
                int names = 0;
                for (SetupChecks.Item other : SetupChecks.Item.values()) {
                    if (Pattern.compile("\\b" + other.name() + "\\b").matcher(chunk).find()) names++;
                }
                if (names != 1) continue;
                Matcher m = Pattern.compile("R\\.id\\.(\\w+)").matcher(chunk);
                while (m.find() && found == null) {
                    if (textViews.contains(m.group(1))) found = m.group(1);
                }
                if (found != null) break;
            }
            assertNotNull("SetupActivity maps SetupChecks.Item." + item
                    + " to a status TextView of activity_setup.xml", found);
            out.put(item, found);
        }
        return out;
    }

    /** First Button (with an id) after {@code statusId} in document order, before another status. */
    private static String fixButtonAfter(String statusId, Set<String> statuses) throws Exception {
        List<Element> all = documentOrder(layout());
        boolean after = false;
        for (Element e : all) {
            String id = idOf(e);
            if (statusId.equals(id)) { after = true; continue; }
            if (!after) continue;
            if (id != null && statuses.contains(id)) return null;
            if ("Button".equals(e.getTagName()) && id != null) return id;
        }
        return null;
    }

    private static List<String> statements(String src) {
        return new ArrayList<>(Arrays.asList(src.split("[;{}]")));
    }

    private static Document layout() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        try (InputStream in = Files.newInputStream(LAYOUT)) {
            return f.newDocumentBuilder().parse(in);
        }
    }

    private static String idOf(Element e) {
        String id = e.getAttribute("android:id");
        if (id == null || id.isEmpty()) return null;
        return id.substring(id.indexOf('/') + 1);
    }

    private static Element byId(Document d, String id) {
        for (Element e : documentOrder(d)) if (id.equals(idOf(e))) return e;
        return null;
    }

    private static boolean hasAncestor(Element e, String id) {
        for (Node n = e.getParentNode(); n instanceof Element; n = n.getParentNode()) {
            if (id.equals(idOf((Element) n))) return true;
        }
        return false;
    }

    private static List<Element> documentOrder(Document d) {
        List<Element> out = new ArrayList<>();
        walk(d.getDocumentElement(), out);
        return out;
    }

    private static void walk(Element e, List<Element> out) {
        out.add(e);
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element) walk((Element) kids.item(i), out);
        }
    }

    private static List<String> documentOrderIds(Document d) {
        List<String> ids = new ArrayList<>();
        for (Element e : documentOrder(d)) ids.add(idOf(e));
        return ids;
    }

    private static String allValues(String dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (java.util.stream.Stream<Path> s = Files.list(RES.resolve(dir))) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".xml"))::iterator) {
                sb.append(read(p));
            }
        }
        return sb.toString();
    }

    private static String colour(String xml, String name) {
        Matcher m = Pattern.compile("<color name=\"" + name + "\">#(?:FF)?([0-9A-Fa-f]{6})</color>").matcher(xml);
        assertTrue("colour " + name, m.find());
        return m.group(1).toUpperCase();
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

    private static String code() throws IOException {
        return read(SETUP).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?<![:\"])//[^\\n]*", "");
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
