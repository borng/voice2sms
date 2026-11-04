package com.voice2sms.sendlater.send;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** send-lock-cover acceptance: source checks on the cover, the controller and the activity. */
public class LockCoverSourceTest {

    private static final Path SEND = Paths.get("src/main/java/com/voice2sms/sendlater/send");
    private static final Path CONTROLLER = SEND.resolve("ScheduledSendController.java");
    private static final Path COVER = SEND.resolve("LockCover.java");
    private static final Path ACTIVITY = Paths.get("src/main/java/com/voice2sms/GVoiceWebViewActivity.java");
    private static final Path LAYOUT = Paths.get("src/main/res/layout/sendlater_send_cover.xml");
    private static final Path STRINGS = Paths.get("src/main/res/values/sendlater_send.xml");

    private static String raw(Path p) throws IOException {
        assertTrue("missing " + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String code(Path p) throws IOException {
        return SendPathSourceTest.stripComments(raw(p));
    }

    private static String body(String src, String name) {
        String b = SendPathSourceTest.methodBody(src, name);
        assertNotNull(name + " missing", b);
        return b;
    }

    @Test public void theCoverViewConsumesTouchKeyAndLongPress() throws IOException {
        String show = body(code(COVER), "show");
        assertTrue(show.contains("setOnTouchListener((view, event) -> true)"));
        assertTrue(show.contains("setOnKeyListener((view, keyCode, event) -> true)"));
        assertTrue(show.contains("setOnGenericMotionListener((view, event) -> true)"));
        assertTrue(show.contains("setOnLongClickListener(view -> true)"));
        assertTrue("screen readers cannot drive the page underneath",
                show.contains("IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"));
        assertTrue("no soft keyboard while covered", show.contains("FLAG_ALT_FOCUSABLE_IM"));
        String layout = raw(LAYOUT);
        assertTrue("opaque", layout.contains("android:background=\"#FF"));
        assertTrue(layout.contains("android:importantForAccessibility=\"yes\""));
        assertTrue(layout.contains("android:clickable=\"true\""));
    }

    @Test public void theActivityDropsTouchKeyAndBackWhileCovered() throws IOException {
        String act = code(ACTIVITY);
        for (String m : new String[] {"dispatchTouchEvent", "dispatchKeyEvent", "dispatchGenericMotionEvent", "onBackPressed"}) {
            Matcher d = Pattern.compile("public\\s+\\w+\\s+" + m + "\\s*\\(").matcher(act);
            assertTrue(m + " override missing", d.find());
            String b = act.substring(d.start());
            b = b.substring(0, b.indexOf("\n    }") + 6);
            int covered = b.indexOf("isCovered()");
            assertTrue(m + " must short-circuit while covered", covered >= 0);
            int ret = b.indexOf("return", covered);
            int sup = Math.max(b.indexOf("super."), b.indexOf("finish()"));
            assertTrue(m + ": consumed before the normal handling", ret > covered && ret < sup);
        }
    }

    @Test public void noMessageTextIsBoundToTheCover() throws IOException {
        String cover = code(COVER);
        assertFalse("the cover never reads message text", cover.contains("getText("));
        assertFalse(cover.contains("getRecipientDisplayName("));
        assertTrue("only the masked number", cover.contains("LockCoverPolicy.maskedNumber("));
        assertTrue("the cover takes a number, nothing else", cover.contains("void setRecipient(String e164)"));
        String ctrl = code(CONTROLLER);
        Matcher m = Pattern.compile("cover\\.setRecipient\\(([^)]*)\\)").matcher(ctrl);
        int n = 0;
        while (m.find()) {
            n++;
            String arg = m.group(1);
            assertTrue("cover gets the number only: " + arg, arg.equals("job.e164") || arg.equals("targetE164"));
        }
        assertTrue(n > 0);
        String layout = raw(LAYOUT) + raw(STRINGS);
        assertFalse("no data binding of message fields", layout.contains("@{"));
    }

    @Test public void lockFlagsAreAppliedOnlyAfterTheClaimAndClearedBeforeFinish() throws IOException {
        String ctrl = code(CONTROLLER);
        String handle = body(ctrl, "handle");
        int take = handle.indexOf("LaunchClaims.INSTANCE.take(");
        int flags = handle.indexOf("applyLockFlags(");
        assertTrue("claim first", take >= 0 && flags > take);
        assertTrue("the flags follow the policy", handle.contains("LockCoverPolicy.applyLockFlags("));
        String hand = body(ctrl, "acceptHandOff");
        assertTrue(hand.indexOf("take(") < hand.indexOf("onClaimed("));

        String end = body(ctrl, "endJob");
        int clear = end.indexOf("applyLockFlags(false)");
        int fin = end.indexOf("host.finish()");
        assertTrue("lock flags are cleared before finish()", clear >= 0 && fin > clear);
        Matcher f = Pattern.compile("host\\.finish\\(\\)").matcher(ctrl);
        int count = 0;
        while (f.find()) count++;
        assertTrue("endJob is the only place the controller finishes", count == 1);
    }

    @Test public void hardTimeoutPauseAndJobEndFinishThroughThePolicy() throws IOException {
        String ctrl = code(CONTROLLER);
        assertTrue(body(ctrl, "onHardTimeout").contains("Event.TIMEOUT"));
        assertTrue(body(ctrl, "onHardTimeout").contains("endJob("));
        assertTrue(body(ctrl, "onJobEnded").contains("Event.JOB_ENDED"));
        assertTrue(body(ctrl, "onPausedTooLong").contains("LockCoverPolicy.finishNow("));
        assertTrue(body(ctrl, "onPausedTooLong").contains("endJob(finish)"));
        assertTrue("the hard timer is armed per job", body(ctrl, "startNext").contains("hardTimeout"));
        String act = code(ACTIVITY);
        assertTrue(body(act, "onPause").contains("scheduledSend.onPauseOrStop(false)"));
        assertTrue(body(act, "onStop").contains("scheduledSend.onPauseOrStop(true)"));
        assertTrue(body(act, "onResume").contains("scheduledSend.onResume()"));
    }

    @Test public void navigationIsGatedWhileCovered() throws IOException {
        String act = code(ACTIVITY);
        String nav = body(act, "shouldOverrideUrlLoading");
        int gate = nav.indexOf("allowsNavigation(");
        assertTrue(gate >= 0);
        assertTrue("the cover gate runs before the origin allow-list", gate < nav.indexOf("OriginGuard"));
        assertTrue(nav.contains("isForMainFrame()"));
        assertTrue(body(code(CONTROLLER), "allowsNavigation").contains("LockCoverPolicy.allowNavigation("));
    }

    @Test public void everyScheduledJobIsCoveredAndTimedRegardlessOfTheKeyguard() throws IOException {
        // OD26 replaces OD25's "unlocked path unchanged": the keyguard never skips the cover.
        String ctrl = code(CONTROLLER);
        assertFalse("no launch-time keyguard flag gates the cover, timeout or pause", ctrl.contains("overKeyguard"));
        String start = body(ctrl, "startNext");
        assertTrue("the hard timer is armed for every job", start.contains("postDelayed(hardTimeout"));
        assertFalse(start.contains("if (lockedNow)"));
        assertTrue(body(ctrl, "updateCover").contains("LockCoverPolicy.showCover(claimed, jobState, lockedNow)"));
        String handle = body(ctrl, "handle");
        int cover = handle.indexOf("updateCover()");
        int flags = handle.indexOf("LockCoverPolicy.applyLockFlags(");
        assertTrue("cover first, then the flags", cover >= 0 && flags > cover);
        assertTrue(handle.contains("cover.isShown()"));
        String hand = body(ctrl, "onClaimed");
        assertTrue(hand.indexOf("updateCover()") < hand.indexOf("LockCoverPolicy.applyLockFlags("));
        String settle = body(ctrl, "settle");
        assertFalse("settle never drops flags under a cover", settle.contains("applyLockFlags("));
    }

    @Test public void webViewTouchesFromTheCoverPathStayGuarded() throws IOException {
        String act = code(ACTIVITY);
        assertTrue(body(act, "pageView").contains("isActivityAlive()"));
        String kb = body(act, "requestShowKeyboard");
        assertTrue(kb.indexOf("isActivityAlive()") < kb.indexOf("isCovered()"));
        assertTrue("the singleton WebView gets its long-press back", body(code(CONTROLLER), "onDestroy").contains("cover.hide()"));
        String hide = body(code(COVER), "hide");
        assertTrue(hide.contains("setOnLongClickListener(null)"));
        assertTrue(hide.contains("IMPORTANT_FOR_ACCESSIBILITY_AUTO"));
    }
}
