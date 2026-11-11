package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Acceptance (setup-checks; D59, OD44, OD45): which required setup items are missing for which
 * launcher-page group, decided by pure logic over fake probes.
 *
 * <p>Pinned API (com.voice2sms.sendlater.ui.SetupChecks; the logic below runs on the JVM, no
 * android.* code executes):
 * <pre>
 *   enum Item { DEFAULT_SMS, ACCESSIBILITY, NOTIFICATION_LISTENER, POST_NOTIFICATIONS,
 *               EXACT_ALARMS, BATTERY, FULL_SCREEN_INTENT, GOOGLE_ACCOUNT }
 *   interface Probes {
 *       boolean sendLaterEnabled();      // SendLaterSettings.isEnabled on device
 *       int sdkInt();                    // Build.VERSION.SDK_INT on device
 *       boolean isOk(Item item);         // the existing check for that item on device
 *   }
 *   static List&lt;Item&gt; missing(Probes probes, String groupKey)
 *       // the items that belong to that home group, apply now, and whose probe says not OK;
 *       // no duplicates; order not asserted
 *   static String rowKey(Item item, String groupKey)
 *       // preference key of the row for that item inside that group's sub-screen, or null
 *   static android.content.Intent fixIntent(android.content.Context c, Item item)  // source test
 * </pre>
 * Group keys are the existing home group row keys (home-scheduled contract): group_send_later,
 * group_default_sms, group_google_account, group_gemini, group_notifications, group_about.
 *
 * <p>Group membership follows where each item's row already lives: DEFAULT_SMS in Default SMS;
 * GOOGLE_ACCOUNT in Google account; ACCESSIBILITY in Send Later and in Gemini;
 * NOTIFICATION_LISTENER, POST_NOTIFICATIONS, EXACT_ALARMS, BATTERY, FULL_SCREEN_INTENT in Send
 * Later. Applicability: Send Later items (and Send Later's accessibility) only when Send Later is
 * on; accessibility for Gemini always; FULL_SCREEN_INTENT only on API 34+; DEFAULT_SMS and
 * GOOGLE_ACCOUNT always. The Notifications group is not asserted while POST_NOTIFICATIONS is
 * missing (the spec places that item with Send Later but does not rule on the Notifications row).
 */
public class SetupChecksTest {

    private static final String CLASS = "com.voice2sms.sendlater.ui.SetupChecks";

    static final String SEND_LATER = "group_send_later";
    static final String DEFAULT_SMS = "group_default_sms";
    static final String ACCOUNT = "group_google_account";
    static final String GEMINI = "group_gemini";
    static final String NOTIFICATIONS = "group_notifications";
    static final String ABOUT = "group_about";

    private static final List<String> ITEMS = Arrays.asList("DEFAULT_SMS", "ACCESSIBILITY",
            "NOTIFICATION_LISTENER", "POST_NOTIFICATIONS", "EXACT_ALARMS", "BATTERY",
            "FULL_SCREEN_INTENT", "GOOGLE_ACCOUNT");
    private static final List<String> SEND_LATER_ONLY = Arrays.asList("NOTIFICATION_LISTENER",
            "POST_NOTIFICATIONS", "EXACT_ALARMS", "BATTERY", "FULL_SCREEN_INTENT");

    // ------------------------------------------------------------------ enum

    @Test public void itemEnumHasExactlyThePinnedItems() throws Exception {
        Object[] constants = itemClass().getEnumConstants();
        assertNotNull("SetupChecks.Item is an enum", constants);
        Set<String> names = new HashSet<>();
        for (Object c : constants) names.add(((Enum<?>) c).name());
        assertEquals(new HashSet<>(ITEMS), names);
    }

    // ------------------------------------------------------------------ all OK

    @Test public void nothingMissingAnywhereWhenEveryProbeIsOk() throws Exception {
        for (boolean sl : new boolean[]{true, false}) {
            for (int sdk : new int[]{33, 34, 35}) {
                Object p = probes(sl, sdk);
                for (String g : Arrays.asList(SEND_LATER, DEFAULT_SMS, ACCOUNT, GEMINI, NOTIFICATIONS, ABOUT)) {
                    assertEquals("sl=" + sl + " sdk=" + sdk + " " + g, set(), missing(p, g));
                }
            }
        }
    }

    // ------------------------------------------------------------------ Send Later on

    @Test public void sendLaterOnEverythingMissingFlagsEachGroupExactly() throws Exception {
        Object p = probes(true, 34, ITEMS.toArray(new String[0]));
        assertEquals(set("ACCESSIBILITY", "NOTIFICATION_LISTENER", "POST_NOTIFICATIONS",
                "EXACT_ALARMS", "BATTERY", "FULL_SCREEN_INTENT"), missing(p, SEND_LATER));
        assertEquals(set("DEFAULT_SMS"), missing(p, DEFAULT_SMS));
        assertEquals(set("GOOGLE_ACCOUNT"), missing(p, ACCOUNT));
        assertEquals(set("ACCESSIBILITY"), missing(p, GEMINI));
        assertEquals(set(), missing(p, ABOUT));
    }

    @Test public void notificationListenerAloneFlagsSendLater() throws Exception {
        // The old home attention flag missed the listener; SetupChecks must not.
        Object p = probes(true, 34, "NOTIFICATION_LISTENER");
        assertEquals(set("NOTIFICATION_LISTENER"), missing(p, SEND_LATER));
        assertEquals(set(), missing(p, GEMINI));
        assertEquals(set(), missing(p, DEFAULT_SMS));
        assertEquals(set(), missing(p, ACCOUNT));
    }

    @Test public void eachSendLaterItemAloneFlagsOnlySendLater() throws Exception {
        for (String item : SEND_LATER_ONLY) {
            Object p = probes(true, 34, item);
            assertEquals(item, set(item), missing(p, SEND_LATER));
            assertEquals(item, set(), missing(p, GEMINI));
            assertEquals(item, set(), missing(p, DEFAULT_SMS));
            assertEquals(item, set(), missing(p, ACCOUNT));
            assertEquals(item, set(), missing(p, ABOUT));
        }
    }

    @Test public void accessibilityOffFlagsBothSendLaterAndGeminiWhenSendLaterOn() throws Exception {
        Object p = probes(true, 34, "ACCESSIBILITY");
        assertEquals(set("ACCESSIBILITY"), missing(p, SEND_LATER));
        assertEquals(set("ACCESSIBILITY"), missing(p, GEMINI));
        assertEquals(set(), missing(p, DEFAULT_SMS));
    }

    // ------------------------------------------------------------------ Send Later off

    @Test public void sendLaterOffItsItemsAreNotFlagged() throws Exception {
        Object p = probes(false, 34, ITEMS.toArray(new String[0]));
        assertEquals("Send Later off: its group shows nothing", set(), missing(p, SEND_LATER));
        for (String g : Arrays.asList(DEFAULT_SMS, ACCOUNT, GEMINI, ABOUT)) {
            Set<String> got = missing(p, g);
            for (String item : Arrays.asList("NOTIFICATION_LISTENER", "EXACT_ALARMS", "BATTERY",
                    "FULL_SCREEN_INTENT")) {
                assertTrue(g + " must not flag " + item + " with Send Later off: " + got,
                        !got.contains(item));
            }
        }
    }

    @Test public void accessibilityFlaggedForGeminiEvenWhenSendLaterOff() throws Exception {
        Object p = probes(false, 34, "ACCESSIBILITY");
        assertEquals(set("ACCESSIBILITY"), missing(p, GEMINI));
        assertEquals(set(), missing(p, SEND_LATER));
    }

    @Test public void defaultSmsAndAccountApplyWhetherSendLaterIsOnOrOff() throws Exception {
        for (boolean sl : new boolean[]{true, false}) {
            Object p = probes(sl, 34, "DEFAULT_SMS", "GOOGLE_ACCOUNT");
            assertEquals("sl=" + sl, set("DEFAULT_SMS"), missing(p, DEFAULT_SMS));
            assertEquals("sl=" + sl, set("GOOGLE_ACCOUNT"), missing(p, ACCOUNT));
            assertEquals("sl=" + sl, set(), missing(p, SEND_LATER));
            assertEquals("sl=" + sl, set(), missing(p, GEMINI));
        }
    }

    // ------------------------------------------------------------------ API level

    @Test public void fullScreenIntentOnlyAppliesFromApi34() throws Exception {
        assertEquals(set(), missing(probes(true, 33, "FULL_SCREEN_INTENT"), SEND_LATER));
        assertEquals(set(), missing(probes(true, 24, "FULL_SCREEN_INTENT"), SEND_LATER));
        assertEquals(set("FULL_SCREEN_INTENT"), missing(probes(true, 34, "FULL_SCREEN_INTENT"), SEND_LATER));
        assertEquals(set("FULL_SCREEN_INTENT"), missing(probes(true, 35, "FULL_SCREEN_INTENT"), SEND_LATER));
    }

    @Test public void missingHasNoDuplicates() throws Exception {
        Object p = probes(true, 34, ITEMS.toArray(new String[0]));
        for (String g : Arrays.asList(SEND_LATER, DEFAULT_SMS, ACCOUNT, GEMINI, NOTIFICATIONS, ABOUT)) {
            List<?> raw = missingRaw(p, g);
            assertEquals(g + " " + raw, new HashSet<Object>(raw).size(), raw.size());
        }
    }

    // ------------------------------------------------------------------ rows inside groups

    @Test public void eachItemMapsToItsExistingRowInsideItsGroup() throws Exception {
        String[][] rows = {
                {"DEFAULT_SMS", DEFAULT_SMS, "set_default_sms"},
                {"GOOGLE_ACCOUNT", ACCOUNT, "switch_account"},
                {"ACCESSIBILITY", GEMINI, "gemini_intercept_toggle"},
                {"ACCESSIBILITY", SEND_LATER, "sendlater_accessibility"},
                {"NOTIFICATION_LISTENER", SEND_LATER, "sendlater_listener"},
                {"POST_NOTIFICATIONS", SEND_LATER, "sendlater_notifications"},
                {"EXACT_ALARMS", SEND_LATER, "sendlater_exact_alarm"},
                {"BATTERY", SEND_LATER, "sendlater_battery"},
        };
        for (String[] r : rows) {
            assertEquals(r[0] + " in " + r[1], r[2], rowKey(r[0], r[1]));
            assertTrue(r[2] + " is a row of " + r[1] + " in preferences.xml",
                    groupScreen(r[1]).contains("android:key=\"" + r[2] + "\""));
        }
        // An item has no row in a group it does not belong to.
        assertNull(rowKey("DEFAULT_SMS", SEND_LATER));
        assertNull(rowKey("EXACT_ALARMS", GEMINI));
        assertNull(rowKey("GOOGLE_ACCOUNT", ABOUT));
    }

    // ------------------------------------------------------------------ helpers

    private static Class<?> setupChecks() {
        try {
            return Class.forName(CLASS);
        } catch (ClassNotFoundException e) {
            fail("missing class " + CLASS + " (D59 SetupChecks)");
            return null;
        }
    }

    private static Class<?> itemClass() {
        try {
            return Class.forName(CLASS + "$Item");
        } catch (ClassNotFoundException e) {
            fail("missing enum " + CLASS + ".Item");
            return null;
        }
    }

    private static Class<?> probesClass() {
        try {
            return Class.forName(CLASS + "$Probes");
        } catch (ClassNotFoundException e) {
            fail("missing interface " + CLASS + ".Probes");
            return null;
        }
    }

    /** Fake probes: every item OK except {@code failing}. */
    private static Object probes(final boolean sendLaterOn, final int sdk, String... failing) {
        final Set<String> bad = new HashSet<>(Arrays.asList(failing));
        Class<?> pc = probesClass();
        return Proxy.newProxyInstance(pc.getClassLoader(), new Class<?>[]{pc}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "sendLaterEnabled": return sendLaterOn;
                case "sdkInt": return sdk;
                case "isOk": return !bad.contains(((Enum<?>) args[0]).name());
                case "toString": return "FakeProbes" + bad;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: throw new AssertionError("unexpected probe call " + m.getName());
            }
        });
    }

    private static List<?> missingRaw(Object probes, String group) throws Exception {
        Method m = setupChecks().getMethod("missing", probesClass(), String.class);
        Object out = invoke(m, probes, group);
        assertNotNull("missing(...) never returns null", out);
        return (List<?>) out;
    }

    private static Set<String> missing(Object probes, String group) throws Exception {
        Set<String> names = new HashSet<>();
        for (Object o : missingRaw(probes, group)) names.add(((Enum<?>) o).name());
        return names;
    }

    private static String rowKey(String item, String group) throws Exception {
        Method m = setupChecks().getMethod("rowKey", itemClass(), String.class);
        return (String) invoke(m, itemConst(item), group);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object itemConst(String name) {
        return Enum.valueOf((Class) itemClass(), name);
    }

    private static Object invoke(Method m, Object... args) throws Exception {
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof Exception) throw (Exception) t;
            if (t instanceof Error) throw (Error) t;
            throw e;
        }
    }

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    /** The nested PreferenceScreen of preferences.xml with that key, up to its closing tag. */
    private static String groupScreen(String key) throws IOException {
        Path p = Paths.get(System.getProperty("user.dir"), "src", "main", "res", "xml", "preferences.xml");
        if (!Files.exists(p)) {
            p = Paths.get(System.getProperty("user.dir"), "app", "src", "main", "res", "xml", "preferences.xml");
        }
        String xml = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?s)<PreferenceScreen\\s[^>]*android:key=\"" + Pattern.quote(key)
                + "\".*?</PreferenceScreen>").matcher(xml);
        assertTrue("group screen " + key, m.find());
        return m.group();
    }
}
