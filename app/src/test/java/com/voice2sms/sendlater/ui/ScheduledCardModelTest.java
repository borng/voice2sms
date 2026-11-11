package com.voice2sms.sendlater.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.voice2sms.sendlater.domain.HoldReason;
import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.ScheduledMessage.Status;
import com.voice2sms.sendlater.domain.Watermark;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Acceptance (home-scheduled; R35a, D47, OD37, AC15): the pure model behind the launcher
 * page's "Scheduled messages" card.
 *
 * <p>Pinned API (com.voice2sms.sendlater.ui.ScheduledCardModel, pure JVM, no android.*):
 * <pre>
 *   static final int MAX_ROWS                               // 3
 *   static ScheduledCardModel build(List&lt;ScheduledMessage&gt; records)   // null = none
 *   List&lt;ScheduledMessage&gt; getRows()   // the next MAX_ROWS SCHEDULED/HELD records, soonest
 *                                     //   scheduledFor first (held rows interleaved by time)
 *   int getTotal()                    // N of "See all (N)": every SCHEDULED + HELD record
 *   boolean isEmpty()                 // getTotal() == 0 (the card shows its empty state)
 *   static Tap tapFor(ScheduledMessage m)   // enum ScheduledCardModel.Tap { EDIT, UPCOMING }:
 *                                           //   SCHEDULED = EDIT (sheet edit mode),
 *                                           //   HELD = UPCOMING
 * </pre>
 * Only SCHEDULED and HELD count (R35a): IN_FLIGHT and terminal statuses are neither listed
 * nor counted. No time cutoff: a HELD message is usually past its time and still listed.
 * Fixtures use distinct times, so tie order is not asserted. Numbers are fictional 555-01xx.
 * Called through reflection so the suite compiles before the class exists.
 */
public class ScheduledCardModelTest {

    private static final String CLASS = "com.voice2sms.sendlater.ui.ScheduledCardModel";
    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    /** Arbitrary fixed epoch (2026-06-10T12:00Z). */
    private static final long NOW = 1_781_092_800_000L;
    private static final String E164 = "+15555550101";

    private static long nextId = 100;

    private static ScheduledMessage msg(Status status, long scheduledFor) {
        ScheduledMessage.Builder b = new ScheduledMessage.Builder()
                .id(nextId++)
                .createdAt(scheduledFor - 2 * HOUR)
                .scheduledFor(scheduledFor)
                .text("Placeholder message line one\nline two")
                .recipientE164(E164)
                .recipientDisplayName("Test Contact")
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

    // ------------------------------------------------------------------ reflection helpers

    private static Class<?> model() {
        try {
            return Class.forName(CLASS, false, ScheduledCardModelTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("missing " + CLASS + " (R35a card model)");
        }
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        Class<?> c = model();
        try {
            Method m = c.getMethod(name, types);
            if (target == null) assertTrue(name + " must be static", Modifier.isStatic(m.getModifiers()));
            return m.invoke(target, args);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(CLASS + " has no public " + name + Arrays.toString(types));
        } catch (IllegalAccessException e) {
            throw new AssertionError(name + " not accessible: " + e);
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            if (t instanceof Error) throw (Error) t;
            throw new AssertionError(t);
        }
    }

    private static Object build(List<ScheduledMessage> records) {
        Object o = call(null, "build", new Class<?>[]{List.class}, records);
        assertNotNull("build never returns null", o);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static List<ScheduledMessage> rows(Object card) {
        List<ScheduledMessage> l = (List<ScheduledMessage>) call(card, "getRows", new Class<?>[0]);
        assertNotNull("getRows never returns null", l);
        return l;
    }

    private static int total(Object card) {
        return (Integer) call(card, "getTotal", new Class<?>[0]);
    }

    private static boolean isEmpty(Object card) {
        return (Boolean) call(card, "isEmpty", new Class<?>[0]);
    }

    private static String tapFor(ScheduledMessage m) {
        Object t = call(null, "tapFor", new Class<?>[]{ScheduledMessage.class}, m);
        assertNotNull("tapFor never returns null for SCHEDULED/HELD", t);
        assertTrue("tapFor returns an enum", t instanceof Enum);
        return ((Enum<?>) t).name();
    }

    private static List<Long> ids(List<ScheduledMessage> l) {
        List<Long> out = new ArrayList<>();
        for (ScheduledMessage m : l) out.add(m.getId());
        return out;
    }

    private static List<ScheduledMessage> list(ScheduledMessage... m) {
        return new ArrayList<>(Arrays.asList(m));
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void maxRowsIsThree() throws Exception {
        Field f;
        try {
            f = model().getField("MAX_ROWS");
        } catch (NoSuchFieldException e) {
            throw new AssertionError(CLASS + ".MAX_ROWS missing");
        }
        assertTrue(Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()));
        assertEquals("the card lists the next 3 (R35a)", 3, f.getInt(null));
    }

    @Test
    public void listsTheNextThreeScheduledOrHeldSoonestFirst() {
        ScheduledMessage s4 = msg(Status.SCHEDULED, NOW + 4 * HOUR);
        ScheduledMessage s1 = msg(Status.SCHEDULED, NOW + 1 * HOUR);
        ScheduledMessage h0 = msg(Status.HELD, NOW - 30 * MIN); // held past its time: still listed
        ScheduledMessage s2 = msg(Status.SCHEDULED, NOW + 2 * HOUR);
        ScheduledMessage h3 = msg(Status.HELD, NOW + 3 * HOUR);
        Object card = build(list(s4, s1, h0, s2, h3));

        assertEquals("next 3 by time, held rows interleaved by time",
                Arrays.asList(h0.getId(), s1.getId(), s2.getId()), ids(rows(card)));
        assertEquals("See all (N) counts every SCHEDULED and HELD record", 5, total(card));
        assertFalse(isEmpty(card));
    }

    @Test
    public void countIncludesRowsBeyondTheFirstThree() {
        List<ScheduledMessage> l = new ArrayList<>();
        for (int i = 7; i >= 1; i--) l.add(msg(i % 2 == 0 ? Status.HELD : Status.SCHEDULED, NOW + i * HOUR));
        Object card = build(l);
        assertEquals(3, rows(card).size());
        assertEquals(7, total(card));
        List<ScheduledMessage> r = rows(card);
        assertEquals(NOW + 1 * HOUR, r.get(0).getScheduledFor());
        assertEquals(NOW + 2 * HOUR, r.get(1).getScheduledFor());
        assertEquals(NOW + 3 * HOUR, r.get(2).getScheduledFor());
    }

    @Test
    public void inFlightAndTerminalRecordsAreNeitherListedNorCounted() {
        ScheduledMessage live = msg(Status.SCHEDULED, NOW + 5 * HOUR);
        ScheduledMessage held = msg(Status.HELD, NOW + 6 * HOUR);
        List<ScheduledMessage> l = list(live, held);
        long t = NOW - HOUR;
        for (Status st : new Status[]{Status.IN_FLIGHT, Status.SENT_VERIFIED, Status.CANCELED,
                Status.SELF_PREEMPTED, Status.EXPIRED, Status.FAILED}) {
            l.add(msg(st, t)); // all sooner than the live ones
            t += MIN;
        }
        Object card = build(l);
        assertEquals(Arrays.asList(live.getId(), held.getId()), ids(rows(card)));
        assertEquals("only SCHEDULED/HELD count toward N", 2, total(card));
    }

    @Test
    public void fewerThanThreeAreAllListed() {
        ScheduledMessage a = msg(Status.SCHEDULED, NOW + 2 * HOUR);
        ScheduledMessage b = msg(Status.SCHEDULED, NOW + HOUR);
        Object card = build(list(a, b));
        assertEquals(Arrays.asList(b.getId(), a.getId()), ids(rows(card)));
        assertEquals(2, total(card));
    }

    @Test
    public void emptyStateWhenNothingIsScheduledOrHeld() {
        Object none = build(Collections.<ScheduledMessage>emptyList());
        assertTrue(isEmpty(none));
        assertEquals(0, total(none));
        assertTrue(rows(none).isEmpty());

        Object nullInput = build(null);
        assertTrue("null records = empty card, no crash", isEmpty(nullInput));
        assertEquals(0, total(nullInput));

        Object onlyDone = build(list(msg(Status.SENT_VERIFIED, NOW - HOUR),
                msg(Status.CANCELED, NOW + HOUR)));
        assertTrue("only finished records = empty state", isEmpty(onlyDone));
        assertTrue(rows(onlyDone).isEmpty());
    }

    @Test
    public void inputListIsNotModified() {
        ScheduledMessage a = msg(Status.SCHEDULED, NOW + 2 * HOUR);
        ScheduledMessage b = msg(Status.SCHEDULED, NOW + HOUR);
        List<ScheduledMessage> in = list(a, b);
        build(in);
        assertEquals(Arrays.asList(a, b), in);
    }

    @Test
    public void scheduledRowOpensTheEditSheetHeldRowOpensUpcoming() {
        assertEquals("tap on a SCHEDULED row = sheet edit mode (R16)", "EDIT",
                tapFor(msg(Status.SCHEDULED, NOW + HOUR)));
        assertEquals("tap on a HELD row = Upcoming (R35a)", "UPCOMING",
                tapFor(msg(Status.HELD, NOW - HOUR)));
    }

    @Test
    public void tapEnumHasExactlyEditAndUpcoming() {
        Class<?> tap = null;
        for (Class<?> c : model().getDeclaredClasses()) if (c.getSimpleName().equals("Tap")) tap = c;
        assertNotNull(CLASS + ".Tap enum missing", tap);
        assertTrue(tap.isEnum());
        List<String> names = new ArrayList<>();
        for (Object o : tap.getEnumConstants()) names.add(((Enum<?>) o).name());
        assertEquals(Arrays.asList("EDIT", "UPCOMING"), names);
    }

    @Test
    public void modelIsPureJvm() throws IOException {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater/ui/ScheduledCardModel.java");
        if (!Files.exists(p)) fail("missing " + p);
        String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        assertFalse("no android.* in the card model", src.contains("import android."));
        assertFalse("no androidx.* in the card model", src.contains("import androidx."));
        assertFalse("no java.time (API 26+)", src.contains("java.time"));
    }
}
