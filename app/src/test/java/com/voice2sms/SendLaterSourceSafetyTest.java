package com.voice2sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.voice2sms.sendlater.voice.overlay.VoiceOverlayIds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Send Later source-level invariants (master spec I1, I5, I7, I8, AC12), guards G1-G7.
 *
 * <p>Like {@link SourceSafetyTest}, these read the main sources as text and run on the plain
 * JVM. Each guard names its requirement id. A failing guard means the invariant broke; fix the
 * production code (or change the spec first), never loosen the guard to make it pass.
 *
 * <ul>
 *   <li>G1 (I8): WebView access from Send Later code only goes through the activity's guarded
 *       hosts, and every such access inside GVoiceWebViewActivity checks isActivityAlive() in its
 *       own method or posted lambda, ahead of the access.</li>
 *   <li>G2 (I8): no force_auto_send / EXTRA_FORCE_AUTO_SEND under sendlater/ nor in the
 *       activity's scheduled branch; an active scheduled job never reaches injectComposer().</li>
 *   <li>G3 (I7): no Log.* call under sendlater/ (or in the activity's Send Later members) whose
 *       arguments name a draft / text / body / preview / contact-name value; a
 *       BuildConfig.DEBUG-guarded hash is the only exception.</li>
 *   <li>G4 (I7, R8): the accessibility service's Voice id allowlist is exactly the R8 ids and no
 *       other Voice resource id is spelled anywhere in the main sources.</li>
 *   <li>G5 (I5, I8): the scheduled path takes ScheduledSendQueue slots and never consults
 *       AutoSendRateLimiter.</li>
 *   <li>G6 (I1): ACTION_SCHEDULED (the "clear the draft" signal) is broadcast only after a
 *       verified repository.schedule, and from nowhere else.</li>
 *   <li>G7 (AC12, I8): the baseline SourceSafetyTest guards are all still present and none is
 *       ignored (Gradle runs them alongside this class).</li>
 * </ul>
 */
public class SendLaterSourceSafetyTest {

    private static final Path MAIN = Paths.get("src/main/java/com/voice2sms");
    private static final Path SENDLATER = MAIN.resolve("sendlater");
    private static final Path ACTIVITY = MAIN.resolve("GVoiceWebViewActivity.java");
    private static final Path TEST = Paths.get("src/test/java/com/voice2sms");

    /** Send Later members of GVoiceWebViewActivity (methods, inner classes, field initialisers). */
    private static final List<String> ACTIVITY_SENDLATER_METHODS = Arrays.asList(
            "injectScheduledComposer", "installSendLater", "readThread");
    private static final List<String> ACTIVITY_SENDLATER_CLASSES = Arrays.asList(
            "SendLaterHost", "ScheduledHost");
    private static final List<String> ACTIVITY_SENDLATER_FIELDS = Arrays.asList("composerProbe");

    /** A scope (lambda or method body) that names any of these is Send Later code. */
    private static final Pattern SENDLATER_SYMBOL = Pattern.compile(
            "\\b(sendLaterHooks|scheduledSend|ScheduledSendController|SendLaterWebHooks"
                    + "|ForegroundUse|SCHEDULED_COMPOSER_PROBE_JS|ThreadReadCallback)\\b");

    // ------------------------------------------------------------------ G1 (I8)

    /**
     * G1a (I8): code under sendlater/ never holds or drives a WebView itself. It reaches the page
     * only through the activity's Host interfaces (SendLaterWebHooks.Host,
     * ScheduledSendController.Host), whose implementations G1b checks. GVoiceWebViewActivity is
     * named only as a class literal (to build the launch intent), never for a field or method.
     */
    @Test
    public void g1_sendLaterCode_neverTouchesWebViewDirectly() throws IOException {
        Pattern webViewApi = Pattern.compile(
                "\\bWebView\\b|\\bwebView\\b|\\.evaluateJavascript\\s*\\(|\\.loadUrl\\s*\\("
                        + "|\\.addJavascriptInterface\\s*\\(|\\bimport\\s+android\\.webkit\\.WebView");
        Pattern activityMember = Pattern.compile("\\bGVoiceWebViewActivity\\s*\\.\\s*(?!class\\b)\\w+");
        for (Path p : sendLaterSources()) {
            String code = Lexed.of(read(p)).blanked;
            Matcher m = webViewApi.matcher(code);
            assertFalse("G1 (I8): " + p + " touches a WebView directly (\"" + (m.find() ? m.group() : "")
                    + "\"); go through the activity's guarded Host", webViewApi.matcher(code).find());
            Matcher a = activityMember.matcher(code);
            assertFalse("G1 (I8): " + p + " reaches into GVoiceWebViewActivity (" + (a.find() ? a.group() : "")
                    + "); only GVoiceWebViewActivity.class is allowed", activityMember.matcher(code).find());
        }
    }

    /**
     * G1a' (I8): every use of the host's page view in the send package is gated by host.isAlive()
     * in the same statement, ahead of it.
     */
    @Test
    public void g1_hostPageView_isGatedByIsAlive() throws IOException {
        int seen = 0;
        for (Path p : sendLaterSources()) {
            String code = Lexed.of(read(p)).blanked;
            Matcher m = Pattern.compile("\\.pageView\\s*\\(\\s*\\)").matcher(code);
            while (m.find()) {
                int stmtStart = statementStart(code, m.start());
                String before = code.substring(stmtStart, m.start());
                if (before.matches("(?s).*\\bView\\s+$")) continue; // the interface declaration
                seen++;
                assertTrue("G1 (I8): " + p + " uses pageView() without host.isAlive() first: "
                        + code.substring(stmtStart, Math.min(code.length(), m.end() + 20)).trim(),
                        before.contains("isAlive()"));
            }
        }
        assertTrue("G1: expected the send path to place its cover over host.pageView()", seen > 0);
    }

    /**
     * G1b (I8): in GVoiceWebViewActivity, every webView access that belongs to Send Later (the
     * named members, any posted lambda or Runnable naming a Send Later symbol) is preceded by
     * isActivityAlive() inside its own method or lambda body. A check in the enclosing method does
     * not cover a lambda posted from it (onRenderProcessGone nulls webView in between).
     */
    @Test
    public void g1_activitySendLaterWebViewAccess_checksIsActivityAlive() throws IOException {
        String code = Lexed.of(read(ACTIVITY)).blanked;
        List<int[]> regions = activitySendLaterRegions(code);
        int checked = 0;
        Matcher m = Pattern.compile("\\bwebView\\b").matcher(code);
        while (m.find()) {
            int idx = m.start();
            int scope = enclosingScope(code, idx);
            if (scope < 0) continue; // field declaration
            String scopeHeader = header(code, scope);
            boolean inRegion = false;
            for (int[] r : regions) if (idx >= r[0] && idx < r[1]) inRegion = true;
            boolean posted = scopeHeader.endsWith("->") || scopeHeader.matches("(?s).*\\bvoid\\s+run\\s*\\(\\s*\\)$");
            int scopeEnd = matchingBrace(code, scope);
            boolean sendLaterScope = SENDLATER_SYMBOL.matcher(code.substring(scope, scopeEnd)).find();
            if (!inRegion && !(posted && sendLaterScope)) continue;
            checked++;
            assertTrue("G1 (I8): GVoiceWebViewActivity line " + lineOf(code, idx)
                            + " touches webView in Send Later code without isActivityAlive() first in its own "
                            + (posted ? "lambda/Runnable" : "method"),
                    code.substring(scope, idx).contains("isActivityAlive()"));
        }
        assertTrue("G1: expected Send Later webView accesses to check (found " + checked + ")", checked >= 6);
    }

    /**
     * G1c (I8): the V2SBridge Send Later methods run on a binder thread; they never touch webView
     * in their own body, only in a runOnUiThread lambda (checked by G1b).
     */
    @Test
    public void g1_bridgeSendLaterMethods_touchWebViewOnlyInPostedLambda() throws IOException {
        String code = Lexed.of(read(ACTIVITY)).blanked;
        int bridge = code.indexOf("class V2SBridge");
        assertTrue("V2SBridge not found", bridge >= 0);
        int bridgeEnd = matchingBrace(code, code.indexOf('{', bridge));
        Matcher m = Pattern.compile("\\bwebView\\b").matcher(code);
        int sendLaterMethods = 0;
        Matcher js = Pattern.compile("@JavascriptInterface\\s+public\\s+\\w+\\s+(\\w+)\\s*\\(").matcher(code);
        js.region(bridge, bridgeEnd);
        while (js.find()) {
            int open = code.indexOf('{', js.end());
            int close = matchingBrace(code, open);
            String body = code.substring(open, close);
            if (!body.contains("sendLaterHooks")) continue;
            sendLaterMethods++;
            m.region(open, close);
            while (m.find()) {
                int scope = enclosingScope(code, m.start());
                assertTrue("G1 (I8): V2SBridge." + js.group(1) + " touches webView on the binder thread (line "
                        + lineOf(code, m.start()) + ")", scope != open && header(code, scope).endsWith("->"));
            }
        }
        assertTrue("G1: expected the Send Later bridge methods", sendLaterMethods >= 5);
    }

    // ------------------------------------------------------------------ G2 (I8)

    private static final Pattern FORCE_AUTO_SEND = Pattern.compile(
            "force_auto_send|FORCE_AUTO_SEND|forceAutoSend", Pattern.CASE_INSENSITIVE);

    /** G2 (I8): Send Later never asks for the Wear/Gemini force-auto-send path. */
    @Test
    public void g2_noForceAutoSend_underSendLater() throws IOException {
        for (Path p : sendLaterSources()) {
            String code = Lexed.of(read(p)).code; // literals kept: "force_auto_send" is a string
            assertFalse("G2 (I8): " + p + " mentions force_auto_send / EXTRA_FORCE_AUTO_SEND",
                    FORCE_AUTO_SEND.matcher(code).find());
            assertFalse("G2 (I8): " + p + " routes through SmsHandlerActivity (the force-auto-send router)",
                    code.contains("SmsHandlerActivity"));
        }
    }

    /**
     * G2 (I8): the activity's scheduled branch (injectScheduledComposer, ScheduledHost) never reads
     * the force_auto_send extra, and an active scheduled job is never handed to injectComposer().
     */
    @Test
    public void g2_activityScheduledBranch_noForceAutoSend() throws IOException {
        Lexed lx = Lexed.of(read(ACTIVITY));
        String code = lx.code;
        String inject = memberBody(lx, "injectScheduledComposer");
        assertNotNull("injectScheduledComposer not found", inject);
        String host = classBody(lx, "ScheduledHost");
        assertNotNull("ScheduledHost not found", host);
        for (String body : Arrays.asList(inject, host)) {
            assertFalse("G2 (I8): scheduled branch mentions force_auto_send", FORCE_AUTO_SEND.matcher(body).find());
            assertFalse("G2 (I8): scheduled branch reads the launch intent's extras",
                    body.contains("getIntent()"));
            assertFalse("G2 (I8): scheduled branch calls the review/auto-send composer",
                    Pattern.compile("\\binjectComposer\\s*\\(").matcher(body).find());
        }
        // The page-finished dispatch: injectComposer only when no scheduled job is active.
        Matcher d = Pattern.compile("if\\s*\\(\\s*scheduledSend\\s*==\\s*null\\s*\\|\\|\\s*!\\s*scheduledSend\\.isActive\\(\\)\\s*\\)"
                + "\\s*injectComposer\\(\\);\\s*else\\s+if\\s*\\(\\s*scheduledSend\\.isComposing\\(\\)\\s*\\)"
                + "\\s*injectScheduledComposer\\(\\);").matcher(code);
        assertTrue("G2 (I8): onPageFinished must route an active scheduled job to injectScheduledComposer, "
                + "never injectComposer", d.find());
        assertEquals("G2 (I8): injectComposer() must have exactly one call site (the gated dispatch)",
                1, count(lx.blanked, "\\binjectComposer\\s*\\(\\s*\\)\\s*;"));
    }

    // ------------------------------------------------------------------ G3 (I7)

    /** Identifiers that carry message text, drafts, notification previews or contact names. */
    private static final Set<String> SENSITIVE_PARTS = new HashSet<>(Arrays.asList(
            "draft", "drafts", "text", "texts", "body", "bodies", "preview", "snippet"));
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i).*(displayname|contactname|sendername|personname).*");

    /** True when a camelCase / snake_case part of {@code ident} names text-bearing data. */
    static boolean isSensitive(String ident) {
        if (SENSITIVE_NAME.matcher(ident).matches()) return true;
        for (String part : ident.split("_|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
            if (SENSITIVE_PARTS.contains(part.toLowerCase())) return true;
        }
        return false;
    }
    private static final Pattern LOG_CALL = Pattern.compile(
            "\\b(?:android\\.util\\.)?Log\\s*\\.\\s*(v|d|i|w|e|wtf|println)\\s*\\(");

    /**
     * G3 (I7): no Log.* argument under sendlater/ (nor in the activity's Send Later members)
     * names a text-bearing value (draft, text, body, preview, message.getText(), display or
     * contact names). The only exception is a hash under an explicit BuildConfig.DEBUG guard.
     * Also: no System.out / printStackTrace there.
     */
    @Test
    public void g3_noMessageTextInLogs() throws IOException {
        int calls = 0;
        List<String> bad = new ArrayList<>();
        for (Path p : sendLaterSources()) {
            String code = Lexed.of(read(p)).blanked;
            calls += collectSensitiveLogs(p.toString(), code, 0, code.length(), bad);
            assertFalse("G3 (I7): " + p + " prints to stdout/stderr",
                    Pattern.compile("\\bSystem\\s*\\.\\s*(out|err)\\b|\\.printStackTrace\\s*\\(").matcher(code).find());
        }
        String act = Lexed.of(read(ACTIVITY)).blanked;
        for (int[] r : activitySendLaterRegions(act)) {
            calls += collectSensitiveLogs("GVoiceWebViewActivity", act, r[0], r[1], bad);
        }
        assertTrue("G3 (I7): Log calls name message text / drafts / names:\n" + String.join("\n", bad), bad.isEmpty());
        assertTrue("G3: expected to inspect the Send Later Log calls (saw " + calls + ")", calls >= 20);
    }

    private static int collectSensitiveLogs(String where, String code, int from, int to, List<String> bad) {
        Matcher m = LOG_CALL.matcher(code);
        m.region(from, to);
        int n = 0;
        while (m.find()) {
            n++;
            int open = m.end() - 1;
            int close = matchingParen(code, open);
            String args = code.substring(open + 1, close - 1);
            String stmtPrefix = code.substring(statementStart(code, m.start()), m.start());
            boolean debugGuarded = stmtPrefix.matches("(?s).*\\bBuildConfig\\s*\\.\\s*DEBUG\\s*\\)\\s*$");
            Matcher id = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*").matcher(args);
            while (id.find()) {
                String ident = id.group();
                if (!isSensitive(ident)) continue;
                if (debugGuarded && ident.toLowerCase().contains("hash")) continue;
                bad.add(where + ":" + lineOf(code, m.start()) + " Log." + m.group(1) + " names '" + ident + "'");
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ G4 (I7, R8)

    /** The R8 recon ids the native Voice overlay may look up (spec R1, R2, R3, R33). */
    private static final Set<String> R8_IDS = new TreeSet<>(Arrays.asList(
            "toolbar_title", "send_message_text", "send_message_button", "input_message_container", "list"));
    private static final String VOICE_PKG = "com.google.android.apps.googlevoice";

    /** G4 (I7, R8): VoiceOverlayIds.R8_IDS is exactly the R8 id set, as full Voice resource ids. */
    @Test
    public void g4_voiceIdAllowlist_isExactlyR8() {
        Set<String> expected = new TreeSet<>();
        for (String n : R8_IDS) expected.add(VOICE_PKG + ":id/" + n);
        assertEquals("G4 (I7, R8): the Voice id allowlist changed; R8 must change first",
                expected, new TreeSet<>(VoiceOverlayIds.R8_IDS));
        assertFalse("G4 (I7): isAllowed accepts an id outside R8",
                VoiceOverlayIds.isAllowed(VOICE_PKG + ":id/message_text"));
        assertFalse("G4 (I7): isAllowed accepts null", VoiceOverlayIds.isAllowed(null));
    }

    /**
     * G4 (I7, R8): no other Voice resource id is spelled anywhere in the main tree (Java, XML,
     * assets). ":id/" literals are allowed only in the two R8 id holders (VoiceScreenSignature,
     * VoiceOverlayIds) and for Gemini ids in GeminiSmsInterceptService; every Voice id literal
     * there names an R8 id.
     */
    @Test
    public void g4_noOtherVoiceIdLiteral() throws IOException {
        Set<String> holders = new HashSet<>(Arrays.asList("VoiceScreenSignature.java", "VoiceOverlayIds.java"));
        List<Path> all;
        try (Stream<Path> s = Files.walk(Paths.get("src/main"))) {
            all = s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().matches(".*\\.(java|xml|js|json|txt)$"))
                    .sorted().collect(Collectors.toList());
        }
        Pattern idLiteral = Pattern.compile(":id/([A-Za-z0-9_]+)");
        Set<String> voiceIdsSeen = new TreeSet<>();
        for (Path p : all) {
            String name = p.getFileName().toString();
            String text = name.endsWith(".java") ? Lexed.of(read(p)).code : read(p);
            assertFalse("G4 (I7): Voice resource id spelled in " + p, text.contains("googlevoice:id/"));
            if (!name.endsWith(".java")) continue;
            if (holders.contains(name)) {
                Matcher pre = Pattern.compile("\\bPREFIX\\s*\\+\\s*\"([A-Za-z0-9_]+)\"").matcher(text);
                while (pre.find()) voiceIdsSeen.add(pre.group(1));
            }
            Matcher m = idLiteral.matcher(text);
            while (m.find()) {
                if (holders.contains(name)) {
                    voiceIdsSeen.add(m.group(1));
                } else if (name.equals("GeminiSmsInterceptService.java")) {
                    String before = text.substring(Math.max(0, m.start() - 40), m.start());
                    assertTrue("G4 (I7): non-Gemini ':id/' literal in GeminiSmsInterceptService: " + m.group(1),
                            before.matches("(?s).*GEMINI_PACKAGE\\s*\\+\\s*\"$"));
                } else {
                    fail("G4 (I7): ':id/" + m.group(1) + "' literal outside the R8 id holders in " + p);
                }
            }
            if (!holders.contains(name) && !name.equals("NotificationParser.java")) {
                assertFalse("G4 (I7): " + p + " builds ids from the Voice package constant",
                        Pattern.compile("VOICE_PACKAGE\\s*\\+").matcher(text).find());
            }
        }
        assertEquals("G4 (I7, R8): Voice id literals in the holders are exactly R8", R8_IDS, voiceIdsSeen);
    }

    // ------------------------------------------------------------------ G5 (I5, I8)

    /**
     * G5 (I5, I8): the scheduled path spaces and caps sends with ScheduledSendQueue
     * (acquireSlot + REFUSED handling in the sender) and never consults the interactive
     * AutoSendRateLimiter, neither under sendlater/ nor in the activity's scheduled branch.
     */
    @Test
    public void g5_scheduledPath_usesScheduledSendQueue_notAutoSendRateLimiter() throws IOException {
        for (Path p : sendLaterSources()) {
            String code = Lexed.of(read(p)).blanked;
            assertFalse("G5 (I8): " + p + " consults AutoSendRateLimiter",
                    Pattern.compile("\\bAutoSendRateLimiter\\b").matcher(code).find());
        }
        String act = Lexed.of(read(ACTIVITY)).blanked;
        for (int[] r : activitySendLaterRegions(act)) {
            assertFalse("G5 (I8): the activity's scheduled branch consults AutoSendRateLimiter (line "
                            + lineOf(act, r[0]) + ")",
                    act.substring(r[0], r[1]).contains("AutoSendRateLimiter"));
        }
        String start = memberBody(Lexed.of(read(SENDLATER.resolve("send/WebViewScheduledSender.java"))), "start");
        assertNotNull("WebViewScheduledSender.start not found", start);
        int slot = start.indexOf("ScheduledSendQueue.get(");
        assertTrue("G5 (I5): WebViewScheduledSender.start must take a ScheduledSendQueue slot",
                slot >= 0 && start.indexOf(".acquireSlot(", slot) > slot);
        assertTrue("G5 (I5): a REFUSED slot must stop the send",
                start.indexOf("ScheduledSendQueue.REFUSED") > slot);
        Matcher launch = Pattern.compile("\\blaunch\\s*\\(").matcher(start);
        assertTrue("G5 (I5): the launch is posted only after the slot was granted",
                launch.find() && launch.start() > start.indexOf("ScheduledSendQueue.REFUSED"));
    }

    // ------------------------------------------------------------------ G6 (I1)

    /**
     * G6 (I1): ACTION_SCHEDULED makes the capturing surface clear its draft, so it is built in
     * exactly one place, after SendLaterRepository.schedule(...) returned a positive id (a
     * PersistException or id <= 0 returns first), and it stays app-internal (setPackage).
     */
    @Test
    public void g6_actionScheduled_onlyAfterVerifiedPersist() throws IOException {
        Pattern build = Pattern.compile("new\\s+Intent\\s*\\(\\s*(?:SendLaterIntents\\s*\\.\\s*)?ACTION_SCHEDULED\\b"
                + "|setAction\\s*\\(\\s*(?:SendLaterIntents\\s*\\.\\s*)?ACTION_SCHEDULED\\b");
        List<String> sites = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(MAIN)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        String literal = "com.voice2sms.sendlater.action.SCHEDULED";
        for (Path p : files) {
            Lexed lx = Lexed.of(read(p));
            if (!p.endsWith(Paths.get("sendlater/SendLaterIntents.java"))) {
                assertFalse("G6 (I1): ACTION_SCHEDULED's literal is spelled outside SendLaterIntents in " + p,
                        lx.code.contains("\"" + literal + "\""));
            }
            Matcher m = build.matcher(lx.blanked);
            while (m.find()) {
                sites.add(p.getFileName().toString());
                int scope = enclosingScope(lx.blanked, m.start());
                String method = lx.blanked.substring(scope, m.start());
                int sched = method.indexOf("SendLaterRepository.get(");
                assertTrue("G6 (I1): ACTION_SCHEDULED built in " + p + " without a prior repository.schedule",
                        sched >= 0 && method.indexOf(".schedule(", sched) > sched);
                int afterSched = method.indexOf(".schedule(", sched);
                String tail = method.substring(afterSched);
                assertTrue("G6 (I1): the persist failure path must return before ACTION_SCHEDULED",
                        Pattern.compile("(?s)catch\\s*\\(\\s*PersistException\\b[^{]*\\{[^}]*\\breturn\\s*;").matcher(tail).find());
                assertTrue("G6 (I1): an id <= 0 must return before ACTION_SCHEDULED",
                        Pattern.compile("(?s)if\\s*\\(\\s*id\\s*<=\\s*0\\s*\\)\\s*\\{[^}]*\\breturn\\s*;").matcher(tail).find());
                String stmt = lx.blanked.substring(m.start(), lx.blanked.indexOf(';', m.start()));
                assertTrue("G6: ACTION_SCHEDULED must be app-internal (setPackage)", stmt.contains(".setPackage("));
            }
        }
        assertEquals("G6 (I1): ACTION_SCHEDULED must be built in exactly one place",
                Collections.singletonList("SendLaterSheetActivity.java"), sites);
    }

    // ------------------------------------------------------------------ G7 (AC12, I8)

    /** G7 (AC12, I8): the baseline guards are still there and none is skipped. */
    @Test
    public void g7_baselineSourceSafetyGuardsIntact() throws IOException {
        String src = read(TEST.resolve("SourceSafetyTest.java"));
        List<String> baseline = Arrays.asList(
                "voice2smsApplication_doesNotCallPrefetchUrlAsync", "v2sBridge_runOnUiThread_checksActivityAlive",
                "typingPaths_checkActivityAlive", "requestAuthToken_callbackChecksActivityAlive",
                "loadGoogleVoice_nullChecksWebView", "richContentWebView_advertisesExpectedMimeTypes",
                "wearListener_isIntentTrampoline_noWebViewCoupling", "wearListener_requestId_mandatoryWithDedup",
                "wearListener_alwaysPostsNotificationFallback", "wearListener_setsForceAutoSend",
                "wearListener_consumeDataItem_wildcardAuthority", "injectComposer_consultsAutoSendRateLimiter",
                "settings_versionStamp_includesShaAndDate", "wear_sendToReceiver_isNoDisplayTrampoline",
                "wear_manifest_declaresStandalone", "wear_manifest_declaresSendtoFilter",
                "wear_gradle_matchesPhoneApplicationId", "wear_validator_mirrorsPhoneSideRules",
                "wear_pathPrefix_matchesPhoneSide");
        String code = Lexed.of(src).blanked;
        for (String name : baseline) {
            assertTrue("G7 (AC12): SourceSafetyTest lost its guard " + name,
                    Pattern.compile("@Test\\s+public\\s+void\\s+" + name + "\\s*\\(").matcher(code).find());
        }
        Pattern skip = Pattern.compile("@\\s*(?:org\\s*\\.\\s*junit\\s*\\.\\s*)?Ignore\\b|\\bAssume\\s*\\.|\\bassume[A-Z]\\w*\\s*\\(");
        assertFalse("G7 (AC12): SourceSafetyTest must not skip guards", skip.matcher(code).find());
        assertFalse("G7 (AC12): this class must not skip guards", skip.matcher(Lexed.of(read(TEST.resolve(
                "SendLaterSourceSafetyTest.java"))).blanked).find());
    }

    // ------------------------------------------------------------------ helpers

    private static String read(Path p) throws IOException {
        assertTrue("source file missing: " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), "UTF-8");
    }

    private static List<Path> sendLaterSources() throws IOException {
        try (Stream<Path> s = Files.walk(SENDLATER)) {
            List<Path> list = s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
            assertTrue("expected the sendlater/ sources", list.size() > 30);
            return list;
        }
    }

    /**
     * Source with comments removed ({@link #code}), and the same with string / char literal
     * contents replaced by spaces ({@link #blanked}) so braces and "//" inside literals never
     * confuse the brace matching. Both keep every other character at its offset.
     */
    static final class Lexed {
        final String code;
        final String blanked;

        private Lexed(String code, String blanked) {
            this.code = code;
            this.blanked = blanked;
        }

        static Lexed of(String src) {
            StringBuilder code = new StringBuilder(src.length());
            StringBuilder blank = new StringBuilder(src.length());
            int i = 0, n = src.length();
            while (i < n) {
                char c = src.charAt(i);
                char next = i + 1 < n ? src.charAt(i + 1) : 0;
                if (c == '/' && next == '/') {
                    while (i < n && src.charAt(i) != '\n') { code.append(' '); blank.append(' '); i++; }
                } else if (c == '/' && next == '*') {
                    int end = src.indexOf("*/", i + 2);
                    end = end < 0 ? n : end + 2;
                    for (; i < end; i++) {
                        char k = src.charAt(i) == '\n' ? '\n' : ' ';
                        code.append(k);
                        blank.append(k);
                    }
                } else if (c == '"' && src.startsWith("\"\"\"", i)) {
                    throw new AssertionError("text blocks are not Java 8");
                } else if (c == '"' || c == '\'') {
                    code.append(c);
                    blank.append(c);
                    i++;
                    while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
                        if (src.charAt(i) == '\\' && i + 1 < n) {
                            code.append(src, i, i + 2);
                            blank.append("  ");
                            i += 2;
                        } else {
                            code.append(src.charAt(i));
                            blank.append(' ');
                            i++;
                        }
                    }
                    if (i < n) {
                        code.append(src.charAt(i));
                        blank.append(src.charAt(i));
                        i++;
                    }
                } else {
                    code.append(c);
                    blank.append(c);
                    i++;
                }
            }
            return new Lexed(code.toString(), blank.toString());
        }
    }

    /** Index just after the brace matching the '{' at {@code open}. */
    private static int matchingBrace(String code, int open) {
        assertEquals("not an opening brace at " + open, '{', code.charAt(open));
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i + 1;
        }
        throw new AssertionError("unbalanced braces from " + open);
    }

    /** Index just after the paren matching the '(' at {@code open}. */
    private static int matchingParen(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i + 1;
        }
        throw new AssertionError("unbalanced parens from " + open);
    }

    /** Start of the statement containing {@code idx}: after the previous top-level ; { or }. */
    private static int statementStart(String code, int idx) {
        int depth = 0;
        for (int i = idx - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                if (depth == 0) continue; // inside the call's own argument list
                depth--;
            } else if (depth == 0 && (c == ';' || c == '{' || c == '}')) return i + 1;
        }
        return 0;
    }

    /** The trimmed text between the previous statement boundary and the '{' at {@code open}. */
    private static String header(String code, int open) {
        return code.substring(statementStart(code, open), open).trim();
    }

    private static final Pattern CONTROL = Pattern.compile(
            "^(?:if|else|for|while|do|try|catch|finally|synchronized|switch|case|default|static)\\b.*", Pattern.DOTALL);

    /**
     * The '{' of the innermost method body, lambda body or class body enclosing {@code idx}
     * (control blocks such as if / try / for are skipped), or -1 at top level.
     */
    private static int enclosingScope(String code, int idx) {
        List<Integer> stack = new ArrayList<>();
        for (int i = 0; i < idx; i++) {
            char c = code.charAt(i);
            if (c == '{') stack.add(i);
            else if (c == '}' && !stack.isEmpty()) stack.remove(stack.size() - 1);
        }
        for (int k = stack.size() - 1; k >= 0; k--) {
            int open = stack.get(k);
            String h = header(code, open);
            if (CONTROL.matcher(h).matches() || h.isEmpty() || h.endsWith("=")) continue;
            if (Pattern.compile("(?s).*\\b(class|interface|enum)\\s+\\w+.*").matcher(h).matches()
                    || h.matches("(?s).*new\\s+[\\w.<>]+\\s*\\([^)]*\\)$")) {
                return k == stack.size() - 1 ? -1 : stack.get(k + 1); // field / instance initialiser level
            }
            return open;
        }
        return -1;
    }

    /** [start, end) of each Send Later member of GVoiceWebViewActivity (blanked source). */
    private static List<int[]> activitySendLaterRegions(String code) {
        List<int[]> out = new ArrayList<>();
        for (String m : ACTIVITY_SENDLATER_METHODS) {
            Matcher d = Pattern.compile("\\b(?:private|public|protected)\\s+[\\w<>\\[\\]]+\\s+" + m + "\\s*\\(").matcher(code);
            assertTrue("G1: GVoiceWebViewActivity." + m + " not found", d.find());
            int open = code.indexOf('{', d.end());
            out.add(new int[] {d.start(), matchingBrace(code, open)});
        }
        for (String c : ACTIVITY_SENDLATER_CLASSES) {
            int at = code.indexOf("class " + c);
            assertTrue("G1: GVoiceWebViewActivity." + c + " not found", at >= 0);
            out.add(new int[] {at, matchingBrace(code, code.indexOf('{', at))});
        }
        for (String f : ACTIVITY_SENDLATER_FIELDS) {
            Matcher d = Pattern.compile("\\b" + f + "\\s*=\\s*new\\b").matcher(code);
            assertTrue("G1: GVoiceWebViewActivity." + f + " not found", d.find());
            out.add(new int[] {d.start(), matchingBrace(code, code.indexOf('{', d.end()))});
        }
        return out;
    }

    /** Body (declaration through closing brace) of the first method named {@code name}. */
    private static String memberBody(Lexed lx, String name) {
        String code = lx.blanked;
        Matcher d = Pattern.compile("\\b(?:private|public|protected|static|final|synchronized|\\s)*[\\w<>\\[\\]]+\\s+"
                + name + "\\s*\\([^;{]*\\)\\s*(?:throws[^{]*)?\\{").matcher(code);
        if (!d.find()) return null;
        int open = d.end() - 1;
        return lx.code.substring(d.start(), matchingBrace(code, open));
    }

    /** Declaration through closing brace of the class named {@code name}, literals kept. */
    private static String classBody(Lexed lx, String name) {
        String code = lx.blanked;
        int at = code.indexOf("class " + name);
        if (at < 0) return null;
        return lx.code.substring(at, matchingBrace(code, code.indexOf('{', at)));
    }

    private static int count(String code, String regex) {
        Matcher m = Pattern.compile(regex).matcher(code);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static int lineOf(String code, int idx) {
        int line = 1;
        for (int i = 0; i < idx && i < code.length(); i++) if (code.charAt(i) == '\n') line++;
        return line;
    }
}
