package com.voice2sms.sendlater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Acceptance (pending-notification; R45, R40, D47, OD39, OD24, AC15): source, manifest and
 * resource checks of the Android side of the "N scheduled" notification.
 *
 * <p><b>Pinned interface.</b> {@code PendingSummaryNotifier} (exactly one file under
 * {@code sendlater/}, in package {@code com.voice2sms.sendlater}, {@code .engine}, {@code .ui}
 * or {@code .notify}):
 * <pre>
 *   public static void refresh(android.content.Context)   // safe from any thread
 *   public static final String CHANNEL_ID                  // its own channel id
 *   public static final int NOTIFICATION_ID                // the one notification id
 * </pre>
 * refresh reads the SCHEDULED/HELD rows ({@code allPending(} / {@code allHeld(}) inside a
 * background block, builds {@code PendingSummary.build(...)} with
 * {@code SendLaterSettings.isEnabled(...)}, and posts or cancels {@code NOTIFICATION_ID}.
 *
 * <p><b>Trigger points.</b> A trigger file "reaches refresh" when its source (comments
 * stripped) calls {@code PendingSummaryNotifier.refresh(} directly, or sends a broadcast of an
 * action ({@code SendLaterIntents.ACTION_SCHEDULED} / {@code ACTION_STATE_CHANGED}) that a
 * non-exported manifest receiver handles by calling {@code PendingSummaryNotifier.refresh(}.
 * Trigger files: the sheet (schedule), the engine's state broadcaster (edit, cancel, hold,
 * send outcome, expiry, snooze; {@code AndroidEngineDeps}), the master switch
 * ({@code SendLaterSettingsController}) and the boot/time-change receiver
 * ({@code SendLaterBootReceiver}, after the re-arm).
 */
public class PendingSummaryNotifierSourceTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path SENDLATER = MAIN.resolve("sendlater");
    private static final Path MANIFEST = Paths.get("src/main/AndroidManifest.xml");
    private static final Path VALUES = Paths.get("src/main/res/values");

    private static final String[] PACKAGES = {
            "com.voice2sms.sendlater.", "com.voice2sms.sendlater.engine.",
            "com.voice2sms.sendlater.ui.", "com.voice2sms.sendlater.notify."};

    private static final String ACTION_SCHEDULED = "com.voice2sms.sendlater.action.SCHEDULED";
    private static final String ACTION_STATE_CHANGED = "com.voice2sms.sendlater.action.STATE_CHANGED";

    /** Channel ids that already exist; the scheduled-messages channel is its own. */
    private static final List<String> OTHER_CHANNELS = Arrays.asList(
            "voice2sms_autosend", "sendlater_a11y", "incoming_sms", "voice2sms_wear",
            "sendlater_battery", "sendlater_last_call", "sendlater_hold", "sendlater_info",
            "sendlater_send");

    private static final Pattern REFRESH_CALL =
            Pattern.compile("\\bPendingSummaryNotifier\\s*\\.\\s*refresh\\s*\\(");

    // ------------------------------------------------------------------ helpers

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String noComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\\n]*", " ");
    }

    private static Path findOne(String fileName) throws IOException {
        List<Path> hits;
        try (Stream<Path> s = Files.walk(SENDLATER)) {
            hits = s.filter(p -> p.getFileName().toString().equals(fileName))
                    .collect(Collectors.toList());
        }
        assertEquals("exactly one " + fileName + " under sendlater/: " + hits, 1, hits.size());
        return hits.get(0);
    }

    private static String notifierSource() throws IOException {
        return noComments(read(findOne("PendingSummaryNotifier.java")));
    }

    private static Class<?> notifierClass() {
        for (String p : PACKAGES) {
            try {
                return Class.forName(p + "PendingSummaryNotifier", false,
                        PendingSummaryNotifierSourceTest.class.getClassLoader());
            } catch (ClassNotFoundException ignored) {
                // next package
            }
        }
        fail("PendingSummaryNotifier not found in " + Arrays.toString(PACKAGES));
        return null;
    }

    /** True when {@code pos} sits inside the argument list of a background-run call. */
    private static boolean insideBackgroundBlock(String code, int pos) {
        Pattern start = Pattern.compile("(\\.execute\\s*\\(|\\.submit\\s*\\(|new\\s+Thread\\s*\\("
                + "|\\brunInBackground\\s*\\()");
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

    /** Argument text of the call starting at the '(' at {@code open}. */
    private static String args(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return code.substring(open + 1, i);
            }
        }
        return code.substring(open + 1);
    }

    private static Document manifest() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        return f.newDocumentBuilder().parse(MANIFEST.toFile());
    }

    /** Source files of non-exported manifest receivers for {@code action} that call refresh. */
    private static List<String> refreshingReceiversFor(String action) throws Exception {
        List<String> out = new ArrayList<>();
        NodeList receivers = manifest().getElementsByTagName("receiver");
        for (int i = 0; i < receivers.getLength(); i++) {
            Element r = (Element) receivers.item(i);
            boolean handles = false;
            NodeList actions = r.getElementsByTagName("action");
            for (int j = 0; j < actions.getLength(); j++) {
                if (action.equals(((Element) actions.item(j)).getAttribute("android:name"))) handles = true;
            }
            if (!handles) continue;
            if (!"false".equals(r.getAttribute("android:exported"))) continue;
            String name = r.getAttribute("android:name");
            String fqcn = name.startsWith(".") ? "com.voice2sms" + name : name;
            if (!fqcn.startsWith("com.voice2sms.")) continue;
            Path src = MAIN.resolve(fqcn.substring("com.voice2sms.".length()).replace('.', '/') + ".java");
            if (Files.exists(src) && REFRESH_CALL.matcher(noComments(read(src))).find()) out.add(fqcn);
        }
        return out;
    }

    /** See the class doc: direct call, or a broadcast handled by a refreshing receiver. */
    private static void assertReachesRefresh(Path file, String what, String... actionConstants)
            throws Exception {
        String code = noComments(read(file));
        if (REFRESH_CALL.matcher(code).find()) return;
        List<String> via = new ArrayList<>();
        for (String constant : actionConstants) {
            String literal = constant.equals("ACTION_SCHEDULED") ? ACTION_SCHEDULED : ACTION_STATE_CHANGED;
            boolean sends = Pattern.compile("new\\s+Intent\\s*\\(\\s*(SendLaterIntents\\s*\\.\\s*)?"
                    + constant + "\\b").matcher(code).find() && code.contains("sendBroadcast(");
            if (sends && !refreshingReceiversFor(literal).isEmpty()) via.add(constant);
        }
        assertFalse(what + " (" + file.getFileName() + ") must refresh the pending notification"
                + " (R45): call PendingSummaryNotifier.refresh(...) or send a broadcast that a"
                + " non-exported manifest receiver handles by calling it", via.isEmpty());
    }

    // ------------------------------------------------------------------ pinned interface

    @Test
    public void refreshIsPublicStaticTakingAContext() throws Exception {
        Class<?> c = notifierClass();
        Class<?> context = Class.forName("android.content.Context", false,
                PendingSummaryNotifierSourceTest.class.getClassLoader());
        Method m = c.getMethod("refresh", context);
        assertTrue("refresh(Context) is static", Modifier.isStatic(m.getModifiers()));
        assertTrue("refresh(Context) is public", Modifier.isPublic(m.getModifiers()));
        assertEquals(void.class, m.getReturnType());
    }

    @Test
    public void ownChannelAndOneNotificationId() throws Exception {
        Class<?> c = notifierClass();
        Field ch = c.getField("CHANNEL_ID");
        Field id = c.getField("NOTIFICATION_ID");
        assertTrue(Modifier.isStatic(ch.getModifiers()) && Modifier.isFinal(ch.getModifiers()));
        assertTrue(Modifier.isStatic(id.getModifiers()) && Modifier.isFinal(id.getModifiers()));
        assertEquals(String.class, ch.getType());
        assertEquals(int.class, id.getType());
        String channel = (String) ch.get(null);
        assertNotNull(channel);
        assertFalse("CHANNEL_ID not blank", channel.trim().isEmpty());
        assertFalse("'Scheduled messages' is its own channel, not " + channel + " (R45)",
                OTHER_CHANNELS.contains(channel));
    }

    @Test
    public void channelIsNamedScheduledMessagesAndIsLowImportanceAndSilent() throws Exception {
        String code = notifierSource();
        Matcher m = Pattern.compile("new\\s+NotificationChannel(Compat\\.Builder)?\\s*\\(").matcher(code);
        assertTrue("PendingSummaryNotifier creates its channel (R45)", m.find());
        // The name may be the constructor argument or a NotificationChannelCompat.Builder
        // setName(...) argument, as a literal or a string resource.
        boolean named = code.contains("\"Scheduled messages\"");
        Matcher res = Pattern.compile("R\\.string\\.(\\w+)").matcher(code);
        while (!named && res.find()) named = stringValue(res.group(1)).equals("Scheduled messages");
        assertTrue("channel name 'Scheduled messages' (R45/OD39)", named);
        assertTrue("channel IMPORTANCE_LOW (silent, R45)", code.contains("IMPORTANCE_LOW"));
        for (String loud : new String[] {"IMPORTANCE_DEFAULT", "IMPORTANCE_HIGH", "IMPORTANCE_MAX",
                "PRIORITY_HIGH", "PRIORITY_MAX", "setVibrate(", "enableVibration(true)",
                "setFullScreenIntent(", "DEFAULT_SOUND", "DEFAULT_ALL"}) {
            assertFalse("silent notification must not use " + loud, code.contains(loud));
        }
        assertTrue("setOnlyAlertOnce(true) (OD39)",
                Pattern.compile("setOnlyAlertOnce\\s*\\(\\s*true\\s*\\)").matcher(code).find());
    }

    private static String stringValue(String name) throws IOException {
        try (Stream<Path> s = Files.list(VALUES)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".xml")).collect(Collectors.toList())) {
                Matcher m = Pattern.compile("<string\\s+name=\"" + name + "\"[^>]*>([^<]*)</string>")
                        .matcher(read(p));
                if (m.find()) return m.group(1).trim();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ R40: no foreground service

    @Test
    public void noForegroundServiceAndDismissible() throws Exception {
        String code = notifierSource();
        for (String fg : new String[] {"startForeground", "startForegroundService",
                "FOREGROUND_SERVICE", "extends Service", "setForegroundServiceBehavior"}) {
            assertFalse("no foreground service for the pending notification (R40): " + fg,
                    code.contains(fg));
        }
        assertFalse("dismissible: not setOngoing(true) (OD39)",
                Pattern.compile("setOngoing\\s*\\(\\s*true\\s*\\)").matcher(code).find());
        assertFalse("no auto-send hooks from the notifier",
                code.contains("force_auto_send") || code.contains("EXTRA_FORCE_AUTO_SEND")
                        || code.contains("AutoSendRateLimiter"));
    }

    // ------------------------------------------------------------------ post / cancel

    @Test
    public void refreshBuildsTheModelFromTheSwitchAndCancelsWhenNothingToShow() throws Exception {
        String code = notifierSource();
        assertTrue("refresh builds PendingSummary.build(...)",
                Pattern.compile("\\bPendingSummary\\s*\\.\\s*build\\s*\\(").matcher(code).find());
        assertTrue("refresh reads the master switch via SendLaterSettings.isEnabled (OD24, D36)",
                Pattern.compile("SendLaterSettings\\s*\\.\\s*isEnabled\\s*\\(").matcher(code).find());
        assertTrue("refresh cancels NOTIFICATION_ID when none pending or Send Later is off (R45)",
                Pattern.compile("\\.cancel\\s*\\([^;]*NOTIFICATION_ID").matcher(code).find());
        assertTrue("refresh posts NOTIFICATION_ID",
                Pattern.compile("\\.notify\\s*\\([^;]*NOTIFICATION_ID").matcher(code).find());
    }

    @Test
    public void storeReadsRunOffTheMainThread() throws Exception {
        String code = notifierSource();
        Matcher m = Pattern.compile("\\ballPending\\s*\\(|\\ballHeld\\s*\\(").matcher(code);
        int n = 0;
        while (m.find()) {
            n++;
            assertTrue("store read at offset " + m.start() + " must run in a background block"
                    + " (refresh is called from main-thread receivers; Room forbids main-thread"
                    + " queries)", insideBackgroundBlock(code, m.start()));
        }
        assertTrue("refresh reads SCHEDULED and HELD rows (allPending / allHeld)", n >= 1);
    }

    @Test
    public void respectsMissingPostNotificationsSilently() throws Exception {
        String code = notifierSource();
        assertTrue("POST_NOTIFICATIONS absence handled (check, areNotificationsEnabled or catch"
                        + " SecurityException)",
                code.contains("POST_NOTIFICATIONS") || code.contains("areNotificationsEnabled(")
                        || Pattern.compile("catch\\s*\\(\\s*SecurityException").matcher(code).find());
    }

    // ------------------------------------------------------------------ public version / tap

    @Test
    public void lockScreenGetsTheRedactedPublicVersion() throws Exception {
        String code = notifierSource();
        assertTrue("setPublicVersion(...) (R45)", code.contains("setPublicVersion("));
        assertTrue("private visibility so the public version shows on the lock screen",
                code.contains("VISIBILITY_PRIVATE"));
        assertFalse("not VISIBILITY_PUBLIC", code.contains("VISIBILITY_PUBLIC"));
        assertTrue("public version uses the model's getPublicText()", code.contains("getPublicText("));
    }

    @Test
    public void tapOpensTheLauncherScheduledCard() throws Exception {
        String code = notifierSource();
        assertTrue("PendingIntent.getActivity (R45 tap)", code.contains("PendingIntent.getActivity("));
        assertTrue("explicit SettingsActivity component", code.contains("SettingsActivity.class"));
        assertTrue("SettingsActivity.EXTRA_SHOW_SCHEDULED (home-scheduled contract)",
                code.contains("EXTRA_SHOW_SCHEDULED"));
        assertTrue("FLAG_IMMUTABLE", code.contains("FLAG_IMMUTABLE"));
        assertTrue("content intent set", code.contains("setContentIntent("));
    }

    @Test
    public void noSensitiveLogging() throws Exception {
        for (String file : new String[] {"PendingSummaryNotifier.java", "PendingSummary.java"}) {
            String code = noComments(read(findOne(file)));
            Matcher m = Pattern.compile("\\bLog\\.[vdiwe]\\s*\\(").matcher(code);
            while (m.find()) {
                String a = args(code, m.end() - 1);
                for (String bad : new String[] {"getText", "getRecipientDisplayName",
                        "getRecipientE164", "getPublicText", "text", "name", "e164"}) {
                    assertFalse(file + ": no names, numbers or text in logs: Log(" + a + ")",
                            Pattern.compile("\\b" + bad + "\\b").matcher(a).find()
                                    || a.contains(bad + "("));
                }
            }
        }
    }

    // ------------------------------------------------------------------ trigger points

    @Test
    public void scheduleFromTheSheetRefreshes() throws Exception {
        assertReachesRefresh(SENDLATER.resolve("ui/SendLaterSheetActivity.java"),
                "schedule", "ACTION_SCHEDULED", "ACTION_STATE_CHANGED");
    }

    @Test
    public void engineStateChangesRefresh() throws Exception {
        // AndroidEngineDeps.Broadcaster is where every engine status change (edit, cancel, hold,
        // send outcome, expiry, snooze) is announced.
        assertReachesRefresh(SENDLATER.resolve("engine/AndroidEngineDeps.java"),
                "engine cancel/send/hold/expiry", "ACTION_STATE_CHANGED");
    }

    @Test
    public void masterSwitchRefreshes() throws Exception {
        assertReachesRefresh(SENDLATER.resolve("ui/SendLaterSettingsController.java"),
                "master switch (OD24)", "ACTION_STATE_CHANGED");
    }

    @Test
    public void bootAndTimeChangeRefresh() throws Exception {
        Path boot = SENDLATER.resolve("engine/SendLaterBootReceiver.java");
        assertReachesRefresh(boot, "boot / time change re-register", "ACTION_STATE_CHANGED");
    }

    @Test
    public void refreshingReceiverIsNotExportedAndPackageScoped() throws Exception {
        // Any receiver whose source calls refresh and handles an app-internal Send Later action
        // must be exported=false (no other app can drive it).
        NodeList receivers = manifest().getElementsByTagName("receiver");
        for (int i = 0; i < receivers.getLength(); i++) {
            Element r = (Element) receivers.item(i);
            String name = r.getAttribute("android:name");
            String fqcn = name.startsWith(".") ? "com.voice2sms" + name : name;
            if (!fqcn.startsWith("com.voice2sms.")) continue;
            Path src = MAIN.resolve(fqcn.substring("com.voice2sms.".length()).replace('.', '/') + ".java");
            if (!Files.exists(src) || !REFRESH_CALL.matcher(noComments(read(src))).find()) continue;
            NodeList actions = r.getElementsByTagName("action");
            for (int j = 0; j < actions.getLength(); j++) {
                String a = ((Element) actions.item(j)).getAttribute("android:name");
                if (a.startsWith("com.voice2sms.sendlater.action.")) {
                    assertEquals(fqcn + " handles " + a + " and must be exported=false", "false",
                            r.getAttribute("android:exported"));
                }
            }
        }
        // The notifier exists at all (otherwise this test is vacuous).
        notifierSource();
    }
}
