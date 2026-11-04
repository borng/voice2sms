package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.SendLaterSettings;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * wizard-defaults (R41-R43, D26-D28; R37 copy): Send Later on by default, two numbered setup
 * steps (Send Later, Battery) in the existing step style, live status lines refreshed in
 * onResume, and the battery indicator on the wizard and the Settings row. Paths are relative to
 * the app module.
 */
public class WizardDefaultsSourceTest {

    private static final Path LAYOUT = Paths.get("src/main/res/layout/activity_setup.xml");
    private static final Path SETUP = Paths.get("src/main/java/com/voice2sms/SetupActivity.java");
    private static final Path SETTINGS = Paths.get("src/main/java/com/voice2sms/SettingsActivity.java");
    private static final Path PREFS = Paths.get("src/main/res/xml/preferences.xml");
    private static final Path DRAWABLES = Paths.get("src/main/res/drawable");
    private static final Path BATTERY_STRINGS = Paths.get("src/main/res/values/sendlater_battery.xml");
    private static final Path SETTINGS_STRINGS = Paths.get("src/main/res/values/sendlater_settings.xml");

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String code(Path p) throws IOException {
        return read(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static Document parse(Path p) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        return f.newDocumentBuilder().parse(p.toFile());
    }

    /** Every element in document order. */
    private static List<Element> all(Document d) {
        NodeList nl = d.getElementsByTagName("*");
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        return out;
    }

    private static int indexOfId(List<Element> els, String id) {
        for (int i = 0; i < els.size(); i++) {
            if (("@+id/" + id).equals(els.get(i).getAttribute("android:id"))) return i;
        }
        return -1;
    }

    private static Element byId(List<Element> els, String id) {
        int i = indexOfId(els, id);
        assertTrue("layout has @+id/" + id, i >= 0);
        return els.get(i);
    }

    private static boolean inside(Element child, Element parent) {
        for (org.w3c.dom.Node n = child.getParentNode(); n != null; n = n.getParentNode()) {
            if (n == parent) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ R41 / D26

    @Test public void sendLaterIsOnByDefault() throws Exception {
        assertTrue(SendLaterSettings.DEFAULT_ENABLED);
        assertTrue("an install that never set the switch reads on",
                SendLaterSettings.Parse.enabled(new java.util.HashMap<String, Object>()));
        String prefs = read(PREFS);
        int k = prefs.indexOf("android:key=\"sendlater_enabled\"");
        assertTrue(k >= 0);
        String element = prefs.substring(prefs.lastIndexOf('<', k), prefs.indexOf("/>", k));
        assertTrue("switch defaultValue true", element.contains("android:defaultValue=\"true\""));
    }

    // ------------------------------------------------------------------ R42 / D27 layout

    @Test public void wizardHasSendLaterAndBatteryStepsAfterStepThreeInOrder() throws Exception {
        List<Element> els = all(parse(LAYOUT));
        int step3 = indexOfId(els, "step3_status");
        int sendLater = indexOfId(els, "step_sendlater");
        int battery = indexOfId(els, "step_battery");
        int done = indexOfId(els, "btn_done");
        assertTrue(step3 >= 0 && sendLater > step3 && battery > sendLater && done > battery);

        String[] ordered = {
                "step_sendlater",
                "sendlater_listener_status", "btn_sendlater_listener",
                "sendlater_exact_status", "btn_sendlater_exact",
                "sendlater_notifications_status", "btn_sendlater_notifications",
                "step_battery",
                "battery_indicator", "battery_status", "btn_battery_fix"};
        int last = -1;
        for (String id : ordered) {
            int i = indexOfId(els, id);
            assertTrue(id + " present and in order", i > last);
            last = i;
        }
        Element sl = byId(els, "step_sendlater");
        Element bt = byId(els, "step_battery");
        for (String id : new String[] {"btn_sendlater_listener", "btn_sendlater_exact",
                "btn_sendlater_notifications"}) {
            assertEquals("Button", byId(els, id).getTagName());
            assertTrue(id + " inside the Send Later step", inside(byId(els, id), sl));
        }
        assertEquals("Button", byId(els, "btn_battery_fix").getTagName());
        assertTrue(inside(byId(els, "btn_battery_fix"), bt));
        assertTrue(inside(byId(els, "battery_samsung_advice"), bt));
    }

    @Test public void newStepsUseTheExistingStepStyle() throws Exception {
        List<Element> els = all(parse(LAYOUT));
        Element step1Card = (Element) byId(els, "step1_status").getParentNode();
        for (String id : new String[] {"step_sendlater", "step_battery"}) {
            Element card = byId(els, id);
            for (String attr : new String[] {"android:background", "android:padding",
                    "android:elevation", "android:layout_marginBottom", "android:orientation"}) {
                assertEquals(id + " " + attr, step1Card.getAttribute(attr), card.getAttribute(attr));
            }
        }
        String step1Colour = byId(els, "step1_status").getAttribute("android:textColor");
        for (String id : new String[] {"sendlater_listener_status", "sendlater_exact_status",
                "sendlater_notifications_status", "battery_status"}) {
            Element t = byId(els, id);
            assertEquals(id + " is a TextView", "TextView", t.getTagName());
            assertEquals(id + " colour like step 1", step1Colour, t.getAttribute("android:textColor"));
            assertTrue(id + " text from sendlater_ strings",
                    t.getAttribute("android:text").startsWith("@string/sendlater_"));
        }
        String layout = read(LAYOUT);
        assertTrue("numbered 4", layout.contains("android:text=\"4\""));
        assertTrue("numbered 5", layout.contains("android:text=\"5\""));
    }

    @Test public void statusStringsStartWithStatus() throws Exception {
        String s = read(SETTINGS_STRINGS) + read(BATTERY_STRINGS);
        for (String name : new String[] {"sendlater_setup_listener_on", "sendlater_setup_listener_off",
                "sendlater_setup_exact_on", "sendlater_setup_exact_off",
                "sendlater_setup_notifications_on", "sendlater_setup_notifications_off",
                "sendlater_battery_setup_status_ok", "sendlater_battery_setup_status_limited"}) {
            assertTrue(name, s.contains("name=\"" + name + "\">Status: "));
        }
        assertFalse("Send Later is no longer called optional or off in the wizard",
                s.contains(">Optional: Send Later<") || s.contains("It is off until you turn it on"));
    }

    @Test public void sendLaterStepKeepsThePlainWordsReasons() throws Exception {
        List<Element> els = all(parse(LAYOUT));
        Element sl = byId(els, "step_sendlater");
        List<String> texts = new ArrayList<>();
        for (Element e : els) if (inside(e, sl)) texts.add(e.getAttribute("android:text"));
        assertTrue("why accessibility also watches Google Voice (R37)",
                texts.contains("@string/sendlater_settings_onboarding_accessibility"));
        assertTrue("why notification access is needed (R37)",
                texts.contains("@string/sendlater_settings_onboarding_listener"));
        assertTrue(texts.contains("@string/sendlater_settings_onboarding_body"));
    }

    @Test public void batteryIndicatorHasADescriptionAndBothDrawablesAreVectors() throws Exception {
        List<Element> els = all(parse(LAYOUT));
        Element img = byId(els, "battery_indicator");
        assertEquals("ImageView", img.getTagName());
        assertTrue("TalkBack description", img.getAttribute("android:contentDescription").startsWith("@string/"));
        for (String n : new String[] {"sendlater_ic_battery_ok.xml", "sendlater_ic_battery_warn.xml"}) {
            Document d = parse(DRAWABLES.resolve(n));
            assertEquals(n + " is a vector drawable", "vector", d.getDocumentElement().getTagName());
        }
    }

    // ------------------------------------------------------------------ SetupActivity wiring

    @Test public void setupWiresEachButton() throws Exception {
        String src = code(SETUP);
        assertTrue(src.contains("SendLaterListenerStatus.openSettingsIntent()"));
        assertTrue(src.contains("Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM"));
        assertTrue("exact alarms request names the package",
                src.contains("Uri.parse(\"package:\" + getPackageName())"));
        assertTrue("exact alarm request guarded to API 31+", src.contains("Build.VERSION_CODES.S"));
        assertTrue("POST_NOTIFICATIONS runtime request",
                src.contains("requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}"));
        assertTrue("guarded to API 33+", src.contains("SDK_INT >= 33"));
        assertTrue(src.contains("onRequestPermissionsResult("));
        assertTrue(src.contains("BatteryHealth.fixIntent("));
        assertTrue("Samsung advice only on Samsung", src.contains("BatteryHealth.isSamsung()"));
        assertFalse("old optional card is gone", src.contains("addSendLaterCard"));
        assertFalse("old battery note is gone", src.contains("addBatteryNote"));
    }

    @Test public void setupRefreshesEveryStatusInOnResume() throws Exception {
        String src = code(SETUP);
        int r = src.indexOf("protected void onResume()");
        assertTrue("SetupActivity has onResume", r >= 0);
        String body = src.substring(r, src.indexOf('}', r));
        assertTrue("onResume refreshes the statuses", body.contains("updateStatusIndicators()"));
        int u = src.indexOf("private void updateStatusIndicators()");
        assertTrue(u >= 0);
        String refresh = src.substring(u, src.indexOf("\n    }\n", u));
        for (String id : new String[] {"step1_status", "step3_status", "sendlater_listener_status",
                "sendlater_exact_status", "sendlater_notifications_status", "battery_status",
                "battery_indicator"}) {
            assertTrue("refresh updates " + id, refresh.contains("R.id." + id));
        }
        assertTrue("indicator chosen from the live status",
                refresh.contains("BatteryIndicator.of(") && refresh.contains("BatteryHealth.check("));
        assertTrue(refresh.contains("setContentDescription("));
    }

    // ------------------------------------------------------------------ Settings row (R43)

    @Test public void settingsBatteryRowSetsTheIndicatorIconOnResume() throws Exception {
        String src = code(SETTINGS);
        int u = src.indexOf("private void updateBatteryStatus(");
        assertTrue(u >= 0);
        String body = src.substring(u, src.indexOf("\n        }\n", u));
        assertTrue("summary unchanged", body.contains("BatteryAdvice.text(status, status.isSamsung())"));
        assertTrue("icon from the indicator", body.contains("setIcon(BatteryIndicator.of(status).iconRes)"));
        int r = src.indexOf("public void onResume()");
        assertTrue(r >= 0);
        String resume = src.substring(r, src.indexOf("\n        }\n", r));
        assertTrue("onResume refreshes the row", resume.contains("updateBatteryStatus("));
    }

    @Test public void noLeftoverStaticCardsInSetup() throws IOException {
        assertNotNull(read(SETUP));
        assertFalse(code(SETUP).contains("new LinearLayout(this)"));
    }
}
