package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.voice2sms.sendlater.domain.ScheduledMessage;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** simplify-io item 1: list loads fetch watermarks per list, with the same per-row result. */
public class RepositoryLoadBatchTest {

    private static ScheduledMessageEntity row(long id) {
        ScheduledMessageEntity e = new ScheduledMessageEntity();
        e.id = id;
        e.createdAt = 1_000L * id;
        e.scheduledFor = 2_000L * id;
        e.text = "placeholder " + id;
        return e;
    }

    private static WatermarkEntity wm(long id) {
        WatermarkEntity w = new WatermarkEntity();
        w.messageId = id;
        w.quality = "DEVICE_ONLY";
        w.capturedAtDevice = 77L * id;
        return w;
    }

    @Test public void attachMatchesPerRowLookupIncludingMissingWatermarks() {
        List<ScheduledMessageEntity> rows = Arrays.asList(row(3), row(1), row(2));
        Map<Long, WatermarkEntity> byId = new HashMap<>();
        byId.put(3L, wm(3));
        byId.put(2L, wm(2));
        byId.put(99L, wm(99)); // not in the list: ignored
        List<ScheduledMessage> out = SendLaterRepository.attach(rows, byId);
        assertEquals(3, out.size());
        for (int i = 0; i < rows.size(); i++) {
            ScheduledMessageEntity e = rows.get(i);
            ScheduledMessage expected = StoreMapping.toDomain(e, byId.get(e.id));
            assertEquals("row order kept", e.id, out.get(i).getId());
            assertTrue(SendLaterRepository.sameContent(expected, out.get(i)));
        }
        assertNull("no watermark row -> null watermark", out.get(1).getWatermark());
        assertEquals(StoreMapping.toDomain(wm(3)), out.get(0).getWatermark());
    }

    @Test public void idChunksKeepOrderAndBound() {
        List<ScheduledMessageEntity> rows = new ArrayList<>();
        for (long i = 1; i <= 1001; i++) rows.add(row(i));
        List<List<Long>> chunks = SendLaterRepository.idChunks(rows, SendLaterRepository.ID_CHUNK);
        assertEquals(3, chunks.size());
        assertEquals(500, chunks.get(0).size());
        assertEquals(500, chunks.get(1).size());
        assertEquals(1, chunks.get(2).size());
        assertEquals(Long.valueOf(1), chunks.get(0).get(0));
        assertEquals(Long.valueOf(1001), chunks.get(2).get(0));
        assertTrue(SendLaterRepository.idChunks(new ArrayList<ScheduledMessageEntity>(), 500).isEmpty());
        assertTrue(SendLaterRepository.ID_CHUNK < 999);
    }

    @Test public void loadUsesOneWatermarkQueryPerChunkNotPerRow() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/store/SendLaterRepository.java")),
                StandardCharsets.UTF_8);
        int start = src.indexOf("private List<ScheduledMessage> load(");
        String body = src.substring(start, src.indexOf("\n    }\n", start));
        assertTrue(body.contains("dao.watermarks("));
        assertTrue("no per-row watermark query", !body.contains("dao.watermark("));
        String dao = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/voice2sms/sendlater/store/SendLaterDao.java")),
                StandardCharsets.UTF_8);
        assertTrue(dao.contains("SELECT * FROM watermark WHERE messageId IN (:ids)"));
    }
}
