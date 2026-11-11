package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.SendLaterSettings;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Implementer's check (upcoming-settings, R36): every sendlater_* key in preferences.xml,
 * including the 8 store-owned SendLaterSettings.KEY_*, sits inside the single 'Send Later'
 * category. Category and keys are looked up in ONE parsed document, so ancestor checks
 * compare nodes of the same tree.
 */
public class UpcomingPreferencesPlacementTest {

    private static Document prefs() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        return f.newDocumentBuilder().parse(new File("src/main/res/xml/preferences.xml"));
    }

    private static Element category(Document d) {
        List<Element> out = new ArrayList<>();
        NodeList cats = d.getElementsByTagName("PreferenceCategory");
        for (int i = 0; i < cats.getLength(); i++) {
            Element c = (Element) cats.item(i);
            if ("@string/sendlater_settings_category".equals(c.getAttribute("android:title"))) out.add(c);
        }
        assertEquals(1, out.size());
        return out.get(0);
    }

    private static boolean inside(Node n, Element ancestor) {
        for (Node p = n.getParentNode(); p != null; p = p.getParentNode()) if (p == ancestor) return true;
        return false;
    }

    private static List<Element> withKey(Document d, String key) {
        List<Element> out = new ArrayList<>();
        NodeList all = d.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (key.equals(e.getAttribute("android:key"))) out.add(e);
        }
        return out;
    }

    @Test
    public void storeKeysSitOnceInsideTheCategory() throws Exception {
        Document d = prefs();
        Element cat = category(d);
        int n = 0;
        for (Field f : SendLaterSettings.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !f.getName().startsWith("KEY_")) continue;
            String key = (String) f.get(null);
            List<Element> els = withKey(d, key);
            assertEquals(key, 1, els.size());
            assertTrue(key + " inside Send Later", inside(els.get(0), cat));
            n++;
        }
        assertEquals(8, n);
    }

    @Test
    public void everySendLaterKeyIsInsideTheCategory() throws Exception {
        Document d = prefs();
        Element cat = category(d);
        NodeList all = d.getElementsByTagName("*");
        int seen = 0;
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            String k = e.getAttribute("android:key");
            if (!k.startsWith("sendlater_") || e == cat) continue;
            assertNotNull(k);
            assertTrue(k + " inside Send Later", inside(e, cat));
            seen++;
        }
        assertTrue(seen >= 13);
    }
}
