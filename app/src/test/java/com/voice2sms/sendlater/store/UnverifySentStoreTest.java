package com.voice2sms.sendlater.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * Deliver M4: the one guarded way out of SENT_VERIFIED is SENT_VERIFIED -> HELD(UNVERIFIED);
 * every other source status is refused in SQL, and the ordinary hold still never touches a sent
 * row.
 */
public class UnverifySentStoreTest {

    private static String read(String rel) throws IOException {
        Path p = Paths.get("src/main/java/com/voice2sms/sendlater/" + rel);
        if (!Files.exists(p)) p = Paths.get("app").resolve(p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test public void onlySentVerifiedIsASourceStatus() {
        assertEquals(Collections.singletonList("SENT_VERIFIED"), SendLaterRepository.SENT_VERIFIED_ONLY);
        assertTrue("hold() still never applies to a sent row",
                !SendLaterRepository.HOLDABLE.contains("SENT_VERIFIED"));
    }

    @Test public void unverifySentIsAGuardedTransitionToHeldUnverified() throws IOException {
        String repo = read("store/SendLaterRepository.java");
        int at = repo.indexOf("public boolean unverifySent(long id)");
        assertTrue(at > 0);
        String body = repo.substring(at, repo.indexOf("\n    }\n", at));
        assertTrue(body, body.contains("to(id, Status.HELD, HoldReason.UNVERIFIED, SENT_VERIFIED_ONLY)"));
        // to() goes through the guarded UPDATE ... WHERE status IN (:from).
        String dao = read("store/SendLaterDao.java");
        int t = dao.indexOf("int transition(");
        assertTrue(dao.substring(dao.lastIndexOf("@Query(", t), t).contains("status IN (:from)"));
    }

    @Test public void engineStoreDefaultRefusesAndTheAppStoreDelegates() throws IOException {
        String es = read("engine/EngineStore.java");
        int at = es.indexOf("default boolean unverifySent(long id)");
        assertTrue(at > 0);
        assertTrue(es.substring(at, es.indexOf("}", at)).contains("return false;"));
        assertTrue(read("engine/AndroidEngineDeps.java")
                .contains("public boolean unverifySent(long id) { return r().unverifySent(id); }"));
    }
}
