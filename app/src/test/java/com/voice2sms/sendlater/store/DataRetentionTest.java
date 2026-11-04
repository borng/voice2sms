package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.voice2sms.sendlater.domain.ScheduledMessage.Status;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * dl-privacy (OD31, whole-branch review M1): terminal rows go 30 days after their terminal state,
 * thread-directory pairs 90 days after last seen, at most once a day.
 */
public class DataRetentionTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 1_800_000_000_000L;

    /** In-memory stand-in applying the same predicates as the DAO SQL. */
    private static final class Table implements DataRetention.Target {
        final List<long[]> messages = new ArrayList<>(); // {id, updatedAt, terminal?1:0}
        final Set<Long> watermarks = new HashSet<>();
        final List<Long> directory = new ArrayList<>();  // lastSeenMs
        final List<Long> terminalCutoffs = new ArrayList<>();
        final List<Long> directoryCutoffs = new ArrayList<>();
        boolean throwOnDirectory;

        void message(long id, long updatedAt, Status s) {
            messages.add(new long[] {id, updatedAt,
                    SendLaterRepository.TERMINAL.contains(s.name()) ? 1 : 0});
            watermarks.add(id);
        }

        @Override public int purgeTerminalBefore(long beforeMs) {
            terminalCutoffs.add(beforeMs);
            int n = 0;
            for (int i = messages.size() - 1; i >= 0; i--) {
                long[] m = messages.get(i);
                if (m[2] == 1 && m[1] < beforeMs) {
                    messages.remove(i);
                    watermarks.remove(m[0]);
                    n++;
                }
            }
            return n;
        }

        @Override public int purgeDirectoryBefore(long beforeMs) {
            directoryCutoffs.add(beforeMs);
            if (throwOnDirectory) throw new IllegalStateException("disk");
            int n = 0;
            for (int i = directory.size() - 1; i >= 0; i--) {
                if (directory.get(i) < beforeMs) { directory.remove(i); n++; }
            }
            return n;
        }

        boolean has(long id) {
            for (long[] m : messages) if (m[0] == id) return true;
            return false;
        }
    }

    private static final class Mark implements DataRetention.Mark {
        long last;
        int writes;
        Mark(long last) { this.last = last; }
        @Override public long lastRunMs() { return last; }
        @Override public void setLastRunMs(long ms) { last = ms; writes++; }
    }

    @Test public void windowsAre30And90Days() {
        assertEquals(30 * DAY, DataRetention.TERMINAL_RETENTION_MS);
        assertEquals(SendLaterRepository.RECENT_WINDOW_MS, DataRetention.TERMINAL_RETENTION_MS);
        assertEquals(90 * DAY, DataRetention.DIRECTORY_RETENTION_MS);
        assertEquals(DAY, DataRetention.MIN_INTERVAL_MS);
        assertEquals(NOW - 30 * DAY, DataRetention.terminalCutoff(NOW));
        assertEquals(NOW - 90 * DAY, DataRetention.directoryCutoff(NOW));
    }

    @Test public void isDue_neverRun_dayElapsed_clockBack() {
        assertTrue(DataRetention.isDue(0, NOW));
        assertTrue(DataRetention.isDue(-5, NOW));
        assertFalse(DataRetention.isDue(NOW, NOW));
        assertFalse(DataRetention.isDue(NOW - DAY + 1, NOW));
        assertTrue(DataRetention.isDue(NOW - DAY, NOW));
        assertTrue(DataRetention.isDue(NOW - 10 * DAY, NOW));
        assertTrue("clock moved backwards", DataRetention.isDue(NOW + DAY, NOW));
    }

    @Test public void terminalRowsOlderThan30DaysGoWithTheirWatermarks() {
        Table t = new Table();
        t.message(1, NOW - 31 * DAY, Status.SENT_VERIFIED);
        t.message(2, NOW - 31 * DAY, Status.CANCELED);
        t.message(3, NOW - 31 * DAY, Status.EXPIRED);
        t.message(4, NOW - 31 * DAY, Status.SELF_PREEMPTED);
        t.message(5, NOW - 31 * DAY, Status.FAILED);
        t.message(6, NOW - 29 * DAY, Status.SENT_VERIFIED);
        t.message(7, NOW - 30 * DAY, Status.CANCELED); // exactly at the cutoff: kept
        DataRetention.Result r = DataRetention.run(t, new Mark(0), NOW);
        assertTrue(r.ran);
        assertEquals(5, r.messages);
        for (long id = 1; id <= 5; id++) {
            assertFalse("purged " + id, t.has(id));
            assertFalse("watermark purged " + id, t.watermarks.contains(id));
        }
        assertTrue(t.has(6));
        assertTrue(t.has(7));
        assertTrue(t.watermarks.contains(6L));
    }

    @Test public void liveRowsAreNeverPurgedHoweverOld() {
        Table t = new Table();
        t.message(1, NOW - 400 * DAY, Status.SCHEDULED);
        t.message(2, NOW - 400 * DAY, Status.HELD);
        t.message(3, NOW - 400 * DAY, Status.IN_FLIGHT);
        DataRetention.Result r = DataRetention.run(t, new Mark(0), NOW);
        assertEquals(0, r.messages);
        assertTrue(t.has(1) && t.has(2) && t.has(3));
    }

    @Test public void directoryEntriesOlderThan90DaysGo() {
        Table t = new Table();
        t.directory.add(NOW - 91 * DAY);
        t.directory.add(NOW - 90 * DAY); // at the cutoff: kept
        t.directory.add(NOW - DAY);
        DataRetention.Result r = DataRetention.run(t, new Mark(0), NOW);
        assertEquals(1, r.directory);
        assertEquals(2, t.directory.size());
        assertEquals(Long.valueOf(NOW - 90 * DAY), t.directoryCutoffs.get(0));
    }

    @Test public void atMostDaily_markWrittenAfterRun() {
        Table t = new Table();
        Mark m = new Mark(0);
        assertTrue(DataRetention.run(t, m, NOW).ran);
        assertEquals(NOW, m.last);
        assertFalse(DataRetention.run(t, m, NOW + DAY - 1).ran);
        assertEquals(1, t.terminalCutoffs.size());
        assertTrue(DataRetention.run(t, m, NOW + DAY).ran);
        assertEquals(2, t.terminalCutoffs.size());
        assertEquals(2, m.writes);
    }

    @Test public void failedPurgeLeavesMarkSoNextStartRetries() {
        Table t = new Table();
        t.throwOnDirectory = true;
        Mark m = new Mark(NOW - 2 * DAY);
        try {
            DataRetention.run(t, m, NOW);
            fail("expected the store failure to propagate");
        } catch (IllegalStateException expected) {
            // the engine catches it
        }
        assertEquals(0, m.writes);
        assertEquals(NOW - 2 * DAY, m.last);
    }

    @Test public void everyStatusIsLiveXorTerminal() {
        for (Status s : Status.values()) {
            boolean live = SendLaterRepository.LIVE.contains(s.name());
            boolean terminal = SendLaterRepository.TERMINAL.contains(s.name());
            assertTrue(s + " must be live xor terminal", live ^ terminal);
        }
    }

    // ---- source checks on the SQL that the in-memory table mirrors ----

    private static String read(String p) throws IOException {
        return new String(Files.readAllBytes(Paths.get(p)), "UTF-8");
    }

    @Test public void daoSqlMatchesThePolicy() throws IOException {
        String dao = read("src/main/java/com/voice2sms/sendlater/store/SendLaterDao.java").replaceAll("\\s+", " ");
        assertTrue(dao.contains("\"DELETE FROM scheduled_message WHERE status IN (:terminal) AND updatedAt < :beforeMs\""));
        assertTrue(dao.contains("\"DELETE FROM watermark WHERE messageId IN (SELECT id FROM scheduled_message \" "
                + "+ \"WHERE status IN (:terminal) AND updatedAt < :beforeMs)\""));
        assertTrue(dao.contains("\"DELETE FROM thread_directory WHERE lastSeenMs < :beforeMs\""));
        String repo = read("src/main/java/com/voice2sms/sendlater/store/SendLaterRepository.java").replaceAll("\\s+", " ");
        int at = repo.indexOf("public int purgeTerminalBefore(long beforeMs)");
        assertTrue(at > 0);
        String body = repo.substring(at, repo.indexOf("public int purgeDirectoryBefore", at));
        assertTrue("one transaction", body.contains("db.runInTransaction("));
        int wm = body.indexOf("dao.deleteWatermarksOfTerminalBefore(TERMINAL, beforeMs)");
        int msg = body.indexOf("dao.deleteTerminalBefore(TERMINAL, beforeMs)");
        assertTrue("watermarks deleted before their rows", wm > 0 && msg > wm);
    }

    @Test public void engineRunsThePurgeOnProcessStartInBothSwitchStates() throws IOException {
        String src = read("src/main/java/com/voice2sms/sendlater/engine/SendLaterEngine.java");
        int start = src.indexOf("public static void onProcessStart(");
        int end = src.indexOf("// ================================================================== due time", start);
        assertTrue(start > 0 && end > start);
        String body = src.substring(start, end);
        int off = body.indexOf("doSweepExpired(d, false);");
        int offPurge = body.indexOf("purgeStaleData(context, d);", off);
        int ret = body.indexOf("return;", off);
        assertTrue("off branch purges before returning", off > 0 && offPurge > off && offPurge < ret);
        int onPurge = body.indexOf("purgeStaleData(context, d);", ret);
        int rearm = body.indexOf("schedulerOf(d).reregisterAll(context);", ret);
        assertTrue("on branch purges before the re-arm", onPurge > ret && rearm > onPurge);
        int helper = src.indexOf("private static void purgeStaleData(Context context, EngineDeps d)");
        assertTrue(helper > 0);
        String h = src.substring(helper, src.indexOf("\n    }\n", helper));
        assertTrue(h.contains("DataRetention.runIfDue(context, d.conditions.nowMs())"));
        assertTrue("purge failure must not stop process start", h.contains("catch (RuntimeException e)"));
    }

    @Test public void androidWiringDoesNotCreateTheDatabase() throws IOException {
        String src = read("src/main/java/com/voice2sms/sendlater/store/DataRetention.java");
        int at = src.indexOf("public static Result runIfDue(Context context, long nowMs)");
        assertTrue(at > 0);
        int exists = src.indexOf("getDatabasePath(SendLaterDatabase.NAME).exists()", at);
        int repo = src.indexOf("SendLaterRepository.get(app)", at);
        assertTrue(exists > at && repo > exists);
        assertTrue(DataRetention.PREFS.startsWith("sendlater_"));
    }
}
