package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.RemindReason;
import com.voice2sms.sendlater.domain.Rung;
import com.voice2sms.sendlater.domain.ScheduledMessage;
import com.voice2sms.sendlater.domain.Watermark;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OD22(1)/(4) schema v2: the migration is additive (no destructive fallback, no DROP/DELETE),
 * adds exactly the new entity columns with matching affinity and nullability, and the mapping
 * keeps the original watermark and rung reason. Fictional numbers only.
 */
public class SendLaterMigrationTest {

    private static final long T0 = 1_781_092_800_000L;

    /** v1 columns, pinned from the store node's entities. */
    private static final Set<String> V1_WATERMARK = new HashSet<>(Arrays.asList(
            "messageId", "quality", "newestServerId", "newestTs", "bubbleHash", "capturedAtDevice", "skewEstimateMs"));
    private static final Set<String> V1_MESSAGE = new HashSet<>(Arrays.asList(
            "id", "createdAt", "scheduledFor", "text", "recipientE164", "recipientDisplayName", "threadItemId",
            "source", "rung", "holdIfReplied", "latenessPolicy", "status", "holdReason", "lastError",
            "attemptCount", "conversationKey", "inFlightBodyHash", "updatedAt"));

    private static String src() throws IOException {
        return new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/store/SendLaterDatabase.java")), StandardCharsets.UTF_8);
    }

    @Test public void databaseIsV2WithAnExplicitMigrationAndNoDestructiveFallback() throws IOException {
        String s = src();
        assertTrue(s.contains("version = 2"));
        assertTrue(s.contains(".addMigrations(MIGRATION_1_2)"));
        assertFalse(s.contains("fallbackToDestructiveMigration"));
        assertEquals(1, SendLaterDatabase.MIGRATION_1_2.startVersion);
        assertEquals(2, SendLaterDatabase.MIGRATION_1_2.endVersion);
    }

    @Test public void migrationSqlIsAdditiveOnly() {
        for (String sql : SendLaterDatabase.MIGRATION_1_2_SQL) {
            String u = sql.toUpperCase(Locale.ROOT);
            assertFalse(sql, u.contains("DROP") || u.contains("DELETE") || u.contains("RENAME"));
            assertFalse(sql, u.contains("NOT NULL"));
            assertTrue(sql, u.startsWith("ALTER TABLE") && u.contains("ADD COLUMN") || u.startsWith("UPDATE"));
        }
    }

    @Test public void migrationAddsExactlyTheNewEntityColumns() {
        check(WatermarkEntity.class, "watermark", V1_WATERMARK);
        check(ScheduledMessageEntity.class, "scheduled_message", V1_MESSAGE);
    }

    private static void check(Class<?> entity, String table, Set<String> v1) {
        List<String> added = new ArrayList<>();
        for (String sql : SendLaterDatabase.MIGRATION_1_2_SQL) {
            String prefix = "ALTER TABLE `" + table + "` ADD COLUMN `";
            if (sql.startsWith(prefix)) added.add(sql.substring(prefix.length()));
        }
        Set<String> expected = new HashSet<>();
        for (Field f : entity.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || v1.contains(f.getName())) continue;
            expected.add(f.getName());
            Class<?> t = f.getType();
            assertFalse(f.getName() + " must be nullable (boxed) to be added without a default", t.isPrimitive());
            String affinity = t == String.class ? "TEXT" : "INTEGER";
            assertTrue(f.getName() + " added as " + affinity + ": " + added,
                    added.contains(f.getName() + "` " + affinity));
        }
        assertEquals(expected.size(), added.size());
    }

    @Test public void existingR3RowsBecomeNoSessionSaves() {
        boolean found = false;
        for (String sql : SendLaterDatabase.MIGRATION_1_2_SQL) {
            if (sql.startsWith("UPDATE")) {
                found = true;
                assertTrue(sql.contains("'NO_SESSION'") && sql.contains("'R3'"));
            }
        }
        assertTrue(found);
    }

    // ------------------------------------------------------------------ mapping

    private static Watermark wm(long at) {
        return new Watermark(Watermark.Quality.TIMESTAMP, "id-1", at - 60_000L, "h", at, 5L);
    }

    private static Watermark fresh(long at) {
        return new Watermark(Watermark.Quality.DEVICE_ONLY, null, null, null, at, null);
    }

    @Test public void replacingKeepsTheFirstWatermarkAsOriginal() {
        WatermarkEntity v1 = StoreMapping.toEntity(4, wm(T0));
        WatermarkEntity first = StoreMapping.replacing(4, v1, fresh(T0 + 3_600_000L), T0);
        assertEquals(fresh(T0 + 3_600_000L), StoreMapping.toDomain(first));
        assertEquals(wm(T0), StoreMapping.originalOf(first));
        WatermarkEntity second = StoreMapping.replacing(4, first, fresh(T0 + 7_200_000L), T0);
        assertEquals(fresh(T0 + 7_200_000L), StoreMapping.toDomain(second));
        assertEquals("original survives a second snooze", wm(T0), StoreMapping.originalOf(second));
    }

    @Test public void replacingWithoutARowUsesCreatedAt() {
        WatermarkEntity e = StoreMapping.replacing(4, null, fresh(T0 + 1000), T0);
        assertEquals(fresh(T0), StoreMapping.originalOf(e));
        assertNull(StoreMapping.replacing(4, null, null, T0));
    }

    @Test public void v1RowHasNoOriginal() {
        assertNull(StoreMapping.originalOf(StoreMapping.toEntity(4, wm(T0))));
        assertNull(StoreMapping.originalOf(null));
    }

    @Test public void roundTripCarriesOriginalAndRungReason() {
        ScheduledMessage m = new ScheduledMessage.Builder()
                .id(9).createdAt(T0).scheduledFor(T0 + 1).text("x").recipientE164("+15555550100")
                .rung(Rung.R3).rungReason(RemindReason.NO_SESSION)
                .watermark(fresh(T0 + 5)).originalWatermark(wm(T0)).build();
        ScheduledMessageEntity e = StoreMapping.toEntity(m, T0);
        assertEquals("NO_SESSION", e.rungReason);
        WatermarkEntity we = StoreMapping.toEntity(9, m.getWatermark(), m.getOriginalWatermark());
        ScheduledMessage back = StoreMapping.toDomain(e, we);
        assertTrue(SendLaterRepository.sameContent(m, back));
        assertEquals(wm(T0), back.getOriginalWatermark());
        assertEquals(RemindReason.NO_SESSION, back.getRungReason());
        assertFalse(SendLaterRepository.sameContent(m, m.toBuilder().rungReason(null).build()));
        assertFalse(SendLaterRepository.sameContent(m, m.toBuilder().originalWatermark(null).build()));
    }

    @Test public void repositoryRescheduleUsesReplacingAndUpgradeRungClearsTheReason() throws IOException {
        String repo = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/store/SendLaterRepository.java")), StandardCharsets.UTF_8);
        assertTrue(repo.contains("StoreMapping.replacing("));
        String dao = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/store/SendLaterDao.java")), StandardCharsets.UTF_8);
        assertTrue(dao.contains("SET rung = :to, rungReason = NULL"));
    }
}
