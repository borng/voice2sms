package com.voice2sms.sendlater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.voice2sms.sendlater.domain.ChipLabel;
import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.Watermark;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Acceptance (pending-notification; R45, D47, OD39, OD24, D39, AC15): the pure model behind the
 * ongoing "N scheduled" notification.
 *
 * <p>Pinned API ({@code PendingSummary}, pure JVM, no android.*, in one of the packages
 * {@code com.voice2sms.sendlater}, {@code .engine}, {@code .ui} or {@code .notify}; the same
 * package as {@code PendingSummaryNotifier} is expected):
 * <pre>
 *   static PendingSummary build(List&lt;ScheduledMessage&gt; records, boolean sendLaterEnabled,
 *                               long nowMs, TimeZone zone)
 *       // null = no notification: the notifier cancels it (no SCHEDULED/HELD record, or
 *       //        Send Later off, OD24). records may be null or contain any status.
 *   int getCount()          // N = SCHEDULED + HELD records; IN_FLIGHT and terminal never count
 *   String getText()        // "N scheduled · next &lt;who&gt; &lt;time&gt;"
 *   String getPublicText()  // lock-screen public version: no names, numbers or message text
 * </pre>
 * {@code <who>} (D39) is the next message's display name (trimmed), else
 * {@code ChipLabel.formatNumber(e164)}. {@code <time>} contains the next message's time as
 * "h:mm a" in {@code zone} (a weekday or date prefix is the implementation's call). "next" is the
 * soonest {@code scheduledFor}; fixtures that mix SCHEDULED and HELD put the HELD record later,
 * so which of the two kinds "next" may pick is not asserted. Message text never appears.
 *
 * <p>Called through reflection so the suite compiles before the class exists. Numbers are
 * fictional 555-01xx; names are placeholders.
 */
public class PendingSummaryModelTest {

    private static final String[] PACKAGES = {
            "com.voice2sms.sendlater.", "com.voice2sms.sendlater.engine.",
            "com.voice2sms.sendlater.ui.", "com.voice2sms.sendlater.notify."};

    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    /** Arbitrary fixed epoch (2026-06-10T12:00Z). */
    private static final long NOW = 1_781_092_800_000L;
    private static final TimeZone ZONE = TimeZone.getTimeZone("America/Denver");

    private static final String E164_A = "+15555550101";
    private static final String E164_B = "+15555550102";
    private static final String E164_C = "+15555550103";
    private static final String NAME_A = "Test Contact";
    private static final String NAME_B = "Other Person";
    private static final String BODY = "Placeholder body zebra quartz";

    private static long nextId = 500;

    // ------------------------------------------------------------------ fixtures

    private static ScheduledMessage msg(Status status, long scheduledFor, String name, String e164) {
        ScheduledMessage.Builder b = new ScheduledMessage.Builder()
                .id(nextId++)
                .createdAt(scheduledFor - 2 * HOUR)
                .scheduledFor(scheduledFor)
                .text(BODY)
                .recipientE164(e164)
                .recipientDisplayName(name)
                .source(ScheduledMessage.Source.NATIVE_GV)
                .rung(Rung.R1)
                .holdIfReplied(true)
                .latenessPolicy(LatenessPolicy.DEFAULT)
                .status(status)
                .attemptCount(0)
                .watermark(new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null,
                        scheduledFor - 2 * HOUR, null));
        if (status == Status.HELD) b.holdReason(HoldReason.REPLIED);
        return b.build();
    }

    private static String hmm(long t) {
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.getDefault());
        f.setTimeZone(ZONE);
        return f.format(new Date(t));
    }

    // ------------------------------------------------------------------ reflection

    private static Class<?> modelClass() {
        for (String p : PACKAGES) {
            try {
                return Class.forName(p + "PendingSummary");
            } catch (ClassNotFoundException ignored) {
                // try the next package
            }
        }
        fail("PendingSummary (pure model for the R45 notification) not found in "
                + Arrays.toString(PACKAGES));
        return null;
    }

    private static Object build(List<ScheduledMessage> records, boolean enabled) {
        Class<?> c = modelClass();
        try {
            Method m = c.getMethod("build", List.class, boolean.class, long.class, TimeZone.class);
            return m.invoke(null, records, enabled, NOW, ZONE);
        } catch (NoSuchMethodException e) {
            fail("PendingSummary.build(List<ScheduledMessage>, boolean, long, TimeZone) missing");
        } catch (InvocationTargetException e) {
            throw new AssertionError("PendingSummary.build threw " + e.getCause(), e.getCause());
        } catch (IllegalAccessException e) {
            fail("PendingSummary.build must be public static: " + e);
        }
        return null;
    }

    private static Object get(Object model, String getter) {
        try {
            return model.getClass().getMethod(getter).invoke(model);
        } catch (NoSuchMethodException e) {
            fail("PendingSummary." + getter + "() missing");
        } catch (InvocationTargetException e) {
            throw new AssertionError(getter + " threw " + e.getCause(), e.getCause());
        } catch (IllegalAccessException e) {
            fail("PendingSummary." + getter + "() must be public: " + e);
        }
        return null;
    }

    private static int count(Object model) {
        return (Integer) get(model, "getCount");
    }

    private static String text(Object model) {
        return (String) get(model, "getText");
    }

    private static String publicText(Object model) {
        return (String) get(model, "getPublicText");
    }

    // ------------------------------------------------------------------ 0 pending: cancelled

    @Test
    public void zeroPendingBuildsNothingSoTheNotificationIsCancelled() {
        modelClass();
        assertNull("no records -> null (notification cancelled, R45)",
                build(new ArrayList<ScheduledMessage>(), true));
        assertNull("null records -> null", build(null, true));
    }

    @Test
    public void onlyInFlightAndTerminalRecordsCountAsZero() {
        List<ScheduledMessage> rows = new ArrayList<>();
        rows.add(msg(Status.IN_FLIGHT, NOW + HOUR, NAME_A, E164_A));
        rows.add(msg(Status.SENT_VERIFIED, NOW - HOUR, NAME_A, E164_A));
        rows.add(msg(Status.CANCELED, NOW + 2 * HOUR, NAME_B, E164_B));
        rows.add(msg(Status.SELF_PREEMPTED, NOW + 3 * HOUR, NAME_B, E164_B));
        rows.add(msg(Status.EXPIRED, NOW - 30 * HOUR, NAME_A, E164_A));
        rows.add(msg(Status.FAILED, NOW - 2 * HOUR, NAME_A, E164_A));
        assertNull("only SCHEDULED and HELD keep the notification up (R45)", build(rows, true));
    }

    // ------------------------------------------------------------------ Send Later off: cancelled

    @Test
    public void sendLaterOffBuildsNothingEvenWithPendingMessages() {
        List<ScheduledMessage> rows = Arrays.asList(
                msg(Status.SCHEDULED, NOW + HOUR, NAME_A, E164_A),
                msg(Status.HELD, NOW + 2 * HOUR, NAME_B, E164_B));
        assertNull("Send Later off -> no notification (R45, OD24)", build(rows, false));
    }

    // ------------------------------------------------------------------ 1 pending

    @Test
    public void onePendingReadsOneScheduledNextNameAndTime() {
        long t = NOW + 3 * HOUR + 15 * MIN;
        Object m = build(Collections.singletonList(msg(Status.SCHEDULED, t, NAME_A, E164_A)), true);
        assertNotNull("one SCHEDULED record -> notification", m);
        assertEquals(1, count(m));
        String s = text(m);
        assertTrue("text starts with '1 scheduled · next Test Contact ' (R45): " + s,
                s.startsWith("1 scheduled · next " + NAME_A + " "));
        assertTrue("text carries the next time " + hmm(t) + ": " + s, s.contains(hmm(t)));
    }

    @Test
    public void nameIsTrimmedAndFallsBackToTheFormattedNumber() {
        long t = NOW + 90 * MIN;
        Object padded = build(Collections.singletonList(
                msg(Status.SCHEDULED, t, "  " + NAME_A + "  ", E164_A)), true);
        assertTrue("display name trimmed (D39): " + text(padded),
                text(padded).startsWith("1 scheduled · next " + NAME_A + " "));

        String formatted = ChipLabel.formatNumber(E164_A);
        assertEquals("fixture sanity: landed D39 formatter", "(555) 555-0101", formatted);
        for (String noName : new String[] {null, "", "   "}) {
            Object m = build(Collections.singletonList(msg(Status.SCHEDULED, t, noName, E164_A)), true);
            String s = text(m);
            assertTrue("no name -> ChipLabel.formatNumber(e164) (D39): " + s,
                    s.startsWith("1 scheduled · next " + formatted + " "));
            assertFalse("raw E.164 not shown when it formats: " + s, s.contains(E164_A));
        }
    }

    @Test
    public void oneHeldOnlyStillShowsAndCountsOne() {
        Object m = build(Collections.singletonList(
                msg(Status.HELD, NOW - 20 * MIN, NAME_A, E164_A)), true);
        assertNotNull("a HELD record keeps the notification up (R45)", m);
        assertEquals("HELD counts as pending (R45)", 1, count(m));
        assertTrue("reads '1 scheduled': " + text(m), text(m).startsWith("1 scheduled"));
    }

    // ------------------------------------------------------------------ N pending incl. HELD

    @Test
    public void manyPendingCountIncludesHeldAndNextIsTheSoonest() {
        long soonest = NOW + 40 * MIN;
        List<ScheduledMessage> rows = new ArrayList<>();
        // Unsorted on purpose; the HELD record is later than the soonest SCHEDULED one.
        rows.add(msg(Status.SCHEDULED, NOW + 5 * HOUR, NAME_A, E164_A));
        rows.add(msg(Status.HELD, NOW + 2 * HOUR, NAME_A, E164_A));
        rows.add(msg(Status.SCHEDULED, soonest, NAME_B, E164_B));
        rows.add(msg(Status.SCHEDULED, NOW + 26 * HOUR, null, E164_C));
        rows.add(msg(Status.SENT_VERIFIED, NOW - HOUR, NAME_A, E164_A));
        rows.add(msg(Status.CANCELED, NOW + 10 * MIN, NAME_A, E164_A));
        rows.add(msg(Status.IN_FLIGHT, NOW + MIN, NAME_A, E164_A));

        Object m = build(rows, true);
        assertNotNull(m);
        assertEquals("3 SCHEDULED + 1 HELD; IN_FLIGHT and terminal rows excluded (R45)", 4, count(m));
        String s = text(m);
        assertTrue("'4 scheduled · next Other Person ' (soonest): " + s,
                s.startsWith("4 scheduled · next " + NAME_B + " "));
        assertTrue("next time " + hmm(soonest) + ": " + s, s.contains(hmm(soonest)));
    }

    @Test
    public void countChangesWithThePendingSet() {
        List<ScheduledMessage> rows = new ArrayList<>();
        rows.add(msg(Status.SCHEDULED, NOW + HOUR, NAME_A, E164_A));
        rows.add(msg(Status.SCHEDULED, NOW + 2 * HOUR, NAME_B, E164_B));
        assertTrue(text(build(rows, true)).startsWith("2 scheduled · next " + NAME_A + " "));
        rows.add(msg(Status.HELD, NOW + 3 * HOUR, NAME_B, E164_B));
        assertEquals(3, count(build(rows, true)));
        assertTrue(text(build(rows, true)).startsWith("3 scheduled · "));
    }

    // ------------------------------------------------------------------ privacy

    @Test
    public void summaryTextNeverCarriesMessageText() {
        List<ScheduledMessage> rows = Arrays.asList(
                msg(Status.SCHEDULED, NOW + HOUR, NAME_A, E164_A),
                msg(Status.HELD, NOW + 2 * HOUR, NAME_B, E164_B));
        String s = text(build(rows, true));
        assertFalse("message text never in the notification (R45): " + s, s.contains("Placeholder"));
        assertFalse(s.contains("zebra"));
    }

    @Test
    public void publicVersionHasNoNamesNumbersOrText() {
        List<ScheduledMessage> rows = Arrays.asList(
                msg(Status.SCHEDULED, NOW + HOUR, NAME_A, E164_A),
                msg(Status.SCHEDULED, NOW + 2 * HOUR, null, E164_B),
                msg(Status.HELD, NOW + 3 * HOUR, NAME_B, E164_C));
        Object m = build(rows, true);
        String p = publicText(m);
        assertNotNull("lock-screen public version text (R45)", p);
        assertFalse("public version is not blank", p.trim().isEmpty());
        for (String secret : new String[] {NAME_A, NAME_B, "Test", "Other", E164_A, E164_B, E164_C,
                ChipLabel.formatNumber(E164_A), ChipLabel.formatNumber(E164_B), "555", "0101",
                "0102", "0103", "Placeholder", "zebra"}) {
            assertFalse("public version must not contain '" + secret + "': " + p, p.contains(secret));
        }
        assertFalse("public version is not the private text", p.equals(text(m)));

        Object one = build(Collections.singletonList(msg(Status.SCHEDULED, NOW + HOUR, NAME_A, E164_A)), true);
        String p1 = publicText(one);
        assertFalse("single-message public version has no name: " + p1, p1.contains(NAME_A));
        assertFalse(p1.contains("Placeholder"));
    }

    // ------------------------------------------------------------------ purity

    @Test
    public void modelIsPureJvm() throws IOException {
        Path root = Paths.get("src/main/java/com/voice2sms/sendlater");
        List<Path> hits;
        try (Stream<Path> s = Files.walk(root)) {
            hits = s.filter(p -> p.getFileName().toString().equals("PendingSummary.java"))
                    .collect(Collectors.toList());
        }
        assertEquals("exactly one PendingSummary.java under sendlater/: " + hits, 1, hits.size());
        String src = new String(Files.readAllBytes(hits.get(0)), StandardCharsets.UTF_8);
        assertFalse("PendingSummary is pure JVM (no android.* imports)", src.contains("import android."));
        assertFalse("PendingSummary is pure JVM (no androidx.* imports)", src.contains("import androidx."));
    }
}
