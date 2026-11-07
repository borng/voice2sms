package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Deliver M2: the row version the engine evaluated ({@code scheduledFor} + {@code updatedAt}) is
 * read with the row and is what the guarded IN_FLIGHT write compares. Fictional data only.
 */
public class InFlightSnapshotTest {

    private static String read(String rel) throws IOException {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater/store/" + rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test public void updatedAtIsReadWithTheRowAndKeptByToBuilder() {
        ScheduledMessageEntity e = new ScheduledMessageEntity();
        e.id = 9L;
        e.createdAt = 1_000L;
        e.scheduledFor = 5_000L;
        e.text = "placeholder";
        e.updatedAt = 4_242L;
        ScheduledMessage m = StoreMapping.toDomain(e, null);
        assertEquals(4_242L, m.getUpdatedAt());
        assertEquals(4_242L, m.toBuilder().build().getUpdatedAt());
        assertEquals(0L, new ScheduledMessage.Builder().build().getUpdatedAt());
    }

    @Test public void guardedInFlightUpdateComparesScheduledForAndUpdatedAt() throws IOException {
        String dao = read("SendLaterDao.java");
        int at = dao.indexOf("int toInFlightIfUnchanged(");
        assertTrue(at > 0);
        String query = dao.substring(dao.lastIndexOf("@Query(", at), at);
        assertTrue(query, query.contains("status IN (:from)"));
        assertTrue(query, query.contains("scheduledFor = :scheduledFor"));
        assertTrue(query, query.contains("updatedAt = :updatedAt"));
        assertTrue(query, query.contains("updatedAt = :now"));
    }

    @Test public void repositoryExposesTheGuardedTransitionOverWaitingRows() throws IOException {
        String repo = read("SendLaterRepository.java");
        int at = repo.indexOf("public boolean markInFlight(long id, String bodyHash, long scheduledFor, long updatedAt)");
        assertTrue(at > 0);
        String body = repo.substring(at, repo.indexOf("\n    }\n", at));
        assertTrue(body, body.contains("dao.toInFlightIfUnchanged(") && body.contains("WAITING"));
    }
}
