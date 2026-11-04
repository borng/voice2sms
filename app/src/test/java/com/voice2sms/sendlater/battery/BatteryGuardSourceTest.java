package com.voice2sms.sendlater.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Source-level guards for the battery-guard node (R38, R39, R40, D25). Paths are relative to the
 * app module, like the other Send Later source tests.
 */
public class BatteryGuardSourceTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java/com/voice2sms");
    private static final Path SENDLATER = MAIN_JAVA.resolve("sendlater");
    private static final Path BATTERY = SENDLATER.resolve("battery");
    private static final Path ENGINE = SENDLATER.resolve("engine");
    private static final Path HEALTH = BATTERY.resolve("BatteryHealth.java");
    private static final Path MANIFEST = Paths.get("src/main/AndroidManifest.xml");
    private static final Path STRINGS = Paths.get("src/main/res/values/sendlater_battery.xml");
    private static final Path PREFS_XML = Paths.get("src/main/res/xml/preferences.xml");

    private static String read(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static List<Path> javaUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static String batterySource() throws IOException {
        List<Path> files = javaUnder(BATTERY);
        assertFalse("sendlater/battery has no sources yet", files.isEmpty());
        StringBuilder sb = new StringBuilder();
        for (Path p : files) sb.append(stripComments(read(p))).append('\n');
        return sb.toString();
    }

    private static int count(String hay, String needle) {
        int n = 0;
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    // --- interfaces (reflection; classes are not initialised) ---

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, BatteryGuardSourceTest.class.getClassLoader());
    }

    @Test public void batteryHealthExposesCheckAndFixIntent() throws Exception {
        Class<?> c = load("com.voice2sms.sendlater.battery.BatteryHealth");
        Method check = c.getDeclaredMethod("check", Context.class);
        assertTrue(Modifier.isStatic(check.getModifiers()));
        assertEquals(BatteryStatus.class, check.getReturnType());
        Method fix = c.getDeclaredMethod("fixIntent", Context.class);
        assertTrue(Modifier.isStatic(fix.getModifiers()));
        assertEquals(Intent.class, fix.getReturnType());
    }

    @Test public void batteryWarningExposesMaybeNotifyAndOnAlarmLate() throws Exception {
        Class<?> c = load("com.voice2sms.sendlater.battery.BatteryWarning");
        Method m = c.getDeclaredMethod("maybeNotify", Context.class, long.class);
        assertTrue(Modifier.isStatic(m.getModifiers()));
        assertTrue(Modifier.isPublic(m.getModifiers()));
        Method late = c.getDeclaredMethod("onAlarmLate", Context.class, long.class);
        assertTrue(Modifier.isStatic(late.getModifiers()));
        assertTrue(Modifier.isPublic(late.getModifiers()));
    }

    @Test public void pureClassesHaveNoAndroidImports() throws IOException {
        for (String f : new String[] {"BatteryAdvice.java", "BatteryStatus.java", "BatteryWarningThrottle.java"}) {
            String src = read(BATTERY.resolve(f));
            assertFalse(f + " must be pure JVM", src.contains("import android."));
            assertFalse(f + " must be pure JVM", src.contains("import androidx."));
        }
    }

    // --- BatteryHealth (R38) ---

    @Test public void checkUsesTheTwoPlatformSignalsAndManufacturer() throws IOException {
        String src = stripComments(read(HEALTH));
        assertTrue(src.contains("isBackgroundRestricted("));
        assertTrue(src.contains("isIgnoringBatteryOptimizations("));
        assertTrue(src.contains("Build.MANUFACTURER"));
        assertTrue("isBackgroundRestricted is API 28+: guard it",
                src.contains("VERSION_CODES.P") || src.contains(">= 28"));
    }

    @Test public void fixIntentTriesTheThreeScreensInOrder() throws IOException {
        String src = stripComments(read(HEALTH));
        int a = src.indexOf("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
        int b = src.indexOf("ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS");
        int c = src.indexOf("ACTION_APPLICATION_DETAILS_SETTINGS");
        assertTrue("request-ignore intent missing", a >= 0);
        assertTrue("battery optimization settings intent missing", b >= 0);
        assertTrue("app details intent missing", c >= 0);
        assertTrue("order: request-ignore, then optimization settings, then app details", a < b && b < c);
        assertTrue("request-ignore and app details carry package: data", src.contains("\"package:\""));
    }

    @Test public void fixIntentChecksResolveActivityForEachCandidate() throws IOException {
        String src = stripComments(read(HEALTH));
        int n = count(src, "resolveActivity(");
        boolean loop = Pattern.compile("\\b(for|while)\\s*\\(").matcher(src).find();
        assertTrue("each of the three candidates is checked with resolveActivity (3 calls or one in a loop)",
                n >= 3 || (n >= 1 && loop));
        if (n < 3) {
            // Loop form: the resolveActivity call must sit after all three candidates are built.
            int c = src.indexOf("ACTION_APPLICATION_DETAILS_SETTINGS");
            assertTrue(src.lastIndexOf("resolveActivity(") > c);
        } else {
            // Unrolled form: a resolveActivity check follows each candidate before the next one.
            int a = src.indexOf("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
            int b = src.indexOf("ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS");
            int c = src.indexOf("ACTION_APPLICATION_DETAILS_SETTINGS");
            int ra = src.indexOf("resolveActivity(", a);
            int rb = src.indexOf("resolveActivity(", b);
            int rc = src.indexOf("resolveActivity(", c);
            assertTrue("resolveActivity after the request-ignore candidate, before the next", ra > a && ra < b);
            assertTrue("resolveActivity after the settings candidate, before app details", rb > b && rb < c);
            assertTrue("resolveActivity after the app details candidate", rc > c);
        }
    }

    // --- R40 efficiency budget ---

    @Test public void noWakelockPollingOrForegroundServiceUnderBattery() throws IOException {
        String src = batterySource();
        for (String banned : new String[] {
                "WakeLock", "newWakeLock", "PARTIAL_WAKE_LOCK", ".acquire(", "startForeground",
                "setRepeating", "setInexactRepeating", "WorkManager", "JobScheduler", "scheduleAtFixedRate",
                "force_auto_send", "EXTRA_FORCE_AUTO_SEND", "AutoSendRateLimiter"}) {
            assertFalse("sendlater/battery must not use " + banned, src.contains(banned));
        }
    }

    // --- R39 notification ---

    @Test public void warningUsesOwnLowImportanceChannelAndImmutablePendingIntent() throws IOException {
        String src = batterySource();
        assertTrue(src.contains("NotificationChannel"));
        assertTrue("own low-importance channel", src.contains("IMPORTANCE_LOW"));
        assertTrue(src.contains("FLAG_IMMUTABLE"));
        assertTrue("the warning carries a Fix action", src.contains("addAction"));
        assertTrue("timestamp key", src.contains("sendlater_battery_warned_at")
                || src.contains("KEY_WARNED_AT"));
    }

    @Test public void receiverForScheduledBroadcastCallsMaybeNotify() throws IOException {
        String src = batterySource();
        assertTrue("a BroadcastReceiver under sendlater/battery", src.contains("extends BroadcastReceiver"));
        assertTrue(src.contains("ACTION_SCHEDULED"));
        assertTrue(src.contains("maybeNotify("));
    }

    @Test public void manifestBlockAfterSetupActivityWithInternalReceiverAndPermission() throws IOException {
        String m = read(MANIFEST);
        assertTrue("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission",
                Pattern.compile("<uses-permission\\s+android:name=\"android\\.permission\\.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS\"")
                        .matcher(m).find());
        int start = m.indexOf("<!-- sendlater:battery-guard start -->");
        int end = m.indexOf("<!-- sendlater:battery-guard end -->");
        assertTrue("block start marker", start >= 0);
        assertTrue("block end marker", end > start);
        int setup = m.indexOf("android:name=\".SetupActivity\"");
        assertTrue(setup >= 0);
        int setupClose = m.indexOf("/>", setup);
        int setupClose2 = m.indexOf("</activity>", setup);
        int setupEnd = setupClose2 >= 0 && setupClose2 < start ? Math.max(setupClose, setupClose2) : setupClose;
        assertTrue("block comes after the SetupActivity element", start > setupEnd);
        String block = m.substring(start, end);
        assertTrue(block.contains("<receiver"));
        assertTrue(block.contains(".sendlater.battery."));
        assertTrue("internal receiver", block.contains("android:exported=\"false\""));
        assertTrue(block.contains("com.voice2sms.sendlater.action.SCHEDULED"));
    }

    @Test public void stringsLiveInSendlaterBatteryXml() throws IOException {
        String s = read(STRINGS);
        assertTrue(s.contains("<resources"));
        assertTrue("notification title", s.contains("Scheduled messages may be late"));
        assertTrue("Fix action label", s.contains(">Fix<"));
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        assertTrue("force-stop copy", lower.contains("force"));
        assertTrue("Samsung copy", s.contains("Never sleeping"));
    }

    // --- Settings row (R38) ---

    @Test public void batteryPreferenceKeyIsDeclared() throws IOException {
        String x = read(PREFS_XML);
        assertTrue(x.contains("android:key=\"sendlater_battery\""));
    }

    @Test public void settingsRowRefreshesOnResumeAndOpensFixIntent() throws IOException {
        Path owner = null;
        for (Path p : javaUnder(MAIN_JAVA)) {
            if (p.startsWith(BATTERY)) continue;
            String src = stripComments(read(p));
            if (src.contains("BatteryHealth.fixIntent(") && src.contains("BatteryAdvice.text(")) {
                owner = p;
                break;
            }
        }
        assertNotNull("a settings screen uses BatteryAdvice.text and BatteryHealth.fixIntent", owner);
        String src = stripComments(read(owner));
        assertTrue(owner + " refreshes the summary in onResume", src.contains("onResume("));
        assertTrue(owner + " finds the row by its key",
                src.contains("\"sendlater_battery\"") || src.contains("KEY_BATTERY") || src.contains("KEY_PREF"));
    }

    // --- engine late-alarm signal (R39) ---

    @Test public void engineDuePathReportsLateAlarms() throws IOException {
        boolean found = false;
        for (Path p : javaUnder(ENGINE)) {
            if (stripComments(read(p)).contains("BatteryWarning.onAlarmLate(")) { found = true; break; }
        }
        assertTrue("engine due path calls BatteryWarning.onAlarmLate(ctx, lateMs)", found);
    }

    @Test public void noWakelockAddedToEngineForBattery() throws IOException {
        // R40: the late-alarm signal must not bring a wakelock into the engine.
        for (Path p : javaUnder(ENGINE)) {
            String src = stripComments(read(p));
            assertFalse(p + " acquires a wakelock", src.contains("newWakeLock"));
        }
    }

}
