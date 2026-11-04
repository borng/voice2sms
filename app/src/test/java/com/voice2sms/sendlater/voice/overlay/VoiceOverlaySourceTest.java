package com.voice2sms.sendlater.voice.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.voice.logic.VoiceScreenSignature;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Source-level guards for the voice-overlay node: R8/I7 (reads only the allowlisted Voice ids, never
 * logs Voice text), R40 (event-path efficiency), I8 (Gemini interception unchanged), A3 delegation
 * from GeminiSmsInterceptService, overlay window rules, manifest and strings. Paths are relative to
 * the app module.
 *
 * <p>Pinned names: the R8 allowlist is the single constant {@code VoiceOverlayIds.R8_IDS} (a
 * Collection of full {@code <voice pkg>:id/<name>} ids, pure JVM class) in
 * {@code com.voice2sms.sendlater.voice.overlay}; strings live in {@code res/values/sendlater_overlay.xml}.
 */
public class VoiceOverlaySourceTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path SENDLATER = MAIN.resolve("sendlater");
    private static final Path VOICE = SENDLATER.resolve("voice");
    private static final Path OVERLAY = VOICE.resolve("overlay");
    private static final Path SERVICE = MAIN.resolve("GeminiSmsInterceptService.java");
    private static final Path CONFIG = Paths.get("src/main/res/xml/gemini_accessibility_config.xml");
    private static final Path MANIFEST = Paths.get("src/main/AndroidManifest.xml");
    private static final Path VALUES = Paths.get("src/main/res/values");
    private static final Path STRINGS = VALUES.resolve("sendlater_overlay.xml");

    /** Recon ids (landed.md, 2026-11-04): the only Voice ids R8 lets the service look at. */
    private static final Set<String> R8_NAMES = new HashSet<>(Arrays.asList(
            "send_message_text", "send_message_button", "toolbar_title", "input_message_container",
            "input_field_container", "attach_media_button", "camera_button", "list", "message_text",
            "message_time", "messagelist_avatar"));

    /** Ids this node needs (signature, tab/chip anchor, bubble list). */
    private static final List<String> REQUIRED_NAMES = Arrays.asList(
            "toolbar_title", "send_message_text", "send_message_button", "input_message_container", "list");

    /** Gemini ids in GeminiSmsInterceptService at baseline (main @ 7fa8184). */
    private static final Set<String> GEMINI_ID_NAMES = new TreeSet<>(Arrays.asList(
            "assistant_robin_lockscreen_compatible_action_card_button_element",
            "assistant_robin_floaty_title_text", "assistant_robin_action_card_text"));

    // Baseline hashes (main @ 7fa8184 == feature/send-later @ c6024c5), whitespace-collapsed SHA-256.
    private static final String NOTIFICATION_BLOCK_SHA =
            "6f82367a90053f83a03e0ae04f97cd82982b3efda04c19df7ae92f1562b9d34a";
    private static final String GEMINI_PATH_SHA =
            "eb0c071fce073335423c14ec1af09e75071316adde6aa7bef735d4b22a1f3234";
    private static final String SERVICE_INFO_SHA =
            "6e08862e86ec26f86a34f3c7b65799bc8a64b68b36d3db87a46cf704bc1bd483";
    private static final String CONFIG_XML_SHA =
            "e8313431539c9238ebefa4de54ea2bcec1396dbb0821214289b66f8ce5a2914d";

    private static final String NOTIFICATION_START =
            "if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {";
    private static final String GEMINI_START =
            "if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {\n            CharSequence className";

    // ------------------------------------------------------------------ helpers

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

    private static List<Path> overlayFiles() throws IOException {
        List<Path> files = javaUnder(OVERLAY);
        assertFalse("voice/overlay package has no sources yet", files.isEmpty());
        return files;
    }

    private static String overlayCode() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Path p : overlayFiles()) sb.append(stripComments(read(p))).append('\n');
        return sb.toString();
    }

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//[^\\n]*", " ");
    }

    private static String stripStrings(String src) {
        return src.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    /** Index of the brace matching the one at {@code open}; skips literals and comments. */
    private static int close(String s, int open) {
        int d = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') { i++; while (s.charAt(i) != '"') { if (s.charAt(i) == '\\') i++; i++; } continue; }
            if (c == '\'') { i++; while (s.charAt(i) != '\'') { if (s.charAt(i) == '\\') i++; i++; } continue; }
            if (c == '/' && s.charAt(i + 1) == '/') { while (s.charAt(i) != '\n') i++; continue; }
            if (c == '/' && s.charAt(i + 1) == '*') { i = s.indexOf("*/", i + 2) + 1; continue; }
            if (c == '{') d++;
            else if (c == '}') { d--; if (d == 0) return i; }
        }
        return -1;
    }

    /** Body (including braces) of the first method declaration named {@code name}, or null. */
    private static String methodBody(String src, String name) {
        Matcher m = Pattern.compile("(?m)^\\s*(?:@\\w+\\s+)*(?:(?:public|private|protected|static|final|synchronized)\\s+)*"
                + "[\\w<>\\[\\], .]+\\s+" + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*(?:throws [\\w., ]+)?\\{").matcher(src);
        if (!m.find()) return null;
        int open = m.end() - 1;
        int end = close(src, open);
        return end < 0 ? null : src.substring(open, end + 1);
    }

    private static String norm(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String sha(String s) throws Exception {
        byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static int count(String hay, String needle) {
        int n = 0;
        int i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    // ------------------------------------------------------------------ interfaces

    @Test public void overlayPackageHasPinnedTypes() {
        for (String f : new String[] {"VoiceOverlayController.java", "VoiceOverlayState.java",
                "VoiceDraftSnapshot.java", "VoiceDraftFlow.java", "VoiceNodeAccessor.java", "VoiceOverlayIds.java"}) {
            assertTrue("missing voice/overlay/" + f, Files.exists(OVERLAY.resolve(f)));
        }
    }

    @Test public void controllerExposesAttachOnEventDetach() throws IOException {
        String src = stripComments(read(OVERLAY.resolve("VoiceOverlayController.java")));
        assertTrue("class VoiceOverlayController", src.matches("(?s).*\\bclass\\s+VoiceOverlayController\\b.*"));
        assertTrue("attach(AccessibilityService)", src.matches(
                "(?s).*\\bvoid\\s+attach\\s*\\(\\s*(final\\s+)?(android\\.accessibilityservice\\.)?AccessibilityService\\s+\\w+\\s*\\).*"));
        assertTrue("onEvent(AccessibilityEvent)", src.matches(
                "(?s).*\\bvoid\\s+onEvent\\s*\\(\\s*(final\\s+)?(android\\.view\\.accessibility\\.)?AccessibilityEvent\\s+\\w+\\s*\\).*"));
        assertTrue("detach()", src.matches("(?s).*\\bvoid\\s+detach\\s*\\(\\s*\\).*"));
    }

    @Test public void pureSeamsHaveNoAndroidImports() throws IOException {
        for (String f : new String[] {"VoiceOverlayState.java", "VoiceDraftSnapshot.java", "VoiceDraftFlow.java",
                "VoiceNodeAccessor.java", "VoiceOverlayIds.java"}) {
            String src = read(OVERLAY.resolve(f));
            assertFalse(f + " must be pure JVM (JVM-tested)", src.matches("(?s).*\\bimport\\s+androidx?\\..*"));
        }
    }

    // ------------------------------------------------------------------ R8 / I7: what is read from Voice

    @SuppressWarnings("unchecked")
    @Test public void r8AllowlistIsOneConstantOfReconIds() throws Exception {
        Class<?> ids = Class.forName("com.voice2sms.sendlater.voice.overlay.VoiceOverlayIds");
        Field f = ids.getDeclaredField("R8_IDS");
        assertTrue("R8_IDS is static final", Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()));
        f.setAccessible(true);
        Object v = f.get(null);
        assertTrue("R8_IDS is a Collection<String>", v instanceof Collection);
        Collection<String> all = (Collection<String>) v;
        String prefix = VoiceScreenSignature.VOICE_PACKAGE + ":id/";
        Set<String> names = new HashSet<>();
        for (String id : all) {
            assertNotNull(id);
            assertTrue("full resource id expected: " + id, id.startsWith(prefix));
            String name = id.substring(prefix.length());
            assertTrue("R8: id outside the recon allowlist: " + name, R8_NAMES.contains(name));
            names.add(name);
        }
        for (String need : REQUIRED_NAMES) assertTrue("R8_IDS lacks " + need, names.contains(need));
    }

    @Test public void noVoiceIdLiteralsOutsideTheAllowlist() throws IOException {
        Pattern idLiteral = Pattern.compile(":id/");
        for (Path p : javaUnder(SENDLATER)) {
            String name = p.getFileName().toString();
            if (name.equals("VoiceOverlayIds.java") || name.equals("VoiceScreenSignature.java")) continue;
            String src = stripComments(read(p));
            assertFalse("R8: Voice id literal outside VoiceOverlayIds in " + p, src.contains("googlevoice:id/"));
            if (p.startsWith(VOICE)) {
                assertFalse("R8: ':id/' literal in " + p + " (use VoiceOverlayIds.R8_IDS)",
                        idLiteral.matcher(src).find());
            }
        }
        String svc = stripComments(read(SERVICE));
        assertFalse("R8: no Voice id literal in the service", svc.contains("googlevoice:id/"));
        Matcher m = Pattern.compile(":id/(\\w+)").matcher(svc);
        Set<String> found = new TreeSet<>();
        while (m.find()) found.add(m.group(1));
        assertEquals("the service's only id literals are the Gemini ones", GEMINI_ID_NAMES, found);
    }

    @Test public void overlayLooksUpNodesOnlyByAllowlistedViewId() throws IOException {
        String code = overlayCode();
        assertTrue("R40: uses findAccessibilityNodeInfosByViewId", code.contains("findAccessibilityNodeInfosByViewId("));
        assertFalse("R8: view-id lookups use the allowlist constant, never a literal",
                code.matches("(?s).*findAccessibilityNodeInfosByViewId\\s*\\(\\s*\".*"));
        assertFalse("R8: no text search over Voice", code.contains("findAccessibilityNodeInfosByText("));
        assertFalse("R8: no window enumeration beyond the active one", code.contains("getWindows("));
        assertTrue("R8_IDS is the source of the ids", code.contains("VoiceOverlayIds"));
    }

    private static final Pattern LOG_CALL =
            Pattern.compile("\\bLog\\s*\\.\\s*(v|d|i|w|e|wtf)\\s*\\(([^;]*)\\)\\s*;");
    private static final Pattern CONTENT = Pattern.compile(
            "(?i)(text|title|draft|desc|bubble|name|e164|number|candidate|snapshot|label|getText|"
                    + "getContentDescription|body|message)");

    @Test public void noVoiceTextInLogCalls() throws IOException {
        overlayFiles();
        for (Path p : javaUnder(VOICE)) {
            String src = stripStrings(stripComments(read(p)));
            Matcher m = LOG_CALL.matcher(src);
            while (m.find()) {
                assertFalse("I7: " + p.getFileName() + " logs Voice content: " + m.group(0),
                        CONTENT.matcher(m.group(2)).find());
            }
            assertFalse(p + ": no System.out / printStackTrace",
                    src.contains("System.out") || src.contains("System.err") || src.contains("printStackTrace"));
        }
    }

    // ------------------------------------------------------------------ R40: event-path efficiency

    @Test public void eventPathFiltersDebouncesAndNeverWalksTheTree() throws IOException {
        String code = overlayCode();
        assertTrue("filters to WINDOW_STATE_CHANGED", code.contains("TYPE_WINDOW_STATE_CHANGED"));
        assertTrue("filters to WINDOW_CONTENT_CHANGED", code.contains("TYPE_WINDOW_CONTENT_CHANGED"));
        assertTrue("debounce constant of 250 ms", code.matches("(?s).*\\b\\w+\\s*=\\s*250L?\\s*;.*"));
        assertTrue("coalescing debounce: removeCallbacks", code.contains("removeCallbacks("));
        assertTrue("coalescing debounce: postDelayed", code.contains("postDelayed("));
        assertTrue("main-thread Handler", code.contains("getMainLooper()"));
        assertTrue("skips work while the screen is off", code.contains("isInteractive("));
        assertTrue("recycles nodes", code.contains(".recycle()"));
        assertFalse("no upward walks", code.contains("getParent("));
        assertTrue("R40: at most one getChild( (the bubble list rows), never a full-tree walk",
                count(stripStrings(code), "getChild(") <= 1);
        assertFalse("no lastScanTimestamp reuse", code.contains("lastScanTimestamp"));
    }

    @Test public void onEventDoesNoNodeOrRepositoryWork() throws IOException {
        String src = stripComments(read(OVERLAY.resolve("VoiceOverlayController.java")));
        String body = methodBody(src, "onEvent");
        assertNotNull("onEvent body", body);
        for (String bad : new String[] {"SendLaterRepository", "SendLaterDatabase", "ThreadDirectory",
                "getContentResolver", "findAccessibilityNodeInfosByViewId", "getRootInActiveWindow",
                "getChild(", "VoiceBubbleParser"}) {
            assertFalse("R40: onEvent must stay cheap (debounced work only): " + bad, body.contains(bad));
        }
        assertTrue("R40: onEvent posts the debounced work", body.contains("postDelayed(") || body.contains("schedule")
                || body.contains("debounce") || body.contains("post("));
    }

    @Test public void chipStateComesFromAnInMemoryMapRefreshedOnStateChanged() throws IOException {
        String code = overlayCode();
        assertTrue("refresh on ACTION_STATE_CHANGED", code.contains("ACTION_STATE_CHANGED"));
        assertTrue("listens for ACTION_SCHEDULED", code.contains("ACTION_SCHEDULED"));
        assertTrue("runtime receiver is not exported", code.contains("RECEIVER_NOT_EXPORTED"));
        assertTrue("chip label via ChipLabel", code.contains("ChipLabel"));
    }

    // ------------------------------------------------------------------ I8: Gemini unchanged, A3 delegation

    @Test public void geminiInterceptionCodeIsUnchanged() throws Exception {
        String src = read(SERVICE);
        int n = src.indexOf(NOTIFICATION_START);
        assertTrue("notification branch present", n >= 0);
        int nEnd = close(src, n + NOTIFICATION_START.length() - 1);
        assertEquals("I8: notification branch changed", NOTIFICATION_BLOCK_SHA, sha(norm(src.substring(n, nEnd + 1))));

        int g = src.indexOf(GEMINI_START);
        assertTrue("Gemini path present", g >= 0);
        int m = src.indexOf("private void checkVoiceConfirmPath()");
        assertTrue(m > g);
        int mEnd = close(src, src.indexOf('{', m));
        assertEquals("I8: Gemini interception code (window/content/click paths through checkVoiceConfirmPath) changed",
                GEMINI_PATH_SHA, sha(norm(src.substring(g, mEnd + 1))));

        int i1 = src.indexOf("info.eventTypes");
        int i2 = src.indexOf("setServiceInfo(info);");
        assertTrue(i1 >= 0 && i2 > i1);
        assertEquals("onServiceConnected event types / flags changed",
                SERVICE_INFO_SHA, sha(norm(src.substring(i1, i2 + "setServiceInfo(info);".length()))));

        assertEquals("gemini_accessibility_config.xml changed", CONFIG_XML_SHA, sha(norm(read(CONFIG))));
        assertEquals("lastScanTimestamp keeps its three Gemini uses only", 3, count(src, "lastScanTimestamp"));
    }

    @Test public void serviceDelegatesVoiceEventsBeforeTheNonGeminiReturn() throws IOException {
        String src = read(SERVICE);
        assertTrue("service uses VoiceOverlayController", src.contains("VoiceOverlayController"));
        int n = src.indexOf(NOTIFICATION_START);
        int nEnd = close(src, n + NOTIFICATION_START.length() - 1);
        int g = src.indexOf(GEMINI_START);
        assertTrue(n >= 0 && nEnd > n && g > nEnd);
        String between = stripComments(src.substring(nEnd + 1, g));
        assertTrue("Voice delegation sits between the notification branch and the Gemini path",
                between.matches("(?s).*\\.onEvent\\s*\\(\\s*event\\s*\\).*"));
        assertTrue("delegation is gated on the Voice package", between.contains("VOICE_PACKAGE"));
        assertTrue("non-Gemini packages still return early",
                between.contains("GEMINI_PACKAGE") && between.contains("return;"));
        int delegate = between.indexOf(".onEvent(");
        int earlyReturn = between.lastIndexOf("return;");
        assertTrue("delegation happens before the non-Gemini return", delegate < earlyReturn);
        for (String bad : new String[] {"getRootInActiveWindow", "getSource(", "findAccessibilityNodeInfos",
                "lastScanTimestamp", "new "}) {
            assertFalse("R40: no node access/allocation on the shared early path: " + bad, between.contains(bad));
        }
    }

    @Test public void serviceAttachesAndDetachesTheController() throws IOException {
        String src = stripComments(read(SERVICE));
        assertTrue("attach(this)", src.matches("(?s).*\\.attach\\s*\\(\\s*this\\s*\\).*"));
        String destroy = methodBody(src, "onDestroy");
        assertNotNull("onDestroy override removes the overlays", destroy);
        assertTrue("onDestroy detaches", destroy.contains("detach("));
        String interrupt = methodBody(src, "onInterrupt");
        assertNotNull(interrupt);
        assertTrue("onInterrupt removes the overlays", interrupt.contains("detach(")
                || interrupt.toLowerCase().contains("hide") || interrupt.toLowerCase().contains("remove"));
    }

    @Test public void sendLaterEnabledGatesTheOverlay() throws IOException {
        String all = overlayCode() + stripComments(read(SERVICE));
        assertTrue("Send Later enabled setting consulted", all.contains("SendLaterSettings"));
    }

    // ------------------------------------------------------------------ overlay window rules

    @Test public void overlayWindowsAreAccessibilityOverlaysWithPinnedFlags() throws IOException {
        String code = overlayCode();
        assertTrue(code.contains("TYPE_ACCESSIBILITY_OVERLAY"));
        assertTrue(code.contains("FLAG_NOT_FOCUSABLE"));
        assertTrue(code.contains("FLAG_NOT_TOUCH_MODAL"));
        assertTrue(code.contains("FLAG_LAYOUT_IN_SCREEN"));
        assertTrue("added and removed through WindowManager", code.contains("addView(") && code.contains("removeView"));
        assertTrue("positioned from node bounds", code.contains("getBoundsInScreen("));
        assertTrue("accent #8AB4F8 (OD9)", code.toUpperCase().contains("8AB4F8") || code.contains("SendLaterAccent"));
        assertFalse("no SYSTEM_ALERT_WINDOW overlay type", code.contains("TYPE_APPLICATION_OVERLAY"));
    }

    // ------------------------------------------------------------------ capture, chip tap, R33

    @Test public void tabTapStartsTheSheetWithNativeCapture() throws IOException {
        String code = overlayCode();
        assertTrue(code.contains("ACTION_CAPTURE"));
        assertTrue("source NATIVE_GV", code.contains("NATIVE_GV"));
        assertTrue("app-internal intent", code.contains("setPackage("));
        assertTrue(code.contains("EXTRA_DRAFT"));
        assertTrue(code.contains("EXTRA_DISPLAY_NAME"));
        assertTrue(code.contains("EXTRA_CANDIDATES"));
        assertTrue(code.contains("EXTRA_WATERMARK_QUALITY"));
        assertTrue("activity from a service", code.contains("FLAG_ACTIVITY_NEW_TASK"));
        assertTrue("contacts only when granted (A5)", code.contains("READ_CONTACTS") && code.contains("checkSelfPermission"));
        assertTrue("directory candidates", code.contains("ThreadDirectory"));
    }

    @Test public void chipTapRoutesToEditOrUpcoming() throws IOException {
        String code = overlayCode();
        assertTrue("one message -> edit", code.contains("ACTION_EDIT") && code.contains("EXTRA_MESSAGE_ID"));
        assertTrue("several -> Upcoming filtered", code.contains("ACTION_UPCOMING") && code.contains("EXTRA_E164"));
    }

    @Test public void onScreenSourceAndEarlyHold() throws IOException {
        String code = overlayCode();
        assertTrue("OD21: on-screen source implements OutboundCapableSource",
                code.matches("(?s).*\\bimplements\\s+[\\w., ]*\\bOutboundCapableSource\\b.*"));
        assertTrue("registered with the engine", code.contains("SendLaterEngine.registerSource("));
        assertTrue("R33: early hold through the engine", code.contains("SendLaterEngine.onInboundEvent("));
        assertTrue("R33: only for holdIfReplied messages", code.contains("isHoldIfReplied()"));
        assertTrue("bubbles parsed with VoiceBubbleParser", code.contains("VoiceBubbleParser"));
    }

    @Test public void neverUsesForceAutoSendOrRateLimiter() throws IOException {
        String code = overlayCode();
        assertFalse(code.contains("force_auto_send"));
        assertFalse(code.contains("EXTRA_FORCE_AUTO_SEND"));
        assertFalse(code.contains("AutoSendRateLimiter"));
    }

    // ------------------------------------------------------------------ manifest + strings

    @Test public void manifestDeclaresReadContactsOnItsOwnLine() throws IOException {
        String mf = read(MANIFEST);
        assertTrue("READ_CONTACTS (optional runtime permission)",
                mf.matches("(?s).*\\n\\s*<uses-permission android:name=\"android\\.permission\\.READ_CONTACTS\"\\s*/>.*"));
        assertFalse("no SYSTEM_ALERT_WINDOW: overlays are accessibility overlays",
                mf.contains("android.permission.SYSTEM_ALERT_WINDOW"));
    }

    private static String unescapeXml(String s) {
        return s.replace("\\'", "'").replace("\\\"", "\"").replace("&#8212;", "\u2014")
                .replace("\\u2014", "\u2014").replace("&#8230;", "\u2026").replace("\\u2026", "\u2026")
                .replace("&amp;", "&");
    }

    @Test public void overlayStringsLiveInTheirOwnFile() throws IOException {
        String xml = unescapeXml(read(STRINGS));
        for (String s : new String[] {"Scheduled, but the draft couldn't be cleared",
                "Saved to Upcoming \u2014 the conversation changed, draft left as is", "Undo"}) {
            assertTrue("sendlater_overlay.xml lacks: " + s, xml.contains(s));
        }
        String strings = read(VALUES.resolve("strings.xml"));
        assertFalse("never edit strings.xml", strings.contains("draft couldn"));
    }
}
