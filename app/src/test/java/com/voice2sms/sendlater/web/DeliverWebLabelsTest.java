package com.voice2sms.sendlater.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Deliver fixes on the WebView surface: UI M5/M6 chip text, OD31 PQ4 held tap, OD31 PQ1 Undo,
 * UI M2 (web half) double tap, whole-branch L3 chip state before the thread binding.
 */
public class DeliverWebLabelsTest {

    private static final String E164 = "+15555550123";
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_793_793_600_000L; // Wed 2026-11-04 12:00 UTC

    private Locale saved;

    @Before public void pinLocale() { saved = Locale.getDefault(); Locale.setDefault(Locale.US); }
    @After public void restoreLocale() { Locale.setDefault(saved); }

    private static String norm(String s) {
        return s.replace(' ', ' ').replace(' ', ' ');
    }

    private static ScheduledMessage msg(long id, long at, ScheduledMessage.Status status) {
        return new ScheduledMessage.Builder().id(id).createdAt(1L).scheduledFor(at).text("x")
                .recipientE164(E164).recipientDisplayName("Test Contact").status(status)
                .holdReason(status == ScheduledMessage.Status.HELD ? HoldReason.REPLIED : null).build();
    }

    private static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    private static String hooks() throws IOException {
        return read("src/main/java/com/voice2sms/sendlater/web/SendLaterWebHooks.java");
    }

    private static String method(String src, String sig) {
        int a = src.indexOf(sig);
        assertTrue(sig, a >= 0);
        return src.substring(a, src.indexOf("\n    }\n", a));
    }

    // ---------------------------------------------------------------- chip text (M5, M6)

    @Test public void chipState_remindedReadsReminderSent() throws Exception {
        List<ScheduledMessage> p = Collections.singletonList(msg(1, NOW - 1000, ScheduledMessage.Status.SCHEDULED));
        JSONObject o = new JSONObject(ChipState.toJson(true, p, null,
                new HashSet<>(Collections.singletonList(1L)), NOW, UTC));
        assertTrue(norm(o.getString("label")).startsWith("Reminder sent · Wed"));
        JSONObject plain = new JSONObject(ChipState.toJson(true, p, null, new HashSet<Long>(), NOW, UTC));
        assertTrue(norm(plain.getString("label")).startsWith("Scheduled · Wed"));
    }

    @Test public void chipState_dateBeyondTheWeek() throws Exception {
        List<ScheduledMessage> p = Collections.singletonList(msg(1, NOW + 19 * DAY, ScheduledMessage.Status.SCHEDULED));
        JSONObject o = new JSONObject(ChipState.toJson(true, p, null, null, NOW, UTC));
        assertEquals("Scheduled · Mon, Nov 23, 12:00 PM", norm(o.getString("label")));
    }

    @Test public void bridgeChipState_passesMarksAndNow() throws IOException {
        String b = method(hooks(), "public String bridgeChipState(");
        assertTrue(b.contains("SendLaterEngine.remindedIds(app, pending)"));
        assertTrue(b.contains("System.currentTimeMillis()"));
    }

    // ---------------------------------------------------------------- chip tap (PQ4)

    @Test public void editMessageId_onlyForASingleScheduled() {
        List<ScheduledMessage> one = Collections.singletonList(msg(3, NOW, ScheduledMessage.Status.SCHEDULED));
        List<ScheduledMessage> held = Collections.singletonList(msg(4, NOW, ScheduledMessage.Status.HELD));
        assertEquals(3L, ChipState.editMessageId(one, null));
        assertEquals("single HELD -> Upcoming", -1L, ChipState.editMessageId(null, held));
        assertEquals(-1L, ChipState.editMessageId(one, held));
        assertEquals(-1L, ChipState.editMessageId(Arrays.asList(msg(5, NOW, ScheduledMessage.Status.SCHEDULED),
                msg(6, NOW, ScheduledMessage.Status.SCHEDULED)), null));
        assertEquals(-1L, ChipState.editMessageId(null, null));
    }

    @Test public void bridgeChipTap_heldOpensFilteredUpcoming() throws IOException {
        String b = method(hooks(), "public void bridgeChipTap(");
        assertTrue(b.contains("ChipState.editMessageId(pending, held)"));
        assertTrue(b.contains("pending.size() + held.size() >= 1"));
        assertTrue(b.contains("ACTION_UPCOMING") && b.contains("EXTRA_E164"));
    }

    // ---------------------------------------------------------------- L3

    @Test public void l3_chipStateAnswersDisabledBeforeTheThreadBinding() throws IOException {
        String b = method(hooks(), "public String bridgeChipState(");
        int bind = b.indexOf("ThreadUrl.isThreadOf(mainFrameUrl, e164)");
        int sw = b.indexOf("SendLaterSettings.isEnabled(app)");
        assertTrue(bind > 0 && sw > 0);
        assertTrue("thread binding checked before the switch is read", bind < sw);
        assertFalse("no enabled answer for a non-thread argument",
                b.contains("ChipState.toJson(true, null, null, TimeZone.getDefault());\n        if (!ThreadUrl"));
        String guard = b.substring(0, b.indexOf("return ChipState.disabled();"));
        assertTrue("non-E.164 and unbound numbers share the disabled answer",
                guard.contains("!ThreadDirectory.isE164(e164) || !ThreadUrl.isThreadOf(mainFrameUrl, e164)"));
    }

    @Test public void l3_javaPushesTheSwitchToThePage() throws IOException {
        String r = method(hooks(), "private void refreshChip()");
        assertTrue(r.contains("SendLaterSettings.isEnabled(app)"));
        assertTrue(r.contains("window.v2sSendLaterRefresh(\" + on + \")"));
        JsSource js = JsSource.injectJs();
        int a = js.code.indexOf("window.v2sSendLaterRefresh = function(on)");
        assertTrue("v2sSendLaterRefresh takes the pushed switch", a >= 0);
        String refresh = js.code.substring(a, js.code.indexOf("\n};", a));
        assertTrue(refresh.contains("if (typeof on === 'boolean') st.switchOn = on;"));
        JsSource.Fn chip = js.first("_v2sSlRefreshChip");
        assertNotNull(chip);
        assertTrue("bridge read only with a thread open", chip.body.contains("if (st.e164) {"));
        assertTrue(chip.body.contains("V2SBridge.sendLaterChipState(st.e164)"));
        assertFalse("never asks the bridge with an empty number", chip.body.contains("st.e164 || ''"));
        assertTrue("off-thread enabled = pushed switch", chip.body.contains("st.enabled = st.switchOn === true;"));
    }

    // ---------------------------------------------------------------- M2 web half

    @Test public void m2_secondTabTapWhileCaptureInFlightIsIgnored() throws IOException {
        JsSource js = JsSource.injectJs();
        JsSource.Fn cap = js.first("_v2sSlCapture");
        assertNotNull(cap);
        int guard = cap.body.indexOf("if (st.captureBusyUntil && Date.now() < st.captureBusyUntil) return;");
        int set = cap.body.indexOf("st.captureBusyUntil = Date.now() + 1500;");
        int call = cap.body.indexOf("V2SBridge.requestSendLater(");
        assertTrue("guard, then mark, then the bridge call", guard >= 0 && set > guard && call > set);
        int a = js.code.indexOf("window.v2sSendLaterClear = function");
        assertTrue("the clear after Schedule ends the in-flight tap",
                js.code.substring(a, js.code.indexOf("\n};", a)).contains("st.captureBusyUntil = 0"));
        String open = method(hooks(), "public void openSheet(");
        assertTrue("Java drops a second sheet start too",
                open.contains("SystemClock.elapsedRealtime() - sheetStartedAt < DOUBLE_TAP_MS"));
    }

    // ---------------------------------------------------------------- PQ1 Undo

    @Test public void pq1_webUndoAlwaysCancelsAndRestoresOnlyIntoAnEmptyComposer() throws IOException {
        JsSource js = JsSource.injectJs();
        JsSource.Fn undo = js.first("_v2sSlOnUndoTap");
        assertNotNull(undo);
        String b = undo.body;
        int cancel = b.indexOf("V2SBridge.requestSendLaterUndo(u.messageId)");
        int empty = b.indexOf("ta.value === ''");
        int restore = b.indexOf("_v2sSlSetInputValue(ta, u.draft, true)");
        assertTrue("cancel first, regardless of the composer", cancel >= 0 && empty > cancel);
        assertTrue("restore guarded by empty composer + same URL + same thread", restore > empty
                && b.substring(empty, restore).contains("location.href === u.href")
                && b.substring(empty, restore).contains("_v2sSlThreadE164() === u.e164"));
        assertTrue("kept-text wording matches native",
                b.contains("Canceled — the composer changed, so your text is in Upcoming under Recent"));
        assertFalse("old refusal is gone", b.contains("Not undone"));
    }
}
