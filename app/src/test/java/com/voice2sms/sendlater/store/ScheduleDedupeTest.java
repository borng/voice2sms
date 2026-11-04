package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.LatenessPolicy;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** dl-sheet, UI M2 (repository half): an identical capture within 10 s returns the existing id. */
public class ScheduleDedupeTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String E164 = "+15555550123";

    private static ScheduledMessage incoming(String e164, String text, ScheduledMessage.Source src) {
        return new ScheduledMessage.Builder()
                .createdAt(NOW).scheduledFor(NOW + 3_600_000L).text(text).recipientE164(e164)
                .source(src).rung(Rung.R1).holdIfReplied(true).latenessPolicy(LatenessPolicy.DEFAULT)
                .status(ScheduledMessage.Status.SCHEDULED).attemptCount(0).build();
    }

    private static ScheduledMessageEntity row(long id, long createdAt, String text, String source,
                                              ScheduledMessage.Status st) {
        ScheduledMessageEntity e = new ScheduledMessageEntity();
        e.id = id;
        e.createdAt = createdAt;
        e.scheduledFor = NOW + 7_200_000L; // a different time still counts as the same capture
        e.text = text;
        e.recipientE164 = E164;
        e.source = source;
        e.status = st.name();
        return e;
    }

    private final ScheduledMessage in = incoming(E164, "on my way", ScheduledMessage.Source.NATIVE_GV);

    @Test public void identicalLiveCaptureWithinTheWindowIsTheDuplicate() {
        ScheduledMessageEntity d = SendLaterRepository.findDuplicate(Arrays.asList(
                row(7, NOW - 3_000, "on my way", "NATIVE_GV", ScheduledMessage.Status.SCHEDULED)), in, NOW);
        assertEquals(7L, d.id);
        assertEquals(10_000L, SendLaterRepository.DEDUPE_WINDOW_MS);
    }

    @Test public void windowIsTenSecondsInclusive() {
        assertEquals(8L, SendLaterRepository.findDuplicate(Arrays.asList(
                row(8, NOW - 10_000, "on my way", "NATIVE_GV", ScheduledMessage.Status.SCHEDULED)), in, NOW).id);
        assertNull(SendLaterRepository.findDuplicate(Arrays.asList(
                row(9, NOW - 10_001, "on my way", "NATIVE_GV", ScheduledMessage.Status.SCHEDULED)), in, NOW));
    }

    @Test public void differentTextSourceOrNumberIsNotADuplicate() {
        List<ScheduledMessageEntity> rows = new ArrayList<>();
        rows.add(row(1, NOW - 1_000, "on my way!", "NATIVE_GV", ScheduledMessage.Status.SCHEDULED));
        rows.add(row(2, NOW - 1_000, "on my way", "WEBVIEW", ScheduledMessage.Status.SCHEDULED));
        ScheduledMessageEntity other = row(3, NOW - 1_000, "on my way", "NATIVE_GV", ScheduledMessage.Status.SCHEDULED);
        other.recipientE164 = "+15555550124";
        rows.add(other);
        assertNull(SendLaterRepository.findDuplicate(rows, in, NOW));
    }

    @Test public void terminalRowsAreNotDuplicatesSoAnUndoThenRescheduleSaves() {
        for (ScheduledMessage.Status st : new ScheduledMessage.Status[] {ScheduledMessage.Status.CANCELED,
                ScheduledMessage.Status.SENT_VERIFIED, ScheduledMessage.Status.EXPIRED,
                ScheduledMessage.Status.FAILED, ScheduledMessage.Status.SELF_PREEMPTED}) {
            assertNull(st.name(), SendLaterRepository.findDuplicate(Arrays.asList(
                    row(4, NOW - 1_000, "on my way", "NATIVE_GV", st)), in, NOW));
        }
        assertEquals(5L, SendLaterRepository.findDuplicate(Arrays.asList(
                row(5, NOW - 1_000, "on my way", "NATIVE_GV", ScheduledMessage.Status.HELD)), in, NOW).id);
    }

    @Test public void newestMatchWinsAndMissingFieldsNeverMatch() {
        ScheduledMessageEntity d = SendLaterRepository.findDuplicate(Arrays.asList(
                row(1, NOW - 5_000, "on my way", "NATIVE_GV", ScheduledMessage.Status.SCHEDULED),
                row(2, NOW - 2_000, "on my way", "NATIVE_GV", ScheduledMessage.Status.SCHEDULED)), in, NOW);
        assertEquals(2L, d.id);
        assertNull(SendLaterRepository.findDuplicate(null, in, NOW));
        assertNull(SendLaterRepository.findDuplicate(Arrays.asList(
                row(1, NOW, "on my way", "NATIVE_GV", ScheduledMessage.Status.SCHEDULED)),
                incoming(null, "on my way", ScheduledMessage.Source.NATIVE_GV), NOW));
    }

    @Test public void scheduleChecksForADuplicateInsideTheTransactionBeforeInserting() throws IOException {
        String repo = read("SendLaterRepository.java");
        int sched = repo.indexOf("public long schedule(");
        int tx = repo.indexOf("db.runInTransaction(", sched);
        int find = repo.indexOf("findDuplicate(dao.recentForRecipient(", sched);
        int insert = repo.indexOf("dao.insertMessage(", sched);
        assertTrue(sched >= 0 && tx > sched && find > tx && insert > find);
        assertTrue(repo.substring(find, insert).contains("return dup.id;"));
        assertTrue(repo.substring(find, insert).contains("DEDUPE_WINDOW_MS"));
        String dao = read("SendLaterDao.java");
        String q = dao.replaceAll("\"\\s*\\+\\s*\"", "");
        assertTrue(q.contains("recipientE164 = :e164 AND createdAt >= :sinceMs AND status IN (:statuses)"));
    }

    private static String read(String name) throws IOException {
        Path p = Paths.get(System.getProperty("user.dir"), "src", "main", "java", "com", "voice2sms",
                "sendlater", "store", name);
        if (!Files.exists(p)) {
            p = Paths.get(System.getProperty("user.dir"), "app", "src", "main", "java", "com", "voice2sms",
                    "sendlater", "store", name);
        }
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
