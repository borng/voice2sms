package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.voice2sms.sendlater.domain.ConversationRef;
import com.voice2sms.sendlater.domain.ThreadEventSource;
import com.voice2sms.sendlater.engine.OutboundCapableSource;
import com.voice2sms.sendlater.engine.ScheduledSender;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Source-level and wiring guards for the send-path node (R27, R30, R31, I5, I8, OD10/OD11/OD21).
 * Paths are relative to the app module, like the other Send Later source tests. Comments are
 * stripped before matching; string literals are kept (a literal "force_auto_send" is exactly what
 * must not appear).
 */
public class SendPathSourceTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java/com/voice2sms");
    private static final Path SENDLATER = MAIN_JAVA.resolve("sendlater");
    private static final Path SEND = SENDLATER.resolve("send");
    private static final Path SENDER = SEND.resolve("WebViewScheduledSender.java");
    private static final Path CONTROLLER = SEND.resolve("ScheduledSendController.java");
    private static final Path THREAD_SOURCE = SEND.resolve("WebViewThreadEventSource.java");
    private static final Path ACTIVITY = MAIN_JAVA.resolve("GVoiceWebViewActivity.java");
    private static final Path APPLICATION = MAIN_JAVA.resolve("Voice2SmsApplication.java");
    private static final Path MANIFEST = Paths.get("src/main/AndroidManifest.xml");

    private static final Pattern FORCE = Pattern.compile("force_auto_send|FORCE_AUTO_SEND");

    // ================================================================== helpers

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

    /** Removes comments, keeping string and char literals intact (lexer, so "https://" survives). */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i += 2;
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                char q = c;
                out.append(c);
                i++;
                while (i < n && s.charAt(i) != q) {
                    if (s.charAt(i) == '\\' && i + 1 < n) { out.append(s.charAt(i)); i++; }
                    out.append(s.charAt(i));
                    i++;
                }
                if (i < n) { out.append(q); i++; }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Same length as {@code s}, with string/char literal contents blanked (for brace matching). */
    static String blankLiterals(String s) {
        StringBuilder out = new StringBuilder(s);
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                char q = c;
                i++;
                while (i < n && s.charAt(i) != q) {
                    if (s.charAt(i) == '\\' && i + 1 < n) { out.setCharAt(i, ' '); i++; }
                    out.setCharAt(i, ' ');
                    i++;
                }
                i++;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static String code(Path p) throws IOException {
        return stripComments(read(p));
    }

    /** Index just past the matching close of the bracket at {@code open} in a literal-blanked text. */
    private static int matchClose(String blank, int open) {
        char o = blank.charAt(open);
        char c = o == '{' ? '}' : ')';
        int depth = 0;
        for (int i = open; i < blank.length(); i++) {
            char ch = blank.charAt(i);
            if (ch == o) depth++;
            else if (ch == c) {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    /** Body (including braces) of the first method declared as {@code name(...) {...}}, or null. */
    static String methodBody(String src, String name) {
        String blank = blankLiterals(src);
        Matcher m = Pattern.compile("(?:void|boolean|int|long|public|private|protected|static|final|synchronized|[A-Z][\\w<>,?\\[\\] ]*)\\s+" + Pattern.quote(name)
                + "\\s*\\(").matcher(blank);
        while (m.find()) {
            int paren = blank.indexOf('(', m.end() - 1);
            int afterParams = matchClose(blank, paren);
            if (afterParams < 0) continue;
            int k = afterParams;
            while (k < blank.length() && blank.charAt(k) != '{' && blank.charAt(k) != ';') k++;
            if (k >= blank.length() || blank.charAt(k) == ';') continue;
            int end = matchClose(blank, k);
            if (end > 0) return src.substring(k, end);
        }
        return null;
    }

    private static String sendPackageCode() throws IOException {
        List<Path> files = javaUnder(SEND);
        assertFalse("sendlater/send has no sources yet", files.isEmpty());
        StringBuilder sb = new StringBuilder();
        for (Path p : files) sb.append(code(p)).append('\n');
        return sb.toString();
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, SendPathSourceTest.class.getClassLoader());
    }

    private static int firstOf(String src, String... needles) {
        int best = -1;
        for (String n : needles) {
            int i = src.indexOf(n);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    /** Field names of type ScheduledSendController declared in the activity. */
    private static Set<String> controllerFields(String activity) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("ScheduledSendController\\s+(\\w+)\\s*[;=]").matcher(activity);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static boolean mentionsScheduled(String text, Set<String> fields) {
        if (text.contains("EXTRA_SCHEDULED_MESSAGE_ID") || text.contains("ScheduledSendController")) return true;
        for (String f : fields) {
            if (Pattern.compile("\\b" + Pattern.quote(f) + "\\b").matcher(text).find()) return true;
        }
        return false;
    }

    private static final Pattern CALL = Pattern.compile("\\b([a-z]\\w*)\\s*\\(");

    /**
     * {@code body} with each call to a method declared in {@code src} replaced by that method's
     * body, recursively up to {@code depth} levels, so call order can be checked textually even
     * when the work is split into helpers.
     */
    static String expand(String src, String body, int depth) {
        if (body == null) return null;
        if (depth <= 0) return body;
        String blank = blankLiterals(body);
        Matcher m = CALL.matcher(blank);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String name = m.group(1);
            if (isDeclaration(blank, m.start())) continue;
            String callee = methodBody(src, name);
            if (callee == null || callee.equals(body)) continue;
            out.append(body, last, m.end());
            out.append(' ').append(expand(src, callee, depth - 1)).append(' ');
            last = m.end();
        }
        out.append(body.substring(last));
        return out.toString();
    }

    /** True when the identifier at {@code at} is being declared (preceded by a type), not called. */
    private static boolean isDeclaration(String blank, int at) {
        int k = at - 1;
        while (k >= 0 && Character.isWhitespace(blank.charAt(k))) k--;
        if (k < 0) return false;
        char c = blank.charAt(k);
        if (c == '.' || c == '(' || c == ',' || c == '=' || c == '!' || c == '&' || c == '|' || c == '?'
                || c == ':' || c == ';' || c == '{' || c == '}' || c == '+' || c == '-' || c == '>') {
            return c == '>' && k > 0 && blank.charAt(k - 1) != '-';  // generic return type, not lambda
        }
        int e = k + 1;
        while (k >= 0 && (Character.isJavaIdentifierPart(blank.charAt(k)) || blank.charAt(k) == ']'
                || blank.charAt(k) == '[')) k--;
        String word = blank.substring(k + 1, e);
        return !(word.equals("return") || word.equals("new") || word.equals("throw") || word.equals("else")
                || word.equals("case"));
    }

    /**
     * True when {@code body} (an activity method) applies setShowWhenLocked directly, through one
     * private activity helper, or through a ScheduledSendController method that does.
     */
    private static boolean appliesShowWhenLocked(String body, String act, String controller, Set<String> fields) {
        String needle = "setShowWhenLocked(";
        String direct = expand(act, body, 1);
        if (direct.contains(needle)) return true;
        Set<String> receivers = new LinkedHashSet<>(fields);
        receivers.add("ScheduledSendController");
        for (String r : receivers) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(r) + "\\s*\\.\\s*(\\w+)\\s*\\(").matcher(direct);
            while (m.find()) {
                String cb = expand(controller, methodBody(controller, m.group(1)), 2);
                if (cb != null && cb.contains(needle)) return true;
            }
        }
        if (direct.contains("new ScheduledSendController(")) {
            String ctor = expand(controller, methodBody(controller, "ScheduledSendController"), 2);
            if (ctor != null && ctor.contains(needle)) return true;
        }
        return false;
    }

    /** Bodies of if-blocks whose condition positively names the scheduled branch. */
    private static List<String> scheduledIfBodies(String src, Set<String> fields) {
        String blank = blankLiterals(src);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\bif\\s*\\(").matcher(blank);
        while (m.find()) {
            int open = blank.indexOf('(', m.start());
            int close = matchClose(blank, open);
            if (close < 0) continue;
            String cond = src.substring(open, close);
            if (!mentionsScheduled(cond, fields) && !Pattern.compile("\\b[iI]sScheduled\\w*\\b|\\bscheduled\\w*\\b")
                    .matcher(cond).find()) continue;
            if (Pattern.compile("!\\s*\\(?\\s*[\\w.]*[sS]cheduled").matcher(cond).find()) continue; // negated
            if (cond.contains("AutoSendRateLimiter")) continue;
            int k = close;
            while (k < blank.length() && Character.isWhitespace(blank.charAt(k))) k++;
            if (k < blank.length() && blank.charAt(k) == '{') {
                int end = matchClose(blank, k);
                if (end > 0) out.add(src.substring(k, end));
            } else {
                int semi = blank.indexOf(';', k);
                if (semi > 0) out.add(src.substring(k, semi + 1));
            }
        }
        return out;
    }

    // ================================================================== interfaces

    @Test public void webViewScheduledSenderImplementsScheduledSender() throws Exception {
        Class<?> c = load("com.voice2sms.sendlater.send.WebViewScheduledSender");
        assertTrue(ScheduledSender.class.isAssignableFrom(c));
        assertFalse(Modifier.isAbstract(c.getModifiers()));
    }

    @Test public void webViewThreadEventSourceIsAnOutboundCapableThreadSource() throws Exception {
        Class<?> c = load("com.voice2sms.sendlater.send.WebViewThreadEventSource");
        assertTrue("OD21: must implement OutboundCapableSource or in-flight rows never retry",
                OutboundCapableSource.class.isAssignableFrom(c));
        assertTrue(ThreadEventSource.class.isAssignableFrom(c));
        assertFalse(Modifier.isAbstract(c.getModifiers()));
    }

    @Test public void webViewThreadEventSourceIsAvailableOnlyForTheOpenThread() throws Exception {
        Class<?> c = load("com.voice2sms.sendlater.send.WebViewThreadEventSource");
        Method m = c.getDeclaredMethod("isAvailableFor", ConversationRef.class);
        assertNotNull("isAvailableFor(ref) must be overridden: available only while the activity shows that thread", m);
        String src = code(THREAD_SOURCE);
        assertTrue("availability is tied to the live GVoiceWebViewActivity",
                src.contains("SendLaterWebHooks.active()") || src.contains("GVoiceWebViewActivity"));
        assertTrue("isAvailable/readSince read the WebView thread", src.contains("readThread("));
        assertTrue("events must carry textHash from ThreadEvent.hashOf (landed rule)",
                src.contains("toThreadEvent(") || src.contains("hashOf("));
    }

    @Test public void scheduledSendControllerExists() throws Exception {
        load("com.voice2sms.sendlater.send.ScheduledSendController");
        String src = code(CONTROLLER);
        assertTrue(src.contains("EXTRA_SCHEDULED_MESSAGE_ID"));
    }

    // ================================================================== no force_auto_send (R30, I8)

    @Test public void noForceAutoSendAnywhereUnderSendLater() throws IOException {
        List<Path> files = javaUnder(SENDLATER);
        assertFalse(files.isEmpty());
        assertTrue("sendlater/send has no sources yet", Files.isDirectory(SEND) && !javaUnder(SEND).isEmpty());
        for (Path p : files) {
            assertFalse(p + " must never use force_auto_send / EXTRA_FORCE_AUTO_SEND",
                    FORCE.matcher(code(p)).find());
        }
    }

    @Test public void scheduledLaunchIntentCarriesOnlyTheScheduledMessageId() throws IOException {
        String src = code(SENDER);
        assertTrue("launch carries EXTRA_SCHEDULED_MESSAGE_ID", src.contains("EXTRA_SCHEDULED_MESSAGE_ID"));
        assertTrue(src.contains("putExtra("));
        assertFalse(FORCE.matcher(src).find());
        assertFalse("never routes through SmsHandlerActivity (the Wear/auto-send trampoline)",
                src.contains("SmsHandlerActivity"));
    }

    @Test public void scheduledBranchOfActivityNeverUsesForceAutoSendOrTheRateLimiter() throws IOException {
        String act = code(ACTIVITY);
        Set<String> fields = controllerFields(act);
        assertTrue("GVoiceWebViewActivity has no scheduled branch yet", mentionsScheduled(act, fields));
        List<String> bodies = scheduledIfBodies(act, fields);
        for (String b : bodies) {
            assertFalse("scheduled branch must not read or set force_auto_send:\n" + b, FORCE.matcher(b).find());
            assertFalse("scheduled branch must not consult AutoSendRateLimiter:\n" + b,
                    b.contains("AutoSendRateLimiter"));
        }
        for (String line : act.split("\n")) {
            if (mentionsScheduled(line, fields)) {
                assertFalse("scheduled line mixes in force_auto_send: " + line.trim(), FORCE.matcher(line).find());
                assertFalse("scheduled line mixes in AutoSendRateLimiter: " + line.trim(),
                        line.contains("AutoSendRateLimiter"));
            }
        }
    }

    // ================================================================== queue, not rate limiter (R30)

    @Test public void sendPackageUsesScheduledSendQueueNotAutoSendRateLimiter() throws IOException {
        String all = sendPackageCode();
        assertFalse("scheduled path never consults AutoSendRateLimiter", all.contains("AutoSendRateLimiter"));
        String sender = code(SENDER);
        assertTrue("sender acquires a ScheduledSendQueue slot", sender.contains("ScheduledSendQueue"));
        assertTrue(sender.contains("acquireSlot("));
        assertTrue("a refused slot (daily cap) must be handled", sender.contains("REFUSED"));
    }

    // ================================================================== Schrodinger rule (R31, I5)

    @Test public void markInFlightIsCalledBeforeTheSendIsTriggered() throws IOException {
        String src = code(SENDER);
        String start = methodBody(src, "start");
        assertNotNull("WebViewScheduledSender.start(Context, ScheduledMessage) missing", start);
        String flow = expand(src, start, 3);
        int slot = flow.indexOf("acquireSlot(");
        int mark = flow.indexOf("markInFlight(");
        int trigger = firstOf(flow, "startActivity(", "setFullScreenIntent(", ".notify(");
        assertTrue("start must call markInFlight(id, bodyHash)", mark >= 0);
        assertTrue("start must trigger a launch (startActivity or a full-screen-intent notification)", trigger >= 0);
        assertTrue("queue slot acquired before markInFlight", slot >= 0 && slot < mark);
        assertTrue("markInFlight must precede every send trigger", mark < trigger);
        assertTrue("body hash comes from ThreadEvent.hashOf", src.contains("hashOf("));
    }

    @Test public void noRetryCallSiteWithoutAPrecedingThreadRead() throws IOException {
        Pattern retry = Pattern.compile("\\b(\\w*[rR]etry\\w*)\\s*\\(");
        for (Path p : javaUnder(SEND)) {
            String src = code(p);
            Matcher m = retry.matcher(blankLiterals(src));
            while (m.find()) {
                if (isDeclaration(blankLiterals(src), m.start())) continue;
                int read = firstOf(src.substring(0, m.start()), "readThread(", "resolveInFlight(");
                assertTrue(p.getFileName() + ": retry call '" + m.group(1)
                        + "' without a preceding readThread/resolveInFlight (I5)", read >= 0);
            }
        }
        assertFalse(javaUnder(SEND).isEmpty());
    }

    @Test public void sendPackageNeverWritesTerminalStatusOrSchedulesRetriesItself() throws IOException {
        String all = blankLiterals(sendPackageCode());
        for (String banned : new String[] {"markSentVerified(", "releaseForRetry(", "registerRetry(",
                "SendLaterEngine.onDue(", "requestDismissKeyguard("}) {
            assertFalse("send path must not call " + banned + " (engine owns status and retries)",
                    all.contains(banned));
        }
    }

    @Test public void controllerVerifiesByReadingTheThreadBeforeReportingTheOutcome() throws IOException {
        String src = code(CONTROLLER);
        assertTrue("controller reads the thread after the send (webview-capture readThread)", src.contains("readThread("));
        assertTrue("controller uses SendVerifier.verify", src.contains("SendVerifier.verify("));
        assertTrue("controller reports through SendLaterEngine.onSendOutcome", src.contains("onSendOutcome("));
        assertTrue("verified path reports SENT_VERIFIED", src.contains("SENT_VERIFIED"));
        assertTrue("no-match path reports SENT_UNVERIFIED", src.contains("SENT_UNVERIFIED"));
        assertTrue("failures before anything is typed report CANNOT_SHOW", src.contains("SendOutcome.CANNOT_SHOW"));
        assertTrue("late reply is distinguished", src.contains("VERIFIED_WITH_LATE_REPLY"));
        for (Path p : javaUnder(SEND)) {
            String f = code(p);
            if (f.contains("SENT_VERIFIED")) {
                assertTrue(p.getFileName() + " reports SENT_VERIFIED without reading the thread",
                        f.contains("readThread(") && f.contains("SendVerifier.verify("));
            }
        }
    }

    // ================================================================== launch (R27, R30, D15)

    @Test public void senderLaunchesGVoiceWebViewActivityPerLaunchPlanner() throws IOException {
        String src = code(SENDER);
        assertTrue(src.contains("LaunchPlanner.plan("));
        assertTrue(src.contains("GVoiceWebViewActivity"));
        assertTrue("launch uses FLAG_ACTIVITY_NEW_TASK", src.contains("FLAG_ACTIVITY_NEW_TASK"));
        assertTrue("DIRECT path starts the activity", src.contains("startActivity("));
        assertTrue("FULL_SCREEN_INTENT path posts a full-screen notification", src.contains("setFullScreenIntent("));
        assertTrue("the full-screen PendingIntent is immutable", src.contains("FLAG_IMMUTABLE"));
        assertTrue("canUseFullScreenIntent (API 34) is consulted", src.contains("canUseFullScreenIntent("));
        assertTrue("canUseFullScreenIntent is API 34+: guard it",
                src.contains("UPSIDE_DOWN_CAKE") || src.contains(">= 34"));
        assertTrue("keyguard state is read", src.contains("isKeyguardLocked("));
        assertTrue("screen state is read", src.contains("isInteractive("));
        assertTrue("CANNOT_SHOW returns false so the engine records HOLD/REMIND(LOCKED)",
                src.contains("CANNOT_SHOW") && src.contains("return false"));
        assertFalse("no ScheduledSendActivity in the MVP path", src.contains("ScheduledSendActivity"));
        assertFalse("no foreground service in the MVP path", src.contains("startForegroundService("));
    }

    @Test public void noScheduledSendActivityOrForegroundServiceAnywhere() throws IOException {
        assertFalse("sendlater/send has no sources yet", javaUnder(SEND).isEmpty());
        assertFalse(Files.exists(SEND.resolve("ScheduledSendActivity.java")));
        for (Path p : javaUnder(SENDLATER)) {
            String src = code(p);
            assertFalse(p + ": OD10 no ScheduledSendActivity", src.contains("ScheduledSendActivity"));
            assertFalse(p + ": OD10 no foreground service", src.contains("startForeground("));
            assertFalse(p + ": OD10 no foreground service", src.contains("startForegroundService("));
        }
    }

    // ================================================================== lock screen (R30)

    @Test public void setShowWhenLockedAppliedInOnCreateAndOnNewIntent() throws IOException {
        String act = code(ACTIVITY);
        Set<String> fields = controllerFields(act);
        String onCreate = methodBody(act, "onCreate");
        String onNewIntent = methodBody(act, "onNewIntent");
        assertNotNull(onCreate);
        assertNotNull(onNewIntent);
        String controller = code(CONTROLLER);
        assertTrue("onCreate must apply setShowWhenLocked for the scheduled branch",
                appliesShowWhenLocked(onCreate, act, controller, fields));
        assertTrue("onNewIntent must apply setShowWhenLocked for the scheduled branch",
                appliesShowWhenLocked(onNewIntent, act, controller, fields));
        String both = act + "\n" + controller;
        assertTrue(both.contains("setShowWhenLocked(true)"));
        assertTrue(both.contains("setTurnScreenOn(true)"));
        assertTrue("below API 27 the window flags are used",
                both.contains("FLAG_SHOW_WHEN_LOCKED") && both.contains("FLAG_TURN_SCREEN_ON"));
        assertTrue("setShowWhenLocked is API 27+: guard it", both.contains("O_MR1") || both.contains(">= 27"));
        assertFalse("never requestDismissKeyguard", both.contains("requestDismissKeyguard"));
    }

    @Test public void activityFinishesAfterALockScreenSend() throws IOException {
        String src = code(CONTROLLER);
        assertTrue("controller remembers whether it was launched over the keyguard",
                src.contains("isKeyguardLocked(") || src.contains("KeyguardManager"));
        boolean finishesAfterReport = false;
        Matcher m = Pattern.compile("\\b([a-zA-Z]\\w*)\\s*\\(").matcher(blankLiterals(src));
        while (m.find() && !finishesAfterReport) {
            if (!isDeclaration(blankLiterals(src), m.start())) continue;
            String flow = expand(src, methodBody(src, m.group(1)), 3);
            if (flow == null) continue;
            int outcome = flow.indexOf("onSendOutcome(");
            finishesAfterReport = outcome >= 0 && flow.indexOf("finish()", outcome) > outcome;
        }
        assertTrue("after reporting the outcome the activity is finished (not left on the lock screen)",
                finishesAfterReport);
    }

    // ================================================================== registration

    @Test public void applicationSetsSenderBeforeOnProcessStartAndRegistersSource() throws IOException {
        String app = code(APPLICATION);
        String onCreate = methodBody(app, "onCreate");
        assertNotNull(onCreate);
        int set = onCreate.indexOf("setSender(");
        int start = onCreate.indexOf("onProcessStart(");
        assertTrue("SendLaterEngine.setSender in Application.onCreate", set >= 0);
        assertTrue("onProcessStart line must stay", start >= 0);
        assertTrue("setSender BEFORE onProcessStart so restart retries have a sender", set < start);
        int lineStart = onCreate.lastIndexOf('\n', set) + 1;
        int lineEnd = onCreate.indexOf('\n', set);
        String line = onCreate.substring(lineStart, lineEnd < 0 ? onCreate.length() : lineEnd);
        assertTrue("setSender line installs WebViewScheduledSender", line.contains("WebViewScheduledSender"));
        assertEquals("exactly one setSender call", 1, onCreate.split("setSender\\(", -1).length - 1);

        boolean registered = false;
        for (Path p : javaUnder(MAIN_JAVA)) {
            String s = code(p);
            if (s.contains("registerSource(") && s.contains("WebViewThreadEventSource")
                    && !p.getFileName().toString().equals("WebViewThreadEventSource.java")) registered = true;
            if (p.getFileName().toString().equals("WebViewThreadEventSource.java")
                    && s.contains("registerSource(")) registered = true;
        }
        assertTrue("WebViewThreadEventSource must be registered with SendLaterEngine.registerSource", registered);
    }

    // ================================================================== manifest

    @Test public void manifestDeclaresFullScreenIntentPermissionAndSendPathBlock() throws IOException {
        String m = read(MANIFEST);
        assertTrue("USE_FULL_SCREEN_INTENT permission",
                m.contains("<uses-permission android:name=\"android.permission.USE_FULL_SCREEN_INTENT\" />")
                        || m.contains("<uses-permission android:name=\"android.permission.USE_FULL_SCREEN_INTENT\"/>"));
        int start = m.indexOf("<!-- sendlater:send-path start -->");
        int end = m.indexOf("<!-- sendlater:send-path end -->");
        assertTrue("sendlater:send-path start/end markers", start >= 0 && end > start);
        int act = m.indexOf("android:name=\".GVoiceWebViewActivity\"");
        assertTrue(act >= 0);
        int gt = m.indexOf('>', act);
        int actEnd = m.charAt(gt - 1) == '/' ? gt : m.indexOf("</activity>", act);
        assertTrue("send-path block sits after the GVoiceWebViewActivity element", start > actEnd);
        String between = m.substring(actEnd, start);
        assertFalse("send-path block directly follows GVoiceWebViewActivity", between.contains("<activity"));
        String block = m.substring(start, end);
        assertFalse("OD10: no ScheduledSendActivity", block.contains("<activity"));
        assertFalse("OD10: no foreground service", block.contains("<service"));
        assertFalse(m.contains("ScheduledSendActivity"));
        assertFalse("OD10: no foreground-service permission for the send path",
                m.contains("android.permission.FOREGROUND_SERVICE"));
    }

    @Test public void sendPackageNeverLogsMessageText() throws IOException {
        for (Path p : javaUnder(SEND)) {
            String src = blankLiterals(code(p));
            Matcher m = Pattern.compile("Log\\.[vdiwe]\\s*\\(([^;]*);").matcher(src);
            while (m.find()) {
                String call = m.group(1);
                if (Pattern.compile("getText\\(\\)|\\bbody\\b|\\btext\\b|getRecipientDisplayName").matcher(call).find()) {
                    fail(p.getFileName() + " logs message text or a name: Log." + call);
                }
            }
        }
        assertFalse(javaUnder(SEND).isEmpty());
    }
}
