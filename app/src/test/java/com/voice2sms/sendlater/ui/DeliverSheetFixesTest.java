package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.Decision;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LadderEvaluator;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.NetworkState;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.SessionState;
import com.voice2sms.sendlater.domain.ThreadEvent;
import com.voice2sms.sendlater.domain.Watermark;
import com.voice2sms.sendlater.store.ScheduledMessageEntity;
import com.voice2sms.sendlater.store.StoreMapping;
import com.voice2sms.sendlater.store.WatermarkEntity;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dl-sheet: deliver fixes for the sheet (UI M2 sheet half, UI M3, whole-branch M2 + OD31 P2,
 * OD31 PQ5, OD31 PQ6). Pure JVM plus source/resource checks.
 */
public class DeliverSheetFixesTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;
    private static final long T0 = 1_800_000_000_000L;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final int[] SLOTS = {9 * 60, 13 * 60, 18 * 60};

    private static final Path MAIN = Paths.get(System.getProperty("user.dir"))
            .resolve(Paths.get("src", "main")).toAbsolutePath();
    private static final Path RES = resolveRes();

    private static Path resolveRes() {
        Path p = MAIN.resolve("res");
        if (Files.isDirectory(p)) return p;
        return Paths.get(System.getProperty("user.dir"), "app", "src", "main", "res");
    }

    private static Path javaFile(String rel) {
        Path p = MAIN.resolve("java").resolve(rel);
        if (Files.exists(p)) return p;
        return Paths.get(System.getProperty("user.dir"), "app", "src", "main", "java", rel);
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String activity() throws IOException {
        return read(javaFile("com/voice2sms/sendlater/ui/SendLaterSheetActivity.java"));
    }

    private final Object a = new Object();
    private final Object b = new Object();

    @After public void releaseGuard() {
        CaptureSheetGuard.release(a);
        CaptureSheetGuard.release(b);
    }

    // ------------------------------------------------------------------ UI M2: capture guard

    @Test public void secondCaptureSheetCannotTakeTheGuardUntilTheFirstReleasesIt() {
        assertTrue(CaptureSheetGuard.tryAcquire(a));
        assertTrue("the holder may re-acquire", CaptureSheetGuard.tryAcquire(a));
        assertFalse("a second capture sheet is refused", CaptureSheetGuard.tryAcquire(b));
        CaptureSheetGuard.release(b); // not the holder: no-op
        assertTrue(CaptureSheetGuard.isHeld());
        assertFalse(CaptureSheetGuard.tryAcquire(b));
        CaptureSheetGuard.release(a);
        assertFalse(CaptureSheetGuard.isHeld());
        assertTrue("free again after the first sheet is destroyed", CaptureSheetGuard.tryAcquire(b));
    }

    @Test public void recreatedSheetTakesOverAndNullNeverHolds() {
        assertFalse(CaptureSheetGuard.tryAcquire(null));
        assertTrue(CaptureSheetGuard.tryAcquire(a));
        CaptureSheetGuard.takeOver(b);
        CaptureSheetGuard.release(a);
        assertTrue("the old instance's release does not drop the new holder", CaptureSheetGuard.isHeld());
        CaptureSheetGuard.release(b);
        assertFalse(CaptureSheetGuard.isHeld());
    }

    @Test public void activityGuardsCaptureOnlyAndReleasesOnDestroy() throws IOException {
        String c = activity();
        int capture = c.indexOf("if (SendLaterIntents.ACTION_CAPTURE.equals(action)) {");
        int acquire = c.indexOf("CaptureSheetGuard.tryAcquire(this)", capture);
        int setup = c.indexOf("setupCapture(intent);", capture);
        int edit = c.indexOf("SendLaterIntents.ACTION_EDIT.equals(action)", capture);
        assertTrue(capture >= 0 && acquire > capture && setup > acquire && edit > setup);
        String refused = c.substring(acquire, setup);
        assertTrue("a refused capture finishes before showing anything",
                refused.contains("finish();") && refused.contains("return;"));
        int destroy = c.indexOf("protected void onDestroy()");
        assertTrue(c.indexOf("CaptureSheetGuard.release(this)", destroy) > destroy);
    }

    @Test public void duplicateRecordIsNotReArmedOrReAnnounced() throws IOException {
        String c = activity();
        int persist = c.indexOf("private void persistArmAndAnnounce(");
        int dupe = c.indexOf("isExistingCapture(app, id, msg)", persist);
        // Deliver M2: armed through the engine lock.
        int arm = c.indexOf("SendLaterEngine.registerAlarms(app, stored)", persist);
        int bcast = c.indexOf("new Intent(SendLaterIntents.ACTION_SCHEDULED)", persist);
        assertTrue("duplicate check before arming and announcing", dupe > persist && dupe < arm && arm < bcast);
        String between = c.substring(dupe, arm);
        assertTrue(between.contains("return;"));
        assertTrue("existing = createdAt differs",
                c.contains("row.getCreatedAt() != mine.getCreatedAt()"));
    }

    // ------------------------------------------------------------------ OD31 PQ5: Automatic budget

    @Test public void budgetPhraseUsesMinutesOrWholeHours() {
        assertEquals("10 min", SheetViewModelLogic.budgetPhrase(10 * MIN));
        assertEquals("30 min", SheetViewModelLogic.budgetPhrase(30 * MIN));
        assertEquals("2 h", SheetViewModelLogic.budgetPhrase(2 * HOUR));
        assertEquals("24 h", SheetViewModelLogic.budgetPhrase(DAY));
        assertEquals("90 min", SheetViewModelLogic.budgetPhrase(90 * MIN));
        assertEquals("1 min", SheetViewModelLogic.budgetPhrase(0));
    }

    @Test public void captureBudgetFollowsTheSelectedTime() {
        SheetViewModelLogic m = SheetViewModelLogic.forCapture("+15555550123", null, true, T0, UTC, SLOTS);
        assertTrue(m.pickDateTime(T0 + 20 * MIN, T0));
        assertEquals(10 * MIN, m.automaticBudgetMs(T0));
        assertTrue(m.pickDateTime(T0 + 5 * HOUR, T0));
        assertEquals(30 * MIN, m.automaticBudgetMs(T0));
        assertTrue(m.pickDateTime(T0 + 3 * DAY, T0));
        assertEquals(2 * HOUR, m.automaticBudgetMs(T0));
        // The sheet sat open: the lead time is measured from the tap, as createdAt is.
        assertTrue(m.pickDateTime(T0 + 70 * MIN, T0));
        assertEquals(30 * MIN, m.automaticBudgetMs(T0));
        assertEquals(10 * MIN, m.automaticBudgetMs(T0 + 15 * MIN));
    }

    @Test public void editBudgetUsesTheStoredCreatedAt() {
        ScheduledMessage stored = message(T0, T0 + 20 * MIN, ScheduledMessage.Status.SCHEDULED, null);
        SheetViewModelLogic m = SheetViewModelLogic.forEdit(stored, T0 + 5 * MIN, UTC, SLOTS);
        assertEquals(10 * MIN, m.automaticBudgetMs(T0 + 5 * MIN));
        assertTrue(m.pickDateTime(T0 + 2 * DAY, T0 + 5 * MIN));
        assertEquals("lead time from createdAt, not from the edit", 2 * HOUR, m.automaticBudgetMs(T0 + DAY));
    }

    @Test public void automaticRowShowsItsBudgetAndUpdatesOnRender() throws IOException {
        String s = read(RES.resolve("values/sendlater_sheet.xml"));
        Matcher m = Pattern.compile("name=\"sendlater_sheet_if_late_default_budget\"\\s*>([^<]*)<").matcher(s);
        assertTrue(m.find());
        assertTrue(m.group(1), m.group(1).startsWith("Automatic") && m.group(1).contains("%1$s"));
        String c = activity();
        assertTrue(c.contains("R.string.sendlater_sheet_if_late_default_budget"));
        assertTrue(c.contains("SheetViewModelLogic.budgetPhrase("));
        assertTrue(c.contains("logic.automaticBudgetMs("));
        int render = c.indexOf("private void render()");
        int end = c.indexOf("private void toggleDraft()");
        assertTrue("render re-labels the row when the time changes",
                c.substring(render, end).contains("refreshIfLateLabels()"));
    }

    // ------------------------------------------------------------------ OD31 PQ6: hold checkbox

    @Test public void holdCheckboxHasATalkBackDescriptionAboutTheNotification() throws IOException {
        String layout = read(RES.resolve("layout/sendlater_sheet.xml"));
        Matcher box = Pattern.compile("(?s)<[\\w.]*CheckBox[^>]*@\\+id/hold_checkbox[^>]*>").matcher(layout);
        assertTrue(box.find());
        assertTrue(box.group().contains("android:contentDescription=\"@string/sendlater_sheet_hold_desc\""));
        assertTrue("visible label unchanged", box.group().contains("@string/sendlater_sheet_hold\""));
        String s = read(RES.resolve("values/sendlater_sheet.xml"));
        Matcher d = Pattern.compile("name=\"sendlater_sheet_hold_desc\"\\s*>([^<]*)<").matcher(s);
        assertTrue(d.find());
        String desc = d.group(1).toLowerCase(Locale.ROOT);
        assertTrue(desc, desc.contains("hold if they text first"));
        assertTrue(desc, desc.contains("notification") && desc.contains("ask"));
    }

    // ------------------------------------------------------------------ whole-branch M2 + OD31 P2

    @Test public void editBaselineIsDeviceOnlyAtNow() {
        Watermark w = SheetViewModelLogic.editBaseline(T0 + 7);
        assertEquals(Watermark.Quality.DEVICE_ONLY, w.getQuality());
        assertEquals(T0 + 7, w.getCapturedAtDevice());
        assertEquals(null, w.getNewestServerId());
        assertEquals(null, w.getNewestTs());
    }

    @Test public void everyTimeEditPassesAFreshBaselineToReschedule() throws IOException {
        String c = activity();
        int onUpdate = c.indexOf("private void onUpdate()");
        int body = c.indexOf("private void updateArmAndAnnounce(");
        String u = c.substring(onUpdate, body);
        assertTrue(u, u.contains("updateArmAndAnnounce(id, when, hold, policy, SheetViewModelLogic.editBaseline(now))"));
        String w = c.substring(body);
        // Deliver M2: the reschedule goes through the engine lock.
        assertTrue("reschedule takes the baseline, never null",
                w.contains("SendLaterEngine.reschedule(app, id, when, hold, policy, baseline)"));
        assertFalse(w.substring(0, w.indexOf("\n    }\n")).contains("policy, null)"));
    }

    @Test public void heldOnAReplyThenTimeEditedSendsAtTheNewTime() {
        long reply = T0 + 10 * MIN;
        long edit = T0 + 20 * MIN;
        long newDue = T0 + 2 * HOUR;
        Watermark original = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, T0, null);
        List<ThreadEvent> events = Collections.singletonList(
                new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.SMS, reply,
                        ThreadEvent.hashOf("ok see you"), "ok see you"));

        // Without the fix (old watermark kept) the same reply holds it again.
        ScheduledMessage stale = message(T0, newDue, ScheduledMessage.Status.SCHEDULED, original);
        assertEquals(Decision.Kind.HOLD, evaluate(stale, events, newDue).getKind());

        // The edit as the repository stores it: fresh reply baseline, original kept (OD22(1)).
        WatermarkEntity before = StoreMapping.toEntity(1L, original, null);
        WatermarkEntity after = StoreMapping.replacing(1L, before,
                SheetViewModelLogic.editBaseline(edit), T0);
        ScheduledMessageEntity row = StoreMapping.toEntity(
                message(T0, newDue, ScheduledMessage.Status.SCHEDULED, original), edit);
        row.id = 1L;
        ScheduledMessage edited = StoreMapping.toDomain(row, after);
        assertNotNull(edited.getOriginalWatermark());
        assertEquals(T0, edited.getOriginalWatermark().getCapturedAtDevice());
        assertEquals(edit, edited.getWatermark().getCapturedAtDevice());
        assertEquals(Decision.Kind.SEND, evaluate(edited, events, newDue).getKind());
    }

    @Test public void scheduledRowEditAlsoDropsAnOldReplyRecordedWhileHoldWasOff() {
        long reply = T0 + 5 * MIN;
        long edit = T0 + 30 * MIN;
        long due = T0 + 3 * HOUR;
        Watermark original = new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, T0, null);
        List<ThreadEvent> events = Collections.singletonList(
                new ThreadEvent(ThreadEvent.Direction.IN, ThreadEvent.Kind.SMS, reply,
                        ThreadEvent.hashOf("hi"), "hi"));
        ScheduledMessageEntity row = StoreMapping.toEntity(
                message(T0, due, ScheduledMessage.Status.SCHEDULED, original), edit);
        row.id = 1L;
        ScheduledMessage edited = StoreMapping.toDomain(row, StoreMapping.replacing(1L,
                StoreMapping.toEntity(1L, original, null), SheetViewModelLogic.editBaseline(edit), T0));
        assertEquals(Decision.Kind.SEND, evaluate(edited, events, due).getKind());
    }

    private static Decision evaluate(ScheduledMessage m, List<ThreadEvent> events, long now) {
        return LadderEvaluator.evaluate(m, Rung.R1, events, now, SessionState.ready(),
                NetworkState.CONNECTED, true);
    }

    private static ScheduledMessage message(long created, long due, ScheduledMessage.Status st,
                                            Watermark wm) {
        ScheduledMessage.Builder b = new ScheduledMessage.Builder()
                .id(1L)
                .createdAt(created)
                .scheduledFor(due)
                .text("running late")
                .recipientE164("+15555550123")
                .source(ScheduledMessage.Source.NATIVE_GV)
                .rung(Rung.R1)
                .holdIfReplied(true)
                .latenessPolicy(LatenessPolicy.DEFAULT)
                .status(st)
                .attemptCount(0)
                .watermark(wm);
        if (st == ScheduledMessage.Status.HELD) b.holdReason(HoldReason.REPLIED);
        return b.build();
    }

    // ------------------------------------------------------------------ UI M3: contrast

    /** Material 3 baseline surfaces the sheet and the pickers draw on (lightest/darkest of each). */
    private static final String[] LIGHT_SURFACES = {"FFFFFF", "FEF7FF", "F7F2FA", "F3EDF7", "ECE6F0", "E6E0E9"};
    private static final String[] DARK_SURFACES = {"141218", "1D1B20", "211F26", "2B2930", "36343B"};

    @Test public void textAccentMeets45To1InLightAndDark() throws IOException {
        String day = colour(read(RES.resolve("values/sendlater_colors.xml")), "sendlater_accent_text");
        String night = colour(read(RES.resolve("values-night/sendlater_colors.xml")), "sendlater_accent_text");
        for (String s : LIGHT_SURFACES) {
            assertTrue("light " + day + " on " + s, contrast(day, s) >= 4.5);
        }
        for (String s : DARK_SURFACES) {
            assertTrue("dark " + night + " on " + s, contrast(night, s) >= 4.5);
        }
        // Picker selection: on-primary text on the primary fill, both modes.
        String onDay = colour(read(RES.resolve("values/sendlater_colors.xml")), "sendlater_on_accent_text");
        String onNight = colour(read(RES.resolve("values-night/sendlater_colors.xml")), "sendlater_on_accent_text");
        assertTrue(contrast(onDay, day) >= 4.5);
        assertTrue(contrast(onNight, night) >= 4.5);
    }

    @Test public void fillAccentStaysLightBlueAndTheScheduleButtonKeepsIt() throws IOException {
        String colors = read(RES.resolve("values/sendlater_colors.xml"));
        assertEquals("8AB4F8", colour(colors, "sendlater_accent"));
        assertTrue(contrast(colour(colors, "sendlater_on_accent"), "8AB4F8") >= 4.5);
        String theme = read(RES.resolve("values/sendlater_themes.xml"));
        Matcher sheet = Pattern.compile("(?s)<style\\s+name=\"Theme\\.SendLater\\.Sheet\".*?</style>").matcher(theme);
        assertTrue(sheet.find());
        assertTrue("colorPrimary is the text-safe accent",
                sheet.group().contains("<item name=\"colorPrimary\">@color/sendlater_accent_text</item>"));
        assertTrue("picker chips keep the fill",
                sheet.group().contains("<item name=\"colorPrimaryContainer\">@color/sendlater_accent</item>"));
        Matcher ov = Pattern.compile("(?s)<style\\s+name=\"ThemeOverlay\\.SendLater\\.FilledAccent\".*?</style>").matcher(theme);
        assertTrue(ov.find());
        assertTrue(ov.group().contains("<item name=\"colorPrimary\">@color/sendlater_accent</item>"));
        assertTrue(ov.group().contains("<item name=\"colorOnPrimary\">@color/sendlater_on_accent</item>"));
        String layout = read(RES.resolve("layout/sendlater_sheet.xml"));
        Matcher btn = Pattern.compile("(?s)<com\\.google\\.android\\.material\\.button\\.MaterialButton[^>]*@\\+id/schedule_button[^>]*>").matcher(layout);
        assertTrue(btn.find());
        assertTrue(btn.group().contains("android:theme=\"@style/ThemeOverlay.SendLater.FilledAccent\""));
    }

    @Test public void noSheetTextUsesTheFillAccentDirectly() throws IOException {
        String layout = read(RES.resolve("layout/sendlater_sheet.xml"));
        assertFalse(layout.contains("android:textColor=\"@color/sendlater_accent\""));
    }

    private static String colour(String xml, String name) {
        Matcher m = Pattern.compile("<color\\s+name=\"" + Pattern.quote(name) + "\"\\s*>\\s*#(?:FF)?([0-9A-Fa-f]{6})\\s*</color>")
                .matcher(xml);
        assertTrue("colour " + name, m.find());
        return m.group(1).toUpperCase(Locale.ROOT);
    }

    private static double contrast(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
